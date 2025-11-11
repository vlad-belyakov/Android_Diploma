package com.example.multimediaexchanger.ui.messages;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.multimediaexchanger.databinding.FragmentMessagesBinding;
import com.example.multimediaexchanger.ui.UdpViewModel;
import com.example.multimediaexchanger.ui.UsbLogViewModel;
import com.example.multimediaexchanger.ui.network.NetworkViewModel;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MessagesFragment extends Fragment {

    private FragmentMessagesBinding binding;
    private UdpViewModel udpViewModel;
    private NetworkViewModel networkViewModel;
    private UsbLogViewModel usbLogViewModel;
    private MessagesViewModel messagesViewModel;
    private MessagesAdapter messagesAdapter;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final int CHUNK_SIZE = 16384; // 16KB

    private File currentReceivingFile;
    private long expectedFileSize = 0;
    private long receivedBytes = 0;
    private boolean legacyMode = false;
    private FileOutputStream fos;

    private final ArrayList<byte[]> pendingChunks = new ArrayList<>();

    // NEW: для надёжной передачи через ACK
    private Set<Integer> receivedChunks = new HashSet<>();
    private int totalChunksExpected;

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) sendFile(uri);
                    else usbLogViewModel.log("File Picker: URI was null");
                }
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentMessagesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        udpViewModel = new ViewModelProvider(requireActivity()).get(UdpViewModel.class);
        networkViewModel = new ViewModelProvider(requireActivity()).get(NetworkViewModel.class);
        usbLogViewModel = new ViewModelProvider(requireActivity()).get(UsbLogViewModel.class);
        messagesViewModel = new ViewModelProvider(this).get(MessagesViewModel.class);

        setupRecyclerView();
        setupClickListeners();
        observeUdpMessages();
        observeChatHistory();
    }

    private void setupRecyclerView() {
        messagesAdapter = new MessagesAdapter(getContext(), new ArrayList<>());
        binding.messagesRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.messagesRecyclerView.setAdapter(messagesAdapter);
    }

    private void setupClickListeners() {
        binding.sendButton.setOnClickListener(v -> sendMessage());
        binding.attachButton.setOnClickListener(v -> openFilePicker());
    }

    private void observeChatHistory() {
        messagesViewModel.getMessages().observe(getViewLifecycleOwner(), messages -> {
            messagesAdapter.updateMessages(messages);
            if (messages != null && !messages.isEmpty()) {
                binding.messagesRecyclerView.post(() ->
                        binding.messagesRecyclerView.scrollToPosition(messages.size() - 1));
            }
        });
    }

    private void sendMessage() {
        String text = binding.messageInput.getText().toString().trim();
        if (text.isEmpty()) return;

        messagesViewModel.addMessage(new Message(Message.MessageType.TEXT_SENT, text));
        binding.messageInput.setText("");

        String targetIp = networkViewModel.getTargetIpAddress().getValue();
        if (targetIp != null && !targetIp.isEmpty()) {
            usbLogViewModel.log("Sending message to " + targetIp);
            udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_TEXT, text.getBytes(StandardCharsets.UTF_8));
        } else {
            usbLogViewModel.log("Message saved locally (no target IP).");
        }
    }

    private void openFilePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            filePickerLauncher.launch(intent);
        } catch (Exception e) {
            usbLogViewModel.log("ERROR: Failed to open file picker", e);
        }
    }

    private void sendFile(Uri uri) {
        String targetIp = networkViewModel.getTargetIpAddress().getValue();
        if (targetIp == null || targetIp.isEmpty()) {
            Toast.makeText(getContext(), "IP адрес получателя не указан", Toast.LENGTH_SHORT).show();
            return;
        }

        executor.execute(() -> {
            try {
                Uri stableUri = copyFileToInternalCache(uri);
                if (stableUri == null) {
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(getContext(), "Не удалось обработать файл", Toast.LENGTH_SHORT).show());
                    return;
                }

                File localFile = new File(stableUri.getPath());
                long fileSize = localFile.length();
                String fileName = localFile.getName();

                usbLogViewModel.log("File Transfer: Sending '" + fileName + "' (" + fileSize + " bytes) to " + targetIp);
                requireActivity().runOnUiThread(() ->
                        messagesViewModel.addMessage(new Message(Message.MessageType.IMAGE_SENT, stableUri)));

                byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
                ByteBuffer headerBuffer = ByteBuffer.allocate(4 + nameBytes.length + 8);
                headerBuffer.putInt(nameBytes.length);
                headerBuffer.put(nameBytes);
                headerBuffer.putLong(fileSize);
                udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_FILE_HEADER, headerBuffer.array());

                // NEW: Надёжная отправка с повтором пока не придёт ACK
                try (InputStream inputStream = new FileInputStream(localFile)) {
                    byte[] buffer = new byte[CHUNK_SIZE];
                    int bytesRead;
                    int chunkIndex = 0;
                    long totalBytesSent = 0;

                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        final int currentIndex = chunkIndex;
                        byte[] chunkData = new byte[4 + bytesRead];
                        ByteBuffer chunkBuffer = ByteBuffer.wrap(chunkData);
                        chunkBuffer.putInt(currentIndex);
                        chunkBuffer.put(buffer, 0, bytesRead);

                        boolean ackReceived = false;
                        while (!ackReceived) {
                            udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_FILE_CHUNK, chunkBuffer.array());

                            // Ждём ACK 300 мс
                            Thread.sleep(100);
                            ackReceived = udpViewModel.isAckReceived(currentIndex);
                        }

                        chunkIndex++;
                        totalBytesSent += bytesRead;
                        updateProgress(totalBytesSent, fileSize);
                    }
                }

                udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_FILE_END, new byte[0]);
                usbLogViewModel.log("File Transfer: Sent " + fileName + " successfully");

            } catch (Exception e) {
                usbLogViewModel.log("ERROR: Failed to send file", e);
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(), "Ошибка отправки файла", Toast.LENGTH_SHORT).show());
            } finally {
                requireActivity().runOnUiThread(() -> binding.fileProgressBar.setVisibility(View.GONE));
            }
        });
    }

    private Uri copyFileToInternalCache(Uri uri) {
        try {
            ContentResolver resolver = requireContext().getContentResolver();
            String sourceFileName = getFileName(resolver, uri);
            String uniqueFileName = System.currentTimeMillis() + "_" + (sourceFileName != null ? sourceFileName : "file");
            File destinationFile = new File(requireContext().getFilesDir(), uniqueFileName);

            try (InputStream inputStream = resolver.openInputStream(uri);
                 FileOutputStream outputStream = new FileOutputStream(destinationFile)) {
                if (inputStream == null) throw new IOException("Could not open input stream for " + uri);
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }

            usbLogViewModel.log("File copied to internal cache: " + destinationFile.getAbsolutePath());
            return Uri.fromFile(destinationFile);
        } catch (Exception e) {
            usbLogViewModel.log("ERROR: Failed to copy file", e);
            return null;
        }
    }

    private String getFileName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                @SuppressLint("Range")
                String name = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                if (name != null) return name;
            }
        }
        return null;
    }

    private void observeUdpMessages() {
        udpViewModel.getReceivedMessage().observe(getViewLifecycleOwner(), message -> {
            if (message == null) return;

            switch (message.type) {
                case UdpViewModel.MESSAGE_TYPE_TEXT:
                    String text = new String(message.payload, StandardCharsets.UTF_8);
                    messagesViewModel.addMessage(new Message(Message.MessageType.TEXT_RECEIVED, text));
                    break;
                case UdpViewModel.MESSAGE_TYPE_FILE_HEADER:
                    handleFileHeader(message.payload);
                    break;
                case UdpViewModel.MESSAGE_TYPE_FILE_CHUNK:
                    handleFileChunk(message.payload);
                    break;
                case UdpViewModel.MESSAGE_TYPE_FILE_END:
                    handleFileEnd();
                    break;
                case UdpViewModel.MESSAGE_TYPE_FILE_ACK: // NEW
                    udpViewModel.receiveAck(message.payload);
                    break;
            }
        });
    }

    private void handleFileHeader(byte[] payload) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            int nameLen = buffer.getInt();
            String name;
            long size;

            if (nameLen < 0 || nameLen > 512) {
                legacyMode = true;
                buffer = ByteBuffer.wrap(payload);
                size = buffer.getLong();
                name = new String(buffer.array(), buffer.position(), buffer.remaining(), StandardCharsets.UTF_8);
            } else {
                legacyMode = false;
                byte[] nameBytes = new byte[nameLen];
                buffer.get(nameBytes);
                name = new String(nameBytes, StandardCharsets.UTF_8);
                size = buffer.getLong();
            }

            expectedFileSize = size;
            receivedBytes = 0;
            totalChunksExpected = (int) Math.ceil((double) expectedFileSize / CHUNK_SIZE);
            receivedChunks.clear();

            currentReceivingFile = new File(requireContext().getCacheDir(), name);
            File parent = currentReceivingFile.getParentFile();
            if (!parent.exists()) parent.mkdirs();
            fos = new FileOutputStream(currentReceivingFile);

            usbLogViewModel.log("Receiving file '" + name + "' (" + expectedFileSize + " bytes)");
            requireActivity().runOnUiThread(() -> binding.fileProgressBar.setVisibility(View.VISIBLE));

            for (byte[] chunk : pendingChunks) writeChunkToFile(chunk);
            pendingChunks.clear();

        } catch (Exception e) {
            usbLogViewModel.log("ERROR: Failed to parse file header", e);
        }
    }

    private void handleFileChunk(byte[] payload) {
        if (fos == null) {
            pendingChunks.add(payload);
            return;
        }
        writeChunkToFile(payload);
    }

    private void writeChunkToFile(byte[] payload) {
        try {
            if (legacyMode) {
                fos.write(payload);
                receivedBytes += payload.length;
            } else {
                ByteBuffer chunkBuf = ByteBuffer.wrap(payload);
                int index = chunkBuf.getInt();
                byte[] data = new byte[chunkBuf.remaining()];
                chunkBuf.get(data);

                if (!receivedChunks.contains(index)) {
                    receivedChunks.add(index);
                    fos.write(data);
                    receivedBytes += data.length;

                    // NEW: Отправка ACK
                    String senderIp = networkViewModel.getTargetIpAddress().getValue();
                    if (senderIp != null) {
                        ByteBuffer ack = ByteBuffer.allocate(4);
                        ack.putInt(index);
                        udpViewModel.sendData(senderIp, UdpViewModel.MESSAGE_TYPE_FILE_ACK, ack.array());
                    }
                }
            }
            updateProgress(receivedBytes, expectedFileSize);
        } catch (Exception e) {
            usbLogViewModel.log("ERROR: Writing chunk failed", e);
        }
    }

    private void handleFileEnd() {
        try {
            if (fos != null) {
                fos.close();
                fos = null;
            }

            if (currentReceivingFile != null) {
                usbLogViewModel.log("File Transfer: Received " + currentReceivingFile.getName());
                messagesViewModel.addMessage(new Message(Message.MessageType.IMAGE_RECEIVED, Uri.fromFile(currentReceivingFile)));
            }

            currentReceivingFile = null;
            receivedBytes = 0;
            expectedFileSize = 0;
            receivedChunks.clear();

        } catch (Exception e) {
            usbLogViewModel.log("ERROR: Closing file failed", e);
        } finally {
            requireActivity().runOnUiThread(() -> binding.fileProgressBar.setVisibility(View.GONE));
        }
    }

    private void updateProgress(long sent, long total) {
        requireActivity().runOnUiThread(() -> {
            binding.fileProgressBar.setMax((int) total);
            binding.fileProgressBar.setProgress((int) sent);
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        executor.shutdown();
        binding = null;
    }
}
