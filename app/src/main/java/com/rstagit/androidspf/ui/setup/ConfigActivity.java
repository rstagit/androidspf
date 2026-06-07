package com.rstagit.androidspf.ui.setup;

import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.rstagit.androidspf.R;
import com.rstagit.androidspf.core.model.TunnelProfile;
import com.rstagit.androidspf.data.repository.TunnelRepository;
import com.rstagit.androidspf.util.ConfigParser;
import com.rstagit.androidspf.util.InputValidator;

import java.util.List;

public final class ConfigActivity extends AppCompatActivity {

    private TunnelRepository repository;

    // Mode selector
    private Spinner spinnerInputMode;

    // Section: manual
    private LinearLayout sectionManual;
    private EditText inputRemote;
    private EditText inputSni;
    private EditText inputPort;
    private Spinner spinnerMethod;

    // Section: single config / sni-ip pair
    private LinearLayout sectionSingle;
    private EditText inputSingleConfig;
    private TextView tvParsedPreview;
    private Button btnParseSingle;

    // Section: bulk config list
    private LinearLayout sectionBulk;
    private EditText inputBulkConfigs;
    private TextView tvBulkParsed;
    private Button btnParseBulk;
    private Button btnPickBest;

    // Parsed state from bulk
    private List<ConfigParser.ParsedEntry> parsedEntries;
    private int selectedEntryIndex = 0;

    // Bottom row
    private Button btnSave;
    private Button btnReset;
    private Button btnPasteClipboard;

    private static final String[] INPUT_MODES = {
        "Manual (IP / SNI / Port)",
        "Single Config or SNI:IP Pair",
        "Bulk Config List"
    };

    private static final int MODE_MANUAL = 0;
    private static final int MODE_SINGLE = 1;
    private static final int MODE_BULK   = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_config);
        }

        repository = new TunnelRepository(this);

        // Mode spinner
        spinnerInputMode = findViewById(R.id.spinner_input_mode);
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, INPUT_MODES);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerInputMode.setAdapter(modeAdapter);

        // Sections
        sectionManual = findViewById(R.id.section_manual);
        sectionSingle = findViewById(R.id.section_single);
        sectionBulk   = findViewById(R.id.section_bulk);

        // Manual fields
        inputRemote = findViewById(R.id.input_remote);
        inputSni    = findViewById(R.id.input_sni);
        inputPort   = findViewById(R.id.input_port);
        spinnerMethod = findViewById(R.id.spinner_method);

        String[] methods = {TunnelProfile.METHOD_COMBINED, TunnelProfile.METHOD_FRAGMENT};
        ArrayAdapter<String> methodAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, methods);
        methodAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMethod.setAdapter(methodAdapter);

        // Single config fields
        inputSingleConfig = findViewById(R.id.input_single_config);
        tvParsedPreview   = findViewById(R.id.tv_parsed_preview);
        btnParseSingle    = findViewById(R.id.btn_parse_single);

        // Bulk fields
        inputBulkConfigs = findViewById(R.id.input_bulk_configs);
        tvBulkParsed     = findViewById(R.id.tv_bulk_parsed);
        btnParseBulk     = findViewById(R.id.btn_parse_bulk);
        btnPickBest      = findViewById(R.id.btn_pick_best);

        // Bottom buttons
        btnSave           = findViewById(R.id.btn_save);
        btnReset          = findViewById(R.id.btn_reset);
        btnPasteClipboard = findViewById(R.id.btn_paste_clipboard);

        loadCurrentProfile();

        // Mode switch
        spinnerInputMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switchMode(position);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Live parse on single config input
        inputSingleConfig.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                tryLiveParseSingle(s.toString());
            }
        });

        btnParseSingle.setOnClickListener(v -> parseSingleAndApply());
        btnParseBulk.setOnClickListener(v -> parseBulkList());
        btnPickBest.setOnClickListener(v -> showPickEntryDialog());
        btnSave.setOnClickListener(v -> onSave());
        btnReset.setOnClickListener(v -> onReset());
        btnPasteClipboard.setOnClickListener(v -> pasteFromClipboard());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void switchMode(int mode) {
        sectionManual.setVisibility(mode == MODE_MANUAL ? View.VISIBLE : View.GONE);
        sectionSingle.setVisibility(mode == MODE_SINGLE ? View.VISIBLE : View.GONE);
        sectionBulk.setVisibility(mode == MODE_BULK   ? View.VISIBLE : View.GONE);
    }

    private void loadCurrentProfile() {
        TunnelProfile profile = repository.getActiveProfile();
        inputRemote.setText(profile.getRemoteEndpoint());
        inputSni.setText(profile.getFakeSni());
        inputPort.setText(String.valueOf(profile.getLocalPort()));

        String method = profile.getBypassMethod();
        ArrayAdapter<String> adapter = (ArrayAdapter<String>) spinnerMethod.getAdapter();
        for (int i = 0; i < adapter.getCount(); i++) {
            if (adapter.getItem(i).equals(method)) {
                spinnerMethod.setSelection(i);
                break;
            }
        }
    }

    private void tryLiveParseSingle(String text) {
        if (text == null || text.trim().isEmpty()) {
            tvParsedPreview.setText(getString(R.string.hint_parse_preview));
            tvParsedPreview.setAlpha(0.5f);
            return;
        }
        ConfigParser.ParsedEntry entry = ConfigParser.parseLine(text.trim());
        if (entry != null) {
            tvParsedPreview.setText("✓  IP: " + entry.ip + ":" + entry.port + "\n    SNI: " + entry.sni);
            tvParsedPreview.setAlpha(1.0f);
        } else {
            tvParsedPreview.setText("— Could not parse yet");
            tvParsedPreview.setAlpha(0.5f);
        }
    }

    private void parseSingleAndApply() {
        String text = inputSingleConfig.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.error_empty_input, Toast.LENGTH_SHORT).show();
            return;
        }
        ConfigParser.ParsedEntry entry = ConfigParser.parseLine(text);
        if (entry == null) {
            Toast.makeText(this, R.string.error_parse_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        applyParsedEntry(entry);
        spinnerInputMode.setSelection(MODE_MANUAL);
        Toast.makeText(this, R.string.parsed_applied, Toast.LENGTH_SHORT).show();
    }

    private void parseBulkList() {
        String text = inputBulkConfigs.getText().toString();
        if (text.trim().isEmpty()) {
            Toast.makeText(this, R.string.error_empty_input, Toast.LENGTH_SHORT).show();
            return;
        }
        parsedEntries = ConfigParser.parseConfigList(text);
        if (parsedEntries.isEmpty()) {
            tvBulkParsed.setText(getString(R.string.error_no_configs_found));
            btnPickBest.setEnabled(false);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(parsedEntries.size()).append(" config(s):\n");
        for (int i = 0; i < parsedEntries.size(); i++) {
            sb.append(i + 1).append(". ").append(parsedEntries.get(i).toString()).append("\n");
        }
        tvBulkParsed.setText(sb.toString());
        btnPickBest.setEnabled(true);

        // Auto-apply first one
        applyParsedEntry(parsedEntries.get(0));
        Toast.makeText(this, getString(R.string.bulk_applied, parsedEntries.size()), Toast.LENGTH_SHORT).show();
    }

    private void showPickEntryDialog() {
        if (parsedEntries == null || parsedEntries.isEmpty()) return;

        String[] items = new String[parsedEntries.size()];
        for (int i = 0; i < parsedEntries.size(); i++) {
            items[i] = (i + 1) + ". " + parsedEntries.get(i).toString();
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.dialog_pick_config)
            .setItems(items, (dialog, which) -> {
                selectedEntryIndex = which;
                applyParsedEntry(parsedEntries.get(which));
                spinnerInputMode.setSelection(MODE_MANUAL);
                Toast.makeText(this, getString(R.string.config_applied, which + 1), Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void applyParsedEntry(ConfigParser.ParsedEntry entry) {
        // Build remote endpoint as host:port
        String remote = entry.ip + ":" + entry.port;
        inputRemote.setText(remote);
        inputSni.setText(entry.sni);
        // Keep local listen port unchanged (user's preference)
    }

    private void pasteFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String pasted = text.toString().trim();

        // Detect mode: if multiple lines with vless/trojan → bulk
        // if single uri or sni:ip pair → single
        // else manual
        String[] lines = pasted.split("\\r?\\n");
        if (lines.length > 1) {
            spinnerInputMode.setSelection(MODE_BULK);
            inputBulkConfigs.setText(pasted);
            parseBulkList();
        } else {
            spinnerInputMode.setSelection(MODE_SINGLE);
            inputSingleConfig.setText(pasted);
            parseSingleAndApply();
        }
    }

    private void onSave() {
        String remote  = InputValidator.sanitize(inputRemote.getText().toString());
        String sni     = InputValidator.sanitize(inputSni.getText().toString());
        String portStr = InputValidator.sanitize(inputPort.getText().toString());
        String method  = (String) spinnerMethod.getSelectedItem();

        if (!InputValidator.isValidEndpoint(remote)) {
            inputRemote.setError(getString(R.string.error_invalid_endpoint));
            spinnerInputMode.setSelection(MODE_MANUAL);
            return;
        }
        if (!InputValidator.isValidSni(sni)) {
            inputSni.setError(getString(R.string.error_invalid_sni));
            spinnerInputMode.setSelection(MODE_MANUAL);
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            inputPort.setError(getString(R.string.error_invalid_port));
            spinnerInputMode.setSelection(MODE_MANUAL);
            return;
        }
        if (!InputValidator.isValidPort(port)) {
            inputPort.setError(getString(R.string.error_invalid_port));
            spinnerInputMode.setSelection(MODE_MANUAL);
            return;
        }

        TunnelProfile profile = new TunnelProfile.Builder()
            .remoteEndpoint(remote)
            .fakeSni(sni)
            .bypassMethod(method)
            .localPort(port)
            .build();

        repository.saveProfile(profile);
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void onReset() {
        repository.resetToDefaults();
        loadCurrentProfile();
        Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show();
    }
}
