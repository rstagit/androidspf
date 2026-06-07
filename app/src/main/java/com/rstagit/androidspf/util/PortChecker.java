package com.rstagit.androidspf.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

public final class PortChecker {
    private PortChecker() {}

    public static boolean isLocalPortAvailable(int port) {
        if (port < 1024 || port > 65535) return false;
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
