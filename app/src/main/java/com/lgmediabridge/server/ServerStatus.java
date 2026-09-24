package com.lgmediabridge.server;

import com.lgmediabridge.core.Formats;

/** Immutable snapshot of the media server, used by every UI screen. */
public final class ServerStatus {

    public enum State { STOPPED, STARTING, RUNNING, ERROR }

    public final State state;
    public final String address;
    public final int port;
    public final String baseUrl;
    public final long startedAt;
    public final boolean ssdpListening;
    public final long searchesAnswered;
    public final int requestsServed;
    public final int activeClients;
    public final String error;

    private ServerStatus(State state, String address, int port, String baseUrl, long startedAt,
                         boolean ssdpListening, long searchesAnswered, int requestsServed,
                         int activeClients, String error) {
        this.state = state;
        this.address = address;
        this.port = port;
        this.baseUrl = baseUrl;
        this.startedAt = startedAt;
        this.ssdpListening = ssdpListening;
        this.searchesAnswered = searchesAnswered;
        this.requestsServed = requestsServed;
        this.activeClients = activeClients;
        this.error = error;
    }

    public static ServerStatus stopped() {
        return new ServerStatus(State.STOPPED, null, 0, null, 0, false, 0, 0, 0, null);
    }

    public static ServerStatus starting() {
        return new ServerStatus(State.STARTING, null, 0, null, 0, false, 0, 0, 0, null);
    }

    public static ServerStatus running(String address, int port, String baseUrl, long startedAt,
                                       boolean ssdpListening, long searchesAnswered,
                                       int requestsServed, int activeClients) {
        return new ServerStatus(State.RUNNING, address, port, baseUrl, startedAt, ssdpListening,
                searchesAnswered, requestsServed, activeClients, null);
    }

    public static ServerStatus error(String message) {
        return new ServerStatus(State.ERROR, null, 0, null, 0, false, 0, 0, 0, message);
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public String uptime() {
        return startedAt == 0 ? "—" : Formats.elapsed(System.currentTimeMillis() - startedAt);
    }

    public String hostPort() {
        return address == null ? "—" : address + ":" + port;
    }

    public String describe() {
        switch (state) {
            case RUNNING:
                return "Running on " + hostPort();
            case STARTING:
                return "Starting…";
            case ERROR:
                return error == null ? "Stopped because of an error" : error;
            default:
                return "Stopped";
        }
    }
}
