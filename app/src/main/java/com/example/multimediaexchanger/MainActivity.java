package com.example.multimediaexchanger;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.example.multimediaexchanger.databinding.ActivityMainBinding;
import com.example.multimediaexchanger.ui.UdpViewModel;
import com.example.multimediaexchanger.ui.UsbLogViewModel;
import com.example.multimediaexchanger.ui.network.NetworkViewModel;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private UsbLogViewModel usbLogViewModel;
    private UdpViewModel udpViewModel;
    private NetworkViewModel networkViewModel;

    private static final int PERMISSIONS_REQUEST_CODE = 123;

    private final BroadcastReceiver powerConnectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null || usbLogViewModel == null) return;

            if (action.equals(Intent.ACTION_POWER_CONNECTED)) {
                Intent batteryStatusIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int chargePlug = batteryStatusIntent != null ? batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) : -1;
                if (chargePlug == BatteryManager.BATTERY_PLUGGED_USB) {
                    usbLogViewModel.log("System: USB cable connected (charging).");
                } else {
                    usbLogViewModel.log("System: Power cable connected (not USB).");
                }
            } else if (action.equals(Intent.ACTION_POWER_DISCONNECTED)) {
                usbLogViewModel.log("System: Power cable disconnected.");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // --- ViewModel Initialization ---
        usbLogViewModel = new ViewModelProvider(this).get(UsbLogViewModel.class);
        udpViewModel = new ViewModelProvider(this).get(UdpViewModel.class);
        networkViewModel = new ViewModelProvider(this).get(NetworkViewModel.class);

        // Set the logger instance for all ViewModels that need it
        udpViewModel.setLogger(usbLogViewModel);
        networkViewModel.setLogger(usbLogViewModel);
        
        usbLogViewModel.log("----------------------------------");
        usbLogViewModel.log("            APP STARTED            ");
        usbLogViewModel.log("----------------------------------");

        checkAndRequestPermissions();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(powerConnectionReceiver, filter);

        AppBarConfiguration appBarConfiguration = new AppBarConfiguration.Builder(
                R.id.navigation_network, R.id.navigation_messages, R.id.navigation_files, R.id.navigation_stream, R.id.navigation_calls)
                .build();
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_activity_main);

        NavigationUI.setupWithNavController(binding.navView, navController);

        // Observe incoming calls to switch to the calls tab
        observeCallRequests(navController, binding.navView);
    }

    private void observeCallRequests(NavController navController, BottomNavigationView navView) {
        udpViewModel.getReceivedMessage().observe(this, message -> {
            if (message != null && message.type == UdpViewModel.MESSAGE_TYPE_CALL_REQUEST) {
                // Check if we are not already on the calls screen
                if (navController.getCurrentDestination() != null && navController.getCurrentDestination().getId() != R.id.navigation_calls) {
                    usbLogViewModel.log("System: Incoming call detected, switching to Calls tab.");
                    // This is the key change: select the tab directly
                    navView.setSelectedItemId(R.id.navigation_calls);
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(powerConnectionReceiver);
        usbLogViewModel.log("----------------------------------");
        usbLogViewModel.log("             APP CLOSED             ");
        usbLogViewModel.log("----------------------------------");
        binding = null;
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        String[] permissionsToRequest;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest = new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            };
        } else {
            permissionsToRequest = new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            };
        }

        for (String permission : permissionsToRequest) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(permission);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), PERMISSIONS_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            List<String> deniedPermissions = new ArrayList<>();
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    usbLogViewModel.log("Permission granted: " + permissions[i]);
                } else {
                    deniedPermissions.add(permissions[i]);
                    usbLogViewModel.log("Permission DENIED: " + permissions[i]);
                }
            }

            if (!deniedPermissions.isEmpty()) {
                boolean permanentlyDenied = false;
                for (String perm : deniedPermissions) {
                    if (!ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
                        permanentlyDenied = true;
                        break;
                    }
                }

                if (permanentlyDenied) {
                    new AlertDialog.Builder(this)
                            .setTitle("Требуются разрешения")
                            .setMessage("Некоторые важные разрешения были отклонены навсегда. Для корректной работы приложения, пожалуйста, предоставьте их в настройках.")
                            .setPositiveButton("В настройки", (dialog, which) -> {
                                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                Uri uri = Uri.fromParts("package", getPackageName(), null);
                                intent.setData(uri);
                                startActivity(intent);
                            })
                            .setNegativeButton("Отмена", (dialog, which) -> dialog.dismiss())
                            .create()
                            .show();
                }
            }
        }
    }
}
