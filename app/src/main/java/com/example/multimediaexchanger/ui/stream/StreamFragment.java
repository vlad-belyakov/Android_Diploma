package com.example.multimediaexchanger.ui.stream;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
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
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
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

    private static final String TAG = "StreamFragment";

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ExecutorService streamingExecutor;
    private MediaCodec videoEncoder, audioEncoder, videoDecoder, audioDecoder;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Surface decodingSurface;

    private static final int VIDEO_WIDTH = 640, VIDEO_HEIGHT = 480, VIDEO_BITRATE = 1000000, VIDEO_FRAME_RATE = 20;
    private static final int AUDIO_SAMPLE_RATE = 44100, AUDIO_BITRATE = 64000, AUDIO_CHANNEL_COUNT = 1;
    private int audioInputBufferSize;

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

    // =========================================================================================
    // STREAMING (SENDING) LOGIC
    // =========================================================================================

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
                setupEncoders();
                requireActivity().runOnUiThread(this::startCameraAndEncoders);
            } catch (Exception e) {
                usbLogViewModel.log("ERROR: Stream setup failed", e);
                if (getActivity() != null) getActivity().runOnUiThread(this::stopStream);
            }
        });
    }

    private void stopStream() {
        if (!isStreaming) return;
        isStreaming = false;
        usbLogViewModel.log("Stream: Streaming stopped.");
        try {
            if (cameraProviderFuture != null && cameraProviderFuture.get() != null) cameraProviderFuture.get().unbindAll();
        } catch (Exception e) { usbLogViewModel.log("WARN: Error unbinding camera", e); }

        streamingExecutor.execute(this::cleanupEncoders);

        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                binding.startStreamButton.setText("Начать трансляцию");
                binding.cameraPreviewView.setVisibility(View.GONE);
            });
        }
    }

    @SuppressLint("MissingPermission")
    private void setupEncoders() throws IOException {
        // Video Encoder
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaFormat videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT);
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        videoEncoder.setCallback(new EncoderCallback(true));

        // Audio Encoder
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
        audioInputBufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.setCallback(new EncoderCallback(false));
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, audioInputBufferSize);
    }

    private void startCameraAndEncoders() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext());
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.cameraPreviewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(VIDEO_WIDTH, VIDEO_HEIGHT))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(streamingExecutor, this::processImageForEncoder);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);

                videoEncoder.start();
                audioEncoder.start();
                audioRecord.startRecording();

                streamingExecutor.execute(this::audioEncodingLoop);

            } catch (Exception e) {
                usbLogViewModel.log("ERROR: Failed to bind camera for streaming", e);
                stopStream();
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }
    
    private void processImageForEncoder(ImageProxy image) {
         if (isStreaming && videoEncoder != null && image.getImage() != null) {
            try {
                int inputBufferIndex = videoEncoder.dequeueInputBuffer(-1);
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = videoEncoder.getInputBuffer(inputBufferIndex);
                    inputBuffer.clear();
                    // Simplified YUV420 to NV21 conversion
                    ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
                    ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
                    ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();
                    int ySize = yBuffer.remaining();
                    int uSize = uBuffer.remaining();
                    int vSize = vBuffer.remaining();
                    byte[] nv21 = new byte[ySize + uSize + vSize];
                    yBuffer.get(nv21, 0, ySize);
                    vBuffer.get(nv21, ySize, vSize);
                    uBuffer.get(nv21, ySize + vSize, uSize);

                    inputBuffer.put(nv21);
                    videoEncoder.queueInputBuffer(inputBufferIndex, 0, nv21.length, image.getImageInfo().getTimestamp(), 0);
                }
            } catch (Exception e) {
                usbLogViewModel.log("WARN: Image processing for encoder failed", e);
            } finally {
                image.close();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void audioEncodingLoop() {
        while (isStreaming && audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                int inputBufferIndex = audioEncoder.dequeueInputBuffer(-1);
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputBufferIndex);
                    inputBuffer.clear();
                    int length = audioRecord.read(inputBuffer, audioInputBufferSize);
                    if (length > 0) {
                        audioEncoder.queueInputBuffer(inputBufferIndex, 0, length, System.nanoTime() / 1000, 0);
                    }
                }
            } catch (Exception e) {
                usbLogViewModel.log("ERROR: Audio encoding loop failed", e);
                break;
            }
        }
    }

    private void cleanupEncoders() {
        try { if (audioRecord != null) { if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop(); audioRecord.release(); audioRecord = null; } } catch (Exception e) { usbLogViewModel.log("WARN: AudioRecord cleanup failed", e); }
        try { if (videoEncoder != null) { videoEncoder.stop(); videoEncoder.release(); videoEncoder = null; } } catch (Exception e) { usbLogViewModel.log("WARN: Video encoder cleanup failed", e); }
        try { if (audioEncoder != null) { audioEncoder.stop(); audioEncoder.release(); audioEncoder = null; } } catch (Exception e) { usbLogViewModel.log("WARN: Audio encoder cleanup failed", e); }
        usbLogViewModel.log("Stream: Encoders cleaned up.");
    }

    // =========================================================================================
    // WATCHING (RECEIVING) LOGIC
    // =========================================================================================

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
            startDecoding();
        }
    }

    private void stopWatching() {
        if (!isWatching) return;
        isWatching = false;
        usbLogViewModel.log("Stream: Watching stopped.");
        cleanupDecoders();
        
        if(getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                binding.watchStreamButton.setText("Смотреть трансляцию");
                binding.decodedStreamView.setVisibility(View.GONE);
            });
        }
    }

    private void observeIncomingData() {
        udpViewModel.getReceivedMessage().observe(getViewLifecycleOwner(), message -> {
            if (message == null || !isWatching) return;
            // Offload to executor to avoid blocking LiveData thread
            streamingExecutor.execute(() -> {
                switch (message.type) {
                    case UdpViewModel.MESSAGE_TYPE_STREAM_VIDEO_CONFIG:
                    case UdpViewModel.MESSAGE_TYPE_STREAM_VIDEO_DATA:
                        if (videoDecoder != null) feedDecoder(videoDecoder, message.payload, message.type == UdpViewModel.MESSAGE_TYPE_STREAM_VIDEO_CONFIG);
                        break;

                    case UdpViewModel.MESSAGE_TYPE_STREAM_AUDIO_CONFIG:
                    case UdpViewModel.MESSAGE_TYPE_STREAM_AUDIO_DATA:
                        if (audioDecoder != null) feedDecoder(audioDecoder, message.payload, message.type == UdpViewModel.MESSAGE_TYPE_STREAM_AUDIO_CONFIG);
                        break;
                }
            });
        });
    }
    
    private void feedDecoder(MediaCodec decoder, byte[] data, boolean isConfig) {
        try {
            int index = decoder.dequeueInputBuffer(-1);
            if (index >= 0) {
                ByteBuffer buffer = decoder.getInputBuffer(index);
                buffer.clear();
                buffer.put(data);
                long presentationTime = (isConfig) ? 0 : System.nanoTime() / 1000;
                int flags = (isConfig) ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
                decoder.queueInputBuffer(index, 0, data.length, presentationTime, flags);
            }
        } catch (Exception e) {
            usbLogViewModel.log("WARN: Failed to feed decoder", e);
        }
    }

    private void startDecoding() {
        streamingExecutor.execute(() -> {
            try {
                setupDecoders();
            } catch (Exception e) {
                usbLogViewModel.log("ERROR: Failed to setup decoders on watch start", e);
                if (getActivity() != null) getActivity().runOnUiThread(this::stopWatching);
            }
        });
    }

    private void setupDecoders() throws IOException {
        if (decodingSurface == null) throw new IOException("Decoding surface is not available.");
        usbLogViewModel.log("Stream: Setting up decoders...");
        
        // Video Decoder
        videoDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaFormat vFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT);
        videoDecoder.setCallback(new DecoderCallback(true));
        videoDecoder.configure(vFmt, decodingSurface, null, 0);
        videoDecoder.start();

        // Audio Decoder
        audioDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        int aChanCfg = (AUDIO_CHANNEL_COUNT == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        MediaFormat aFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT);
        audioDecoder.setCallback(new DecoderCallback(false));
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

    private void cleanupDecoders() {
        try { if (videoDecoder != null) { videoDecoder.stop(); videoDecoder.release(); videoDecoder = null; } } catch (Exception e) { usbLogViewModel.log("WARN: Video decoder cleanup failed", e); }
        try { if (audioDecoder != null) { audioDecoder.stop(); audioDecoder.release(); audioDecoder = null; } } catch (Exception e) { usbLogViewModel.log("WARN: Audio decoder cleanup failed", e); }
        try { if (audioTrack != null) { if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) audioTrack.stop(); audioTrack.release(); audioTrack = null; } } catch (Exception e) { usbLogViewModel.log("WARN: Audio track cleanup failed", e); }
        usbLogViewModel.log("Stream: Decoders cleaned up.");
    }

    // =========================================================================================
    // CODEC CALLBACKS (Used for both encoding and decoding)
    // =========================================================================================

    private class EncoderCallback extends MediaCodec.Callback {
        private final boolean isVideo;

        EncoderCallback(boolean isVideo) {
            this.isVideo = isVideo;
        }

        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) { /* Not used in encoder for this setup */ }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            if (!isStreaming) return;
            try {
                ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                if (outputBuffer != null && info.size > 0) {
                    byte packetType;
                    if (isVideo) {
                        packetType = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 ? UdpViewModel.MESSAGE_TYPE_STREAM_VIDEO_CONFIG : UdpViewModel.MESSAGE_TYPE_STREAM_VIDEO_DATA;
                    } else {
                        packetType = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 ? UdpViewModel.MESSAGE_TYPE_STREAM_AUDIO_CONFIG : UdpViewModel.MESSAGE_TYPE_STREAM_AUDIO_DATA;
                    }
                    
                    byte[] data = new byte[info.size];
                    outputBuffer.get(data);

                    String targetIp = networkViewModel.getTargetIpAddress().getValue();
                    if (targetIp != null && !targetIp.isEmpty()) {
                         udpViewModel.sendData(targetIp, packetType, data);
                    }
                }
                codec.releaseOutputBuffer(index, false);
            } catch (Exception e) {
                usbLogViewModel.log("ERROR: " + (isVideo ? "Video" : "Audio") + " encoder output processing failed", e);
            }
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            usbLogViewModel.log("ERROR: " + (isVideo ? "Video" : "Audio") + " Encoder onError", e);
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
            usbLogViewModel.log("INFO: " + (isVideo ? "Video" : "Audio") + " Encoder format changed: " + format);
        }
    }

    private class DecoderCallback extends MediaCodec.Callback {
        private final boolean isVideo;

        DecoderCallback(boolean isVideo) {
            this.isVideo = isVideo;
        }

        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) { /* Not used in decoder for this setup */ }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            if (!isWatching) return;

            try {
                 if (isVideo) {
                    // The key change is here: we now render every frame.
                    codec.releaseOutputBuffer(index, true);
                 } else {
                    ByteBuffer outBuf = codec.getOutputBuffer(index);
                    if (outBuf != null && info.size > 0 && audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        byte[] chunk = new byte[info.size];
                        outBuf.get(chunk);
                        audioTrack.write(chunk, 0, info.size);
                    }
                    codec.releaseOutputBuffer(index, false);
                }
            } catch (Exception e) {
                 usbLogViewModel.log("WARN: " + (isVideo ? "Video" : "Audio") + " decoder output processing failed", e);
            }
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            usbLogViewModel.log("ERROR: " + (isVideo ? "Video" : "Audio") + " Decoder onError", e);
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
             usbLogViewModel.log("INFO: " + (isVideo ? "Video" : "Audio") + " Decoder format changed: " + format);
        }
    }

    // =========================================================================================
    // SURFACEHOLDER CALLBACKS
    // =========================================================================================

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        usbLogViewModel.log("Stream: Decoding surface created.");
        this.decodingSurface = holder.getSurface();
        if (isWatching) {
            startDecoding();
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        usbLogViewModel.log("Stream: Decoding surface destroyed.");
        this.decodingSurface = null;
        stopWatching();
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
