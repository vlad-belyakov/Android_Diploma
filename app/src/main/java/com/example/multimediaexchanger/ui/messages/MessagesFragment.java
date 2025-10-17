package com.example.multimediaexchanger.ui.messages;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
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
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.multimediaexchanger.databinding.FragmentMessagesBinding;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MessagesFragment extends Fragment {

    private FragmentMessagesBinding binding;
    private UsbManager usbManager;
    private UsbDeviceConnection connection;
    private UsbEndpoint endpointIn, endpointOut;
    private volatile boolean stopReading = false;
    private Thread readingThread;
    private ExecutorService messageExecutor;
    private volatile boolean isDeviceConnected = false;

    private final List<Message> messageList = new ArrayList<>();
    private MessagesAdapter messagesAdapter;

    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    private static final String TAG = "MessagesFragment";
    private static final byte COMMAND_MESSAGE_TEXT = 0x06;
    private static final byte COMMAND_MESSAGE_IMAGE = 0x07;

    private final ActivityResultLauncher<Intent> imagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    Uri imageUri = result.getData().getData();
                    sendImage(imageUri);
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
        binding = FragmentMessagesBinding.inflate(inflater, container, false);
        messageExecutor = Executors.newCachedThreadPool();

        setupRecyclerView();

        usbManager = (UsbManager) requireActivity().getSystemService(Context.USB_SERVICE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(requireActivity(), usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        setupClickListeners();
        checkForConnectedDevice();
        return binding.getRoot();
    }

    private void setupRecyclerView() {
        messagesAdapter = new MessagesAdapter(getContext(), messageList);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        layoutManager.setStackFromEnd(true);
        binding.messagesRecyclerView.setLayoutManager(layoutManager);
        binding.messagesRecyclerView.setAdapter(messagesAdapter);
    }

    private void setupClickListeners() {
        binding.sendButton.setOnClickListener(v -> {
            String text = binding.messageInput.getText().toString().trim();
            if (!text.isEmpty() && isDeviceConnected) {
                sendTextMessage(text);
                binding.messageInput.setText("");
            } else if (!isDeviceConnected) {
                Toast.makeText(getContext(), "USB-устройство не подключено.", Toast.LENGTH_SHORT).show();
            }
        });

        binding.attachButton.setOnClickListener(v -> {
            if (!isDeviceConnected) {
                Toast.makeText(getContext(), "USB-устройство не подключено.", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            imagePickerLauncher.launch(intent);
        });
    }

    private void sendTextMessage(String text) {
        messageExecutor.execute(() -> {
            if (!isDeviceConnected || endpointOut == null) return;
            try {
                byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
                ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + textBytes.length).order(ByteOrder.BIG_ENDIAN);
                buffer.put(COMMAND_MESSAGE_TEXT).putInt(textBytes.length).put(textBytes);
                connection.bulkTransfer(endpointOut, buffer.array(), buffer.position(), 5000);

                requireActivity().runOnUiThread(() -> {
                    addMessage(new Message(Message.MessageType.TEXT_SENT, text));
                });

            } catch (Exception e) {
                Log.e(TAG, "Ошибка отправки текстового сообщения", e);
            }
        });
    }

    private void sendImage(Uri imageUri) {
        messageExecutor.execute(() -> {
            if (!isDeviceConnected || endpointOut == null) return;
            try (InputStream inputStream = requireContext().getContentResolver().openInputStream(imageUri)) {
                if (inputStream == null) return;

                byte[] imageBytes = new byte[inputStream.available()];
                inputStream.read(imageBytes);

                ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + imageBytes.length).order(ByteOrder.BIG_ENDIAN);
                buffer.put(COMMAND_MESSAGE_IMAGE).putInt(imageBytes.length).put(imageBytes);

                connection.bulkTransfer(endpointOut, buffer.array(), buffer.limit(), 10000);

                requireActivity().runOnUiThread(() -> {
                    addMessage(new Message(Message.MessageType.IMAGE_SENT, imageUri));
                });

            } catch (Exception e) {
                Log.e(TAG, "Ошибка отправки изображения", e);
            }
        });
    }

    private void startReading() {
        stopReading = false;
        readingThread = new Thread(() -> {
            while (!stopReading) {
                if (connection == null || endpointIn == null) break;
                try {
                    byte[] header = readExactly(5);
                    if (header == null) break;

                    ByteBuffer headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
                    byte command = headerBuffer.get();
                    int length = headerBuffer.getInt();

                    if (length <= 0 || length > 10 * 1024 * 1024) { // 10MB limit
                        Log.w(TAG, "Неверная длина сообщения: " + length);
                        continue;
                    }

                    byte[] data = readExactly(length);
                    if (data == null) break;

                    if (command == COMMAND_MESSAGE_TEXT) {
                        String text = new String(data, StandardCharsets.UTF_8);
                        requireActivity().runOnUiThread(() -> {
                            addMessage(new Message(Message.MessageType.TEXT_RECEIVED, text));
                        });
                    } else if (command == COMMAND_MESSAGE_IMAGE) {
                        File imageFile = saveImageToFile(data);
                        if (imageFile != null) {
                            Uri imageUri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".provider", imageFile);
                            requireActivity().runOnUiThread(() -> {
                                addMessage(new Message(Message.MessageType.IMAGE_RECEIVED, imageUri));
                            });
                        }
                    }

                } catch (IOException e) {
                    if (!stopReading) Log.e(TAG, "Ошибка чтения", e);
                    break;
                }
            }
        });
        readingThread.start();
    }

    private File saveImageToFile(byte[] imageData) {
        try {
            File imagesDir = new File(requireContext().getCacheDir(), "images");
            if (!imagesDir.exists()) {
                imagesDir.mkdirs();
            }
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File imageFile = new File(imagesDir, "IMG_" + timeStamp + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                fos.write(imageData);
            }
            return imageFile;
        } catch (IOException e) {
            Log.e(TAG, "Не удалось сохранить изображение", e);
            return null;
        }
    }

    private void addMessage(Message message) {
        messageList.add(message);
        messagesAdapter.notifyItemInserted(messageList.size() - 1);
        binding.messagesRecyclerView.scrollToPosition(messageList.size() - 1);
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
                throw new IOException("USB read failed.");
            }
        }
        return buffer;
    }

    // --- USB & LIFECYCLE LOGIC ---
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
        isDeviceConnected = false;
        stopReading = true;
        if (readingThread != null) {
            try {
                readingThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
        if (messageExecutor != null) messageExecutor.shutdown();
        try {
            requireActivity().unregisterReceiver(usbReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Unregister receiver failed", e);
        }
        binding = null;
    }
}
