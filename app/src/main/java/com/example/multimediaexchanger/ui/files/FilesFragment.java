package com.example.multimediaexchanger.ui.files;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.example.multimediaexchanger.databinding.FragmentFilesBinding;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FilesFragment extends Fragment {

    private FragmentFilesBinding binding;
    private UsbManager usbManager;
    private UsbDeviceConnection connection;
    private UsbEndpoint endpointIn, endpointOut;
    private volatile boolean stopReading = false;
    private Thread readingThread;
    private ExecutorService fileExecutor;
    private volatile boolean isDeviceConnected = false;

    private final List<File> receivedFiles = new ArrayList<>();

    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    private static final String TAG = "FilesFragment";
    private static final byte COMMAND_FILE = 0x01;

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    if (result.getData().getClipData() != null) {
                        for (int i = 0; i < result.getData().getClipData().getItemCount(); i++) {
                            sendFile(result.getData().getClipData().getItemAt(i).getUri());
                        }
                    } else if (result.getData().getData() != null) {
                        sendFile(result.getData().getData());
                    }
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
        binding = FragmentFilesBinding.inflate(inflater, container, false);
        fileExecutor = Executors.newSingleThreadExecutor();

        usbManager = (UsbManager) requireActivity().getSystemService(Context.USB_SERVICE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(requireActivity(), usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        binding.sendFileButton.setOnClickListener(v -> {
            if (!isDeviceConnected) {
                 Toast.makeText(getContext(), "USB-устройство не подключено.", Toast.LENGTH_SHORT).show();
                 checkForConnectedDevice();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            filePickerLauncher.launch(Intent.createChooser(intent, "Выберите файлы"));
        });

        binding.openReceivedFileButton.setOnClickListener(v -> showReceivedFilesDialog());
        binding.deleteFilesButton.setOnClickListener(v -> showDeleteFilesDialog());

        checkForConnectedDevice();
        return binding.getRoot();
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
        if (connection == null) {
             Log.e(TAG, "Не удалось открыть подключение к устройству");
             return;
        }
        UsbInterface usbInterface = device.getInterface(0);
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close();
            Log.e(TAG, "Не удалось запросить интерфейс");
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
            startReading(); // Start listening for incoming file commands
        } else {
            Log.e(TAG, "Не удалось найти точки входа и выхода");
            closeConnection(); // Clean up if we didn't find everything
        }
    }

    private void sendFile(final Uri fileUri) {
        fileExecutor.execute(() -> {
            if (!isDeviceConnected || endpointOut == null) return;
            try (Cursor cursor = requireActivity().getContentResolver().query(fileUri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    @SuppressLint("Range") String fileName = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                    @SuppressLint("Range") long fileSize = cursor.getLong(cursor.getColumnIndex(OpenableColumns.SIZE));

                    byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
                    ByteBuffer header = ByteBuffer.allocate(1 + 4 + nameBytes.length + 8).order(ByteOrder.BIG_ENDIAN);
                    header.put(COMMAND_FILE).putInt(nameBytes.length).put(nameBytes).putLong(fileSize);

                    connection.bulkTransfer(endpointOut, header.array(), header.position(), 5000);

                    try (InputStream inputStream = requireActivity().getContentResolver().openInputStream(fileUri)) {
                        byte[] buffer = new byte[16384];
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            int sent = connection.bulkTransfer(endpointOut, buffer, bytesRead, 10000);
                             if (sent < 0) throw new IOException("Не удалось отправить содержимое файла");
                        }
                    }
                    requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Отправлено: " + fileName, Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.e(TAG, "Ошибка отправки файла", e);
                 requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Ошибка отправки файла", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void startReading() {
        stopReading = false;
        readingThread = new Thread(() -> {
            while (!stopReading) {
                if (connection == null || endpointIn == null) break;
                try {
                    byte[] commandBytes = readExactly(1);
                    if (commandBytes == null) break;
                    byte command = commandBytes[0];

                    if(command == COMMAND_FILE) {
                        handleFileCommand();
                    } // Other commands (like STREAM, CHAT) are ignored in this fragment

                } catch (IOException e) {
                    if (!stopReading) Log.e(TAG, "Ошибка протокола.", e);
                    break;
                }
            }
        });
        readingThread.start();
    }

    private void handleFileCommand() throws IOException {
         byte[] nameLengthBytes = readExactly(4);
        if (nameLengthBytes == null) throw new IOException("Поток завершен");
        int nameLength = ByteBuffer.wrap(nameLengthBytes).order(ByteOrder.BIG_ENDIAN).getInt();
        if (nameLength <= 0 || nameLength > 4096) throw new IOException("Неверная длина имени файла");

        byte[] nameBytes = readExactly(nameLength);
        if (nameBytes == null) throw new IOException("Поток завершен");
        String fileName = new String(nameBytes, StandardCharsets.UTF_8);

        byte[] contentLengthBytes = readExactly(8);
        if (contentLengthBytes == null) throw new IOException("Поток завершен");
        long contentLength = ByteBuffer.wrap(contentLengthBytes).order(ByteOrder.BIG_ENDIAN).getLong();
        if (contentLength < 0) throw new IOException("Неверная длина содержимого");

        receiveFileContent(fileName, contentLength);
    }

    private void receiveFileContent(String fileName, long contentLength) {
        if (getActivity() == null) return;
        File file = new File(getActivity().getExternalFilesDir(null), fileName);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[16384];
            long totalBytesRead = 0;
            while (totalBytesRead < contentLength && !stopReading) {
                if (connection == null) break;
                long remaining = contentLength - totalBytesRead;
                int toRead = (int) Math.min(buffer.length, remaining);
                int bytesRead = connection.bulkTransfer(endpointIn, buffer, toRead, 5000);
                if (bytesRead > 0) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                } else if (bytesRead < 0) {
                    break;
                }
            }
            synchronized (receivedFiles) {
                receivedFiles.add(file);
            }
             getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Получено: " + fileName, Toast.LENGTH_SHORT).show());
        } catch (IOException e) {
            Log.e(TAG, "Ошибка записи файла " + fileName, e);
        }
    }

    private byte[] readExactly(int byteCount) throws IOException {
        byte[] buffer = new byte[byteCount];
        int offset = 0;
        while (offset < byteCount) {
            if (stopReading || connection == null) return null;
            int bytesRead = connection.bulkTransfer(endpointIn, buffer, offset, byteCount - offset, 10000);
            if (bytesRead > 0) {
                offset += bytesRead;
            } else {
                 throw new IOException("Не удалось прочитать необходимые байты из USB.");
            }
        }
        return buffer;
    }

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
        for (int i = 0; i < receivedFiles.size(); i++) {
            fileNames[i] = receivedFiles.get(i).getName();
        }

        final ArrayList<Integer> selectedItemsIndexes = new ArrayList<>();
        final boolean[] checkedItems = new boolean[receivedFiles.size()];

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Выберите файлы для удаления");
        builder.setMultiChoiceItems(fileNames, checkedItems, (dialog, which, isChecked) -> {
            if (isChecked) {
                selectedItemsIndexes.add(which);
            } else {
                selectedItemsIndexes.remove(Integer.valueOf(which));
            }
        });

        builder.setPositiveButton("Удалить", (dialog, id) -> {
            List<File> filesToRemove = new ArrayList<>();
            int deletedCount = 0;

            // Sort indexes in descending order to avoid index shifting issues when removing
            Collections.sort(selectedItemsIndexes, Collections.reverseOrder());

            for (int index : selectedItemsIndexes) {
                File fileToDelete = receivedFiles.get(index);
                if (fileToDelete.delete()) {
                    filesToRemove.add(fileToDelete);
                    deletedCount++;
                } else {
                    Log.e(TAG, "Не удалось удалить файл: " + fileToDelete.getName());
                }
            }

            synchronized (receivedFiles) {
                receivedFiles.removeAll(filesToRemove);
            }

            Toast.makeText(getContext(), "Удалено файлов: " + deletedCount, Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("Отмена", (dialog, id) -> dialog.dismiss());

        builder.create().show();
    }


    private void openFile(File file) {
        if (getContext() == null) return;
        try {
            Uri fileUri = FileProvider.getUriForFile(getContext(), getActivity().getPackageName() + ".provider", file);
            String mimeType = getMimeType(fileUri);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(fileUri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            if (intent.resolveActivity(getActivity().getPackageManager()) != null) {
                startActivity(intent);
            } else {
                Toast.makeText(getContext(), "Не найдено приложение для открытия этого типа файла.", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка открытия файла", e);
            Toast.makeText(getContext(), "Не удалось открыть файл.", Toast.LENGTH_SHORT).show();
        }
    }

    private String getMimeType(Uri uri) {
        String mimeType = null;
        if (uri.getScheme().equals(ContentResolver.SCHEME_CONTENT)) {
            mimeType = requireContext().getContentResolver().getType(uri);
        } else {
            String fileExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
            if (fileExtension != null) {
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension.toLowerCase());
            }
        }
        return mimeType;
    }

    private void closeConnection() {
        isDeviceConnected = false;
        stopReading = true;
        if (readingThread != null) {
            try { readingThread.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            readingThread = null;
        }
        if (connection != null) {
            connection.close();
            connection = null;
            endpointIn = null;
            endpointOut = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        closeConnection();
        if (fileExecutor != null) fileExecutor.shutdown();
        try {
            requireActivity().unregisterReceiver(usbReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Unregister receiver failed", e);
        }
        binding = null;
    }
}
