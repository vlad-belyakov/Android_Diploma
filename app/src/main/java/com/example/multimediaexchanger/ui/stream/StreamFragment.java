package com.example.multimediaexchanger.ui.stream;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.multimediaexchanger.databinding.FragmentStreamBinding;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StreamFragment extends Fragment {

    private FragmentStreamBinding binding;
    private UsbManager usbManager;
    private UsbDeviceConnection connection;
    private UsbEndpoint endpointIn, endpointOut;
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    private static final String TAG = "StreamFragment";

    private volatile boolean isDeviceConnected = false;
    private volatile boolean isStreaming = false;
    private volatile boolean stopReading = false;
    private Thread readingThread;

    private static final byte COMMAND_STREAM = 0x02;
    private static final byte COMMAND_CHAT = 0x03;
    private static final byte PACKET_TYPE_VIDEO = 0x10;
    private static final byte PACKET_TYPE_AUDIO = 0x20;

    // --- Components ---
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ExecutorService cameraExecutor, streamingExecutor;
    private MediaCodec videoEncoder, audioEncoder, videoDecoder, audioDecoder;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Surface decodingSurface;

    // --- Configs ---
    private static final int VIDEO_WIDTH = 1280, VIDEO_HEIGHT = 720, VIDEO_BITRATE = 2000000, VIDEO_FRAME_RATE = 30;
    private static final int AUDIO_SAMPLE_RATE = 44100, AUDIO_BITRATE = 64000, AUDIO_CHANNEL_COUNT = 1;

    private final ActivityResultLauncher<String[]> requestPermissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), perms -> {
                if (!Boolean.TRUE.equals(perms.get(Manifest.permission.CAMERA)) || !Boolean.TRUE.equals(perms.get(Manifest.permission.RECORD_AUDIO))) {
                    Toast.makeText(getContext(), "Требуются разрешения на использование камеры и микрофона.", Toast.LENGTH_SHORT).show();
                }
            });

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                        setupCommunication(device);
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                closeConnection();
            }
        }
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentStreamBinding.inflate(inflater, container, false);

        cameraExecutor = Executors.newSingleThreadExecutor();
        streamingExecutor = Executors.newCachedThreadPool();

        usbManager = (UsbManager) requireActivity().getSystemService(Context.USB_SERVICE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(requireActivity(), usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        binding.chatLogTextview.setMovementMethod(new ScrollingMovementMethod());
        decodingSurface = binding.decodedStreamView.getHolder().getSurface();

        setupClickListeners();
        checkPermissions();
        checkForConnectedDevice();

        return binding.getRoot();
    }

    private void setupClickListeners() {
        binding.startStreamButton.setOnClickListener(v -> {
            if (isStreaming) {
                stopVideoStream();
            } else {
                if (!isDeviceConnected) {
                    Toast.makeText(getContext(), "USB-устройство не подключено.", Toast.LENGTH_SHORT).show();
                    return;
                }
                startVideoStream();
            }
        });

        binding.watchStreamButton.setOnClickListener(v -> {
            if (!isDeviceConnected) {
                Toast.makeText(getContext(), "USB-устройство не подключено.", Toast.LENGTH_SHORT).show();
                return;
            }
            requireActivity().runOnUiThread(() -> {
                binding.decodedStreamView.setVisibility(View.VISIBLE);
                binding.cameraPreviewView.setVisibility(View.GONE);
                Toast.makeText(getContext(), "Ожидание трансляции...", Toast.LENGTH_SHORT).show();
            });
        });

        binding.sendChatMessageButton.setOnClickListener(v -> {
            String message = binding.chatMessageInput.getText().toString();
            if (!message.isEmpty() && isDeviceConnected) {
                sendChatMessage(message);
                binding.chatMessageInput.setText("");
                binding.chatLogTextview.append("Я: " + message + "\n");
            }
        });
    }

    private void checkPermissions(){
         if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsLauncher.launch(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO});
        }
    }

    // --- SENDER LOGIC ---

    @SuppressLint("MissingPermission")
    private void startVideoStream() {
        isStreaming = true;
        requireActivity().runOnUiThread(() -> {
             binding.startStreamButton.setText("Остановить трансляцию");
             binding.cameraPreviewView.setVisibility(View.VISIBLE);
             binding.decodedStreamView.setVisibility(View.GONE);
        });

        streamingExecutor.execute(() -> {
            try {
                connection.bulkTransfer(endpointOut, new byte[]{COMMAND_STREAM}, 1, 5000);
                ByteBuffer configBuffer = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
                        .putInt(VIDEO_WIDTH).putInt(VIDEO_HEIGHT).putInt(VIDEO_BITRATE)
                        .putInt(AUDIO_SAMPLE_RATE).putInt(AUDIO_CHANNEL_COUNT);
                connection.bulkTransfer(endpointOut, configBuffer.array(), 20, 5000);

                setupAudioEncoder();
                setupVideoEncoder();

                streamingExecutor.execute(this::streamAudio);

                requireActivity().runOnUiThread(this::startCameraAndAnalysis);

            } catch (Exception e) {
                Log.e(TAG, "Streaming setup failed", e);
                if (getActivity() != null) getActivity().runOnUiThread(this::stopVideoStream);
            }
        });
    }

    private void startCameraAndAnalysis() {
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

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    if (isStreaming && videoEncoder != null) {
                        int bufferIndex = videoEncoder.dequeueInputBuffer(-1);
                        if (bufferIndex >= 0) {
                            ByteBuffer inputBuffer = videoEncoder.getInputBuffer(bufferIndex);
                            if (inputBuffer != null) {
                                inputBuffer.clear();
                                inputBuffer.put(image.getPlanes()[0].getBuffer());
                                inputBuffer.put(image.getPlanes()[2].getBuffer());
                                videoEncoder.queueInputBuffer(bufferIndex, 0, inputBuffer.position(), System.nanoTime() / 1000, 0);
                            }
                        }
                    }
                    image.close();
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);
                Log.d(TAG, "Трансляция началась.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to bind camera for streaming", e);
                stopVideoStream();
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void stopVideoStream() {
        if (!isStreaming) return;
        isStreaming = false;

        try {
             if (cameraProviderFuture != null) cameraProviderFuture.get().unbindAll();
             if (videoEncoder != null) { videoEncoder.stop(); videoEncoder.release(); videoEncoder = null; }
             if (audioEncoder != null) { audioEncoder.stop(); audioEncoder.release(); audioEncoder = null; }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping encoders/camera", e);
        }

        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                binding.startStreamButton.setText("Начать трансляцию");
                binding.cameraPreviewView.setVisibility(View.GONE);
            });
        }
        Log.d(TAG, "Трансляция остановлена.");
    }

    private void sendChatMessage(String message) {
        streamingExecutor.execute(() -> {
            if (!isDeviceConnected || endpointOut == null) return;
            try {
                byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
                ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + messageBytes.length).order(ByteOrder.BIG_ENDIAN);
                buffer.put(COMMAND_CHAT).putInt(messageBytes.length).put(messageBytes);
                connection.bulkTransfer(endpointOut, buffer.array(), buffer.position(), 5000);
            } catch (Exception e) {
                Log.e(TAG, "Send chat message failed", e);
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void streamAudio() {
        int bufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        audioRecord.startRecording();

        while (isStreaming) {
            if (audioEncoder == null) break;
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
        }

        if (audioRecord != null) {
            if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
    }

    private void setupVideoEncoder() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
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
            if (!isStreaming || connection == null || endpointOut == null) return;
            ByteBuffer outputBuffer = codec.getOutputBuffer(index);
            if (outputBuffer != null && info.size > 0 && info.presentationTimeUs > 0) {
                byte[] data = new byte[info.size];
                outputBuffer.get(data);
                ByteBuffer header = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).put(packetType).putInt(info.size);
                connection.bulkTransfer(endpointOut, header.array(), 5, 5000);
                connection.bulkTransfer(endpointOut, data, info.size, 5000);
            }
            codec.releaseOutputBuffer(index, false);
        }
        @Override public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {}
        @Override public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) { Log.e(TAG, "MediaCodec Error", e); }
        @Override public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {}
    }

    // --- RECIPIENT LOGIC ---

    private void startReading() {
        stopReading = false;
        readingThread = new Thread(() -> {
            while (!stopReading) {
                if (connection == null || endpointIn == null) break;
                try {
                    byte[] commandBytes = readExactly(1);
                    if (commandBytes == null) break;
                    byte command = commandBytes[0];

                    if (command == COMMAND_STREAM) handleStreamCommand();
                    else if (command == COMMAND_CHAT) handleChatCommand();

                } catch (IOException e) {
                    if (!stopReading) Log.e(TAG, "Protocol error", e);
                    break;
                }
            }
        });
        readingThread.start();
    }

    private void handleChatCommand() throws IOException {
        byte[] lengthBytes = readExactly(4);
        if (lengthBytes == null) throw new IOException("Stream ended");
        int length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).getInt();
        if (length <= 0 || length > 4096) throw new IOException("Invalid chat length");

        byte[] messageBytes = readExactly(length);
        if (messageBytes == null) throw new IOException("Stream ended");
        final String message = new String(messageBytes, StandardCharsets.UTF_8);

        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> binding.chatLogTextview.append("Собеседник: " + message + "\n"));
        }
    }

    private void handleStreamCommand() throws IOException {
        byte[] configBytes = readExactly(20);
        if (configBytes == null) throw new IOException("Stream ended");

        ByteBuffer cfg = ByteBuffer.wrap(configBytes).order(ByteOrder.BIG_ENDIAN);
        final int vW = cfg.getInt(), vH = cfg.getInt(), vB = cfg.getInt();
        final int aSr = cfg.getInt(), aCc = cfg.getInt();

        requireActivity().runOnUiThread(() -> {
            try {
                binding.decodedStreamView.setVisibility(View.VISIBLE);
                binding.cameraPreviewView.setVisibility(View.GONE);
                setupDecoders(vW, vH, aSr, aCc);
                Toast.makeText(getContext(), "Трансляция началась!", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Log.e(TAG, "Failed to setup decoders", e);
            }
        });

        receiveStream();
    }

    private void setupDecoders(int vW, int vH, int aSr, int aCc) throws IOException {
        MediaFormat vFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vW, vH);
        videoDecoder = MediaCodec.createDecoderByType(vFmt.getString(MediaFormat.KEY_MIME));
        videoDecoder.configure(vFmt, decodingSurface, null, 0);
        videoDecoder.start();

        int aChanCfg = (aCc == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        MediaFormat aFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, aSr, aCc);
        audioDecoder = MediaCodec.createDecoderByType(aFmt.getString(MediaFormat.KEY_MIME));
        audioDecoder.configure(aFmt, null, null, 0);
        audioDecoder.start();

        int minBufSize = AudioTrack.getMinBufferSize(aSr, aChanCfg, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(aSr).setChannelMask(aChanCfg).build())
                .setBufferSizeInBytes(minBufSize).build();
        audioTrack.play();
    }

    private void receiveStream() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (!stopReading) {
            try {
                byte[] typeBytes = readExactly(1);
                if (typeBytes == null) break;
                byte packetType = typeBytes[0];

                byte[] lengthBytes = readExactly(4);
                if (lengthBytes == null) break;
                int dataLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).getInt();

                if (dataLength <= 0 || dataLength > 1_000_000) continue;
                byte[] data = readExactly(dataLength);
                if (data == null) break;

                MediaCodec decoder = (packetType == PACKET_TYPE_VIDEO) ? videoDecoder : audioDecoder;
                if(decoder == null) continue;

                int inIdx = decoder.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                    if (inBuf != null) {
                        inBuf.clear();
                        inBuf.put(data, 0, dataLength);
                        decoder.queueInputBuffer(inIdx, 0, dataLength, System.nanoTime() / 1000, 0);
                    }
                }
                processDecoderOutput(decoder, bufferInfo, packetType == PACKET_TYPE_AUDIO);
            } catch (Exception e) {
                if (!stopReading) Log.e(TAG, "Error reading stream", e);
                break;
            }
        }
    }

    private void processDecoderOutput(MediaCodec decoder, MediaCodec.BufferInfo info, boolean isAudio) {
        if (decoder == null) return;
        int outIdx = decoder.dequeueOutputBuffer(info, 0);
        while(outIdx >= 0) {
            if (isAudio) {
                ByteBuffer outBuf = decoder.getOutputBuffer(outIdx);
                if (info.size > 0 && audioTrack != null) {
                    byte[] chunk = new byte[info.size];
                    outBuf.get(chunk);
                    audioTrack.write(chunk, 0, info.size);
                }
                decoder.releaseOutputBuffer(outIdx, false);
            } else {
                 decoder.releaseOutputBuffer(outIdx, true); // Render to surface
            }
            outIdx = decoder.dequeueOutputBuffer(info, 0);
        }
    }

    private byte[] readExactly(int byteCount) throws IOException {
        byte[] buffer = new byte[byteCount];
        int offset = 0;
        while (offset < byteCount) {
            if (stopReading || connection == null || endpointIn == null) return null;
            int bytesRead = connection.bulkTransfer(endpointIn, buffer, offset, byteCount - offset, 10000);
            if (bytesRead > 0) {
                offset += bytesRead;
            } else {
                 throw new IOException("Failed to read required bytes from USB");
            }
        }
        return buffer;
    }

    // --- COMMON USB & LIFECYCLE LOGIC ---

    private void checkForConnectedDevice() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList.isEmpty()) return;
        for (UsbDevice device : deviceList.values()) {
             PendingIntent pi = PendingIntent.getBroadcast(getContext(), 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
             usbManager.requestPermission(device, pi);
             break;
        }
    }

    private void setupCommunication(UsbDevice device) {
        connection = usbManager.openDevice(device);
        if (connection == null) {
             Log.e(TAG, "Could not open device connection");
             return;
        }
        UsbInterface usbInterface = device.getInterface(0); // Assuming single interface
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close();
            Log.e(TAG, "Could not claim interface");
            return;
        }
        for (int j = 0; j < usbInterface.getEndpointCount(); j++) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(j);
            if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) endpointIn = endpoint;
                if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) endpointOut = endpoint;
            }
        }
        if (endpointIn != null && endpointOut != null) {
            isDeviceConnected = true;
            if (getContext() != null) Toast.makeText(getContext(), "USB-устройство подключено.", Toast.LENGTH_SHORT).show();
            startReading();
        } else {
            Log.e(TAG, "Could not find both IN and OUT endpoints");
            closeConnection();
        }
    }

    private void closeConnection() {
        isDeviceConnected = false;
        stopReading = true;
        if (isStreaming) stopVideoStream();

        if (readingThread != null) {
            try { readingThread.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            readingThread = null;
        }

        // Release all codecs and tracks
        try { if (videoDecoder != null) { videoDecoder.stop(); videoDecoder.release(); } } catch (Exception e) { Log.e(TAG, "Err", e); } finally { videoDecoder = null; }
        try { if (audioDecoder != null) { audioDecoder.stop(); audioDecoder.release(); } } catch (Exception e) { Log.e(TAG, "Err", e); } finally { audioDecoder = null; }
        try { if (audioTrack != null) { audioTrack.stop(); audioTrack.release(); } } catch (Exception e) { Log.e(TAG, "Err", e); } finally { audioTrack = null; }

        if (connection != null) {
            connection.close();
            connection = null;
            endpointIn = null;
            endpointOut = null;
            Log.d(TAG, "USB-подключение закрыто.");
        }

         if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                binding.cameraPreviewView.setVisibility(View.GONE);
                binding.decodedStreamView.setVisibility(View.GONE);
            });
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        closeConnection();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (streamingExecutor != null) streamingExecutor.shutdown();
        try {
            requireActivity().unregisterReceiver(usbReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Unregister receiver failed", e);
        }
        binding = null;
    }
}
