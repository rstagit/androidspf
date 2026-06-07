package com.rstagit.androidspf.ui.dashboard;

import android.net.Uri;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.rstagit.androidspf.R;
import com.rstagit.androidspf.core.engine.TunnelController;
import com.rstagit.androidspf.core.model.LogEntry;
import com.rstagit.androidspf.core.model.TunnelProfile;
import com.rstagit.androidspf.core.model.TunnelState;
import com.rstagit.androidspf.data.repository.TunnelRepository;
import com.rstagit.androidspf.util.ServiceLauncher;

import java.util.List;

public final class LaunchActivity extends AppCompatActivity {

    private TunnelController controller;
    private TunnelRepository repository;
    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSave;

    private TextView statusLabel;
    private TextView statusDetail;
    private TextView toggleLabel;
    private FrameLayout toggleButton;
    private View statusIndicator;

    private EditText inputSni;
    private EditText inputIp;
    private EditText inputPort;
    private TextView tvLogs;
    private ScrollView logScroll;

    private final String selectedMethod = TunnelProfile.METHOD_COMBINED;
    private boolean suppressSave;

    private final TunnelController.StateListener uiListener = new TunnelController.StateListener() {
        @Override
        public void onStateChanged(TunnelState state) {
            runOnUiThread(() -> applyState(state));
        }

        @Override
        public void onLogEntry(LogEntry entry) {
            runOnUiThread(() -> appendLog(entry.getMessage()));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launch);

        controller = TunnelController.getInstance();
        repository = new TunnelRepository(this);

        bindViews();
        loadProfile();
        setupListeners();
        loadInitialLogs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        controller.addListener(uiListener);
        applyState(controller.getCurrentState());
    }

    @Override
    protected void onPause() {
        controller.removeListener(uiListener);
        flushSave();
        super.onPause();
    }

    private void bindViews() {
        statusLabel = findViewById(R.id.status_label);
        statusDetail = findViewById(R.id.status_detail);
        toggleLabel = findViewById(R.id.toggle_label);
        toggleButton = findViewById(R.id.btn_toggle);
        statusIndicator = findViewById(R.id.status_indicator);

        inputSni = findViewById(R.id.input_sni);
        inputIp = findViewById(R.id.input_ip);
        inputPort = findViewById(R.id.input_port);
        tvLogs = findViewById(R.id.tv_logs);
        logScroll = findViewById(R.id.log_scroll);
    }

    private void loadProfile() {
        suppressSave = true;
        TunnelProfile profile = repository.getActiveProfile();
        inputSni.setText(profile.getFakeSni());
        inputIp.setText(profile.getRemoteIp());
        inputPort.setText(String.valueOf(profile.getLocalPort()));
        suppressSave = false;
    }

    private void setupListeners() {
        toggleButton.setOnClickListener(v -> onToggleClicked());
        
        findViewById(R.id.tv_telegram).setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/rstasnispoof"));
                startActivity(intent);
            } catch (Exception ignored) {}
        });

        TextWatcher autoSaveWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                scheduleSave();
            }
        };
        inputSni.addTextChangedListener(autoSaveWatcher);
        inputIp.addTextChangedListener(autoSaveWatcher);
        inputPort.addTextChangedListener(autoSaveWatcher);
    }

    private void loadInitialLogs() {
        List<LogEntry> logs = controller.getLogSnapshot();
        StringBuilder sb = new StringBuilder();
        for (LogEntry entry : logs) {
            sb.append("> ").append(entry.getMessage()).append("\n");
        }
        tvLogs.setText(sb.toString());
        scrollToBottom();
    }

    private void appendLog(String msg) {
        tvLogs.append("> " + msg + "\n");
        scrollToBottom();
    }

    private void scrollToBottom() {
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void scheduleSave() {
        if (suppressSave) return;
        if (pendingSave != null) {
            saveHandler.removeCallbacks(pendingSave);
        }
        pendingSave = this::flushSave;
        saveHandler.postDelayed(pendingSave, 600);
    }

    private void flushSave() {
        if (suppressSave) return;
        TunnelProfile profile = buildProfileFromUi();
        if (profile != null) {
            repository.saveProfile(profile);
        }
    }

    private TunnelProfile buildProfileFromUi() {
        String sni = inputSni.getText().toString().trim();
        String ip = inputIp.getText().toString().trim();
        String portStr = inputPort.getText().toString().trim();

        if (sni.isEmpty() || ip.isEmpty() || portStr.isEmpty()) return null;

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            return null;
        }

        return new TunnelProfile.Builder()
            .remoteEndpoint(ip + ":443")
            .fakeSni(sni)
            .bypassMethod(selectedMethod)
            .localPort(port)
            .build();
    }

    private void onToggleClicked() {
        TunnelState current = controller.getCurrentState();

        if (current.isRunning()) {
            toggleButton.setEnabled(false);
            ServiceLauncher.stopTunnel(this);
            toggleButton.postDelayed(() -> toggleButton.setEnabled(true), 1000);
            return;
        }

        TunnelProfile profile = buildProfileFromUi();
        if (profile == null) {
            appendLog("Error: Please fill all fields correctly");
            return;
        }

        toggleButton.setEnabled(false);
        repository.saveProfile(profile);
        ServiceLauncher.startTunnel(this, profile);
        toggleButton.postDelayed(() -> toggleButton.setEnabled(true), 1000);
    }

    private void applyState(TunnelState state) {
        switch (state.getStatus()) {
            case ACTIVE:
                statusLabel.setText(R.string.status_active);
                toggleLabel.setText(R.string.action_stop);
                toggleButton.setBackgroundResource(R.drawable.bg_power_button_active);
                statusIndicator.setBackgroundResource(R.drawable.indicator_active);
                statusDetail.setText(state.getMessage());
                setInputsEnabled(false);
                break;
            case STARTING:
                statusLabel.setText(R.string.status_starting);
                toggleLabel.setText(R.string.action_starting);
                toggleButton.setBackgroundResource(R.drawable.bg_power_button);
                statusIndicator.setBackgroundResource(R.drawable.indicator_pending);
                statusDetail.setText(state.getMessage());
                setInputsEnabled(false);
                break;
            case ERROR:
                statusLabel.setText(R.string.status_error);
                toggleLabel.setText(R.string.action_start);
                toggleButton.setBackgroundResource(R.drawable.bg_power_button);
                statusIndicator.setBackgroundResource(R.drawable.indicator_error);
                statusDetail.setText(state.getMessage());
                setInputsEnabled(true);
                break;
            default:
                statusLabel.setText(R.string.status_idle);
                toggleLabel.setText(R.string.action_start);
                toggleButton.setBackgroundResource(R.drawable.bg_power_button);
                statusIndicator.setBackgroundResource(R.drawable.indicator_idle);
                statusDetail.setText("Ready to connect");
                setInputsEnabled(true);
        }
    }

    private void setInputsEnabled(boolean enabled) {
        inputSni.setEnabled(enabled);
        inputIp.setEnabled(enabled);
        inputPort.setEnabled(enabled);
        float alpha = enabled ? 1.0f : 0.6f;
        inputSni.setAlpha(alpha);
        inputIp.setAlpha(alpha);
        inputPort.setAlpha(alpha);
    }
}
