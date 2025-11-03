package com.example.multimediaexchanger.ui.network;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
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

        final TextView logTextView = binding.logTextView;
        final ScrollView logScrollView = binding.logScrollView;

        // Make the TextView scrollable
        logTextView.setMovementMethod(new ScrollingMovementMethod());

        // Observer for device's own IP
        networkViewModel.getDeviceIpAddress().observe(getViewLifecycleOwner(), binding.myIpAddressText::setText);

        // Observer for connection status
        networkViewModel.getTargetIpAddress().observe(getViewLifecycleOwner(), targetIp -> {
            if (targetIp != null && !targetIp.isEmpty()) {
                binding.connectionStatusText.setText("Подключено к: " + targetIp);
                binding.connectionStatusText.setBackgroundColor(Color.parseColor("#388E3C")); // Green
            } else {
                binding.connectionStatusText.setText("Нет подключения");
                binding.connectionStatusText.setBackgroundColor(Color.parseColor("#D32F2F")); // Red
            }
        });

        // Observer for logs with auto-scroll
        usbLogViewModel.getLogs().observe(getViewLifecycleOwner(), logText -> {
            logTextView.setText(logText);
            // Post a runnable to scroll down, which will be executed after the layout pass
            logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        });

        // Listener for discovered peers - This now ONLY logs, no auto-connect action
        udpViewModel.getDiscoveredIpEvent().observe(getViewLifecycleOwner(), discoveredIp -> {
            if (discoveredIp != null && !discoveredIp.equals(networkViewModel.getRawDeviceIpAddress().getValue())) {
                usbLogViewModel.log("Net: Peer discovered at " + discoveredIp + ". You can connect manually.");
            }
        });

        // Click listener for the connect button
        binding.connectButton.setOnClickListener(v -> {
            String ip = binding.ipAddressInput.getText().toString();
            if (!ip.isEmpty() && ip.contains(".")) {
                networkViewModel.setTargetIpAddress(ip);
                udpViewModel.sendHandshake(ip);
                Toast.makeText(getContext(), "Запрос на подключение отправлен!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "Введите корректный IP адрес", Toast.LENGTH_SHORT).show();
            }
        });

        // Click listener for the copy logs button to copy the last 100 lines
        binding.copyLogsButton.setOnClickListener(v -> {
            String allLogs = logTextView.getText().toString();
            String[] lines = allLogs.split("\n");
            int start = Math.max(0, lines.length - 100);
            StringBuilder last100Lines = new StringBuilder();
            for (int i = start; i < lines.length; i++) {
                last100Lines.append(lines[i]).append("\n");
            }

            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Last 100 Logs", last100Lines.toString());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(getContext(), "Последние 100 строк логов скопированы", Toast.LENGTH_SHORT).show();
        });

        // Click listener for the clear logs button
        binding.clearLogsButton.setOnClickListener(v -> {
            usbLogViewModel.clearLogs();
            Toast.makeText(getContext(), "Логи очищены", Toast.LENGTH_SHORT).show();
        });

        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
