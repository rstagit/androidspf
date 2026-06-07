package com.rstagit.androidspf.util;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigParser {

    private ConfigParser() {}

    public static class ParsedEntry {
        public final String ip;
        public final String sni;
        public final int port;
        public final String rawConfig;

        public ParsedEntry(String ip, String sni, int port, String rawConfig) {
            this.ip = ip;
            this.sni = sni;
            this.port = port != 0 ? port : 443;
            this.rawConfig = rawConfig;
        }

        @Override
        public String toString() {
            return ip + ":" + port + " / SNI: " + sni;
        }
    }

    private static final Pattern IP_PATTERN =
        Pattern.compile("^(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})$");
    private static final Pattern HOST_PORT_PATTERN =
        Pattern.compile("^([\\w.\\-]+):(\\d{1,5})$");
    private static final Pattern DOMAIN_PATTERN =
        Pattern.compile("^([a-zA-Z0-9][a-zA-Z0-9.\\-]*\\.[a-zA-Z]{2,})$");

    public static ParsedEntry parseVlessOrTrojan(String uri) {
        try {
            URI u = new URI(uri.trim());
            String host = u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : 443;
            String query = u.getRawQuery() != null ? u.getRawQuery() : "";

            String sni = extractQueryParam(query, "sni");
            if (sni == null || sni.isEmpty()) {
                sni = extractQueryParam(query, "host");
            }
            if (sni == null || sni.isEmpty()) {
                sni = host;
            }

            return new ParsedEntry(host, sni, port, uri.trim());
        } catch (Exception e) {
            return null;
        }
    }

    public static ParsedEntry parseLocalConfig(String text) {
        if (text == null || text.isEmpty()) return null;
        text = text.trim();
        Matcher m = HOST_PORT_PATTERN.matcher(text);
        if (m.matches()) {
            String host = m.group(1);
            int port = Integer.parseInt(m.group(2));
            return new ParsedEntry(host, host, port, text);
        }
        return null;
    }

    public static ParsedEntry parseSniIpPair(String input) {
        if (input == null || input.isEmpty()) return null;
        input = input.trim();

        String[] parts = input.split("[:\\s]+", 2);
        if (parts.length == 2) {
            String a = parts[0].trim();
            String b = parts[1].trim();
            if (!a.isEmpty() && !b.isEmpty()) {
                if (looksLikeIp(a) && looksLikeDomain(b)) {
                    return new ParsedEntry(a, b, 443, input);
                } else if (looksLikeDomain(a) && looksLikeIp(b)) {
                    return new ParsedEntry(b, a, 443, input);
                } else if (looksLikeIp(a)) {
                    return new ParsedEntry(a, b, 443, input);
                } else if (looksLikeIp(b)) {
                    return new ParsedEntry(b, a, 443, input);
                } else {
                    return new ParsedEntry(b, a, 443, input);
                }
            }
        }
        if (looksLikeIp(input)) {
            return new ParsedEntry(input, input, 443, input);
        } else if (looksLikeDomain(input)) {
            return new ParsedEntry(input, input, 443, input);
        }
        return null;
    }

    public static List<ParsedEntry> parseConfigList(String text) {
        List<ParsedEntry> results = new ArrayList<>();
        if (text == null || text.isEmpty()) return results;

        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            ParsedEntry entry = parseLine(line);
            if (entry != null) {
                results.add(entry);
            }
        }
        return results;
    }

    public static ParsedEntry parseLine(String line) {
        if (line == null || line.isEmpty()) return null;
        line = line.trim();
        if (line.startsWith("vless://") || line.startsWith("trojan://")
                || line.startsWith("vmess://") || line.startsWith("ss://")) {
            return parseVlessOrTrojan(line);
        }
        if (line.startsWith("127.0.0.1")) {
            ParsedEntry local = parseLocalConfig(line);
            if (local != null) return local;
        }
        return parseSniIpPair(line);
    }

    private static boolean looksLikeIp(String s) {
        return IP_PATTERN.matcher(s).matches();
    }

    private static boolean looksLikeDomain(String s) {
        return DOMAIN_PATTERN.matcher(s).matches();
    }

    private static String extractQueryParam(String query, String key) {
        if (query == null || query.isEmpty()) return null;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equalsIgnoreCase(key)) {
                try {
                    return java.net.URLDecoder.decode(kv[1], "UTF-8");
                } catch (Exception e) {
                    return kv[1];
                }
            }
        }
        return null;
    }
}
