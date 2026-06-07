package com.rstagit.androidspf.core.model;

public final class TunnelState {
    public enum Status {
        IDLE,
        STARTING,
        ACTIVE,
        STOPPING,
        ERROR
    }

    private final Status status;
    private final String message;
    private final int activeConnections;
    private final long sessionId;

    private TunnelState(Status status, String message, int activeConnections, long sessionId) {
        this.status = status;
        this.message = message;
        this.activeConnections = activeConnections;
        this.sessionId = sessionId;
    }

    public static TunnelState idle() {
        return new TunnelState(Status.IDLE, "Stopped", 0, -1);
    }

    public static TunnelState starting() {
        return new TunnelState(Status.STARTING, "Starting...", 0, -1);
    }

    public static TunnelState active(long sessionId, String listenAddress) {
        return new TunnelState(Status.ACTIVE, "Listening on " + listenAddress, 0, sessionId);
    }

    public static TunnelState error(String reason) {
        return new TunnelState(Status.ERROR, reason, 0, -1);
    }

    public static TunnelState withConnections(TunnelState base, int connections) {
        return new TunnelState(base.status, base.message, connections, base.sessionId);
    }

    public Status getStatus() { return status; }
    public String getMessage() { return message; }
    public int getActiveConnections() { return activeConnections; }
    public long getSessionId() { return sessionId; }
    public boolean isRunning() { return status == Status.ACTIVE || status == Status.STARTING; }
}
