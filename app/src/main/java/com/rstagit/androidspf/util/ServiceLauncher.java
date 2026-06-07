package com.rstagit.androidspf.util;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.rstagit.androidspf.core.model.TunnelProfile;
import com.rstagit.androidspf.service.TunnelForegroundService;

public final class ServiceLauncher {
    private ServiceLauncher() {}

    public static void startTunnel(Context context, TunnelProfile profile) {
        Intent intent = new Intent(context, TunnelForegroundService.class);
        intent.setAction(TunnelForegroundService.ACTION_START);
        intent.putExtra(TunnelForegroundService.EXTRA_REMOTE, profile.getRemoteEndpoint());
        intent.putExtra(TunnelForegroundService.EXTRA_SNI, profile.getFakeSni());
        intent.putExtra(TunnelForegroundService.EXTRA_METHOD, profile.getBypassMethod());
        intent.putExtra(TunnelForegroundService.EXTRA_PORT, profile.getLocalPort());
        startService(context, intent);
    }

    public static void stopTunnel(Context context) {
        Intent intent = new Intent(context, TunnelForegroundService.class);
        intent.setAction(TunnelForegroundService.ACTION_STOP);
        startService(context, intent);
    }

    private static void startService(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
