package com.rstagit.androidspf.data.repository;

import android.content.Context;

import com.rstagit.androidspf.core.model.TunnelProfile;
import com.rstagit.androidspf.data.prefs.ProfilePreferences;

public final class TunnelRepository {
    private final ProfilePreferences preferences;

    public TunnelRepository(Context context) {
        this.preferences = new ProfilePreferences(context);
    }

    public TunnelProfile getActiveProfile() {
        return preferences.load();
    }

    public void saveProfile(TunnelProfile profile) {
        preferences.save(profile);
    }

    public void resetToDefaults() {
        preferences.reset();
    }
}
