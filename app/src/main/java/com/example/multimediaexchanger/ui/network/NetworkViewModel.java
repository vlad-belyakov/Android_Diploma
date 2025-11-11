package com.example.multimediaexchanger.ui.network;

import android.app.Application;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

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
    private Network usbNetwork;

    public NetworkViewModel(Application application) {
        super(application);
        findUsbNetwork(application);
    }

    public void setLogger(UsbLogViewModel logger) {
        this.logger = logger;
        findDeviceIp();
    }

    private final MutableLiveData<Boolean> isInCall = new MutableLiveData<>(false);

    public LiveData<Boolean> isInCall() {
        return isInCall;
    }

    public void setInCall(boolean inCall) {
        isInCall.postValue(inCall);
    }

    private void log(String msg) { if (logger != null) logger.log(msg); }

    public LiveData<String> getDeviceIpAddress() { return deviceIpAddress; }
    public LiveData<String> getRawDeviceIpAddress() { return rawDeviceIpAddress; }
    public LiveData<String> getTargetIpAddress() { return targetIpAddress; }
    public void setTargetIpAddress(String ip) { targetIpAddress.setValue(ip); }

    public Network getUsbNetwork() { return usbNetwork; }

    private void findUsbNetwork(Application app) {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Application.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            LinkProperties props = cm.getLinkProperties(network);

            if (caps != null && props != null && props.getInterfaceName() != null) {
                String iface = props.getInterfaceName();
                 if (iface.contains("rndis") || iface.contains("usb") || iface.contains("eth") || iface.contains("rnnet")) {
                     usbNetwork = network;
                     log("Net: Found interface: " + iface + " [USB/Ethernet]");
                     return;
                 }
            }
        }

        log("WARNING: USB/Ethernet network not found, fallback to default network.");
    }

    private void findDeviceIp() {
        executorService.execute(() -> {
            String formattedIp = "Мой IP: не найден";
            String rawIp = null;
            String usbIp = null;

            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                for (NetworkInterface intf : Collections.list(interfaces)) {
                    String name = intf.getName();

                     // 🔒 Wi-Fi интерфейсы игнорируем
                     if (name.contains("wlan") || name.contains("wifi") || name.contains("p2p") || name.contains("radio")) {
                         log("Net: Skipped Wi-Fi interface: " + name);
                         continue;
                     }

                    for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            String hostAddress = addr.getHostAddress();
                            boolean isUsb = name.contains("rndis") || name.contains("usb");
                            boolean isEthernet = name.contains("eth") || name.contains("rnnet");

                            log("Net: Found " + hostAddress + " on " + name +
                                    (isUsb ? " [USB]" : isEthernet ? " [Ethernet]" : ""));

                            if (isUsb || isEthernet) usbIp = hostAddress;
                            else if (rawIp == null) rawIp = hostAddress;
                        }
                    }
                }

                if (usbIp != null) {
                    rawIp = usbIp;
                    formattedIp = "Мой IP (USB/Ethernet): " + rawIp;
                } else if (rawIp != null) {
                    formattedIp = "Мой IP: " + rawIp;
                } else {
                    rawIp = "127.0.0.1";
                    formattedIp = "Мой IP (loopback): 127.0.0.1";
                }

            } catch (SocketException e) {
                log("ERROR: Finding IP failed: " + e.getMessage());
                formattedIp = "Ошибка поиска IP";
                rawIp = "127.0.0.1";
            }

            log("Net: Final selected IP: " + rawIp);
            deviceIpAddress.postValue(formattedIp);
            rawDeviceIpAddress.postValue(rawIp);
        });
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (!executorService.isShutdown()) executorService.shutdown();
    }
}
