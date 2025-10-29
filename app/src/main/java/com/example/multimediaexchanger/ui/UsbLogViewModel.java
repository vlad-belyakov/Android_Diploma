package com.example.multimediaexchanger.ui;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UsbLogViewModel extends AndroidViewModel {

    private final MutableLiveData<Boolean> isConnected = new MutableLiveData<>(false);
    private final MutableLiveData<String> logs = new MutableLiveData<>("");
    private final StringBuilder logBuilder = new StringBuilder();
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final File logFile;
    private static final String LOG_FILE_NAME = "app_log.txt";
    private static final String TAG = "UsbLogViewModel";

    public UsbLogViewModel(@NonNull Application application) {
        super(application);
        logFile = new File(application.getFilesDir(), LOG_FILE_NAME);
        loadLogsFromFile();
    }

    public LiveData<Boolean> isConnected() {
        return isConnected;
    }

    public LiveData<String> getLogs() {
        return logs;
    }

    public void setConnected(boolean connected) {
        isConnected.postValue(connected);
    }

    // ADDED: Overloaded method to log exceptions with full stack trace
    public synchronized void log(String message, Throwable tr) {
        log(message + "\n" + Log.getStackTraceString(tr));
    }

    public synchronized void log(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        String logMessage = timestamp + ": " + message;

        // Update UI
        logBuilder.append(logMessage).append("\n");
        logs.postValue(logBuilder.toString());

        // Write to file in background
        writeLogToFile(logMessage + "\n");
    }

    private void loadLogsFromFile() {
        fileExecutor.execute(() -> {
            if (logFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logBuilder.append(line).append("\n");
                    }
                    logs.postValue(logBuilder.toString());
                    Log.d(TAG, "Logs loaded from file.");
                } catch (IOException e) {
                    Log.e(TAG, "Error loading logs from file", e);
                }
            }
        });
    }

    private void writeLogToFile(String logMessage) {
        fileExecutor.execute(() -> {
            try (FileOutputStream fos = new FileOutputStream(logFile, true); // true for append mode
                 OutputStreamWriter writer = new OutputStreamWriter(fos)) {
                writer.append(logMessage);
            } catch (IOException e) {
                // If our own logger fails, fall back to Android's default Log
                Log.e(TAG, "FATAL: Error writing log to file", e);
            }
        });
    }
    
    @Override
    protected void onCleared() {
        super.onCleared();
        fileExecutor.shutdown();
    }
}
