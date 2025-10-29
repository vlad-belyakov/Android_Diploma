package com.example.multimediaexchanger.ui.stream;

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
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.multimediaexchanger.databinding.FragmentStreamBinding;
import com.example.multimediaexchanger.ui.UdpViewModel;
import com.example.multimediaexchanger.ui.UsbLogViewModel;
import com.example.multimediaexchanger.ui.network.NetworkViewModel;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StreamFragment extends Fragment implements SurfaceHolder.Callback {

    private FragmentStreamBinding binding;
    private UdpViewModel udpViewModel;
    private NetworkViewModel networkViewModel;
    private UsbLogViewModel usbLogViewModel;

    private volatile boolean isStreaming = false;
    private volatile boolean isWatching = false;

    private static final byte PACKET_TYPE_VIDEO = 0x10;
    private static final byte PACKET_TYPE_AUDIO = 0x20;

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ExecutorService streamingExecutor;
    private MediaCodec videoEncoder, audioEncoder, videoDecoder, audioDecoder;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Surface decodingSurface;

    private static final int VIDEO_WIDTH = 640, VIDEO_HEIGHT = 480, VIDEO_BITRATE = 1000000, VIDEO_FRAME_RATE = 20;
    private static final int AUDIO_SAMPLE_RATE = 44100, AUDIO_BITRATE = 64000, AUDIO_CHANNEL_COUNT = 1;

    private final ActivityResultLauncher<String[]> requestPermissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), perms -> {
                if (!Boolean.TRUE.equals(perms.get(Manifest.permission.CAMERA)) || !Boolean.TRUE.equals(perms.get(Manifest.permission.RECORD_AUDIO))) {
                    Toast.makeText(getContext(), "Требуются разрешения на использование камеры и микрофона.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentStreamBinding.inflate(inflater, container, false);

        streamingExecutor = Executors.newCachedThreadPool();

        udpViewModel = new ViewModelProvider(requireActivity()).get(UdpViewModel.class);
        networkViewModel = new ViewModelProvider(requireActivity()).get(NetworkViewModel.class);
        usbLogViewModel = new ViewModelProvider(requireActivity()).get(UsbLogViewModel.class);

        binding.decodedStreamView.getHolder().addCallback(this);

        setupClickListeners();
        checkPermissions();
        observeIncomingData();

        return binding.getRoot();
    }

    private void setupClickListeners() {
        binding.startStreamButton.setOnClickListener(v -> {
            if (isStreaming) {
                stopStream();
            } else {
                String targetIp = networkViewModel.getTargetIpAddress().getValue();
                if (targetIp == null || targetIp.isEmpty()) {
                    usbLogViewModel.log("UI: Start stream failed. Target IP is not set.");
                    Toast.makeText(getContext(), "IP адрес получателя не указан", Toast.LENGTH_SHORT).show();
                    return;
                }
                startStream();
            }
        });

        binding.watchStreamButton.setOnClickListener(v -> {
            if (isWatching) {
                stopWatching();
            } else {
                startWatching();
            }
        });
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsLauncher.launch(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO});
        }
    }

    @SuppressLint("MissingPermission")
    private void startStream() {
        isStreaming = true;
        usbLogViewModel.log("Stream: Starting stream...");
        requireActivity().runOnUiThread(() -> {
            binding.startStreamButton.setText("Остановить трансляцию");
            binding.cameraPreviewView.setVisibility(View.VISIBLE);
            binding.decodedStreamView.setVisibility(View.GONE);
        });

        streamingExecutor.execute(() -> {
            try {
                setupVideoEncoder();
                setupAudioEncoder();
                streamingExecutor.execute(this::streamAudio);
                requireActivity().runOnUiThread(this::startCameraStream);
            } catch (Exception e) {
                usbLogViewModel.log("ERROR: Stream setup failed", e);
                if (getActivity() != null) getActivity().runOnUiThread(this::stopStream);
            }
        });
    }

    private void startCameraStream() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext());
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.cameraPreviewView.getSurfaceProvider());

                if (videoEncoder != null) {
                    preview.setSurfaceProvider(ContextCompat.getMainExecutor(requireContext()), surfaceRequest -> {
                        Surface surface = videoEncoder.createInputSurface();
                        surfaceRequest.provideSurface(surface, ContextCompat.getMainExecutor(requireContext()), result -> {});
                    });
                }

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview);

            } catch (Exception e) {
                usbLogViewModel.log("ERROR: Failed to bind camera for streaming", e);
                stopStream();
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void stopStream() {
        if (!isStreaming) return;
        isStreaming = false;

        try {
            if (cameraProviderFuture != null && cameraProviderFuture.get() != null) cameraProviderFuture.get().unbindAll();
        } catch (Exception e) { usbLogViewModel.log("WARN: Error unbinding camera", e); }
        try { if (videoEncoder != null) { videoEncoder.stop(); videoEncoder.release(); videoEncoder = null; } } catch (Exception e) { usbLogViewModel.log("WARN: Video encoder cleanup failed", e); }
        try { if (audioEncoder != null) { audioEncoder.stop(); audioEncoder.release(); audioEncoder = null; } } catch (Exception e) { usbLogViewModel.log("WARN: Audio encoder cleanup failed", e); }

        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) { usbLogViewModel.log("WARN: AudioRecord cleanup failed", e); }
            audioRecord = null;
        }

        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                binding.startStreamButton.setText("Начать трансляцию");
                binding.cameraPreviewView.setVisibility(View.GONE);
            });
        }
        usbLogViewModel.log("Stream: Streaming stopped.");
    }

    @SuppressLint("MissingPermission")
    private void streamAudio() {
        int bufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        audioRecord.startRecording();

        while (isStreaming) {
            if (audioEncoder == null) break;
            try {
                int inputBufferIndex = audioEncoder.dequeueInputBuffer(-1);
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputBufferIndex);
                    if (inputBuffer != null) {
                        inputBuffer.clear();
                        int length = audioRecord.read(inputBuffer, bufferSize);
                        if (length > 0) {
                            audioEncoder.queueInputBuffer(inputBufferIndex, 0, length, System.nanoTime() / 1000, 0);
                        }
                    }
                }
            } catch (Exception e) {
                usbLogViewModel.log("WARN: Audio streaming read/queue failed", e);
            }
        }
    }

    private void setupVideoEncoder() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        videoEncoder.setCallback(new EncoderCallback(PACKET_TYPE_VIDEO));
        videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        videoEncoder.start();
    }

    private void setupAudioEncoder() throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);

        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        audioEncoder.setCallback(new EncoderCallback(PACKET_TYPE_AUDIO));
        audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();
    }

    private class EncoderCallback extends MediaCodec.Callback {
        private final byte packetType;
        EncoderCallback(byte packetType) { this.packetType = packetType; }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            if (!isStreaming) return;
            String targetIp = networkViewModel.getTargetIpAddress().getValue();
            if (targetIp == null || targetIp.isEmpty()) return;

            try {
                ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                if (outputBuffer != null && info.size > 0) {
                    byte[] data = new byte[info.size];
                    outputBuffer.get(data);

                    byte[] payload = new byte[1 + data.length];
                    payload[0] = packetType;
                    System.arraycopy(data, 0, payload, 1, data.length);

                    udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_STREAM, payload);
                }
                codec.releaseOutputBuffer(index, false);
            } catch (Exception e) {
                usbLogViewModel.log("ERROR: Stream encoder output buffer failed", e);
            }
        }

        @Override public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {}
        @Override public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) { usbLogViewModel.log("ERROR: Stream Encoder error", e); }
        @Override public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {}
    }

    private void startWatching() {
        if (isWatching) return;
        isWatching = true;
        usbLogViewModel.log("Stream: Starting to watch...");
        if(getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                binding.watchStreamButton.setText("Остановить просмотр");
                binding.decodedStreamView.setVisibility(View.VISIBLE);
                binding.cameraPreviewView.setVisibility(View.GONE);
                Toast.makeText(getContext(), "Ожидание трансляции...", Toast.LENGTH_SHORT).show();
            });
        }

        if (decodingSurface != null) {
            try {
                setupDecoders();
            } catch (Exception e) {
                usbLogViewModel.log("ERROR: Failed to setup decoders on watch start", e);
                stopWatching();
            }
        }
    }

    private void stopWatching() {
        if (!isWatching) return;
        isWatching = false;
        cleanupDecoders();
        
        if(getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                binding.watchStreamButton.setText("Смотреть трансляцию");
                binding.decodedStreamView.setVisibility(View.GONE);
            });
        }
        usbLogViewModel.log("Stream: Watching stopped.");
    }
    
    private void cleanupDecoders() {
        try { if (videoDecoder != null) { videoDecoder.stop(); videoDecoder.release(); videoDecoder = null; } } catch (Exception e) { usbLogViewModel.log("WARN: Video decoder cleanup failed", e); }
        try { if (audioDecoder != null) { audioDecoder.stop(); audioDecoder.release(); audioDecoder = null; } } catch (Exception e) { usbLogViewModel.log("WARN: Audio decoder cleanup failed", e); }
        try { if (audioTrack != null) { audioTrack.stop(); audioTrack.release(); audioTrack = null; } } catch (Exception e) { usbLogViewModel.log("WARN: Audio track cleanup failed", e); }
    }

    private void observeIncomingData() {
        udpViewModel.getReceivedMessage().observe(getViewLifecycleOwner(), message -> {
            if (message == null || !isWatching || message.type != UdpViewModel.MESSAGE_TYPE_STREAM) return;

            try {
                ByteBuffer payload = ByteBuffer.wrap(message.payload);
                if (payload.remaining() < 1) return;
                byte streamType = payload.get();
                byte[] data = new byte[payload.remaining()];
                payload.get(data);

                MediaCodec decoder = (streamType == PACKET_TYPE_VIDEO) ? videoDecoder : audioDecoder;
                if (decoder == null) return;

                int inIdx = decoder.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                    if (inBuf != null) {
                        inBuf.clear();
                        inBuf.put(data);
                        decoder.queueInputBuffer(inIdx, 0, data.length, System.nanoTime() / 1000, 0);
                    }
                }
            } catch (Exception e) {
                usbLogViewModel.log("WARN: Incoming stream data processing failed", e);
            }
        });
    }

    private void setupDecoders() throws IOException {
        if (decodingSurface == null) {
            throw new IOException("Decoding surface is not available.");
        }

        usbLogViewModel.log("Stream: Setting up decoders...");
        MediaFormat vFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT);
        videoDecoder = MediaCodec.createDecoderByType(vFmt.getString(MediaFormat.KEY_MIME));
        videoDecoder.setCallback(new DecoderCallback(false));
        videoDecoder.configure(vFmt, decodingSurface, null, 0);
        videoDecoder.start();

        int aChanCfg = (AUDIO_CHANNEL_COUNT == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        MediaFormat aFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT);
        audioDecoder = MediaCodec.createDecoderByType(aFmt.getString(MediaFormat.KEY_MIME));
        audioDecoder.setCallback(new DecoderCallback(true));
        audioDecoder.configure(aFmt, null, null, 0);
        audioDecoder.start();

        int minBufSize = AudioTrack.getMinBufferSize(AUDIO_SAMPLE_RATE, aChanCfg, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(AUDIO_SAMPLE_RATE).setChannelMask(aChanCfg).build())
                .setBufferSizeInBytes(minBufSize).build();
        audioTrack.play();
        usbLogViewModel.log("Stream: Decoders and AudioTrack are ready.");
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        usbLogViewModel.log("Stream: Decoding surface created.");
        this.decodingSurface = holder.getSurface();
        if (isWatching) {
            try {
                setupDecoders();
            } catch (Exception e) {
                usbLogViewModel.log("ERROR: Failed to setup decoders on surfaceCreated", e);
                if(getActivity() != null) getActivity().runOnUiThread(this::stopWatching);
            }
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        usbLogViewModel.log("Stream: Decoding surface destroyed.");
        cleanupDecoders();
        this.decodingSurface = null;
    }

    private class DecoderCallback extends MediaCodec.Callback {
        private final boolean isAudio;
        DecoderCallback(boolean isAudio) { this.isAudio = isAudio; }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            try {
                 if (isAudio) {
                    ByteBuffer outBuf = codec.getOutputBuffer(index);
                    if (outBuf != null && info.size > 0 && audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        byte[] chunk = new byte[info.size];
                        outBuf.get(chunk);
                        audioTrack.write(chunk, 0, info.size);
                    }
                    codec.releaseOutputBuffer(index, false);
                } else {
                    codec.releaseOutputBuffer(index, true);
                }
            } catch (Exception e) {
                usbLogViewModel.log("ERROR: Stream decoder output buffer failed", e);
            }
        }

        @Override public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {}
        @Override public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) { usbLogViewModel.log("ERROR: Stream Decoder error", e); }
        @Override public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {}
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopStream();
        stopWatching();
        if (streamingExecutor != null && !streamingExecutor.isShutdown()) {
            streamingExecutor.shutdown();
        }
        binding = null;
    }
}
