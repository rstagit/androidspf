package com.rstagit.androidspf.core.model;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class LogEntry {
    public enum Level { INFO, OK, WARN, ERROR }

    private static final SimpleDateFormat TIME_FORMAT =
        new SimpleDateFormat("HH:mm:ss", Locale.US);

    private final Level level;
    private final String message;
    private final long timestamp;

    public LogEntry(Level level, String message) {
        this.level = level;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }

    public static LogEntry info(String message) { return new LogEntry(Level.INFO, message); }
    public static LogEntry ok(String message) { return new LogEntry(Level.OK, message); }
    public static LogEntry warn(String message) { return new LogEntry(Level.WARN, message); }
    public static LogEntry error(String message) { return new LogEntry(Level.ERROR, message); }

    public static LogEntry fromRaw(String raw) {
        if (raw.startsWith("[OK]")) return ok(raw.substring(4).trim());
        if (raw.startsWith("[BLOCKED]")) return error(raw.substring(9).trim());
        if (raw.startsWith("[WARN]")) return warn(raw.substring(6).trim());
        if (raw.startsWith("[ERR]")) return error(raw.substring(5).trim());
        if (raw.startsWith("[TLS HELLO]") || raw.startsWith("[FRAGMENT]") || raw.startsWith("[TTL TRICK]")) {
            return info(raw);
        }
        return info(raw.replaceAll("^\\[INFO\\]\\s*", ""));
    }

    public Level getLevel() { return level; }
    public String getMessage() { return message; }
    public long getTimestamp() { return timestamp; }

    public String getFormattedTime() {
        synchronized (TIME_FORMAT) {
            return TIME_FORMAT.format(new Date(timestamp));
        }
    }
}
