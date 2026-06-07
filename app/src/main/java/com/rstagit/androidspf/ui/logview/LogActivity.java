package com.rstagit.androidspf.ui.logview;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;

import com.rstagit.androidspf.R;
import com.rstagit.androidspf.core.engine.TunnelController;
import com.rstagit.androidspf.core.model.LogEntry;
import com.rstagit.androidspf.core.model.TunnelState;

import java.util.ArrayList;
import java.util.List;

public final class LogActivity extends AppCompatActivity {
    private TunnelController controller;
    private LogEntryAdapter logAdapter;

    private final TunnelController.StateListener logListener = new TunnelController.StateListener() {
        @Override
        public void onStateChanged(TunnelState state) {}

        @Override
        public void onLogEntry(LogEntry entry) {
            runOnUiThread(() -> {
                logAdapter.add(entry);
                logAdapter.notifyDataSetChanged();
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_log);
        }

        controller = TunnelController.getInstance();

        ListView listView = findViewById(R.id.log_list);
        Button clearButton = findViewById(R.id.btn_clear);

        List<LogEntry> snapshot = controller.getLogSnapshot();
        logAdapter = new LogEntryAdapter(this, new ArrayList<>(snapshot));
        listView.setAdapter(logAdapter);

        clearButton.setOnClickListener(v -> {
            logAdapter.clear();
            logAdapter.notifyDataSetChanged();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        controller.addListener(logListener);
    }

    @Override
    protected void onPause() {
        controller.removeListener(logListener);
        super.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
