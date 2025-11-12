package com.example.multimediaexchanger.ui.stream;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.multimediaexchanger.databinding.FragmentStreamBinding;
import com.example.multimediaexchanger.ui.network.NetworkViewModel;
import com.example.multimediaexchanger.ui.UdpViewModel;
import com.example.multimediaexchanger.ui.UsbLogViewModel;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StreamFragment extends Fragment implements SurfaceHolder.Callback {

    private FragmentStreamBinding binding;
    private UsbLogViewModel usbLogViewModel;
    private UdpViewModel udpViewModel;
    private NetworkViewModel networkViewModel;

    // Common
    private ExecutorService streamingExecutor;
    private volatile boolean isStreaming = false;
    private volatile boolean isWatching = false;

    // Streaming (Encoding) Constants & Vars
    private static final int VIDEO_WIDTH = 640;
    private static final int VIDEO_HEIGHT = 480;
    private static final int VIDEO_BITRATE = 1_000_000; // 1 Mbps
    private static final int VIDEO_FRAME_RATE = 30;
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_CHANNEL_COUNT = 1;
    private static final int AUDIO_BITRATE = 64000;

    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;
    private AudioRecord audioRecord;
    private int audioInputBufferSize;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private EncoderCallback videoEncoderCallback;
    private EncoderCallback audioEncoderCallback;

    // Watching (Decoding) Vars
    private MediaCodec videoDecoder;
    private MediaCodec audioDecoder;
    private AudioTrack audioTrackPlayer;
    private Surface decodingSurface;
    private DecoderCallback videoDecoderCallback;
    private DecoderCallback audioDecoderCallback;

    private volatile boolean videoConfigAckReceived = false;
    private volatile boolean audioConfigAckReceived = false;
    // Храним последние конфигурационные данные для повторной отправки
    private byte[] lastVideoConfigData;
    private byte[] lastAudioConfigData;


    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentStreamBinding.inflate(inflater, container, false);
        usbLogViewModel = new ViewModelProvider(requireActivity()).get(UsbLogViewModel.class);
        udpViewModel = new ViewModelProvider(requireActivity()).get(UdpViewModel.class);
        networkViewModel = new ViewModelProvider(requireActivity()).get(NetworkViewModel.class);
        streamingExecutor = Executors.newFixedThreadPool(4); // Threads for camera, audio, and network
        binding.decodedStreamView.getHolder().addCallback(this);
        setupButtons();
        observeIncomingData();
        return binding.getRoot();
    }

    private void setupButtons() {
        binding.startStreamButton.setOnClickListener(v -> {
            if (isStreaming) {
                stopStream();
            } else {
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

    // =========================================================================================
    // STREAMING (SENDING) LOGIC
    // =========================================================================================

    private void startStream() {
        if (isStreaming) return;
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
            if (cameraProviderFuture != null && cameraProviderFuture.get() != null) {
                cameraProviderFuture.get().unbindAll();
            }
        } catch (Exception e) {
            usbLogViewModel.log("WARN: Error unbinding camera", e);
        }

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
        videoEncoderCallback = new EncoderCallback(true);
        audioEncoderCallback = new EncoderCallback(false);

        // --- НАЧАЛО ИЗМЕНЕНИЯ ---
        // Video Encoder
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaFormat videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT);
        // Говорим кодеку, что будем использовать Surface для ввода данных
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        videoEncoder.setCallback(videoEncoderCallback);
        // --- КОНЕЦ ИЗМЕНЕНИЯ ---

        // Audio Encoder (без изменений)
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
        audioInputBufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.setCallback(audioEncoderCallback);

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_PERFORMANCE, AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, audioInputBufferSize);
    }


    private void startCameraAndEncoders() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext());
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // --- НАЧАЛО ФИНАЛЬНОГО ИСПРАВЛЕНИЯ ---

                // 1. Создаем Preview для отображения на экране стримера
                Preview screenPreview = new Preview.Builder()
                        .setTargetResolution(new Size(VIDEO_WIDTH, VIDEO_HEIGHT))
                        .setTargetRotation(binding.cameraPreviewView.getDisplay().getRotation()) // Поворот для экрана
                        .build();
                screenPreview.setSurfaceProvider(binding.cameraPreviewView.getSurfaceProvider());

                // 2. Создаем Preview для энкодера (без отображения на экране)
                Surface encoderSurface = videoEncoder.createInputSurface();
                Preview encoderPreview = new Preview.Builder()
                        .setTargetResolution(new Size(VIDEO_WIDTH, VIDEO_HEIGHT))
                        .setTargetRotation(binding.cameraPreviewView.getDisplay().getRotation()) // Поворот для энкодера
                        .build();
                // Направляем поток с этого Preview в Surface энкодера
                encoderPreview.setSurfaceProvider(surface -> surface.provideSurface(encoderSurface, ContextCompat.getMainExecutor(requireContext()), result -> {}));

                // Биндим камеру СРАЗУ К ДВУМ Preview
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, screenPreview, encoderPreview);

                // --- КОНЕЦ ФИНАЛЬНОГО ИСПРАВЛЕНИЯ ---

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




    @SuppressLint("MissingPermission")
    private void audioEncodingLoop() {
        Thread.currentThread().setName("AudioEncodingThread");
        while (isStreaming && audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                if (audioEncoder == null || audioEncoderCallback == null) break;
                Integer inputBufferIndex = audioEncoderCallback.pollInputBuffer();
                if (inputBufferIndex != null) {
                    ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputBufferIndex);
                    inputBuffer.clear();
                    int length = audioRecord.read(inputBuffer, audioInputBufferSize);
                    if (length > 0) {
                        long presentationTimeUs = System.nanoTime() / 1000;
                        audioEncoder.queueInputBuffer(inputBufferIndex, 0, length, presentationTimeUs, 0);
                    }
                } else {
                    Thread.sleep(10);
                }
            } catch (Exception e) {
                usbLogViewModel.log("ERROR: Audio encoding loop failed", e);
                break;
            }
        }
    }



    private void cleanupEncoders() {
        try {        if (audioRecord != null) {
            if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop();
            audioRecord.release();
        }
        } catch (Exception e) { usbLogViewModel.log("WARN: AudioRecord cleanup failed", e); }
        try {
            if (videoEncoder != null) {
                videoEncoder.stop();
                videoEncoder.release();
            }
        } catch (Exception e) { usbLogViewModel.log("WARN: Video encoder cleanup failed", e); }
        try {
            if (audioEncoder != null) {
                audioEncoder.stop();
                audioEncoder.release();
            }
        } catch (Exception e) { usbLogViewModel.log("WARN: Audio encoder cleanup failed", e); }

        // --- НАЧАЛО ИСПРАВЛЕНИЯ ---
        // Обнуляем все переменные
        audioRecord = null;
        videoEncoder = null;
        audioEncoder = null;
        videoEncoderCallback = null;
        audioEncoderCallback = null;
        // --- КОНЕЦ ИСПРАВЛЕНИЯ ---

        usbLogViewModel.log("Stream: Encoders cleaned up.");
    }

    private class EncoderCallback extends MediaCodec.Callback {
        private final boolean isVideo;
        private final ConcurrentLinkedQueue<Integer> availableInputBuffers = new ConcurrentLinkedQueue<>();

        EncoderCallback(boolean isVideo) {
            this.isVideo = isVideo;
        }

        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            availableInputBuffers.offer(index);
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo bufferInfo) {
            try {
                ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                if (outputBuffer == null) {
                    codec.releaseOutputBuffer(index, false);
                    return;
                }
                byte[] data = new byte[bufferInfo.size];
                outputBuffer.get(data);
                String targetIp = networkViewModel.getTargetIpAddress().getValue();

                if (targetIp != null && !targetIp.isEmpty()) {
                    byte messageType;

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        messageType = isVideo ? UdpViewModel.MESSAGE_TYPE_STREAM_VIDEO_CONFIG : UdpViewModel.MESSAGE_TYPE_STREAM_AUDIO_CONFIG;
                        usbLogViewModel.log("Stream: Sending " + (isVideo ? "Video" : "Audio") + " CONFIG frame, size: " + data.length);

                        // --- Логика ACK для конфигурации ---
                        if (isVideo) {
                            videoConfigAckReceived = false;
                            lastVideoConfigData = data; // Сохраняем для повторной отправки
                            streamingExecutor.execute(this::resendVideoConfigLoop); // Запускаем цикл
                        } else {
                            audioConfigAckReceived = false;
                            lastAudioConfigData = data; // Сохраняем для повторной отправки
                            streamingExecutor.execute(this::resendAudioConfigLoop); // Запускаем цикл
                        }
                    } else {
                        messageType = isVideo ? UdpViewModel.MESSAGE_TYPE_STREAM_VIDEO_DATA : UdpViewModel.MESSAGE_TYPE_STREAM_AUDIO_DATA;
                    }

                    udpViewModel.sendData(targetIp, messageType, data);
                }
                codec.releaseOutputBuffer(index, false);
            } catch (Exception e) {
                usbLogViewModel.log("ERROR in EncoderCallback", e);
            }
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            usbLogViewModel.log("ERROR: MediaCodec " + (isVideo ? "Video" : "Audio") + " Encoder error", e);
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
            usbLogViewModel.log("Stream: " + (isVideo ? "Video" : "Audio") + " Encoder format changed to " + format);
        }

        public Integer pollInputBuffer() {
            return availableInputBuffers.poll();
        }

        // --- Методы для повторной отправки конфигов ---
        private void resendVideoConfigLoop() {
            while (isStreaming && !videoConfigAckReceived) {
                try {
                    String targetIp = networkViewModel.getTargetIpAddress().getValue();
                    if (targetIp != null && !targetIp.isEmpty() && lastVideoConfigData != null) {
                        usbLogViewModel.log("Stream: Resending VIDEO CONFIG...");
                        udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_STREAM_VIDEO_CONFIG, lastVideoConfigData);
                    }
                    Thread.sleep(200); // Пауза перед повторной отправкой
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    usbLogViewModel.log("WARN: resendVideoConfigLoop failed", e);
                    break;
                }
            }
        }

        private void resendAudioConfigLoop() {
            while (isStreaming && !audioConfigAckReceived) {
                try {
                    String targetIp = networkViewModel.getTargetIpAddress().getValue();
                    if (targetIp != null && !targetIp.isEmpty() && lastAudioConfigData != null) {
                        usbLogViewModel.log("Stream: Resending AUDIO CONFIG...");
                        udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_STREAM_AUDIO_CONFIG, lastAudioConfigData);
                    }
                    Thread.sleep(200); // Пауза перед повторной отправкой
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    usbLogViewModel.log("WARN: resendAudioConfigLoop failed", e);
                    break;
                }
            }
        }
    }



    // =========================================================================================
    // WATCHING (RECEIVING) LOGIC
    // =========================================================================================

    private void startWatching() {
        if (isWatching) return;
        isWatching = true;
        usbLogViewModel.log("Stream: Starting to watch...");
        requireActivity().runOnUiThread(() -> {
            binding.watchStreamButton.setText("Остановить просмотр");
            binding.decodedStreamView.setVisibility(View.VISIBLE);
            binding.cameraPreviewView.setVisibility(View.GONE);
        });
    }

    private void stopWatching() {
        if (!isWatching) return;
        isWatching = false;
        usbLogViewModel.log("Stream: Watching stopped.");
        streamingExecutor.execute(this::cleanupDecoders);
        requireActivity().runOnUiThread(() -> {
            binding.watchStreamButton.setText("Смотреть трансляцию");
            binding.decodedStreamView.setVisibility(View.GONE);
        });
    }

    private void observeIncomingData() {        udpViewModel.getStreamMessages().observe(getViewLifecycleOwner(), message -> {
        if (message == null) return;

        // --- Обрабатываем ACK, если мы стримим ---
        if (isStreaming) {
            if (message.type == UdpViewModel.MESSAGE_TYPE_STREAM_VIDEO_CONFIG_ACK) {
                usbLogViewModel.log("Stream: Video Config ACK received.");
                videoConfigAckReceived = true;
                return; // Этот пакет больше обрабатывать не нужно
            }
            if (message.type == UdpViewModel.MESSAGE_TYPE_STREAM_AUDIO_CONFIG_ACK) {
                usbLogViewModel.log("Stream: Audio Config ACK received.");
                audioConfigAckReceived = true;
                return; // Этот пакет больше обрабатывать не нужно
            }
        }

        // --- Обрабатываем данные стрима, если мы смотрим ---
        if (!isWatching || message.payload == null) return;

        boolean isVideo = message.type == UdpViewModel.MESSAGE_TYPE_STREAM_VIDEO_CONFIG ||
                message.type == UdpViewModel.MESSAGE_TYPE_STREAM_VIDEO_DATA;

        streamingExecutor.execute(() -> processStreamMessage(isVideo, message));
    });
    }

    private void processStreamMessage(boolean isVideo, UdpViewModel.UdpMessage message) {
        try {
            boolean isConfig = message.type == UdpViewModel.MESSAGE_TYPE_STREAM_VIDEO_CONFIG ||
                    message.type == UdpViewModel.MESSAGE_TYPE_STREAM_AUDIO_CONFIG;

            if (isConfig && (isVideo ? videoDecoder == null : audioDecoder == null)) {
                // Успешно настраиваем декодер
                setupDecoder(isVideo, message.payload);

                // --- Отправляем ACK в ответ ---
                String targetIp = message.senderIp;
                if (targetIp != null && !targetIp.isEmpty()) {
                    byte ackType = isVideo ? UdpViewModel.MESSAGE_TYPE_STREAM_VIDEO_CONFIG_ACK : UdpViewModel.MESSAGE_TYPE_STREAM_AUDIO_CONFIG_ACK;
                    usbLogViewModel.log("Stream: Sending " + (isVideo ? "Video" : "Audio") + " Config ACK to " + targetIp);
                    udpViewModel.sendData(targetIp, ackType, new byte[]{1}); // payload не важен, но не может быть пустым
                }

            } else if (!isConfig && (isVideo ? videoDecoder != null : audioDecoder != null)) {
                feedDecoder(isVideo, message.payload);
            }
        } catch (Exception e) {
            usbLogViewModel.log("ERROR processing " + (isVideo ? "video" : "audio") + " stream data", e);
        }
    }



    private void setupDecoder(boolean isVideo, byte[] configData) throws IOException {
        if (isVideo) {
            usbLogViewModel.log("Stream: Received video config. Setting up video decoder.");
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(configData));
            videoDecoderCallback = new DecoderCallback(true);
            videoDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            videoDecoder.configure(format, decodingSurface, null, 0);
            videoDecoder.setCallback(videoDecoderCallback);
            videoDecoder.start();
        } else {
            usbLogViewModel.log("Stream: Received audio config. Setting up audio decoder.");
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(configData));
            audioDecoderCallback = new DecoderCallback(false);
            audioDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioDecoder.configure(format, null, null, 0);
            audioDecoder.setCallback(audioDecoderCallback);
            setupAudioTrackPlayer(format);
            if (audioTrackPlayer != null) audioTrackPlayer.play();
            audioDecoder.start();
        }
    }

    private void feedDecoder(boolean isVideo, byte[] data) {
        MediaCodec decoder = isVideo ? videoDecoder : audioDecoder;
        DecoderCallback callback = isVideo ? videoDecoderCallback : audioDecoderCallback;
        if (decoder == null || callback == null) return;
        try {
            Integer index = callback.pollInputBuffer();
            if (index != null) {
                ByteBuffer buffer = decoder.getInputBuffer(index);
                if (buffer != null) {
                    buffer.clear();
                    buffer.put(data);
                    long presentationTime = System.nanoTime() / 1000;
                    decoder.queueInputBuffer(index, 0, data.length, presentationTime, 0);
                }
            }
        } catch (Exception e) { usbLogViewModel.log("WARN: Failed to feed decoder", e); }
    }

    private void setupAudioTrackPlayer(MediaFormat format) {
        try {
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int channelConfig = (channelCount == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
            audioTrackPlayer = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .build();
        } catch (Exception e) {
            usbLogViewModel.log("ERROR: Failed to create AudioTrack", e);
        }
    }

    private void cleanupDecoders() {
        try {
            if (videoDecoder != null) {
                videoDecoder.stop();
                videoDecoder.release();
            }
        } catch (Exception e) { usbLogViewModel.log("WARN: Video decoder cleanup failed", e); }
        try {
            if (audioDecoder != null) {
                audioDecoder.stop();
                audioDecoder.release();
            }
        } catch (Exception e) { usbLogViewModel.log("WARN: Audio decoder cleanup failed", e); }
        try {
            if (audioTrackPlayer != null) {
                if(audioTrackPlayer.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) audioTrackPlayer.stop();
                audioTrackPlayer.release();
            }
        } catch (Exception e) { usbLogViewModel.log("WARN: AudioTrack cleanup failed", e); }

        // --- НАЧАЛО ИСПРАВЛЕНИЯ ---
        // Обнуляем все переменные
        videoDecoder = null;
        audioDecoder = null;
        audioTrackPlayer = null;
        videoDecoderCallback = null;
        audioDecoderCallback = null;
        // --- КОНЕЦ ИСПРАВЛЕНИЯ ---

        usbLogViewModel.log("Stream: Decoders cleaned up.");
    }

    private class DecoderCallback extends MediaCodec.Callback {
        private final boolean isVideo;
        private final ConcurrentLinkedQueue<Integer> availableInputBuffers = new ConcurrentLinkedQueue<>();
        DecoderCallback(boolean isVideo) { this.isVideo = isVideo; }
        @Override public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) { availableInputBuffers.offer(index); }
        @Override public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo bufferInfo) {
            try {
                if (isVideo) {
                    codec.releaseOutputBuffer(index, bufferInfo.size != 0);
                } else {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                    if (outputBuffer != null && bufferInfo.size > 0 && audioTrackPlayer != null && audioTrackPlayer.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        byte[] chunk = new byte[bufferInfo.size];
                        outputBuffer.get(chunk);
                        audioTrackPlayer.write(chunk, 0, chunk.length);
                    }
                    codec.releaseOutputBuffer(index, false);
                }
            } catch (Exception e) { usbLogViewModel.log("ERROR in DecoderCallback", e); }
        }
        @Override public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) { usbLogViewModel.log("ERROR: MediaCodec " + (isVideo ? "Video" : "Audio") + " Decoder error", e); }
        @Override public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
            usbLogViewModel.log("Stream: " + (isVideo ? "Video" : "Audio") + " Decoder format changed to " + format);
            if (!isVideo && audioTrackPlayer != null) {
                audioTrackPlayer.stop();
                audioTrackPlayer.release();
                setupAudioTrackPlayer(format);
                if (audioTrackPlayer != null) audioTrackPlayer.play();
            }
        }
        public Integer pollInputBuffer() { return availableInputBuffers.poll(); }
    }

    // SurfaceHolder.Callback implementation
    @Override public void surfaceCreated(@NonNull SurfaceHolder holder) {
        usbLogViewModel.log("Stream: Decoding surface created.");
        decodingSurface = holder.getSurface();
    }
    @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        usbLogViewModel.log("Stream: Decoding surface destroyed.");
        if (isWatching) stopWatching();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopStream();
        stopWatching();
        streamingExecutor.shutdownNow();
        binding = null;
    }
}
