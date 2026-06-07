package com.rstagit.androidspf.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.rstagit.androidspf.core.engine.TunnelController;
import com.rstagit.androidspf.core.model.TunnelProfile;
import com.rstagit.androidspf.core.model.TunnelState;
import com.rstagit.androidspf.ui.dashboard.LaunchActivity;

public final class TunnelForegroundService extends Service {
    public static final String ACTION_START = "com.rstagit.androidspf.ACTION_START";
    public static final String ACTION_STOP = "com.rstagit.androidspf.ACTION_STOP";

    public static final String EXTRA_REMOTE = "extra_remote";
    public static final String EXTRA_SNI = "extra_sni";
    public static final String EXTRA_METHOD = "extra_method";
    public static final String EXTRA_PORT = "extra_port";

    private static final String CHANNEL_ID = "spf_tunnel";
    private static final int NOTIF_ID = 1001;

    private TunnelController controller;

    private final TunnelController.StateListener stateListener = new TunnelController.StateListener() {
        @Override
        public void onStateChanged(TunnelState state) {
            refreshNotification(state.getMessage());
            if (!state.isRunning()) {
                removeForeground();
                stopSelf();
            }
        }

        @Override
        public void onLogEntry(com.rstagit.androidspf.core.model.LogEntry entry) {}
    };

    @Override
    public void onCreate() {
        super.onCreate();
        controller = TunnelController.getInstance();
        controller.addListener(stateListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();

        if (intent == null) {
            startForeground(NOTIF_ID, buildNotification("Service running"));
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (TunnelForegroundService.ACTION_STOP.equals(action)) {
            startForeground(NOTIF_ID, buildNotification("Stopping..."));
            controller.stop();
            return START_NOT_STICKY;
        }

        if (TunnelForegroundService.ACTION_START.equals(action)) {
            startForeground(NOTIF_ID, buildNotification("Starting..."));
            handleStart(intent);
            return START_STICKY;
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        controller.removeListener(stateListener);
        super.onDestroy();
    }

    private void handleStart(Intent intent) {
        String remote = intent.getStringExtra(EXTRA_REMOTE);
        String sni = intent.getStringExtra(EXTRA_SNI);
        String method = intent.getStringExtra(EXTRA_METHOD);
        int port = intent.getIntExtra(EXTRA_PORT, 40443);

        TunnelProfile profile = new TunnelProfile.Builder()
            .remoteEndpoint(remote != null ? remote : "104.19.230.21:443")
            .fakeSni(sni != null ? sni : "www.hcaptcha.com")
            .bypassMethod(method != null ? method : "combined")
            .localPort(port)
            .build();

        controller.start(profile);
        refreshNotification("Proxy on 127.0.0.1:" + port);
    }

    private void refreshNotification(String message) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(message));
    }

    private Notification buildNotification(String message) {
        Intent openIntent = new Intent(this, LaunchActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("RSTA Spoof")
            .setContentText(message)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "SPF Tunnel", NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private void removeForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }
}
