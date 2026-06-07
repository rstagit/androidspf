package com.rstagit.androidspf.util;

import java.util.regex.Pattern;

public final class InputValidator {
    private static final Pattern IP_PORT_PATTERN =
        Pattern.compile("^[\\w.\\-]+(:\\d{1,5})?$");
    private static final Pattern SNI_PATTERN =
        Pattern.compile("^[a-zA-Z0-9.\\-]+$");
    private static final Pattern IP_PATTERN =
        Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");

    private InputValidator() {}

    public static boolean isValidEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) return false;
        return IP_PORT_PATTERN.matcher(endpoint.trim()).matches();
    }

    public static boolean isValidSni(String sni) {
        if (sni == null || sni.isEmpty()) return false;
        return SNI_PATTERN.matcher(sni.trim()).matches();
    }

    public static boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        String trimmed = ip.trim();
        if (!IP_PATTERN.matcher(trimmed).matches()) return false;
        String[] parts = trimmed.split("\\.");
        for (String part : parts) {
            int value = Integer.parseInt(part);
            if (value < 0 || value > 255) return false;
        }
        return true;
    }

    public static boolean isValidPort(int port) {
        return port >= 1024 && port <= 65535;
    }

    public static String sanitize(String input) {
        if (input == null) return "";
        return input.trim();
    }
}
