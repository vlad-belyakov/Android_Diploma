package com.example.multimediaexchanger.ui.calls;

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
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.multimediaexchanger.databinding.FragmentCallsBinding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CallsFragment extends Fragment {

    private FragmentCallsBinding binding;
    private UsbManager usbManager;
    private UsbDeviceConnection connection;
    private UsbEndpoint endpointIn, endpointOut;
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    private static final String TAG = "CallsFragment";

    private volatile boolean isDeviceConnected = false;
    private volatile boolean isInCall = false;
    private volatile boolean stopReading = false;
    private Thread readingThread;
    private ExecutorService callExecutor;

    // --- Commands and Packet Types ---
    private static final byte COMMAND_CALL_START = 0x04;
    private static final byte COMMAND_CALL_END = 0x05;
    private static final byte PACKET_TYPE_CALL_AUDIO = 0x30;

    // --- Audio Components ---
    private MediaCodec audioEncoder, audioDecoder;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;

    // --- Audio Config ---
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_BITRATE = 64000;
    private static final int AUDIO_CHANNEL_COUNT = 1;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (!isGranted) {
                    Toast.makeText(getContext(), "Требуется разрешение на использование микрофона.", Toast.LENGTH_SHORT).show();
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
        binding = FragmentCallsBinding.inflate(inflater, container, false);
        callExecutor = Executors.newCachedThreadPool();

        usbManager = (UsbManager) requireActivity().getSystemService(Context.USB_SERVICE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(requireActivity(), usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        setupClickListeners();
        checkPermissions();
        checkForConnectedDevice();

        return binding.getRoot();
    }

    private void setupClickListeners() {
        binding.startCallButton.setOnClickListener(v -> {
            if (!isInCall) {
                if (!isDeviceConnected) {
                    Toast.makeText(getContext(), "USB-устройство не подключено.", Toast.LENGTH_SHORT).show();
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
        callExecutor.execute(() -> {
             try {
                // Send start call command
                connection.bulkTransfer(endpointOut, new byte[]{COMMAND_CALL_START}, 1, 5000);
                Log.d(TAG, "Start call command sent.");
                // Once command is sent, the reading thread on the other side will handle it and start its own call setup.
                // We can start our local setup right away.
                setupAudioStreaming();
            } catch (Exception e) {
                Log.e(TAG, "Failed to send start call command", e);
                if(getActivity() != null) getActivity().runOnUiThread(this::endCall);
            }
        });
    }
    
    private void onCallStarted() {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                binding.startCallButton.setVisibility(View.GONE);
                binding.endCallButton.setVisibility(View.VISIBLE);
                binding.callStatusTextview.setText("В разговоре");
            });
        }
    }

    @SuppressLint("MissingPermission")
    private void setupAudioStreaming() {
        onCallStarted();
        try {
            // 1. Setup Encoder and Recorder (Sending part)
            setupAudioEncoder();
            int bufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            audioRecord.startRecording();
            callExecutor.execute(this::encodeAndSendAudio);

            // 2. Setup Decoder and Track (Receiving part) is initiated by receiving packets in readingThread
            // For duplex, decoder is set up when the first audio packet arrives or when call starts.
            setupAudioDecoderAndTrack();

            Log.d(TAG, "Audio streaming setup complete.");

        } catch (IOException e) {
            Log.e(TAG, "Failed to setup audio streaming", e);
            endCall();
        }
    }

    private void encodeAndSendAudio() {
        while (isInCall) {
            if (audioEncoder == null || audioRecord == null) break;
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
        }
        Log.d(TAG, "Audio encoding thread finished.");
    }

    private void endCall() {
        if (!isInCall) return;
        isInCall = false;
        
        callExecutor.execute(() -> {
            try {
                if(isDeviceConnected && connection != null) {
                    connection.bulkTransfer(endpointOut, new byte[]{COMMAND_CALL_END}, 1, 5000);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to send end call command", e);
            }
        });
        
        cleanupCallResources();
    }
    
    private void cleanupCallResources(){
         // Stop and release all audio components
        if (audioRecord != null) {
            if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
        if (audioEncoder != null) {
            audioEncoder.stop();
            audioEncoder.release();
            audioEncoder = null;
        }
        if (audioTrack != null) {
            if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }
        if (audioDecoder != null) {
            audioDecoder.stop();
            audioDecoder.release();
            audioDecoder = null;
        }

        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                binding.startCallButton.setVisibility(View.VISIBLE);
                binding.endCallButton.setVisibility(View.GONE);
                binding.callStatusTextview.setText("Готов к звонку");
            });
        }
        Log.d(TAG, "Call resources cleaned up.");
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
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION) // Important for calls
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AUDIO_SAMPLE_RATE)
                        .setChannelMask(audioChannelConfig)
                        .build())
                .setBufferSizeInBytes(minBufferSize)
                .build();
        audioTrack.play();
    }

    private class EncoderCallback extends MediaCodec.Callback {
        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            if (!isInCall || connection == null || endpointOut == null) return;
            ByteBuffer outputBuffer = codec.getOutputBuffer(index);
            if (outputBuffer != null && info.size > 0) {
                byte[] data = new byte[info.size];
                outputBuffer.get(data);
                
                ByteBuffer header = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).put(PACKET_TYPE_CALL_AUDIO).putInt(info.size);
                try {
                    connection.bulkTransfer(endpointOut, header.array(), 5, 1000);
                    connection.bulkTransfer(endpointOut, data, info.size, 1000);
                } catch (Exception e) {
                    // This can happen if the other side disconnects
                }
            }
            codec.releaseOutputBuffer(index, false);
        }

        @Override public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {}
        @Override public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) { Log.e(TAG, "Encoder Error", e); }
        @Override public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {}
    }
    
     private class DecoderCallback extends MediaCodec.Callback {
        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
             if (!isInCall || audioTrack == null) return;
            ByteBuffer outputBuffer = codec.getOutputBuffer(index);
            if (outputBuffer != null && info.size > 0) {
                byte[] chunk = new byte[info.size];
                outputBuffer.get(chunk);
                audioTrack.write(chunk, 0, info.size);
            }
            codec.releaseOutputBuffer(index, false);
        }
        @Override public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {}
        @Override public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) { Log.e(TAG, "Decoder Error", e);}
        @Override public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {}
    }

    // --- USB & LIFECYCLE LOGIC ---
    private void startReading() {
        stopReading = false;
        readingThread = new Thread(() -> {
            while (!stopReading) {
                if (connection == null || endpointIn == null) break;
                try {
                    byte[] header = readExactly(5);
                    if (header == null) break;
                    
                    ByteBuffer headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
                    byte commandOrType = headerBuffer.get();
                    int length = headerBuffer.getInt();

                    if(commandOrType == COMMAND_CALL_START) {
                        if(!isInCall) { // To prevent starting a call if we are already in one
                            isInCall = true;
                             callExecutor.execute(this::setupAudioStreaming);
                        }
                    } else if (commandOrType == COMMAND_CALL_END) {
                        if(isInCall) endCall();
                    } else if (commandOrType == PACKET_TYPE_CALL_AUDIO) {
                        if (isInCall && audioDecoder != null && length > 0 && length < 16384) {
                            byte[] data = readExactly(length);
                            if (data == null) break;
                            int inputBufferIndex = audioDecoder.dequeueInputBuffer(-1);
                            if (inputBufferIndex >= 0) {
                                ByteBuffer inputBuffer = audioDecoder.getInputBuffer(inputBufferIndex);
                                if (inputBuffer != null) {
                                    inputBuffer.clear();
                                    inputBuffer.put(data);
                                    audioDecoder.queueInputBuffer(inputBufferIndex, 0, data.length, System.nanoTime() / 1000, 0);
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    if (!stopReading) Log.e(TAG, "Reading error, stopping.", e);
                    break;
                }
            }
        });
        readingThread.start();
    }

    private byte[] readExactly(int byteCount) throws IOException {
        byte[] buffer = new byte[byteCount];
        int offset = 0;
        while (offset < byteCount) {
            if (stopReading || connection == null) return null;
            int bytesRead = connection.bulkTransfer(endpointIn, buffer, offset, byteCount - offset, 5000);
            if (bytesRead > 0) {
                offset += bytesRead;
            } else {
                 throw new IOException("USB read failed. Expected " + byteCount);
            }
        }
        return buffer;
    }
    
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
        if (connection == null) return;
        UsbInterface usbInterface = device.getInterface(0);
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close();
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
            closeConnection();
        }
    }

    private void closeConnection() {
        if (isInCall) endCall();
        isDeviceConnected = false;
        stopReading = true;
        if (readingThread != null) {
            try { readingThread.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            readingThread = null;
        }
        if (connection != null) {
            connection.close();
            connection = null;
        }
         if (getContext() != null) Toast.makeText(getContext(), "USB-устройство отключено.", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        closeConnection();
        if (callExecutor != null) callExecutor.shutdown();
        try {
            requireActivity().unregisterReceiver(usbReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Unregister receiver failed", e);
        }
        binding = null;
    }
}
