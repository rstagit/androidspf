package com.rstagit.androidspf.core.engine;

import com.rstagit.androidspf.core.model.LogEntry;
import com.rstagit.androidspf.core.model.TunnelProfile;
import com.rstagit.androidspf.core.model.TunnelState;
import com.rstagit.androidspf.core.protocol.GoNativeBridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class TunnelController {
    private static final int MAX_LOG_ENTRIES = 500;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicReference<TunnelState> state = new AtomicReference<>(TunnelState.idle());
    private final AtomicLong activeSessionId = new AtomicLong(-1);
    private final AtomicBoolean logPollerRunning = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<>();
    private final List<LogEntry> logBuffer = Collections.synchronizedList(new ArrayList<>());

    public interface StateListener {
        void onStateChanged(TunnelState tunnelState);
        void onLogEntry(LogEntry entry);
    }

    private static volatile TunnelController instance;

    public static TunnelController getInstance() {
        if (instance == null) {
            synchronized (TunnelController.class) {
                if (instance == null) {
                    instance = new TunnelController();
                }
            }
        }
        return instance;
    }

    private TunnelController() {}

    public void addListener(StateListener listener) {
        listeners.add(listener);
    }

    public void removeListener(StateListener listener) {
        listeners.remove(listener);
    }

    public TunnelState getCurrentState() {
        return state.get();
    }

    public List<LogEntry> getLogSnapshot() {
        synchronized (logBuffer) {
            return new ArrayList<>(logBuffer);
        }
    }

    public void start(TunnelProfile profile) {
        if (state.get().isRunning()) return;
        setState(TunnelState.starting());
        executor.execute(() -> doStart(profile));
    }

    public void stop() {
        executor.execute(this::doStop);
    }

    private void doStart(TunnelProfile profile) {
        if (!GoNativeBridge.load()) {
            appendLog(LogEntry.error("Native library not available"));
            setState(TunnelState.error("Native bridge load failed"));
            return;
        }

        long sessionId = GoNativeBridge.spfStart(
            profile.getLocalPort(),
            profile.getRemoteEndpoint(),
            profile.getFakeSni(),
            profile.getBypassMethod()
        );

        if (sessionId <= 0) {
            drainNativeLogs();
            appendLog(LogEntry.error("Failed to start native session"));
            setState(TunnelState.error("Port busy or proxy start failed"));
            return;
        }

        activeSessionId.set(sessionId);
        String listenAddr = "127.0.0.1:" + profile.getLocalPort();
        setState(TunnelState.active(sessionId, listenAddr));
        appendLog(LogEntry.ok("Proxy active on " + listenAddr));
        startLogPoller();
    }

    private void doStop() {
        stopLogPoller();
        long sessionId = activeSessionId.getAndSet(-1);
        if (sessionId > 0) {
            GoNativeBridge.spfStop(sessionId);
            drainNativeLogs();
            appendLog(LogEntry.info("Tunnel stopped"));
        }
        setState(TunnelState.idle());
    }

    private void startLogPoller() {
        if (!logPollerRunning.compareAndSet(false, true)) return;
        new Thread(() -> {
            while (logPollerRunning.get() && activeSessionId.get() > 0) {
                drainNativeLogs();
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            logPollerRunning.set(false);
        }, "LogPoller").start();
    }

    private void stopLogPoller() {
        logPollerRunning.set(false);
    }

    private void drainNativeLogs() {
        if (!GoNativeBridge.isAvailable()) return;
        String line;
        while ((line = GoNativeBridge.spfPollLog()) != null && !line.isEmpty()) {
            acceptNativeLog(line);
        }
    }

    public void acceptNativeLog(String raw) {
        LogEntry entry = LogEntry.fromRaw(raw);
        appendLog(entry);
    }

    private void appendLog(LogEntry entry) {
        synchronized (logBuffer) {
            logBuffer.add(entry);
            while (logBuffer.size() > MAX_LOG_ENTRIES) {
                logBuffer.remove(0);
            }
        }
        for (StateListener listener : listeners) {
            try {
                listener.onLogEntry(entry);
            } catch (Exception ignored) {}
        }
    }

    private void setState(TunnelState newState) {
        state.set(newState);
        for (StateListener listener : listeners) {
            try {
                listener.onStateChanged(newState);
            } catch (Exception ignored) {}
        }
    }
}
