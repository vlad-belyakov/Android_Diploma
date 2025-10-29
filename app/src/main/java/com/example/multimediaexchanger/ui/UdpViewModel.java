package com.example.multimediaexchanger.ui;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
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
    private UsbLogViewModel logger;

    private DatagramSocket socket;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private volatile boolean isRunning = false;

    private static final int LISTEN_PORT = 12345;

    // Message Types
    public static final byte MESSAGE_TYPE_TEXT = 0x01;
    public static final byte MESSAGE_TYPE_FILE_HEADER = 0x02;
    public static final byte MESSAGE_TYPE_FILE_CHUNK = 0x03;
    public static final byte MESSAGE_TYPE_FILE_END = 0x04;
    public static final byte MESSAGE_TYPE_STREAM = 0x05;
    public static final byte MESSAGE_TYPE_CALL = 0x06;
    public static final byte MESSAGE_TYPE_DISCOVERY = 0x0A;
    public static final byte MESSAGE_TYPE_HANDSHAKE = 0x0B;

    public UdpViewModel(@NonNull Application application) {
        super(application);
    }

    public void setLogger(UsbLogViewModel logger) {
        this.logger = logger;
        startUdpSocket();
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

    public LiveData<UdpMessage> getReceivedMessage() {
        return receivedMessage;
    }

    public LiveData<String> getDiscoveredIpEvent() {
        return discoveredIpEvent;
    }

    private void startUdpSocket() {
        if (isRunning) {
            log("UDP: Socket listener is already running.");
            return;
        }
        isRunning = true;

        executorService.execute(() -> {
            try {
                socket = new DatagramSocket(LISTEN_PORT);
                socket.setBroadcast(true);
                log("UDP: Socket created. Listening on port " + LISTEN_PORT);
                byte[] buffer = new byte[65507];

                while (isRunning) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        socket.receive(packet);

                        String senderIp = packet.getAddress().getHostAddress();
                        if (packet.getLength() > 0) {
                            byte messageType = packet.getData()[0];
                            int length = packet.getLength() - 1;
                            byte[] payload = new byte[length];
                            System.arraycopy(packet.getData(), 1, payload, 0, length);

                            log("UDP: RX: Received " + packet.getLength() + " bytes from " + senderIp + " of type " + String.format("0x%02X", messageType));

                            switch(messageType){
                                case MESSAGE_TYPE_DISCOVERY:
                                    String discoveredIp = new String(payload, StandardCharsets.UTF_8);
                                    discoveredIpEvent.postValue(discoveredIp);
                                    break;
                                case MESSAGE_TYPE_HANDSHAKE:
                                    log("UDP: RX: Handshake received from " + senderIp);
                                    discoveredIpEvent.postValue(senderIp);
                                    byte[] response = "Соединение установлено!".getBytes(StandardCharsets.UTF_8);
                                    sendData(senderIp, MESSAGE_TYPE_TEXT, response);
                                    break;
                                default:
                                    receivedMessage.postValue(new UdpMessage(messageType, payload, senderIp));
                                    break;
                            }
                        }
                    } catch (IOException e) {
                        if (isRunning) log("ERROR: UDP receive error", e);
                    }
                }
            } catch (SocketException e) {
                log("FATAL: UDP socket creation failed", e);
            } finally {
                closeSocket();
            }
        });
    }

    public void sendData(String ipAddress, byte messageType, byte[] data) {
        executorService.execute(() -> {
            if (socket == null || socket.isClosed()) {
                log("ERROR: Cannot send data, socket is not ready.");
                return;
            }
            try {
                InetAddress address = InetAddress.getByName(ipAddress);
                byte[] message = new byte[data.length + 1];
                message[0] = messageType;
                System.arraycopy(data, 0, message, 1, data.length);

                DatagramPacket packet = new DatagramPacket(message, message.length, address, LISTEN_PORT);
                socket.send(packet);
                log("UDP: TX: Sent " + message.length + " bytes of type " + String.format("0x%02X", messageType) + " to " + ipAddress);
            } catch (Exception e) {
                log("ERROR: UDP send packet failed to " + ipAddress, e);
            }
        });
    }
    
    public void sendHandshake(String ipAddress) {
        log("UDP: Queuing handshake to " + ipAddress);
        sendData(ipAddress, MESSAGE_TYPE_HANDSHAKE, new byte[0]);
    }

    public void broadcastDiscovery(String ownIp) {
        if (ownIp == null || ownIp.isEmpty() || !ownIp.contains(".")) return;
        
        executorService.execute(() -> {
            if (socket == null || socket.isClosed()) {
                log("ERROR: Cannot broadcast, socket is not ready.");
                return;
            }
            try {
                socket.setBroadcast(true);
                String broadcastAddress = ownIp.substring(0, ownIp.lastIndexOf(".")) + ".255";
                InetAddress address = InetAddress.getByName(broadcastAddress);
                
                byte[] ipBytes = ownIp.getBytes(StandardCharsets.UTF_8);
                byte[] message = new byte[ipBytes.length + 1];
                message[0] = MESSAGE_TYPE_DISCOVERY;
                System.arraycopy(ipBytes, 0, message, 1, ipBytes.length);

                DatagramPacket packet = new DatagramPacket(message, message.length, address, LISTEN_PORT);
                socket.send(packet);
            } catch (Exception e) {
                log("ERROR: Failed to broadcast discovery", e);
            }
        });
    }

    private void closeSocket(){
        if (socket != null && !socket.isClosed()) {
            socket.close();
            socket = null;
            log("UDP: Socket closed.");
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        isRunning = false;
        closeSocket(); 
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            log("UDP: Executor service shut down.");
        }
    }
}
