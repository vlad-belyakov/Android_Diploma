// File: app/src/main/java/com/example/multimediaexchanger/ui/calls/CallsFragment.java
package com.example.multimediaexchanger.ui.calls;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
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
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.multimediaexchanger.databinding.FragmentCallsBinding;
import com.example.multimediaexchanger.ui.UdpViewModel;
import com.example.multimediaexchanger.ui.UsbLogViewModel;
import com.example.multimediaexchanger.ui.network.NetworkViewModel;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CallsFragment extends Fragment {

    private static final String TAG = "CallsFragment";

    private FragmentCallsBinding binding;
    private UdpViewModel udpViewModel;
    private NetworkViewModel networkViewModel;
    private UsbLogViewModel usbLogViewModel;

    private enum CallState { IDLE, OUTGOING, INCOMING, IN_CALL }
    private volatile CallState currentCallState = CallState.IDLE;

    private final ExecutorService callExecutor = Executors.newCachedThreadPool();

    // PCM params (stereo, 44.1kHz, 16-bit)
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_IN_CONFIG = AudioFormat.CHANNEL_IN_STEREO;
    private static final int CHANNEL_OUT_CONFIG = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    // Audio devices
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;

    // control flags
    private volatile boolean isStreaming = false;
    private volatile boolean captureLoopRunning = false;
    private volatile boolean playbackLoopRunning = false;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (!isGranted) {
                    Toast.makeText(getContext(), "Требуется разрешение на использование микрофона.", Toast.LENGTH_SHORT).show();
                    if (usbLogViewModel != null) usbLogViewModel.log("Call: RECORD_AUDIO permission denied");
                } else {
                    if (usbLogViewModel != null) usbLogViewModel.log("Call: RECORD_AUDIO permission granted");
                }
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentCallsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        udpViewModel = new ViewModelProvider(requireActivity()).get(UdpViewModel.class);
        networkViewModel = new ViewModelProvider(requireActivity()).get(NetworkViewModel.class);
        usbLogViewModel = new ViewModelProvider(requireActivity()).get(UsbLogViewModel.class);

        setupClickListeners();
        checkPermissions();
        observeUdpMessages();
        updateUiForState(CallState.IDLE);
    }

    private void setupClickListeners() {
        binding.callActionButton.setOnClickListener(v -> handleCallAction());
        binding.answerButton.setOnClickListener(v -> handleAnswer());
        binding.rejectButton.setOnClickListener(v -> handleReject());
    }

    private void observeUdpMessages() {
        udpViewModel.getReceivedMessage().observe(getViewLifecycleOwner(), message -> {
            if (message == null) return;
            try {
                switch (message.type) {
                    case UdpViewModel.MESSAGE_TYPE_CALL_REQUEST:
                        if (currentCallState == CallState.IDLE) {
                            usbLogViewModel.log("Call: incoming CALL_REQUEST from " + message.senderIp);
                            updateUiForState(CallState.INCOMING);
                        } else {
                            usbLogViewModel.log("Call: CALL_REQUEST received but already busy");
                        }
                        break;
                    case UdpViewModel.MESSAGE_TYPE_CALL_ACCEPT:
                        usbLogViewModel.log("Call: CALL_ACCEPT received from " + message.senderIp);
                        if (currentCallState == CallState.OUTGOING) {
                            updateUiForState(CallState.IN_CALL);
                            startAudioStreaming();
                        }
                        break;
                    case UdpViewModel.MESSAGE_TYPE_CALL_REJECT:
                        usbLogViewModel.log("Call: CALL_REJECT received");
                        if (currentCallState != CallState.IDLE) {
                            stopAudioStreaming();
                            updateUiForState(CallState.IDLE);
                        }
                        break;
                    case UdpViewModel.MESSAGE_TYPE_CALL_END:
                        usbLogViewModel.log("Call: CALL_END received");
                        if (currentCallState != CallState.IDLE) {
                            stopAudioStreaming();
                            updateUiForState(CallState.IDLE);
                        }
                        break;
                    case UdpViewModel.MESSAGE_TYPE_CALL_AUDIO:
                        usbLogViewModel.log("Call: RX AUDIO message (" + (message.payload == null ? 0 : message.payload.length) + " bytes) from " + message.senderIp);
                        break;
                    default:
                        usbLogViewModel.log("Call: received message type 0x" + String.format("%02X", message.type));
                }
            } catch (Exception e) {
                usbLogViewModel.log("ERROR: observing UDP messages", e);
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
                usbLogViewModel.log("Call: sent CALL_REQUEST → " + targetIp);
                updateUiForState(CallState.OUTGOING);
                break;
            case OUTGOING:
            case IN_CALL:
                udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_CALL_END, new byte[0]);
                usbLogViewModel.log("Call: sent CALL_END → " + targetIp);
                stopAudioStreaming();
                updateUiForState(CallState.IDLE);
                break;
        }
    }

    private void handleAnswer() {
        String targetIp = networkViewModel.getTargetIpAddress().getValue();
        if (targetIp != null && !targetIp.isEmpty() && currentCallState == CallState.INCOMING) {
            udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_CALL_ACCEPT, new byte[0]);
            usbLogViewModel.log("Call: sent CALL_ACCEPT → " + targetIp);
            updateUiForState(CallState.IN_CALL);
            startAudioStreaming();
        }
    }

    private void handleReject() {
        String targetIp = networkViewModel.getTargetIpAddress().getValue();
        if (targetIp != null && !targetIp.isEmpty() && currentCallState == CallState.INCOMING) {
            udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_CALL_REJECT, new byte[0]);
            usbLogViewModel.log("Call: sent CALL_REJECT → " + targetIp);
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
                    binding.callActionButton.setBackgroundColor(requireContext().getColor(android.R.color.holo_green_dark));
                    binding.callActionButton.setVisibility(View.VISIBLE);
                    binding.incomingCallActions.setVisibility(View.GONE);
                    break;
                case OUTGOING:
                    binding.callStatusText.setText("Попытка соединения...");
                    binding.callActionButton.setText("Сбросить");
                    binding.callActionButton.setBackgroundColor(requireContext().getColor(android.R.color.holo_red_dark));
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
                    binding.callActionButton.setBackgroundColor(requireContext().getColor(android.R.color.holo_red_dark));
                    binding.callActionButton.setVisibility(View.VISIBLE);
                    binding.incomingCallActions.setVisibility(View.GONE);
                    break;
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void startAudioStreaming() {
        if (isStreaming) {
            usbLogViewModel.log("Call: startAudioStreaming called but already streaming");
            return;
        }

        isStreaming = true;
        usbLogViewModel.log("Call: starting audio streaming (PCM, stereo, 44.1kHz, 16-bit)");

        callExecutor.execute(() -> {
            try {
                int minIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN_CONFIG, AUDIO_FORMAT);
                int minOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT_CONFIG, AUDIO_FORMAT);

                if (minIn <= 0 || minOut <= 0) {
                    usbLogViewModel.log("ERROR: invalid buffer sizes (in=" + minIn + " out=" + minOut + ")");
                    stopAudioStreaming();
                    return;
                }

                int captureBuffer = Math.max(minIn, SAMPLE_RATE * 2);
                int playBuffer = Math.max(minOut, SAMPLE_RATE * 2);

                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_IN_CONFIG,
                        AUDIO_FORMAT,
                        captureBuffer
                );

                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    usbLogViewModel.log("❌ Микрофон не инициализировался");
                    audioRecord.release();
                    audioRecord = null;
                    stopAudioStreaming();
                    return;
                } else {
                    usbLogViewModel.log("🎙 Микрофон инициализирован");
                }

                audioTrack = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AUDIO_FORMAT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(CHANNEL_OUT_CONFIG)
                                .build())
                        .setBufferSizeInBytes(playBuffer)
                        .build();

                if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                    usbLogViewModel.log("❌ AudioTrack не инициализирован");
                    audioTrack.release();
                    audioTrack = null;
                    if (audioRecord != null) { audioRecord.release(); audioRecord = null; }
                    stopAudioStreaming();
                    return;
                } else {
                    usbLogViewModel.log("🔊 AudioTrack инициализирован");
                }

                audioTrack.play();
                audioRecord.startRecording();

                int frameSize = 2048;
                byte[] captureBufferBytes = new byte[Math.max(frameSize, captureBuffer)];

                captureLoopRunning = true;
                playbackLoopRunning = true;

                // Оптимизированный capture loop с безопасной разбивкой на UDP-пакеты
                callExecutor.execute(() -> {
                    usbLogViewModel.log("Call: optimized capture loop started");
                    try {
                        while (isStreaming && audioRecord != null && captureLoopRunning) {
                            int read = audioRecord.read(captureBufferBytes, 0, captureBufferBytes.length);
                            if (read <= 0) { Thread.sleep(10); continue; }

                            final byte[] sendBufferFinal = Arrays.copyOf(captureBufferBytes, read);
                            String targetIp = networkViewModel.getTargetIpAddress().getValue();
                            if (targetIp != null && !targetIp.isEmpty()) {
                                final int MTU = 1400;
                                int offset = 0;
                                int packetsSent = 0;
                                while (offset < sendBufferFinal.length) {
                                    int chunkSize = Math.min(MTU, sendBufferFinal.length - offset);
                                    byte[] chunk = Arrays.copyOfRange(sendBufferFinal, offset, offset + chunkSize);
                                    udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_CALL_AUDIO, chunk);
                                    offset += chunkSize;
                                    packetsSent++;
                                }
                                usbLogViewModel.log("Call: sent audio chunk split into " + packetsSent + " UDP packets → " + targetIp);
                            } else {
                                usbLogViewModel.log("Call: no target IP to send audio chunk");
                            }

                            Thread.yield();
                        }
                    } catch (InterruptedException ie) {
                        usbLogViewModel.log("Call: optimized capture loop interrupted", ie);
                    } catch (Exception ex) {
                        usbLogViewModel.log("ERROR: optimized capture loop exception", ex);
                    } finally {
                        usbLogViewModel.log("Call: optimized capture loop stopped");
                        captureLoopRunning = false;
                    }
                });

                // Playback loop
                callExecutor.execute(() -> {
                    usbLogViewModel.log("Call: playback loop started");
                    try {
                        while (isStreaming && audioTrack != null && playbackLoopRunning) {
                            byte[] frame = udpViewModel.pollAudioFrame();
                            if (frame == null) { Thread.sleep(4); continue; }

                            int written = 0;
                            try { written = audioTrack.write(frame, 0, frame.length); }
                            catch (Exception e) { usbLogViewModel.log("ERROR: audioTrack.write failed", e); }
                        }
                    } catch (InterruptedException ie) {
                        usbLogViewModel.log("Call: playback loop interrupted", ie);
                    } catch (Exception ex) {
                        usbLogViewModel.log("ERROR: playback loop exception", ex);
                    } finally {
                        usbLogViewModel.log("Call: playback loop stopped");
                        playbackLoopRunning = false;
                    }
                });

            } catch (Exception e) {
                usbLogViewModel.log("ERROR: startAudioStreaming failed", e);
                stopAudioStreaming();
            }
        });
    }

    private void stopAudioStreaming() {
        usbLogViewModel.log("Call: stopping audio streaming");
        isStreaming = false;
        captureLoopRunning = false;
        playbackLoopRunning = false;

        callExecutor.execute(() -> {
            try {
                if (audioRecord != null) {
                    try { if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop(); } catch (Exception ignored) {}
                    try { audioRecord.release(); } catch (Exception ignored) {}
                    audioRecord = null;
                }
                if (audioTrack != null) {
                    try { if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) audioTrack.stop(); } catch (Exception ignored) {}
                    try { audioTrack.release(); } catch (Exception ignored) {}
                    audioTrack = null;
                }
                Thread.sleep(50);
                usbLogViewModel.log("Call: audio resources cleaned");
            } catch (Exception e) { usbLogViewModel.log("ERROR: stopAudioStreaming cleanup", e); }
        });
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        } else {
            if (usbLogViewModel != null) usbLogViewModel.log("Call: RECORD_AUDIO permission already granted");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            if (currentCallState != CallState.IDLE) {
                String targetIp = networkViewModel.getTargetIpAddress().getValue();
                if (targetIp != null && !targetIp.isEmpty()) {
                    udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_CALL_END, new byte[0]);
                    usbLogViewModel.log("Call: sent CALL_END on destroy");
                }
                stopAudioStreaming();
            }
        } catch (Exception e) { usbLogViewModel.log("WARN: onDestroyView cleanup error", e); }
        binding = null;
    }
}
