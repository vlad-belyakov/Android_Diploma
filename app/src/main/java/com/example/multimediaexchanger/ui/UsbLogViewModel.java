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

    public synchronized void log(String message, Throwable tr) {
        log(message + "\n" + Log.getStackTraceString(tr));
    }

    public synchronized void log(String message) {
        StackTraceElement[] stackTrace = new Throwable().getStackTrace();
        String callerInfo = "";
        if (stackTrace.length > 1) {
            for (int i = 1; i < stackTrace.length; i++) {
                StackTraceElement element = stackTrace[i];
                String className = element.getClassName();
                if (!className.equals(UsbLogViewModel.class.getName())) {
                    String simpleClassName = className.substring(className.lastIndexOf('.') + 1);
                    int dollarSign = simpleClassName.indexOf('$');
                    if (dollarSign != -1) {
                        simpleClassName = simpleClassName.substring(0, dollarSign);
                    }
                    callerInfo = "[" + simpleClassName + "." + element.getMethodName() + "] ";
                    break;
                }
            }
        }

        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        String logMessage = timestamp + ": " + callerInfo + message;

        logBuilder.append(logMessage).append("\n");
        logs.postValue(logBuilder.toString());

        writeLogToFile(logMessage + "\n");
    }

    public void clearLogs() {
        fileExecutor.execute(() -> {
            logBuilder.setLength(0);
            logs.postValue("");
            if (logFile.exists()) {
                try {
                    if (logFile.delete()) {
                        if (logFile.createNewFile()) {
                            Log.d(TAG, "Log file cleared and recreated.");
                        } else {
                             Log.e(TAG, "Failed to recreate log file.");
                        }
                    } else {
                        Log.e(TAG, "Failed to delete log file.");
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error managing log file for clearing.", e);
                }
            }
        });
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
            try (FileOutputStream fos = new FileOutputStream(logFile, true);
                 OutputStreamWriter writer = new OutputStreamWriter(fos)) {
                writer.append(logMessage);
            } catch (IOException e) {
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
