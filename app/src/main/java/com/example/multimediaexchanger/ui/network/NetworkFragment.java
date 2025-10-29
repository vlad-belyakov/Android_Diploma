package com.example.multimediaexchanger.ui.network;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.multimediaexchanger.databinding.FragmentNetworkBinding;
import com.example.multimediaexchanger.ui.UdpViewModel;
import com.example.multimediaexchanger.ui.UsbLogViewModel;

public class NetworkFragment extends Fragment {

    private FragmentNetworkBinding binding;
    private NetworkViewModel networkViewModel;
    private UdpViewModel udpViewModel;
    private UsbLogViewModel usbLogViewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentNetworkBinding.inflate(inflater, container, false);

        networkViewModel = new ViewModelProvider(requireActivity()).get(NetworkViewModel.class);
        udpViewModel = new ViewModelProvider(requireActivity()).get(UdpViewModel.class);
        usbLogViewModel = new ViewModelProvider(requireActivity()).get(UsbLogViewModel.class);

        final TextView myIpAddressText = binding.myIpAddressText;
        final TextView connectionStatusText = binding.connectionStatusText; // The new field
        final EditText ipAddressInput = binding.ipAddressInput;
        final Button connectButton = binding.connectButton;
        final TextView logTextView = binding.logTextView;
        final ScrollView logScrollView = binding.logScrollView;

        // Observer for device's own IP
        networkViewModel.getDeviceIpAddress().observe(getViewLifecycleOwner(), myIpAddressText::setText);

        // --- ADDED: Observer for connection status ---
        networkViewModel.getTargetIpAddress().observe(getViewLifecycleOwner(), targetIp -> {
            if (targetIp != null && !targetIp.isEmpty()) {
                connectionStatusText.setText("Подключено к: " + targetIp);
                connectionStatusText.setBackgroundColor(Color.parseColor("#388E3C")); // Green
            } else {
                connectionStatusText.setText("Нет подключения");
                connectionStatusText.setBackgroundColor(Color.parseColor("#D32F2F")); // Red
            }
        });

        // Observer for logs
        usbLogViewModel.getLogs().observe(getViewLifecycleOwner(), logText -> {
            logTextView.setText(logText);
            logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        });

        // Observer for discovered peers
        udpViewModel.getDiscoveredIpEvent().observe(getViewLifecycleOwner(), discoveredIp -> {
            String currentTargetIp = networkViewModel.getTargetIpAddress().getValue();
            if (discoveredIp != null && !discoveredIp.equals(networkViewModel.getRawDeviceIpAddress().getValue()) && !discoveredIp.equals(currentTargetIp)) {
                usbLogViewModel.log("Net: Peer discovered at " + discoveredIp + ". Set as target.");
                ipAddressInput.setText(discoveredIp);
                networkViewModel.setTargetIpAddress(discoveredIp);
            }
        });

        // Click listener for the connect button
        connectButton.setOnClickListener(v -> {
            String ip = ipAddressInput.getText().toString();
            if (!ip.isEmpty() && ip.contains(".")) {
                networkViewModel.setTargetIpAddress(ip);
                udpViewModel.sendHandshake(ip);
                Toast.makeText(getContext(), "Запрос на подключение отправлен!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "Введите корректный IP адрес", Toast.LENGTH_SHORT).show();
            }
        });

        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
