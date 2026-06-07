package com.rstagit.androidspf.core.model;

public final class TunnelProfile {
    public static final String METHOD_FRAGMENT = "fragment";
    public static final String METHOD_FAKE_SNI = "fake_sni";
    public static final String METHOD_COMBINED = "combined";

    public static final String DEFAULT_FAKE_SNI = "www.hcaptcha.com";
    public static final String DEFAULT_REMOTE_IP = "104.19.230.21";
    public static final String DEFAULT_REMOTE = DEFAULT_REMOTE_IP + ":443";
    public static final String DEFAULT_METHOD = METHOD_COMBINED;
    public static final int DEFAULT_PORT = 40443;

    public static final String[] PRESET_IPS = {
        "104.19.230.21",
        "104.19.229.21"
    };

    public static final String[] PRESET_SNIS = {
        "www.hcaptcha.com",
        "hcaptcha.com"
    };

    private final String profileName;
    private final String remoteEndpoint;
    private final String fakeSni;
    private final String bypassMethod;
    private final int localPort;

    private TunnelProfile(Builder builder) {
        this.profileName = builder.profileName;
        this.remoteEndpoint = builder.remoteEndpoint;
        this.fakeSni = builder.fakeSni;
        this.bypassMethod = builder.bypassMethod;
        this.localPort = builder.localPort;
    }

    public String getProfileName() { return profileName; }
    public String getRemoteEndpoint() { return remoteEndpoint; }
    public String getFakeSni() { return fakeSni; }
    public String getBypassMethod() { return bypassMethod; }
    public int getLocalPort() { return localPort; }

    public String getRemoteIp() {
        int colon = remoteEndpoint.lastIndexOf(':');
        if (colon > 0) {
            return remoteEndpoint.substring(0, colon);
        }
        return remoteEndpoint;
    }

    public static Builder defaults() {
        return new Builder()
            .profileName("Default")
            .remoteEndpoint(DEFAULT_REMOTE)
            .fakeSni(DEFAULT_FAKE_SNI)
            .bypassMethod(DEFAULT_METHOD)
            .localPort(DEFAULT_PORT);
    }

    public static final class Builder {
        private String profileName = "Default";
        private String remoteEndpoint = DEFAULT_REMOTE;
        private String fakeSni = DEFAULT_FAKE_SNI;
        private String bypassMethod = DEFAULT_METHOD;
        private int localPort = DEFAULT_PORT;

        public Builder profileName(String val) { this.profileName = val; return this; }
        public Builder remoteEndpoint(String val) { this.remoteEndpoint = val; return this; }
        public Builder fakeSni(String val) { this.fakeSni = val; return this; }
        public Builder bypassMethod(String val) { this.bypassMethod = val; return this; }
        public Builder localPort(int val) { this.localPort = val; return this; }
        public TunnelProfile build() { return new TunnelProfile(this); }
    }
}
