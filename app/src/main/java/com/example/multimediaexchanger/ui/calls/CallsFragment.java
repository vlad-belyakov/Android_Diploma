package com.example.multimediaexchanger.ui.calls;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
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

import com.example.multimediaexchanger.databinding.FragmentCallsBinding;
import com.example.multimediaexchanger.ui.UdpViewModel;
import com.example.multimediaexchanger.ui.UsbLogViewModel;
import com.example.multimediaexchanger.ui.network.NetworkViewModel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CallsFragment extends Fragment {

    private FragmentCallsBinding binding;
    private UdpViewModel udpViewModel;
    private NetworkViewModel networkViewModel;
    private UsbLogViewModel usbLogViewModel;

    private volatile boolean isInCall = false;
    private final ExecutorService callExecutor = Executors.newCachedThreadPool();

    private MediaCodec audioEncoder, audioDecoder;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;

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
        observeIncomingCalls();

        return binding.getRoot();
    }

    private void setupClickListeners() {
        binding.startCallButton.setOnClickListener(v -> {
            if (!isInCall) {
                String targetIp = networkViewModel.getTargetIpAddress().getValue();
                 if (targetIp == null || targetIp.isEmpty()) {
                    usbLogViewModel.log("UI: Start call failed. Target IP is not set.");
                    Toast.makeText(getContext(), "IP адрес получателя не указан", Toast.LENGTH_SHORT).show();
                    return;
                }
                startCall();
            }
        });
        binding.endCallButton.setOnClickListener(v -> {
            if (isInCall) {
                endCall();
            }
        });
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startCall() {
        if (isInCall) return;
        isInCall = true;
        usbLogViewModel.log("Call: Starting call...");
        callExecutor.execute(this::setupAudioStreaming);
    }

    @SuppressLint("MissingPermission")
    private void setupAudioStreaming() {
        requireActivity().runOnUiThread(() -> {
            binding.startCallButton.setVisibility(View.GONE);
            binding.endCallButton.setVisibility(View.VISIBLE);
            binding.callStatusTextview.setText("В разговоре...");
        });

        try {
            setupAudioEncoder();
            setupAudioDecoderAndTrack();

            int bufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            audioRecord.startRecording();

            callExecutor.execute(this::encodeAndSendAudio);
            usbLogViewModel.log("Call: Audio streaming setup complete.");

        } catch (Exception e) {
            usbLogViewModel.log("ERROR: Call setup failed", e);
            if (getActivity() != null) getActivity().runOnUiThread(this::endCall);
        }
    }

    private void encodeAndSendAudio() {
        while (isInCall) {
            if (audioEncoder == null || audioRecord == null) break;
            try {
                int inputBufferIndex = audioEncoder.dequeueInputBuffer(-1);
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputBufferIndex);
                    if (inputBuffer != null) {
                        inputBuffer.clear();
                        int length = audioRecord.read(inputBuffer, inputBuffer.capacity());
                        if (length > 0) {
                            audioEncoder.queueInputBuffer(inputBufferIndex, 0, length, System.nanoTime() / 1000, 0);
                        }
                    }
                }
            } catch(Exception e){
                usbLogViewModel.log("ERROR: Audio encoding/reading failed", e);
            }
        }
    }

    private void endCall() {
        if (!isInCall) return;
        isInCall = false;
        usbLogViewModel.log("Call: Ending call...");
        cleanupCallResources();
    }

    private void cleanupCallResources() {
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                usbLogViewModel.log("WARN: AudioRecord cleanup failed", e);
            }
            audioRecord = null;
        }
        try { if (audioEncoder != null) { audioEncoder.stop(); audioEncoder.release(); audioEncoder = null; } } catch(Exception e){ usbLogViewModel.log("WARN: Encoder cleanup failed", e); }
        try { if (audioTrack != null) { audioTrack.stop(); audioTrack.release(); audioTrack = null; } } catch(Exception e){ usbLogViewModel.log("WARN: AudioTrack cleanup failed", e); }
        try { if (audioDecoder != null) { audioDecoder.stop(); audioDecoder.release(); audioDecoder = null; } } catch(Exception e){ usbLogViewModel.log("WARN: Decoder cleanup failed", e); }
        
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                binding.startCallButton.setVisibility(View.VISIBLE);
                binding.endCallButton.setVisibility(View.GONE);
                binding.callStatusTextview.setText("Готов к звонку");
            });
        }
        usbLogViewModel.log("Call: Resources cleaned up.");
    }

    private void observeIncomingCalls() {
        udpViewModel.getReceivedMessage().observe(getViewLifecycleOwner(), message -> {
            if (isInCall && message != null && message.type == UdpViewModel.MESSAGE_TYPE_CALL) {
                if (audioDecoder != null) {
                    try {
                        int inputBufferIndex = audioDecoder.dequeueInputBuffer(-1);
                        if (inputBufferIndex >= 0) {
                            ByteBuffer inputBuffer = audioDecoder.getInputBuffer(inputBufferIndex);
                            if (inputBuffer != null) {
                                inputBuffer.clear();
                                inputBuffer.put(message.payload);
                                audioDecoder.queueInputBuffer(inputBufferIndex, 0, message.payload.length, System.nanoTime() / 1000, 0);
                            }
                        }
                    } catch(Exception e) {
                        usbLogViewModel.log("WARN: Incoming call audio buffer queue failed", e);
                    }
                }
            }
        });
    }

    private void setupAudioEncoder() throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);

        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        audioEncoder.setCallback(new EncoderCallback());
        audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();
    }

    private void setupAudioDecoderAndTrack() throws IOException {
        int audioChannelConfig = (AUDIO_CHANNEL_COUNT == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT);

        audioDecoder = MediaCodec.createDecoderByType(audioFormat.getString(MediaFormat.KEY_MIME));
        audioDecoder.setCallback(new DecoderCallback());
        audioDecoder.configure(audioFormat, null, null, 0);
        audioDecoder.start();

        int minBufferSize = AudioTrack.getMinBufferSize(AUDIO_SAMPLE_RATE, audioChannelConfig, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(AUDIO_SAMPLE_RATE).setChannelMask(audioChannelConfig).build())
                .setBufferSizeInBytes(minBufferSize).build();
        audioTrack.play();
    }

    private class EncoderCallback extends MediaCodec.Callback {
        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            if (!isInCall) return;
            String targetIp = networkViewModel.getTargetIpAddress().getValue();
            if (targetIp == null || targetIp.isEmpty()) return;

            try {
                ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                if (outputBuffer != null && info.size > 0) {
                    byte[] data = new byte[info.size];
                    outputBuffer.get(data);
                    udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_CALL, data);
                }
                codec.releaseOutputBuffer(index, false);
            } catch (Exception e) {
                usbLogViewModel.log("ERROR: Call encoder output buffer processing failed", e);
            }
        }

        @Override public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {}
        @Override public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) { usbLogViewModel.log("ERROR: Encoder error", e); }
        @Override public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {}
    }

    private class DecoderCallback extends MediaCodec.Callback {
        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            if (!isInCall || audioTrack == null) return;
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

        @Override public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {}
        @Override public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) { usbLogViewModel.log("ERROR: Decoder error", e); }
        @Override public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {}
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        endCall();
        if (callExecutor != null && !callExecutor.isShutdown()) {
            callExecutor.shutdown();
        }
        binding = null;
    }
}
