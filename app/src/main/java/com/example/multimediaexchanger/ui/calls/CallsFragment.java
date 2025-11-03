package com.example.multimediaexchanger.ui.calls;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.multimediaexchanger.R;
import com.example.multimediaexchanger.databinding.FragmentCallsBinding;
import com.example.multimediaexchanger.ui.UdpViewModel;
import com.example.multimediaexchanger.ui.UsbLogViewModel;
import com.example.multimediaexchanger.ui.network.NetworkViewModel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CallsFragment extends Fragment {

    private enum CallState {
        IDLE,       // Готов к звонку
        OUTGOING,   // Исходящий вызов
        INCOMING,   // Входящий вызов
        IN_CALL     // В разговоре
    }

    private FragmentCallsBinding binding;
    private UdpViewModel udpViewModel;
    private NetworkViewModel networkViewModel;
    private UsbLogViewModel usbLogViewModel;

    private volatile CallState currentCallState = CallState.IDLE;
    private final ExecutorService callExecutor = Executors.newCachedThreadPool();

    private MediaCodec audioEncoder, audioDecoder;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;

    private final Queue<byte[]> audioDataQueue = new ConcurrentLinkedQueue<>();
    private final Queue<byte[]> audioConfigQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean isAudioDecoderConfigured = false;
    private int audioInputBufferSize;

    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_BITRATE = 64000;
    private static final int AUDIO_CHANNEL_COUNT = 1;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (!isGranted) {
                    Toast.makeText(getContext(), "Требуется разрешение на использование микрофона.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentCallsBinding.inflate(inflater, container, false);

        udpViewModel = new ViewModelProvider(requireActivity()).get(UdpViewModel.class);
        networkViewModel = new ViewModelProvider(requireActivity()).get(NetworkViewModel.class);
        usbLogViewModel = new ViewModelProvider(requireActivity()).get(UsbLogViewModel.class);

        setupClickListeners();
        checkPermissions();
        observeUdpMessages();

        updateUiForState(CallState.IDLE);
        return binding.getRoot();
    }

    private void setupClickListeners() {
        binding.callActionButton.setOnClickListener(v -> handleCallAction());
        binding.answerButton.setOnClickListener(v -> handleAnswer());
        binding.rejectButton.setOnClickListener(v -> handleReject());
    }

    private void observeUdpMessages() {
        udpViewModel.getReceivedMessage().observe(getViewLifecycleOwner(), message -> {
            if (message == null) return;
            switch (message.type) {
                case UdpViewModel.MESSAGE_TYPE_CALL_REQUEST:
                    if (currentCallState == CallState.IDLE) {
                        updateUiForState(CallState.INCOMING);
                    }
                    break;
                case UdpViewModel.MESSAGE_TYPE_CALL_ACCEPT:
                    if (currentCallState == CallState.OUTGOING) {
                        updateUiForState(CallState.IN_CALL);
                        startAudioStreaming();
                    }
                    break;
                case UdpViewModel.MESSAGE_TYPE_CALL_REJECT:
                case UdpViewModel.MESSAGE_TYPE_CALL_END:
                    if (currentCallState != CallState.IDLE) {
                        updateUiForState(CallState.IDLE);
                        cleanupCallResources();
                    }
                    break;
                case UdpViewModel.MESSAGE_TYPE_STREAM_AUDIO_CONFIG:
                     if (currentCallState == CallState.IN_CALL && audioDecoder != null) {
                        audioConfigQueue.offer(message.payload);
                    }
                    break;
                case UdpViewModel.MESSAGE_TYPE_CALL_AUDIO: // Audio data during call
                     if (currentCallState == CallState.IN_CALL && audioDecoder != null) {
                        audioDataQueue.offer(message.payload);
                    }
                    break;
            }
        });
    }

    private void handleCallAction() {
        String targetIp = networkViewModel.getTargetIpAddress().getValue();
        if (targetIp == null || targetIp.isEmpty()) {
            Toast.makeText(getContext(), "IP адрес получателя не указан", Toast.LENGTH_SHORT).show();
            return;
        }

        switch (currentCallState) {
            case IDLE:
                udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_CALL_REQUEST, new byte[0]);
                updateUiForState(CallState.OUTGOING);
                break;
            case OUTGOING:
            case IN_CALL:
                udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_CALL_END, new byte[0]);
                updateUiForState(CallState.IDLE);
                cleanupCallResources();
                break;
        }
    }

    private void handleAnswer() {
        String targetIp = networkViewModel.getTargetIpAddress().getValue();
        if (targetIp != null && !targetIp.isEmpty() && currentCallState == CallState.INCOMING) {
            udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_CALL_ACCEPT, new byte[0]);
            updateUiForState(CallState.IN_CALL);
            startAudioStreaming();
        }
    }

    private void handleReject() {
        String targetIp = networkViewModel.getTargetIpAddress().getValue();
        if (targetIp != null && !targetIp.isEmpty() && currentCallState == CallState.INCOMING) {
            udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_CALL_REJECT, new byte[0]);
            updateUiForState(CallState.IDLE);
        }
    }

    private void updateUiForState(CallState state) {
        this.currentCallState = state;
        requireActivity().runOnUiThread(() -> {
            switch (state) {
                case IDLE:
                    binding.callStatusText.setText("Готов к звонку");
                    binding.callActionButton.setText("Позвонить");
                    binding.callActionButton.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark));
                    binding.callActionButton.setVisibility(View.VISIBLE);
                    binding.incomingCallActions.setVisibility(View.GONE);
                    break;
                case OUTGOING:
                    binding.callStatusText.setText("Попытка соединения...");
                    binding.callActionButton.setText("Сбросить");
                    binding.callActionButton.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark));
                    binding.callActionButton.setVisibility(View.VISIBLE);
                    binding.incomingCallActions.setVisibility(View.GONE);
                    break;
                case INCOMING:
                    binding.callStatusText.setText("Входящий звонок");
                    binding.callActionButton.setVisibility(View.GONE);
                    binding.incomingCallActions.setVisibility(View.VISIBLE);
                    break;
                case IN_CALL:
                    binding.callStatusText.setText("В разговоре");
                    binding.callActionButton.setText("Завершить");
                    binding.callActionButton.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark));
                    binding.callActionButton.setVisibility(View.VISIBLE);
                    binding.incomingCallActions.setVisibility(View.GONE);
                    break;
            }
        });
    }
    
    private void startAudioStreaming() {
        if (currentCallState != CallState.IN_CALL) return;
        usbLogViewModel.log("Call: Starting audio stream...");
        callExecutor.execute(() -> {
             try {
                setupAudioEncoder();
                setupAudioDecoderAndTrack();
            } catch (Exception e) {
                usbLogViewModel.log("ERROR: Call setup failed", e);
                if (getActivity() != null) getActivity().runOnUiThread(this::handleCallAction); // End call on error
            }
        });
    }

    private void cleanupCallResources() {
        callExecutor.execute(()->{
            isAudioDecoderConfigured = false;
             if (audioRecord != null) {
                try {
                    if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop();
                    audioRecord.release();
                } catch (Exception e) { usbLogViewModel.log("WARN: AudioRecord cleanup failed", e); }
                audioRecord = null;
            }
            try { if (audioEncoder != null) { audioEncoder.stop(); audioEncoder.release(); audioEncoder = null; } } catch(Exception e){ usbLogViewModel.log("WARN: Encoder cleanup failed", e); }
            try { if (audioTrack != null) { audioTrack.stop(); audioTrack.release(); audioTrack = null; } } catch(Exception e){ usbLogViewModel.log("WARN: AudioTrack cleanup failed", e); }
            try { if (audioDecoder != null) { audioDecoder.stop(); audioDecoder.release(); audioDecoder = null; } } catch(Exception e){ usbLogViewModel.log("WARN: Decoder cleanup failed", e); }
            audioDataQueue.clear();
            audioConfigQueue.clear();
            usbLogViewModel.log("Call: Resources cleaned up.");
        });
    }

    @SuppressLint("MissingPermission")
    private void setupAudioEncoder() throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
        audioInputBufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        audioEncoder.setCallback(new EncoderCallback());
        audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, audioInputBufferSize);
        audioRecord.startRecording();
        audioEncoder.start();
    }

    private void setupAudioDecoderAndTrack() throws IOException {
        isAudioDecoderConfigured = false;
        int audioChannelConfig = (AUDIO_CHANNEL_COUNT == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT);

        audioDecoder = MediaCodec.createDecoderByType(audioFormat.getString(MediaFormat.KEY_MIME));
        audioDecoder.setCallback(new DecoderCallback());
        audioDecoder.configure(audioFormat, null, null, 0);

        int minBufferSize = AudioTrack.getMinBufferSize(AUDIO_SAMPLE_RATE, audioChannelConfig, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(AUDIO_SAMPLE_RATE).setChannelMask(audioChannelConfig).build())
                .setBufferSizeInBytes(minBufferSize).build();
        audioTrack.play();
        audioDecoder.start();
    }

    private class EncoderCallback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            if (currentCallState != CallState.IN_CALL || audioRecord == null) return;
            try {
                ByteBuffer inputBuffer = codec.getInputBuffer(index);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    int length = audioRecord.read(inputBuffer, audioInputBufferSize);
                    if (length > 0) {
                        codec.queueInputBuffer(index, 0, length, System.nanoTime() / 1000, 0);
                    }
                }
            } catch (Exception e) {
                usbLogViewModel.log("WARN: Audio reading for encoder failed", e);
            }
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            if (currentCallState != CallState.IN_CALL) return;
            String targetIp = networkViewModel.getTargetIpAddress().getValue();
            if (targetIp == null || targetIp.isEmpty()) return;

            try {
                ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                if (outputBuffer != null) {
                    byte[] data = new byte[info.size];
                    outputBuffer.position(info.offset);
                    outputBuffer.limit(info.offset + info.size);
                    outputBuffer.get(data);

                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        if (info.size > 0) udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_STREAM_AUDIO_CONFIG, data);
                    } else if(info.size > 0) {
                        udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_CALL_AUDIO, data);
                    }
                }
                codec.releaseOutputBuffer(index, false);
            } catch (Exception e) {
                usbLogViewModel.log("ERROR: Call encoder output buffer processing failed", e);
            }
        }

        @Override public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) { usbLogViewModel.log("ERROR: Encoder error", e); }
        @Override public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {}
    }

    private class DecoderCallback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            if (currentCallState != CallState.IN_CALL) return;
            try {
                ByteBuffer inputBuffer = codec.getInputBuffer(index);
                if (inputBuffer == null) return;

                if (!isAudioDecoderConfigured) {
                    byte[] configData = audioConfigQueue.poll();
                    if (configData != null) {
                        inputBuffer.clear();
                        inputBuffer.put(configData);
                        codec.queueInputBuffer(index, 0, configData.length, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                        usbLogViewModel.log("Call: Fed audio CSD to decoder.");
                        isAudioDecoderConfigured = true;
                    }
                    return; // Wait for config before processing data
                }
                
                byte[] data = audioDataQueue.poll();
                if (data != null) {
                    inputBuffer.clear();
                    inputBuffer.put(data);
                    codec.queueInputBuffer(index, 0, data.length, System.nanoTime() / 1000, 0);
                }

            } catch (Exception e) {
                 usbLogViewModel.log("WARN: Audio feeding to decoder failed", e);
            }
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            if (currentCallState != CallState.IN_CALL || audioTrack == null) return;
            try {
                ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                if (outputBuffer != null && info.size > 0 && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    byte[] chunk = new byte[info.size];
                    outputBuffer.get(chunk);
                    audioTrack.write(chunk, 0, info.size);
                }
                codec.releaseOutputBuffer(index, false);
            } catch (Exception e) {
                usbLogViewModel.log("ERROR: Call decoder output buffer processing failed", e);
            }
        }

        @Override public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) { usbLogViewModel.log("ERROR: Decoder error", e); }
        @Override public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {}
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (currentCallState != CallState.IDLE) {
            handleCallAction(); // End call if fragment is destroyed
        }
        if (callExecutor != null && !callExecutor.isShutdown()) {
            callExecutor.shutdown();
        }
        binding = null;
    }
}
