package com.example.multimediaexchanger.ui;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;

import com.example.multimediaexchanger.ui.network.NetworkViewModel;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UdpViewModel extends AndroidViewModel {

    public static class UdpMessage {
        public byte type;
        public byte[] payload;
        public String senderIp;

        public UdpMessage(byte type, byte[] payload, String senderIp) {
            this.type = type;
            this.payload = payload;
            this.senderIp = senderIp;
        }
    }

    private final MutableLiveData<UdpMessage> receivedMessage = new MutableLiveData<>();
    private final MutableLiveData<String> discoveredIpEvent = new MutableLiveData<>();
    private final MutableLiveData<String> handshakeEvent = new MutableLiveData<>();
    private final MutableLiveData<String> socketErrorEvent = new MutableLiveData<>();

    private UsbLogViewModel logger;

    private DatagramSocket socket;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private volatile boolean isRunning = false;

    private static final int LISTEN_PORT = 12345;

    public static final byte MESSAGE_TYPE_TEXT = 0x01;
    public static final byte MESSAGE_TYPE_FILE_HEADER = 0x02;
    public static final byte MESSAGE_TYPE_FILE_CHUNK = 0x03;
    public static final byte MESSAGE_TYPE_FILE_END = 0x04;
    public static final byte MESSAGE_TYPE_HANDSHAKE = 0x0B;

    public static final byte MESSAGE_TYPE_CALL_REQUEST = 0x10;
    public static final byte MESSAGE_TYPE_CALL_ACCEPT = 0x11;
    public static final byte MESSAGE_TYPE_CALL_REJECT = 0x12;
    public static final byte MESSAGE_TYPE_CALL_END = 0x13;
    public static final byte MESSAGE_TYPE_CALL_AUDIO = 0x14;

    public static final byte MESSAGE_TYPE_STREAM_VIDEO_CONFIG = 0x20;
    public static final byte MESSAGE_TYPE_STREAM_VIDEO_DATA = 0x21;
    public static final byte MESSAGE_TYPE_STREAM_AUDIO_CONFIG = 0x22;
    public static final byte MESSAGE_TYPE_STREAM_AUDIO_DATA = 0x23;

    private final Application app;

    public UdpViewModel(@NonNull Application application) {
        super(application);
        this.app = application;
    }

    public void setLogger(UsbLogViewModel logger) {
        this.logger = logger;
        logUsbDevices();
        startUdpSocket();
    }

    private void log(String message) {
        if (logger != null) logger.log(message);
        if (message.contains("ERROR: Socket недоступен")) {
            socketErrorEvent.postValue("Socket недоступен");
        }
    }

    private void log(String message, Throwable tr) {
        if (logger != null) logger.log(message, tr);
    }

    public LiveData<UdpMessage> getReceivedMessage() { return receivedMessage; }
    public LiveData<String> getDiscoveredIpEvent() { return discoveredIpEvent; }
    public LiveData<String> getHandshakeEvent() { return handshakeEvent; }
    public LiveData<String> getSocketErrorEvent() { return socketErrorEvent; }

    private void startUdpSocket() {
        if (isRunning) {
            log("UDP: Socket listener уже запущен.");
            return;
        }
        isRunning = true;

        executorService.execute(() -> {
            try {
                NetworkViewModel networkViewModel =
                        new ViewModelProvider.AndroidViewModelFactory(app).create(NetworkViewModel.class);

                String ip = networkViewModel.getRawDeviceIpAddress().getValue();
                if (ip == null || ip.isEmpty()) ip = findEthernetOrUsbIp();

                if (ip == null) {
                    log("UDP: Ни USB, ни Ethernet интерфейсы не найдены. Wi-Fi запрещён, запуск отменён.");
                    return;
                }

                InetAddress bindAddress = InetAddress.getByName(ip);
                socket = new DatagramSocket(LISTEN_PORT, bindAddress);
                socket.setBroadcast(true);
                log("UDP: Socket создан и привязан к IP: " + ip + " (порт " + LISTEN_PORT + ")");

                byte[] buffer = new byte[65507];

                while (isRunning) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String senderIp = packet.getAddress().getHostAddress();
                    int length = packet.getLength();
                    if (length > 0) {
                        byte messageType = packet.getData()[0];
                        byte[] payload = new byte[length - 1];
                        System.arraycopy(packet.getData(), 1, payload, 0, length - 1);

                        log("UDP: RX " + length + " bytes от " + senderIp + " (тип 0x" + String.format("%02X", messageType) + ")");

                        if (messageType == MESSAGE_TYPE_HANDSHAKE) {
                            log("UDP: Получен Handshake от " + senderIp);
                            discoveredIpEvent.postValue(senderIp);
                            handshakeEvent.postValue(senderIp);
                            byte[] response = "Ethernet/USB соединение установлено!".getBytes(StandardCharsets.UTF_8);
                            sendData(senderIp, MESSAGE_TYPE_TEXT, response);
                        } else {
                            receivedMessage.postValue(new UdpMessage(messageType, payload, senderIp));
                        }
                    }
                }

            } catch (Exception e) {
                log("UDP: Ошибка при создании сокета", e);
            } finally {
                closeSocket();
            }
        });
    }

    private String findEthernetOrUsbIp() {
        try {
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = intf.getName().toLowerCase();

                // Высший приоритет — Ethernet (eth0)
                if (name.equals("eth0")) {
                    for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                        if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                            String ip = addr.getHostAddress();
                            log("UDP: выбран приоритетный интерфейс eth0 → " + ip);
                            return ip;
                        }
                    }
                }
            }

            // Если eth0 нет — fallback на USB/rnnet
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = intf.getName().toLowerCase();
                if (name.contains("wlan") || name.contains("wifi") || name.contains("p2p") || name.contains("radio")) continue;
                if (name.contains("rndis") || name.contains("usb") || name.contains("rnnet")) {
                    for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                        if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                            String ip = addr.getHostAddress();
                            log("UDP: fallback интерфейс: " + name + " → " + ip);
                            return ip;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            log("UDP: Ошибка при поиске интерфейса", e);
        }
        return null;
    }


    private void logUsbDevices() {
        executorService.execute(() -> {
            try {
                log("DEVICES: Проверка сетевых интерфейсов...");
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                for (NetworkInterface intf : Collections.list(interfaces)) {
                    String name = intf.getName().toLowerCase();
                    if (name.contains("rndis") || name.contains("usb") || name.contains("eth") || name.contains("rnnet")) {
                        for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                            if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                                log("DEVICES: " + intf.getName() + " → " + addr.getHostAddress());
                            }
                        }
                    }
                }
                log("DEVICES: Проверка завершена.");
            } catch (Exception e) {
                log("DEVICES: Ошибка при перечислении", e);
            }
        });
    }

    public void sendData(String ipAddress, byte messageType, byte[] data) {
        executorService.execute(() -> {
            try {
                if (socket == null || socket.isClosed()) {
                    log("ERROR: Socket недоступен (Ethernet/USB only).");
                    return;
                }

                InetAddress address = InetAddress.getByName(ipAddress);
                byte[] message = new byte[data.length + 1];
                message[0] = messageType;
                System.arraycopy(data, 0, message, 1, data.length);

                DatagramPacket packet = new DatagramPacket(message, message.length, address, LISTEN_PORT);
                socket.send(packet);

                log("UDP: TX " + message.length + " bytes (тип 0x" +
                        String.format("%02X", messageType) + ") → " + ipAddress);

            } catch (IOException e) {
                log("UDP: Ошибка отправки данных", e);
            }
        });
    }

    public void sendHandshake(String ipAddress) {
        log("UDP: Отправка Handshake → " + ipAddress);
        sendData(ipAddress, MESSAGE_TYPE_HANDSHAKE, new byte[0]);
    }

    private void closeSocket() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
            socket = null;
            log("UDP: Socket закрыт (Ethernet/USB only).");
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        isRunning = false;
        closeSocket();
        if (!executorService.isShutdown()) {
            executorService.shutdown();
            log("UDP: Executor завершён (Ethernet/USB only).");
        }
    }
}
