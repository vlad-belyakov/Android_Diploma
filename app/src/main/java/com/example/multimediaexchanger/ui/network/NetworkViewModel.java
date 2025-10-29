package com.example.multimediaexchanger.ui.network;

import android.app.Application;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.multimediaexchanger.ui.UsbLogViewModel;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NetworkViewModel extends AndroidViewModel {

    private final MutableLiveData<String> deviceIpAddress = new MutableLiveData<>();
    private final MutableLiveData<String> rawDeviceIpAddress = new MutableLiveData<>();
    private final MutableLiveData<String> targetIpAddress = new MutableLiveData<>();
    private UsbLogViewModel logger;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public NetworkViewModel(Application application) {
        super(application);
    }

    public void setLogger(UsbLogViewModel logger) {
        this.logger = logger;
        findDeviceIp();
    }

    private void log(String message) {
        if (logger != null) {
            logger.log(message);
        }
    }

    private void log(String message, Throwable tr) {
        if (logger != null) {
            logger.log(message, tr);
        }
    }

    public LiveData<String> getDeviceIpAddress() {
        return deviceIpAddress;
    }

    public LiveData<String> getRawDeviceIpAddress() {
        return rawDeviceIpAddress;
    }

    public LiveData<String> getTargetIpAddress() {
        return targetIpAddress;
    }

    public void setTargetIpAddress(String ip) {
        targetIpAddress.setValue(ip);
    }

    private void findDeviceIp() {
        executorService.execute(() -> {
            String formattedIp = "Мой IP: не найден";
            String rawIp = null;
            String usbIp = null;

            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                for (NetworkInterface intf : Collections.list(interfaces)) {
                    for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            String hostAddress = addr.getHostAddress();
                            log("Net: Found IP: " + hostAddress + " on interface: " + intf.getName());
                            
                            boolean isUsbInterface = intf.getName().contains("rndis") || intf.getName().contains("usb");

                            if (isUsbInterface) {
                                if (usbIp == null || !hostAddress.endsWith(".1")) {
                                    usbIp = hostAddress;
                                }
                            } else if (rawIp == null) {
                                rawIp = hostAddress;
                            }
                        }
                    }
                }

                if (usbIp != null) {
                    rawIp = usbIp;
                    formattedIp = "Мой IP (USB): " + rawIp;
                } else if (rawIp != null) {
                    formattedIp = "Мой IP: " + rawIp;
                }

            } catch (SocketException e) {
                log("ERROR: Finding IP failed", e);
                formattedIp = "Ошибка поиска IP";
            }
            
            log("Net: Final selected IP: " + rawIp);
            deviceIpAddress.postValue(formattedIp);
            rawDeviceIpAddress.postValue(rawIp);
        });
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}
