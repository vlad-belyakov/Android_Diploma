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
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.multimediaexchanger.R;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MessagesFragment extends Fragment {

    private FragmentMessagesBinding binding;
    private UdpViewModel udpViewModel;
    private NetworkViewModel networkViewModel;
    private UsbLogViewModel usbLogViewModel;
    private MessagesViewModel messagesViewModel;
    private MessagesAdapter messagesAdapter;

    private RecyclerView messagesRecyclerView;
    private EditText messageInput;
    private ProgressBar fileProgressBar;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final int CHUNK_SIZE = 16384; // 16KB

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        sendFile(uri);
                    } else {
                        usbLogViewModel.log("File Picker: URI was null");
                    }
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

        messagesRecyclerView = view.findViewById(R.id.messagesRecyclerView);
        Button sendButton = view.findViewById(R.id.sendButton);
        ImageButton attachButton = view.findViewById(R.id.attachButton);
        messageInput = view.findViewById(R.id.messageInput);
        fileProgressBar = view.findViewById(R.id.fileProgressBar);

        udpViewModel = new ViewModelProvider(requireActivity()).get(UdpViewModel.class);
        networkViewModel = new ViewModelProvider(requireActivity()).get(NetworkViewModel.class);
        usbLogViewModel = new ViewModelProvider(requireActivity()).get(UsbLogViewModel.class);
        messagesViewModel = new ViewModelProvider(this).get(MessagesViewModel.class);

        setupRecyclerView();
        setupClickListeners(sendButton, attachButton);
        observeUdpMessages();
        observeChatHistory();
    }

    private void setupRecyclerView() {
        messagesAdapter = new MessagesAdapter(getContext(), new ArrayList<>());
        messagesRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        messagesRecyclerView.setAdapter(messagesAdapter);
    }

    private void setupClickListeners(Button sendButton, ImageButton attachButton) {
        sendButton.setOnClickListener(v -> sendMessage());
        attachButton.setOnClickListener(v -> openFilePicker());
    }

    private void observeChatHistory() {
        messagesViewModel.getMessages().observe(getViewLifecycleOwner(), messages -> {
            messagesAdapter.updateMessages(messages);
            if (messages != null && !messages.isEmpty()) {
                messagesRecyclerView.scrollToPosition(messages.size() - 1);
            }
        });
    }

    private void sendMessage() {
        String text = messageInput.getText().toString().trim();
        if (text.isEmpty()) return;
        String targetIp = networkViewModel.getTargetIpAddress().getValue();
        if (targetIp == null || targetIp.isEmpty()) {
            Toast.makeText(getContext(), "IP адрес получателя не указан", Toast.LENGTH_SHORT).show();
            return;
        }

        messagesViewModel.addMessage(new Message(Message.MessageType.TEXT_SENT, text));
        udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_TEXT, text.getBytes(StandardCharsets.UTF_8));
        messageInput.setText("");
    }

    private void openFilePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            filePickerLauncher.launch(intent);
        } catch (Exception e) {
            usbLogViewModel.log("ERROR: Failed to open file picker", e);
        }
    }

    // --- TOTALLY REWRITTEN FILE HANDLING LOGIC ---

    private void sendFile(Uri uri) {
        String targetIp = networkViewModel.getTargetIpAddress().getValue();
        if (targetIp == null || targetIp.isEmpty()) {
            Toast.makeText(getContext(), "IP адрес получателя не указан", Toast.LENGTH_SHORT).show();
            return;
        }

        executor.execute(() -> {
            try {
                // Step 1: Copy file to a stable location and get a stable URI
                Uri stableUri = copyFileToInternalCache(uri);
                if (stableUri == null) {
                    requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Не удалось обработать файл", Toast.LENGTH_SHORT).show());
                    return;
                }

                File localFile = new File(stableUri.getPath());
                long fileSize = localFile.length();
                String fileName = localFile.getName();

                usbLogViewModel.log("File Transfer: Starting to send file '" + fileName + "' (" + fileSize + " bytes) to " + targetIp);

                // Step 2: Add message to UI with the STABLE URI
                requireActivity().runOnUiThread(() -> {
                    messagesViewModel.addMessage(new Message(Message.MessageType.IMAGE_SENT, stableUri));
                });

                // Step 3: Send header
                ByteBuffer headerBuffer = ByteBuffer.allocate(Long.BYTES + fileName.length());
                headerBuffer.putLong(fileSize);
                headerBuffer.put(fileName.getBytes(StandardCharsets.UTF_8));
                udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_FILE_HEADER, headerBuffer.array());

                // Step 4: Send file content from the stable local copy
                try (InputStream inputStream = new FileInputStream(localFile)) {
                    byte[] buffer = new byte[CHUNK_SIZE];
                    int bytesRead;
                    long totalBytesSent = 0;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        byte[] chunk = new byte[bytesRead];
                        System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                        udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_FILE_CHUNK, chunk);
                        totalBytesSent += bytesRead;
                        updateProgress(totalBytesSent, fileSize);
                    }
                }

                udpViewModel.sendData(targetIp, UdpViewModel.MESSAGE_TYPE_FILE_END, new byte[0]);
                usbLogViewModel.log("File Transfer: Successfully sent file " + fileName);

            } catch (Exception e) {
                usbLogViewModel.log("ERROR: Failed to send file", e);
                requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), "Ошибка отправки файла", Toast.LENGTH_SHORT).show());
            } finally {
                requireActivity().runOnUiThread(() -> fileProgressBar.setVisibility(View.GONE));
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

                if (inputStream == null) {
                    throw new IOException("Could not open input stream for " + uri);
                }

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }
            usbLogViewModel.log("File copied to internal cache: " + destinationFile.getAbsolutePath());
            return Uri.fromFile(destinationFile);
        } catch (Exception e) {
            usbLogViewModel.log("ERROR: Failed to copy file to internal cache", e);
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
        final FileOutputStream[] fileOutputStream = {null};
        final File[] tempFile = {null};

        udpViewModel.getReceivedMessage().observe(getViewLifecycleOwner(), message -> {
            if (message == null) return;

            switch (message.type) {
                case UdpViewModel.MESSAGE_TYPE_TEXT:
                    String text = new String(message.payload, StandardCharsets.UTF_8);
                    messagesViewModel.addMessage(new Message(Message.MessageType.TEXT_RECEIVED, text));
                    break;

                case UdpViewModel.MESSAGE_TYPE_FILE_HEADER:
                    try {
                        ByteBuffer buffer = ByteBuffer.wrap(message.payload);
                        long fileSize = buffer.getLong();
                        String fileName = new String(buffer.array(), buffer.position(), buffer.remaining(), StandardCharsets.UTF_8);

                        tempFile[0] = new File(requireContext().getCacheDir(), fileName);
                        fileOutputStream[0] = new FileOutputStream(tempFile[0]);
                        usbLogViewModel.log("File Transfer: Receiving file '" + fileName + "' (" + fileSize + " bytes) from " + message.senderIp);
                        requireActivity().runOnUiThread(() -> fileProgressBar.setVisibility(View.VISIBLE));
                    } catch (Exception e) {
                        usbLogViewModel.log("ERROR: Processing file header failed", e);
                    }
                    break;

                case UdpViewModel.MESSAGE_TYPE_FILE_CHUNK:
                    try {
                        if (fileOutputStream[0] != null) {
                            fileOutputStream[0].write(message.payload);
                        }
                    } catch (IOException e) {
                        usbLogViewModel.log("ERROR: Writing file chunk failed", e);
                    }
                    break;

                case UdpViewModel.MESSAGE_TYPE_FILE_END:
                    try {
                        if (fileOutputStream[0] != null) {
                            fileOutputStream[0].close();
                            fileOutputStream[0] = null;

                            if (tempFile[0] != null) {
                                usbLogViewModel.log("File Transfer: Successfully received file " + tempFile[0].getName());
                                messagesViewModel.addMessage(new Message(Message.MessageType.IMAGE_RECEIVED, Uri.fromFile(tempFile[0])));
                                tempFile[0] = null;
                            }
                        }
                    } catch (IOException e) {
                        usbLogViewModel.log("ERROR: Closing file stream failed", e);
                    } finally {
                        requireActivity().runOnUiThread(() -> fileProgressBar.setVisibility(View.GONE));
                    }
                    break;
            }
        });
    }

    private void updateProgress(long sent, long total) {
        requireActivity().runOnUiThread(() -> {
            fileProgressBar.setMax((int) total);
            fileProgressBar.setProgress((int) sent);
            if(fileProgressBar.getVisibility() == View.GONE) {
                fileProgressBar.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
        binding = null;
    }
}
