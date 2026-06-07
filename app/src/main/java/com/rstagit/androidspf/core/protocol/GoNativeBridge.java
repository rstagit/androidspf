package com.rstagit.androidspf.core.protocol;

public final class GoNativeBridge {
    private static volatile boolean loaded = false;
    private static final Object LOCK = new Object();

    private GoNativeBridge() {}

    public static boolean load() {
        if (loaded) return true;
        synchronized (LOCK) {
            if (loaded) return true;
            try {
                System.loadLibrary("snispf");
                System.loadLibrary("androidspf_jni");
                loaded = true;
            } catch (UnsatisfiedLinkError e) {
                android.util.Log.e("GoNativeBridge", "Native load failed: " + e.getMessage(), e);
                return false;
            }
        }
        return true;
    }

    public static boolean isAvailable() {
        return loaded;
    }

    public static native long spfStart(int listenPort, String remoteEndpoint, String fakeSni, String method);
    public static native void spfStop(long sessionId);
    public static native String spfPollLog();
    public static native String spfParseSni(byte[] data, int length);
    public static native String spfVersion();
}
