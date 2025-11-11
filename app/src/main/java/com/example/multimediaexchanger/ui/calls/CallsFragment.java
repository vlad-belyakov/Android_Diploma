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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CallsFragment extends Fragment {

    private FragmentCallsBinding binding;
    private UdpViewModel udpViewModel;
    private NetworkViewModel networkViewModel;
    private UsbLogViewModel usbLogViewModel;

    private enum CallState { IDLE, OUTGOING, INCOMING, IN_CALL }
    private volatile CallState currentCallState = CallState.IDLE;
    private volatile double currentGainFactor = 2.0;
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

    // queue for playback
    private final ConcurrentLinkedQueue<byte[]> playbackQueue = new ConcurrentLinkedQueue<>();

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
        setupVolumeControl();
        checkPermissions();
        observeUdpMessages();
        updateUiForState(CallState.IDLE);
    }

    private void setupVolumeControl() {
        binding.volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    // Преобразуем значение от 0-500 в коэффициент от 0.0 до 5.0
                    currentGainFactor = progress / 100.0;
                    // Можно добавить лог для отладки
                    usbLogViewModel.log("Volume changed to: " + currentGainFactor);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
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
                            networkViewModel.setTargetIpAddress(message.senderIp);

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
                        if (message.payload != null) playbackQueue.offer(message.payload);
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
        networkViewModel.setInCall(state == CallState.IN_CALL || state == CallState.OUTGOING || state == CallState.INCOMING);

        this.currentCallState = state;
        requireActivity().runOnUiThread(() -> {

            boolean showVolumeControl = (state == CallState.IN_CALL);
            binding.volumeSeekBar.setVisibility(showVolumeControl ? View.VISIBLE : View.GONE);
            binding.volumeLabel.setVisibility(showVolumeControl ? View.VISIBLE : View.GONE);

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
        if (isStreaming) return;
        isStreaming = true;

        callExecutor.execute(() -> {
            try {
                int minIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN_CONFIG, AUDIO_FORMAT);
                int minOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT_CONFIG, AUDIO_FORMAT);

                int captureBuffer = minIn * 2; // Уменьшим буфер для меньшей задержки
                int playBuffer = minOut * 2;   // Уменьшим буфер для меньшей задержки

                // Используем MIC как источник. Платформа сама применит AEC,
                // потому что AudioTrack использует USAGE_VOICE_COMMUNICATION.
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                        CHANNEL_IN_CONFIG, AUDIO_FORMAT, captureBuffer);

                audioTrack = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION) // Это ключ к эхоподавлению
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AUDIO_FORMAT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(CHANNEL_OUT_CONFIG)
                                .build())
                        .setBufferSizeInBytes(playBuffer)
                        .build();

                audioRecord.startRecording();
                audioTrack.play();

                captureLoopRunning = true;
                playbackLoopRunning = true;
                usbLogViewModel.log("Call: Audio streaming started successfully.");

                // Capture loop (Цикл захвата)
                callExecutor.execute(() -> {
                    byte[] buffer = new byte[captureBuffer];
                    while (captureLoopRunning && isStreaming) {
                        int read = audioRecord.read(buffer, 0, buffer.length);
                        if (read > 0) {
                            // Просто отправляем "сырые" данные как есть. Не трогаем их.
                            byte[] chunk = Arrays.copyOf(buffer, read);

                            String targetIp = networkViewModel.getTargetIpAddress().getValue();
                            if (targetIp != null && !targetIp.isEmpty()) {
                                // Отправка фрагментированных данных (ваш код здесь правильный)
                                final int MTU = 1400; // Максимальный размер пакета
                                int offset = 0;
                                while (offset < chunk.length) {
                                    int len = Math.min(MTU, chunk.length - offset);
                                    udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_CALL_AUDIO,
                                            Arrays.copyOfRange(chunk, offset, offset + len));
                                    offset += len;
                                }
                            }
                        }
                    }
                    usbLogViewModel.log("Call: Capture loop finished.");
                });

                // Playback loop (Цикл воспроизведения)
                callExecutor.execute(() -> {
                    while (playbackLoopRunning && isStreaming) {
                        byte[] frame = playbackQueue.poll();
                        if (frame != null) {
                            // Применяем усиление к полученному звуку перед воспроизведением
                            applySoftGain(frame);
                            audioTrack.write(frame, 0, frame.length);
                        } else {
                            // Небольшая пауза, чтобы не загружать процессор впустую
                            try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                        }
                    }
                    usbLogViewModel.log("Call: Playback loop finished.");
                });

            } catch (Exception e) {
                usbLogViewModel.log("CRITICAL ERROR: startAudioStreaming failed", e);
                requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Ошибка запуска аудио", Toast.LENGTH_LONG).show());
                stopAudioStreaming();
            }
        });
    }


    private void applySoftGain(byte[] buffer) {
        // Если усиление не требуется (коэффициент равен 1.0), выходим раньше для экономии ресурсов
        if (Math.abs(currentGainFactor - 1.0) < 0.01) {
            return;
        }

        for (int i = 0; i < buffer.length; i += 2) {
            // Преобразуем 2 байта (little-endian) в 16-битный signed short
            short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));

            // Применяем усиление с ограничением, чтобы избежать переполнения и "хрипов"
            double boostedSample = sample * currentGainFactor; // <-- ИСПОЛЬЗУЕМ ПЕРЕМЕННУЮ
            boostedSample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, boostedSample));
            sample = (short) boostedSample;

            // Преобразуем short обратно в 2 байта (little-endian)
            buffer[i] = (byte) (sample & 0xFF);
            buffer[i + 1] = (byte) ((sample >> 8) & 0xFF);
        }
    }


    private void stopAudioStreaming() {
        isStreaming = false;
        captureLoopRunning = false;
        playbackLoopRunning = false;

        callExecutor.execute(() -> {
            try {
                if (audioRecord != null) {
                    audioRecord.stop();
                    audioRecord.release();
                    audioRecord = null;
                }
                if (audioTrack != null) {
                    audioTrack.stop();
                    audioTrack.release();
                    audioTrack = null;
                }
                playbackQueue.clear();
            } catch (Exception e) {
                usbLogViewModel.log("ERROR: stopAudioStreaming cleanup", e);
            }
        });
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopAudioStreaming();
        binding = null;
    }
}
