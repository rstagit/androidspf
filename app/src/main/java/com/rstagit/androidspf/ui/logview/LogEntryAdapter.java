package com.rstagit.androidspf.ui.logview;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.rstagit.androidspf.core.model.LogEntry;

import java.util.List;

public final class LogEntryAdapter extends ArrayAdapter<LogEntry> {
    private final LayoutInflater inflater;

    public LogEntryAdapter(Context context, List<LogEntry> entries) {
        super(context, 0, entries);
        this.inflater = LayoutInflater.from(context);
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(android.R.layout.simple_list_item_2, parent, false);
            holder = new ViewHolder();
            holder.time = convertView.findViewById(android.R.id.text2);
            holder.message = convertView.findViewById(android.R.id.text1);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        LogEntry entry = getItem(position);
        if (entry != null) {
            holder.time.setText(entry.getFormattedTime());
            holder.message.setText(entry.getMessage());
            holder.message.setTextColor(colorForLevel(entry.getLevel()));
        }
        return convertView;
    }

    private int colorForLevel(LogEntry.Level level) {
        switch (level) {
            case OK: return Color.parseColor("#4CAF50");
            case WARN: return Color.parseColor("#FF9800");
            case ERROR: return Color.parseColor("#F44336");
            default: return Color.parseColor("#CCCCCC");
        }
    }

    private static final class ViewHolder {
        TextView time;
        TextView message;
    }
}
