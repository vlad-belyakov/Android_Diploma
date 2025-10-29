package com.example.multimediaexchanger.ui.messages;

import android.app.Application;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MessagesViewModel extends AndroidViewModel {

    private final MutableLiveData<List<Message>> messages = new MutableLiveData<>(new ArrayList<>());
    private final File historyFile;
    private final Gson gson = new Gson();
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private static final String HISTORY_FILE_NAME = "chat_history.json";
    private static final String TAG = "MessagesViewModel";

    public MessagesViewModel(@NonNull Application application) {
        super(application);
        historyFile = new File(application.getFilesDir(), HISTORY_FILE_NAME);
        loadHistory();
    }

    public LiveData<List<Message>> getMessages() {
        return messages;
    }

    public void addMessage(Message message) {
        List<Message> currentMessages = messages.getValue();
        if (currentMessages != null) {
            currentMessages.add(message);
            messages.postValue(currentMessages);
            saveHistory(new ArrayList<>(currentMessages)); // Pass a copy to the background thread
        }
    }

    private void loadHistory() {
        fileExecutor.execute(() -> {
            if (historyFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(historyFile))) {
                    Type listType = new TypeToken<ArrayList<Message>>() {}.getType();
                    List<Message> history = gson.fromJson(reader, listType);
                    if (history != null) {
                        for (Message msg : history) {
                            if (msg.getImageUriString() != null) {
                                msg.setImageUri(Uri.parse(msg.getImageUriString()));
                            }
                        }
                        messages.postValue(history);
                        Log.d(TAG, "Chat history loaded.");
                    }
                } catch (Exception e) { // Catch all exceptions, including JSON parsing errors
                    Log.e(TAG, "Error loading chat history, file might be corrupt. Deleting it.", e);
                    historyFile.delete(); // Delete corrupt file
                    messages.postValue(new ArrayList<>()); // Start with a fresh list
                }
            }
        });
    }

    private void saveHistory(List<Message> messagesToSave) {
        fileExecutor.execute(() -> {
            try (FileWriter writer = new FileWriter(historyFile)) {
                for (Message msg : messagesToSave) {
                    if (msg.getImageUri() != null) {
                        msg.setImageUriString(msg.getImageUri().toString());
                    }
                }
                gson.toJson(messagesToSave, writer);
            } catch (IOException e) {
                Log.e(TAG, "Error saving chat history", e);
            }
        });
    }
    
    @Override
    protected void onCleared() {
        super.onCleared();
        fileExecutor.shutdown();
    }
}
