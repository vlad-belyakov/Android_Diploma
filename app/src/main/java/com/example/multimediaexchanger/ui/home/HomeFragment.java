package com.example.multimediaexchanger.ui.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.multimediaexchanger.databinding.FragmentHomeBinding;
import com.example.multimediaexchanger.ui.UsbLogViewModel;
import com.example.multimediaexchanger.ui.network.NetworkViewModel;

public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;
    private UsbLogViewModel usbLogViewModel;
    private NetworkViewModel networkViewModel;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        // Get a reference to the shared ViewModels
        usbLogViewModel = new ViewModelProvider(requireActivity()).get(UsbLogViewModel.class);
        networkViewModel = new ViewModelProvider(requireActivity()).get(NetworkViewModel.class);

        // Observe connection status from the correct ViewModel
        networkViewModel.getTargetIpAddress().observe(getViewLifecycleOwner(), targetIp -> {
            boolean isConnected = targetIp != null && !targetIp.isEmpty();
            if (isConnected) {
                binding.connectionIndicator.setBackgroundColor(getResources().getColor(android.R.color.holo_green_dark));
            } else {
                binding.connectionIndicator.setBackgroundColor(getResources().getColor(android.R.color.holo_red_dark));
            }
        });

        // Observe logs
        usbLogViewModel.getLogs().observe(getViewLifecycleOwner(), logs -> {
            binding.logsTextView.setText(logs);
        });

        usbLogViewModel.log("HomeFragment view created.");

        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
