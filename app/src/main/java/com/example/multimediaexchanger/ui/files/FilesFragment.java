package com.example.multimediaexchanger.ui.files;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FilesFragment extends Fragment {

    private FragmentFilesBinding binding;
    private UdpViewModel udpViewModel;
    private NetworkViewModel networkViewModel;
    private UsbLogViewModel usbLogViewModel;

    private final List<File> receivedFiles = new ArrayList<>();
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();

    private FileOutputStream receivingFileStream;
    private String receivingFileName;
    private long receivingFileSize;

    private static final String TAG = "FilesFragment";
    private static final int CHUNK_SIZE = 1024 * 60; // 60KB per chunk

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

        return binding.getRoot();
    }

    private void setupClickListeners() {
        binding.sendFileButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*");
            filePickerLauncher.launch(Intent.createChooser(intent, "Выберите файл"));
        });
        binding.openReceivedFileButton.setOnClickListener(v -> showReceivedFilesDialog());
        binding.deleteFilesButton.setOnClickListener(v -> showDeleteFilesDialog());
    }

    private void sendFile(final Uri fileUri) {
        // --- FIXED: Get target IP from the central NetworkViewModel ---
        String targetIp = networkViewModel.getTargetIpAddress().getValue();
        if (targetIp == null || targetIp.isEmpty()) {
            Toast.makeText(getContext(), "IP адрес получателя не указан во вкладке 'Сеть'", Toast.LENGTH_LONG).show();
            return;
        }

        fileExecutor.execute(() -> {
            try (Cursor cursor = requireActivity().getContentResolver().query(fileUri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    @SuppressLint("Range") String fileName = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                    @SuppressLint("Range") long fileSize = cursor.getLong(cursor.getColumnIndex(OpenableColumns.SIZE));
                    usbLogViewModel.log("Files: Sending file: " + fileName + " to " + targetIp);

                    // Step 1: Send File Header
                    byte[] fileNameBytes = fileName.getBytes(StandardCharsets.UTF_8);
                    ByteBuffer headerBuffer = ByteBuffer.allocate(8 + 4 + fileNameBytes.length);
                    headerBuffer.putLong(fileSize).putInt(fileNameBytes.length).put(fileNameBytes);
                    udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_FILE_HEADER, headerBuffer.array());

                    // Step 2: Send File Chunks
                    try (InputStream inputStream = requireActivity().getContentResolver().openInputStream(fileUri)) {
                        byte[] buffer = new byte[CHUNK_SIZE];
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_FILE_CHUNK, ByteBuffer.wrap(buffer, 0, bytesRead).array());
                            Thread.sleep(2);
                        }
                    }

                    // Step 3: Send File End
                    udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_FILE_END, new byte[0]);
                    usbLogViewModel.log("Files: Finished sending " + fileName);
                    requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Отправлено: " + fileName, Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                usbLogViewModel.log("Files: Error sending file: " + e.getMessage());
                Log.e(TAG, "Ошибка отправки файла", e);
            }
        });
    }

    private void observeIncomingData() {
        udpViewModel.getReceivedMessage().observe(getViewLifecycleOwner(), message -> {
            if (message == null) return;
            switch (message.type) {
                case UdpViewModel.MESSAGE_TYPE_FILE_HEADER: handleFileHeader(message.payload); break;
                case UdpViewModel.MESSAGE_TYPE_FILE_CHUNK: handleFileChunk(message.payload); break;
                case UdpViewModel.MESSAGE_TYPE_FILE_END: handleFileEnd(); break;
            }
        });
    }

    private void handleFileHeader(byte[] payload) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            receivingFileSize = buffer.getLong();
            int fileNameLength = buffer.getInt();
            byte[] fileNameBytes = new byte[fileNameLength];
            buffer.get(fileNameBytes);
            receivingFileName = new String(fileNameBytes, StandardCharsets.UTF_8);

            usbLogViewModel.log("Files: Receiving header for " + receivingFileName);

            File file = new File(requireContext().getExternalFilesDir(null), receivingFileName);
            receivingFileStream = new FileOutputStream(file);
            requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Начало приема файла: " + receivingFileName, Toast.LENGTH_SHORT).show());

        } catch (Exception e) {
            Log.e(TAG, "Ошибка обработки заголовка файла", e);
        }
    }

    private void handleFileChunk(byte[] payload) {
        if (receivingFileStream == null) return;
        try {
            receivingFileStream.write(payload);
        } catch (IOException e) {
            Log.e(TAG, "Ошибка записи части файла", e);
        }
    }

    private void handleFileEnd() {
        if (receivingFileStream == null) return;
        try {
            receivingFileStream.close();
            usbLogViewModel.log("Files: Finished receiving " + receivingFileName);

            File receivedFile = new File(requireContext().getExternalFilesDir(null), receivingFileName);
            if (receivedFile.exists() && receivedFile.length() == receivingFileSize) {
                synchronized (receivedFiles) { receivedFiles.add(receivedFile); }
                requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Файл " + receivingFileName + " успешно получен", Toast.LENGTH_LONG).show());
            } else {
                 requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Ошибка приема файла " + receivingFileName, Toast.LENGTH_LONG).show());
            }
        } catch (IOException e) {
            Log.e(TAG, "Ошибка завершения файла", e);
        } finally {
            receivingFileStream = null;
        }
    }

    // ... (Dialog methods like showReceivedFilesDialog, openFile, etc. remain unchanged)
    private void showReceivedFilesDialog() {
        if (receivedFiles.isEmpty()) {
            Toast.makeText(getContext(), "Нет полученных файлов.", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> fileNames = new ArrayList<>();
        for (File file : receivedFiles) {
            fileNames.add(file.getName());
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Выберите полученный файл для открытия");
        builder.setItems(fileNames.toArray(new String[0]), (dialog, which) -> {
            File selectedFile = receivedFiles.get(which);
            openFile(selectedFile);
        });
        builder.create().show();
    }

    private void showDeleteFilesDialog() {
        if (receivedFiles.isEmpty()) {
            Toast.makeText(getContext(), "Нет полученных файлов для удаления.", Toast.LENGTH_SHORT).show();
            return;
        }
        final CharSequence[] fileNames = new CharSequence[receivedFiles.size()];
        for (int i = 0; i < receivedFiles.size(); i++) fileNames[i] = receivedFiles.get(i).getName();

        final boolean[] checkedItems = new boolean[receivedFiles.size()];

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Выберите файлы для удаления");
        builder.setMultiChoiceItems(fileNames, checkedItems, (dialog, which, isChecked) -> checkedItems[which] = isChecked);

        builder.setPositiveButton("Удалить", (dialog, id) -> {
            List<File> filesToRemove = new ArrayList<>();
            for (int i = checkedItems.length - 1; i >= 0; i--) {
                if (checkedItems[i]) {
                    File fileToDelete = receivedFiles.get(i);
                    if (fileToDelete.delete()) {
                        filesToRemove.add(fileToDelete);
                    } else {
                        Log.e(TAG, "Не удалось удалить файл: " + fileToDelete.getName());
                    }
                }
            }
            synchronized (receivedFiles) { receivedFiles.removeAll(filesToRemove); }
            Toast.makeText(getContext(), "Удалено файлов: " + filesToRemove.size(), Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("Отмена", (dialog, id) -> dialog.dismiss());
        builder.create().show();
    }

    private void openFile(File file) {
        if (getContext() == null) return;
        try {
            Uri fileUri = FileProvider.getUriForFile(getContext(), requireActivity().getPackageName() + ".provider", file);
            String mimeType = getMimeType(fileUri);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(fileUri, mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (intent.resolveActivity(getActivity().getPackageManager()) != null) {
                startActivity(intent);
            } else {
                Toast.makeText(getContext(), "Не найдено приложение для открытия этого типа файла.", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка открытия файла", e);
        }
    }

    private String getMimeType(Uri uri) {
        String mimeType = null;
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            mimeType = requireContext().getContentResolver().getType(uri);
        } else {
            String fileExtension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString());
            if (fileExtension != null) mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension.toLowerCase());
        }
        return mimeType;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        fileExecutor.shutdownNow();
        try {
            if (receivingFileStream != null) {
                receivingFileStream.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        binding = null;
    }
}
