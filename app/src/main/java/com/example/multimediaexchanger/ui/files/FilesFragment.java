package com.example.multimediaexchanger.ui.files;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.multimediaexchanger.databinding.FragmentFilesBinding;
import com.example.multimediaexchanger.ui.UdpViewModel;
import com.example.multimediaexchanger.ui.UsbLogViewModel;
import com.example.multimediaexchanger.ui.network.NetworkViewModel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class FilesFragment extends Fragment {

    private FragmentFilesBinding binding;
    private UdpViewModel udpViewModel;
    private NetworkViewModel networkViewModel;
    private UsbLogViewModel usbLogViewModel;

    private final List<File> receivedFiles = new ArrayList<>();
    private final ExecutorService fileExecutor = Executors.newCachedThreadPool(); // Используем CachedThreadPool для RUDP

    // --- RUDP Состояние --- 
    // Отправитель
    private final AtomicBoolean isSending = new AtomicBoolean(false);
    private final AtomicBoolean headerAckReceived = new AtomicBoolean(false);
    private final ConcurrentHashMap<Integer, byte[]> sendingChunkBuffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> chunkSentTimestamp = new ConcurrentHashMap<>();
    private final ConcurrentSkipListSet<Integer> ackedChunks = new ConcurrentSkipListSet<>();

    // Получатель
    private FileOutputStream receivingFileStream;
    private String receivingFileName;
    private long receivingFileSize;
    private final ConcurrentHashMap<Integer, byte[]> receivedChunkBuffer = new ConcurrentHashMap<>();
    private final AtomicInteger nextExpectedSequence = new AtomicInteger(1);

    // --- RUDP Константы ---
    private static final int CHUNK_SIZE = 1024 * 32; // 32KB
    private static final int RUDP_WINDOW_SIZE = 16;
    private static final int RUDP_TIMEOUT_MS = 1000;

    // Типы сообщений для RUDP протокола
    public static final byte MESSAGE_TYPE_FILE_HEADER_RUDP = 0x0F;
    public static final byte MESSAGE_TYPE_FILE_CHUNK_RUDP = 0x0E;
    public static final byte MESSAGE_TYPE_FILE_END_RUDP = 0x0C;
    public static final byte MESSAGE_TYPE_FILE_ACK = 0x0D;

    private static class FileDetails {
        final String name; final long size;
        FileDetails(String n, long s) { name = n; size = s; }
    }

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    sendFile(result.getData().getData());
                }
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentFilesBinding.inflate(inflater, container, false);

        udpViewModel = new ViewModelProvider(requireActivity()).get(UdpViewModel.class);
        networkViewModel = new ViewModelProvider(requireActivity()).get(NetworkViewModel.class);
        usbLogViewModel = new ViewModelProvider(requireActivity()).get(UsbLogViewModel.class);

        setupClickListeners();
        observeIncomingData();
        loadReceivedFilesFromStorage(); // Загружаем список файлов при старте

        return binding.getRoot();
    }

    private void setupClickListeners() {
        binding.sendFileButton.setOnClickListener(v -> {
            if (isSending.get()) {
                stopSending();
            } else {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*");
                filePickerLauncher.launch(Intent.createChooser(intent, "Выберите файл"));
            }
        });
        binding.openReceivedFileButton.setOnClickListener(v -> showReceivedFilesDialog());
        binding.deleteFilesButton.setOnClickListener(v -> showDeleteFilesDialog());
    }

    // --- RUDP: Логика Отправки ---

    private void sendFile(final Uri fileUri) {
        String targetIp = networkViewModel.getTargetIpAddress().getValue();
        if (targetIp == null || targetIp.isEmpty()) {
            Toast.makeText(getContext(), "IP адрес получателя не указан", Toast.LENGTH_LONG).show();
            return;
        }

        fileExecutor.execute(() -> {
            if (!isSending.compareAndSet(false, true)) {
                usbLogViewModel.log("RUDP: Уже идет другая отправка.");
                return;
            }

            requireActivity().runOnUiThread(() -> binding.sendFileButton.setText("Остановить"));

            // 1. Очистка состояния от предыдущих отправок
            resetSendingState();

            FileDetails fileDetails = getFileDetailsFromUri(fileUri);
            if (fileDetails == null) {
                usbLogViewModel.log("RUDP: Не удалось получить информацию о файле: " + fileUri);
                stopSending();
                return;
            }

            try {
                usbLogViewModel.log("RUDP: Начало отправки: " + fileDetails.name + " (" + fileDetails.size + " байт)");

                // 2. Чтение файла в память в виде списка чанков
                List<byte[]> allChunks = readFileIntoChunks(fileUri);
                int totalChunks = allChunks.size();
                if (totalChunks == 0) {
                    udpViewModel.sendData(targetIp, MESSAGE_TYPE_FILE_END_RUDP, new byte[0]);
                    usbLogViewModel.log("RUDP: Файл пуст, отправлен только END.");
                    stopSending();
                    return;
                }
                usbLogViewModel.log("RUDP: Файл разделен на " + totalChunks + " чанков.");

                // 3. Отправка заголовка, пока не получим подтверждение (ACK)
                sendHeaderAndWaitForAck(targetIp, fileDetails, totalChunks);

                if (!isSending.get()) return; // Проверка, не была ли отправка отменена

                // 4. Запуск фонового потока для повторной отправки неподтвержденных чанков
                fileExecutor.submit(() -> resendLoop(targetIp));

                // 5. Основной цикл отправки чанков со 'скользящим окном'
                int currentChunkIndex = 0;
                while (currentChunkIndex < totalChunks && isSending.get()) {
                    if (chunkSentTimestamp.size() >= RUDP_WINDOW_SIZE) {
                        Thread.sleep(10); // Окно заполнено, ждем ACK
                        continue;
                    }
                    sendChunk(targetIp, allChunks.get(currentChunkIndex), currentChunkIndex + 1);
                    currentChunkIndex++;
                }

                // 6. Ожидание подтверждения всех отправленных чанков
                while (ackedChunks.size() < totalChunks && isSending.get()) {
                    Thread.sleep(100);
                }

                // 7. Отправка сообщения о завершении
                if (isSending.get()) {
                    usbLogViewModel.log("RUDP: Все чанки подтверждены. Отправка завершения.");
                    for (int i = 0; i < 5; i++) { // Отправляем несколько раз для надежности
                        udpViewModel.sendData(targetIp, MESSAGE_TYPE_FILE_END_RUDP, new byte[0]);
                        Thread.sleep(50);
                    }
                    requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Отправлено: " + fileDetails.name, Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                usbLogViewModel.log("RUDP: КРИТИЧЕСКАЯ ОШИБКА при отправке: " + e.getMessage() + "\n" + Arrays.toString(e.getStackTrace()));
            } finally {
                stopSending();
            }
        });
    }

    private void stopSending() {
        if (isSending.compareAndSet(true, false)) {
            usbLogViewModel.log("RUDP: Отправка файла остановлена.");

            resetSendingState();

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (getContext() != null) Toast.makeText(getContext(), "Отправка файлов отменена или закончена", Toast.LENGTH_SHORT).show();
                    if (binding != null) binding.sendFileButton.setText("Отправить файл");
                });
            }
        }
    }
    
    private void resetSendingState(){
        headerAckReceived.set(false);
        sendingChunkBuffer.clear();
        chunkSentTimestamp.clear();
        ackedChunks.clear();
    }

    private void sendHeaderAndWaitForAck(String targetIp, FileDetails fileDetails, int totalChunks) throws InterruptedException {
        byte[] fileNameBytes = fileDetails.name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer headerBuffer = ByteBuffer.allocate(8 + 4 + 4 + fileNameBytes.length);
        headerBuffer.putLong(fileDetails.size).putInt(totalChunks).putInt(fileNameBytes.length).put(fileNameBytes);
        byte[] headerPayload = headerBuffer.array();

        while (!headerAckReceived.get() && isSending.get()) {
            usbLogViewModel.log("RUDP: Отправка заголовка...");
            udpViewModel.sendData(targetIp, MESSAGE_TYPE_FILE_HEADER_RUDP, headerPayload);
            Thread.sleep(RUDP_TIMEOUT_MS);
        }
    }

    private void sendChunk(String targetIp, byte[] chunkData, int sequenceNumber) {
        if (ackedChunks.contains(sequenceNumber)) return; // Не отправлять уже подтвержденный

        ByteBuffer chunkBuffer = ByteBuffer.allocate(4 + chunkData.length);
        chunkBuffer.putInt(sequenceNumber).put(chunkData);
        byte[] payload = chunkBuffer.array();

        sendingChunkBuffer.put(sequenceNumber, payload);
        chunkSentTimestamp.put(sequenceNumber, System.currentTimeMillis());

        udpViewModel.sendData(targetIp, MESSAGE_TYPE_FILE_CHUNK_RUDP, payload);
    }

    private void resendLoop(String targetIp) {
        while (isSending.get()) {
            try {
                long now = System.currentTimeMillis();
                for (Integer seq : chunkSentTimestamp.keySet()) {
                    if (now - chunkSentTimestamp.getOrDefault(seq, 0L) > RUDP_TIMEOUT_MS) {
                        if (!ackedChunks.contains(seq)) {
                            byte[] payload = sendingChunkBuffer.get(seq);
                            if (payload != null) {
                                usbLogViewModel.log("RUDP: ПОВТОРНАЯ ОТПРАВКА чанка #" + seq + " из-за таймаута.");
                                udpViewModel.sendData(targetIp, MESSAGE_TYPE_FILE_CHUNK_RUDP, payload);
                                chunkSentTimestamp.put(seq, now);
                            }
                        }
                    }
                }
                Thread.sleep(200);
            } catch (Exception e) {
                usbLogViewModel.log("RUDP: Ошибка в цикле повторной отправки", e);
            }
        }
    }

    // --- RUDP: Логика Приема ---

    private void observeIncomingData() {
        udpViewModel.getReceivedMessage().observe(getViewLifecycleOwner(), message -> {
            if (message == null) return;
            // ACK обрабатываем немедленно
            if (message.type == MESSAGE_TYPE_FILE_ACK) {
                handleAck(message.payload);
                return;
            }
            // Остальные сообщения обрабатываем в фоновом потоке
            fileExecutor.execute(() -> {
                switch (message.type) {
                    case MESSAGE_TYPE_FILE_HEADER_RUDP: handleFileHeader(message.payload, message.senderIp); break;
                    case MESSAGE_TYPE_FILE_CHUNK_RUDP: handleFileChunk(message.payload, message.senderIp); break;
                    case MESSAGE_TYPE_FILE_END_RUDP: handleFileEnd(); break;
                }
            });
        });
    }

    private void handleAck(byte[] payload) {
        if (!isSending.get() || payload.length < 4) return;
        int ackedSeq = ByteBuffer.wrap(payload).getInt();
        if (ackedSeq == 0) { // ACK для заголовка
            headerAckReceived.set(true);
            return;
        }
        if (ackedChunks.add(ackedSeq)) { // Если это новый ACK
            sendingChunkBuffer.remove(ackedSeq);
            chunkSentTimestamp.remove(ackedSeq);
        }
    }

    private void handleFileHeader(byte[] payload, String senderIp) {
        try {
            resetReceivingState();
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            receivingFileSize = buffer.getLong();
            int totalChunks = buffer.getInt(); // Теперь мы знаем, сколько чанков ожидать
            int fileNameLength = buffer.getInt();
            byte[] fileNameBytes = new byte[fileNameLength];
            buffer.get(fileNameBytes);
            receivingFileName = new String(fileNameBytes, StandardCharsets.UTF_8);

            // Отправляем подтверждение (ACK) для заголовка
            ByteBuffer ackBuffer = ByteBuffer.allocate(4).putInt(0);
            udpViewModel.sendData(senderIp, MESSAGE_TYPE_FILE_ACK, ackBuffer.array());
            usbLogViewModel.log("RUDP: Заголовок получен для '" + receivingFileName + "'. Ожидается " + totalChunks + " чанков. Отправлен ACK #0.");

            File file = new File(requireContext().getExternalFilesDir(null), receivingFileName);
            receivingFileStream = new FileOutputStream(file);
            requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Начало приема: " + receivingFileName, Toast.LENGTH_SHORT).show());

        } catch (Exception e) {
            usbLogViewModel.log("RUDP ERROR: Не удалось обработать заголовок", e);
            resetReceivingState();
        }
    }

    private void handleFileChunk(byte[] payload, String senderIp) {
        if (receivingFileStream == null) return;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            int sequenceNumber = buffer.getInt();

            // Немедленно отправляем ACK на полученный чанк
            ByteBuffer ackBuffer = ByteBuffer.allocate(4).putInt(sequenceNumber);
            udpViewModel.sendData(senderIp, MESSAGE_TYPE_FILE_ACK, ackBuffer.array());

            if (sequenceNumber < nextExpectedSequence.get() || receivedChunkBuffer.containsKey(sequenceNumber)) {
                return; // Дубликат или уже обработанный пакет
            }

            byte[] chunkData = new byte[buffer.remaining()];
            buffer.get(chunkData);

            if (sequenceNumber == nextExpectedSequence.get()) {
                receivingFileStream.write(chunkData);
                nextExpectedSequence.incrementAndGet();

                // Проверяем буфер на наличие следующих по порядку чанков
                while (receivedChunkBuffer.containsKey(nextExpectedSequence.get())) {
                    byte[] bufferedChunk = receivedChunkBuffer.remove(nextExpectedSequence.get());
                    receivingFileStream.write(bufferedChunk);
                    usbLogViewModel.log("RUDP: Записан чанк #" + nextExpectedSequence.get() + " из буфера.");
                    nextExpectedSequence.incrementAndGet();
                }
            } else {
                // Пакет пришел не по порядку. Сохраняем в буфер.
                receivedChunkBuffer.put(sequenceNumber, chunkData);
                usbLogViewModel.log("RUDP: Чанк #" + sequenceNumber + " пришел не по порядку. Сохранен в буфер. Ожидается #" + nextExpectedSequence.get());
            }
        } catch (Exception e) {
            usbLogViewModel.log("RUDP ERROR: Не удалось обработать чанк", e);
        }
    }

    private void handleFileEnd() {
        if (receivingFileStream == null) return;
        usbLogViewModel.log("RUDP: Получен сигнал о завершении передачи.");

        File receivedFile = new File(requireContext().getExternalFilesDir(null), receivingFileName);
        String failReason = null;

        if (!receivedChunkBuffer.isEmpty()) {
            failReason = "остались недостающие чанки в буфере: " + receivedChunkBuffer.keySet().toString();
        } else if (receivedFile.length() != receivingFileSize) {
            failReason = "неверный итоговый размер (ожидалось: " + receivingFileSize + ", по факту: " + receivedFile.length() + ")";
        }

        if (failReason == null) {
            synchronized (receivedFiles) {
                // Добавляем в начало, чтобы новые файлы были сверху
                receivedFiles.add(0, receivedFile);
            }
            requireActivity().runOnUiThread(() -> {
                Toast.makeText(getContext(), "Файл '" + receivingFileName + "' успешно получен", Toast.LENGTH_LONG).show();
                if (binding != null) {
                    binding.openReceivedFileButton.setVisibility(View.VISIBLE);
                    binding.deleteFilesButton.setVisibility(View.VISIBLE);
                }
            });
            usbLogViewModel.log("RUDP: Файл '"+receivingFileName+"' собран успешно!");
        } else {
            final String finalFailReason = failReason;
            usbLogViewModel.log("RUDP ERROR: Сборка файла '" + receivingFileName + "' не удалась. Причина: " + finalFailReason);
            requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Ошибка при получении файла: " + finalFailReason, Toast.LENGTH_LONG).show());
            if(receivedFile.exists()) receivedFile.delete(); // Удаляем поврежденный/неполный файл
        }
        resetReceivingState();
    }

    private void resetReceivingState() {
        try {
            if (receivingFileStream != null) receivingFileStream.close();
        } catch (IOException e) {
            usbLogViewModel.log("RUDP WARN: Не удалось закрыть поток файла при сбросе состояния", e);
        }
        receivingFileStream = null;
        receivingFileName = null;
        receivingFileSize = 0;
        receivedChunkBuffer.clear();
        nextExpectedSequence.set(1);
    }

    // --- Вспомогательные методы, реализация сохранена ---

    private List<byte[]> readFileIntoChunks(Uri uri) throws IOException {
        List<byte[]> chunks = new ArrayList<>();
        try (InputStream inputStream = requireContext().getContentResolver().openInputStream(uri)) {
            if (inputStream == null) throw new IOException("Не удалось открыть поток для URI: " + uri);
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                byte[] chunkData = new byte[bytesRead];
                System.arraycopy(buffer, 0, chunkData, 0, bytesRead);
                chunks.add(chunkData);
            }
        }
        return chunks;
    }

    @Nullable
    private FileDetails getFileDetailsFromUri(@NonNull Uri uri) {
        ContentResolver resolver = requireContext().getContentResolver();
        String name = null;
        long size = -1;

        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) {
                    name = cursor.getString(nameIndex);
                }

                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        }

        if (name == null) {
            name = uri.getLastPathSegment(); // Fallback for name
        }

        if (size <= 0) { // Fallback for size
            try (InputStream inputStream = resolver.openInputStream(uri)) {
                if (inputStream != null) {
                    size = inputStream.available();
                }
            } catch (IOException e) {
                usbLogViewModel.log("WARN: Could not determine file size from input stream for " + name, e);
                return null;
            }
        }

        if (name != null && size > 0) {
            return new FileDetails(name, size);
        } else {
            return null;
        }
    }

    private void showReceivedFilesDialog() {
        if (receivedFiles.isEmpty()) {
            Toast.makeText(getContext(), "Нет полученных файлов", Toast.LENGTH_SHORT).show();
            return;
        }

        // Гарантируем, что работаем с актуальной и отсортированной копией
        ArrayList<File> currentFiles = new ArrayList<>(receivedFiles);
        currentFiles.sort(Comparator.comparingLong(File::lastModified).reversed());

        List<String> fileNames = new ArrayList<>();
        for (File file : currentFiles) {
            fileNames.add(file.getName());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, fileNames);

        new AlertDialog.Builder(getContext())
                .setTitle("Полученные файлы")
                .setAdapter(adapter, (dialog, which) -> {
                    File selectedFile = currentFiles.get(which);

                    // 1. ОПРЕДЕЛЯЕМ MIME-ТИП ПО РАСШИРЕНИЮ ФАЙЛА (БОЛЕЕ НАДЕЖНО)
                    String mimeType = null;
                    String fileName = selectedFile.getName();
                    int lastDot = fileName.lastIndexOf('.');
                    if (lastDot != -1 && lastDot < fileName.length() - 1) {
                        String extension = fileName.substring(lastDot + 1).toLowerCase();
                        mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                    }
                    if (mimeType == null) {
                        mimeType = "*/*"; // Тип по умолчанию, если не удалось определить
                    }

                    // 2. ПОЛУЧАЕМ ПРАВИЛЬНЫЙ URI ЧЕРЕЗ FILEPROVIDER
                    // Authority должен точно совпадать с тем, что указан в AndroidManifest.xml
                    String authority = requireContext().getPackageName() + ".provider";
                    Uri fileUri;
                    try {
                        fileUri = FileProvider.getUriForFile(requireContext(), authority, selectedFile);
                    } catch (IllegalArgumentException e) {
                        usbLogViewModel.log("CRITICAL: FileProvider не может найти файл. Проверьте пути.", e);
                        Toast.makeText(getContext(), "Ошибка: не удалось создать ссылку на файл.", Toast.LENGTH_LONG).show();
                        return;
                    }

                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(fileUri, mimeType);
                    // Даем временное разрешение приложению, которое будет открывать файл
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    // Создаем Chooser, чтобы система всегда предлагала выбор
                    Intent chooser = Intent.createChooser(intent, "Открыть файл с помощью...");

                    try {
                        startActivity(chooser);
                    } catch (android.content.ActivityNotFoundException e) {
                        Toast.makeText(getContext(), "Не найдено приложений для открытия этого типа файла", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }


    private void showDeleteFilesDialog() {
        if (receivedFiles.isEmpty()) {
            Toast.makeText(getContext(), "Нет файлов для удаления", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(getContext())
                .setTitle("Удалить все файлы?")
                .setMessage("Вы уверены, что хотите удалить все полученные файлы? Это действие необратимо.")
                .setPositiveButton("Удалить", (dialog, which) -> {
                    fileExecutor.execute(() -> {
                        int deletedCount = 0;
                        synchronized (receivedFiles) {
                           for (File file : new ArrayList<>(receivedFiles)) {
                                if (file.delete()) {
                                    receivedFiles.remove(file);
                                    deletedCount++;
                                }
                           }
                        }
                        final int finalDeletedCount = deletedCount;
                        if (getActivity() != null) requireActivity().runOnUiThread(() -> {
                            Toast.makeText(getContext(), "Удалено файлов: " + finalDeletedCount, Toast.LENGTH_SHORT).show();
                            if (receivedFiles.isEmpty() && binding != null) {
                                binding.openReceivedFileButton.setVisibility(View.GONE);
                                binding.deleteFilesButton.setVisibility(View.GONE);
                            }
                        });
                    });
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void loadReceivedFilesFromStorage() {
        fileExecutor.execute(() -> {
            if (getContext() == null) return;
            File storageDir = getContext().getExternalFilesDir(null);
            if (storageDir == null) {
                usbLogViewModel.log("ERROR: Не удалось получить доступ к хранилищу файлов.");
                return;
            }

            File[] files = storageDir.listFiles();
            if (files == null) return;


            synchronized (receivedFiles) {
                receivedFiles.clear();
                receivedFiles.addAll(Arrays.asList(files));
                // Сортируем файлы по дате изменения, чтобы новые были сверху
                receivedFiles.sort(Comparator.comparingLong(File::lastModified).reversed());
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (binding != null) {
                        boolean filesExist = !receivedFiles.isEmpty();
                        binding.openReceivedFileButton.setVisibility(filesExist ? View.VISIBLE : View.GONE);
                        binding.deleteFilesButton.setVisibility(filesExist ? View.VISIBLE : View.GONE);
                    }
                });
            }
        });
    }
}
