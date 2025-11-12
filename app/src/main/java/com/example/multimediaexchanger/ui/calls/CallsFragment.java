package com.example.multimediaexchanger.ui.calls;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class CallsFragment extends Fragment {

    private FragmentCallsBinding binding;
    private UdpViewModel udpViewModel;
    private NetworkViewModel networkViewModel;
    private UsbLogViewModel usbLogViewModel;

    private enum CallState { IDLE, OUTGOING, INCOMING, IN_CALL }
    private volatile CallState currentCallState = CallState.IDLE;
    private volatile double currentGainFactor = 2.0;
    private ExecutorService callExecutor = Executors.newCachedThreadPool();

    // Хорошее значение для начала - от 5 до 10.
    private static final int JITTER_BUFFER_START_THRESHOLD = 8;

    // Минимальное количество пакетов, которое мы стараемся поддерживать в буфере.
    private static final int JITTER_BUFFER_MIN_THRESHOLD = 4;


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
    private final LinkedBlockingQueue<short[]> playbackQueue = new LinkedBlockingQueue<>();

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
        // --- НАЧАЛО: УВЕЛИЧЕНИЕ ДИАПАЗОНА ГРОМКОСТИ ---
        // Устанавливаем диапазон от 0 до 800. Это даст нам коэффициент от 0.0 до 8.0
        binding.volumeSeekBar.setMax(800);
        // --- КОНЕЦ: УВЕЛИЧЕНИЕ ДИАПАЗОНА ГРОМКОСТИ ---

        // Устанавливаем начальное значение на 1.0 (без усиления)
        binding.volumeSeekBar.setProgress(100);

        binding.volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Преобразуем значение от 0-800 в коэффициент от 0.0-8.0
                currentGainFactor = progress / 100.0f;
                // Логируем только если меняет пользователь, чтобы не спамить в консоль
                if (fromUser) {
                    // Используем Locale.US для точки в качестве десятичного разделителя
                    usbLogViewModel.log(String.format(java.util.Locale.US, "Volume changed to: %.2f", currentGainFactor));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Ничего не делаем
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Ничего не делаем
            }
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
                        if (message.payload != null && message.payload.length > 0) {
                            // Превращаем байты в short'ы ПЕРЕД добавлением в очередь
                            short[] audioShorts = new short[message.payload.length / 2];
                            ByteBuffer.wrap(message.payload).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(audioShorts);
                            playbackQueue.offer(audioShorts);
                        }
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

    // Добавляем SuppressLint для разрешения, так как мы его проверяем
    // УДАЛИТЕ СТАРЫЙ МЕТОД applySoftGain, он больше не нужен.

    @SuppressLint("MissingPermission")
    private void startAudioStreaming() {
        if (isStreaming) return;

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            usbLogViewModel.log("ERROR: startAudioStreaming called without RECORD_AUDIO permission.");
            Toast.makeText(getContext(), "Нет разрешения на использование микрофона", Toast.LENGTH_SHORT).show();
            updateUiForState(CallState.IDLE);
            return;
        }

        AudioManager audioManager = (AudioManager) requireContext().getSystemService(android.content.Context.AUDIO_SERVICE);
        if (audioManager != null) {
            // Устанавливаем громкость потока голосовых вызовов на максимум
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0);
            usbLogViewModel.log("Call: Stream volume set to max (" + maxVolume + ")");
        }

        isStreaming = true;
        callExecutor = Executors.newFixedThreadPool(2);

        try {
            // --- НАЧАЛО: ФИНАЛЬНАЯ СИНХРОНИЗАЦИЯ БУФЕРОВ ---
            // 1. Получаем минимально допустимый системой размер буфера
            int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN_CONFIG, AUDIO_FORMAT);

            // 2. Выбираем наш рабочий размер блока. Он должен быть НЕ МЕНЬШЕ минимального.
            //    Если minBufferSize = 1920, а мы выберем 1280, ничего не заработает.
            //    Поэтому наш буфер должен быть либо равен minBufferSize, либо больше и кратен ему.
            //    Самый простой и надежный вариант - просто использовать minBufferSize.
            final int BUFFER_SIZE = minBufferSize;

            // --- КОНЕЦ: ФИНАЛЬНАЯ СИНХРОНИЗАЦИЯ БУФЕРОВ ---

            // Создаем AudioRecord с буфером, РАВНЫМ размеру нашего блока чтения.
            // Это заставит read() почти всегда возвращать ровно BUFFER_SIZE байт.
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                    CHANNEL_IN_CONFIG, AUDIO_FORMAT, BUFFER_SIZE);



            // Для AudioTrack размер буфера может быть больше, это не так критично.
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
                    .setBufferSizeInBytes(BUFFER_SIZE)
                    .build();

            audioRecord.startRecording();
            audioTrack.play();
            usbLogViewModel.log("Call: Audio streaming started successfully with buffer size: " + BUFFER_SIZE);

            // Capture Loop
            callExecutor.execute(() -> {
                Thread.currentThread().setName("AudioCaptureThread");
                byte[] buffer = new byte[BUFFER_SIZE];
                while (isStreaming && !Thread.currentThread().isInterrupted()) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    // Теперь read почти всегда будет равен BUFFER_SIZE
                    if (read > 0) {
                        String targetIp = networkViewModel.getTargetIpAddress().getValue();
                        if (targetIp != null && !targetIp.isEmpty()) {
                            // Если прочитали меньше, чем ожидали, отправляем урезанный пакет.
                            // Главное - не терять данные.
                            if (read < BUFFER_SIZE) {
                                byte[] smallerChunk = Arrays.copyOf(buffer, read);
                                udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_CALL_AUDIO, smallerChunk);
                            } else {
                                udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_CALL_AUDIO, buffer);
                            }
                        }
                    } else if (read < 0) {
                        usbLogViewModel.log("ERROR: AudioRecord read failed with code: " + read);
                    }
                }
                usbLogViewModel.log("Call: Capture loop finished.");
            });

            // Playback Loop
            callExecutor.execute(() -> {
                Thread.currentThread().setName("AudioPlaybackThread");
                while (isStreaming && !Thread.currentThread().isInterrupted()) {
                    try {
                        short[] frame = playbackQueue.take();
                        for (int i = 0; i < frame.length; i++) {
                            int newSample = (int) (frame[i] * currentGainFactor);
                            frame[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, newSample));
                        }
                        audioTrack.write(frame, 0, frame.length);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        usbLogViewModel.log("ERROR in Playback loop", e);
                    }
                }
                usbLogViewModel.log("Call: Playback loop finished.");
            });

        } catch (Exception e) {
            usbLogViewModel.log("CRITICAL ERROR: startAudioStreaming failed", e);
            requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Ошибка запуска аудио", Toast.LENGTH_LONG).show());
            stopAudioStreaming();
        }
    }







    private void stopAudioStreaming() {
        if (!isStreaming) return;
        isStreaming = false;

        // Это инициирует корректную остановку
        if (callExecutor != null && !callExecutor.isShutdown()) {
            // Мягко просим все потоки в пуле остановиться.
            // isStreaming = false; в циклах while теперь увидят это.
            // Также это прервет потоки, если они спят в Thread.sleep()
            callExecutor.shutdownNow();

            // Запускаем задачу ожидания в другом потоке, чтобы не блокировать UI
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    // Ждем до 2 секунд, пока потоки захвата и воспроизведения не завершатся
                    if (!callExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                        usbLogViewModel.log("WARN: Audio threads did not terminate gracefully.");
                    }

                    // Теперь, когда мы уверены, что потоки остановлены, безопасно освобождаем ресурсы
                    if (audioRecord != null) {
                        if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                            audioRecord.stop();
                        }
                        audioRecord.release();
                        audioRecord = null;
                        usbLogViewModel.log("Call: AudioRecord released.");
                    }
                    if (audioTrack != null) {
                        if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                            audioTrack.stop();
                        }
                        audioTrack.release();
                        audioTrack = null;
                        usbLogViewModel.log("Call: AudioTrack released.");
                    }
                    playbackQueue.clear();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    usbLogViewModel.log("ERROR: Interrupted while waiting for audio threads to stop.", e);
                } catch (Exception e) {
                    usbLogViewModel.log("ERROR: stopAudioStreaming cleanup", e);
                }
            });
        }
    }




    /*private void applySoftGain(byte[] buffer) {
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
    }*/




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
