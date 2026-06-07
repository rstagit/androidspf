package com.rstagit.androidspf.data.prefs;

import android.content.Context;
import android.content.SharedPreferences;

import com.rstagit.androidspf.core.model.TunnelProfile;

public final class ProfilePreferences {
    private static final String FILE_NAME = "spf_profile";
    private static final String KEY_REMOTE = "remote_endpoint";
    private static final String KEY_SNI = "fake_sni";
    private static final String KEY_METHOD = "bypass_method";
    private static final String KEY_PORT = "local_port";

    private final SharedPreferences prefs;

    public ProfilePreferences(Context context) {
        this.prefs = context.getApplicationContext()
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    public void save(TunnelProfile profile) {
        prefs.edit()
            .putString(KEY_REMOTE, profile.getRemoteEndpoint())
            .putString(KEY_SNI, profile.getFakeSni())
            .putString(KEY_METHOD, profile.getBypassMethod())
            .putInt(KEY_PORT, profile.getLocalPort())
            .apply();
    }

    public TunnelProfile load() {
        return new TunnelProfile.Builder()
            .remoteEndpoint(prefs.getString(KEY_REMOTE, TunnelProfile.DEFAULT_REMOTE))
            .fakeSni(prefs.getString(KEY_SNI, TunnelProfile.DEFAULT_FAKE_SNI))
            .bypassMethod(prefs.getString(KEY_METHOD, TunnelProfile.DEFAULT_METHOD))
            .localPort(prefs.getInt(KEY_PORT, TunnelProfile.DEFAULT_PORT))
            .build();
    }

    public void reset() {
        prefs.edit().clear().apply();
    }
}
