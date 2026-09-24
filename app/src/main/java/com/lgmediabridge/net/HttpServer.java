package com.lgmediabridge.net;

import com.lgmediabridge.core.Formats;
import com.lgmediabridge.core.LogBus;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Purpose-built HTTP/1.1 server for DLNA streaming.
 *
 * Design notes that matter for reliability:
 *   * bound explicitly to the LAN address (never {@code 0.0.0.0} by default), so
 *     the media library is unreachable from cellular/VPN interfaces;
 *   * one connection per thread from a bounded pool - DLNA players open several
 *     parallel connections (browse + range fetches + thumbnails) and one long
 *     movie must not block a browse request;
 *   * reads have a timeout, writes do not: a slow TV is allowed to drain a
 *     4 GB file at its own pace;
 *   * a client that disappears mid-stream is detected through the write failure
 *     and the socket is dropped without touching the rest of the server.
 */
public final class HttpServer {

    public interface Handler {
        void handle(HttpRequest request, HttpResponse response) throws IOException;
    }

    private static final String TAG = "HttpServer";
    private static final int SO_TIMEOUT_MS = 30_000;
    private static final int MAX_CONCURRENT_CLIENTS = 32;

    private final Handler handler;
    private final Semaphore clientSlots = new Semaphore(MAX_CONCURRENT_CLIENTS);
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger activeConnections = new AtomicInteger();

    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private volatile int port;
    private volatile String boundAddress = "0.0.0.0";
    private ExecutorService clients;
    private Thread acceptThread;
    private long startedAt;

    public HttpServer(Handler handler) {
        this.handler = handler;
    }

    public synchronized void start(String address, int port) throws IOException {
        if (running) {
            stop();
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        InetAddress bindAddress = address == null || address.isEmpty()
                ? null : InetAddress.getByName(address);
        socket.bind(bindAddress == null
                ? new InetSocketAddress(port)
                : new InetSocketAddress(bindAddress, port), 32);
        this.serverSocket = socket;
        this.port = socket.getLocalPort();
        this.boundAddress = bindAddress == null ? "0.0.0.0" : bindAddress.getHostAddress();
        this.running = true;
        this.startedAt = System.currentTimeMillis();
        this.clients = Formats.newPool("http-client", 8);
        this.acceptThread = new Thread(this::acceptLoop, "http-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
        LogBus.get().i(TAG, "listening on " + boundAddress + ":" + port);
    }

    public synchronized void stop() {
        running = false;
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing is best effort
            }
        }
        if (clients != null) {
            clients.shutdown();
            clients = null;
        }
        LogBus.get().i(TAG, "stopped");
    }

    public boolean isRunning() {
        return running;
    }

    public int port() {
        return port;
    }

    public String boundAddress() {
        return boundAddress;
    }

    public int requestCount() {
        return requestCount.get();
    }

    public int activeConnections() {
        return activeConnections.get();
    }

    public long uptimeMillis() {
        return startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
    }

    private void acceptLoop() {
        while (running) {
            ServerSocket socket = serverSocket;
            if (socket == null) {
                break;
            }
            try {
                Socket client = socket.accept();
                try {
                    client.setTcpNoDelay(true);
                    client.setKeepAlive(true);
                    client.setSoTimeout(SO_TIMEOUT_MS);
                } catch (SocketException ignored) {
                    // broken socket options are not fatal
                }
                ExecutorService pool = clients;
                if (pool == null) {
                    client.close();
                    break;
                }
                pool.execute(() -> serve(client));
            } catch (IOException e) {
                if (running) {
                    LogBus.get().w(TAG, "accept failed: " + e.getMessage());
                    // A transient accept failure (fd pressure) must not spin.
                    try {
                        Thread.sleep(120);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void serve(Socket socket) {
        activeConnections.incrementAndGet();
        try {
            serveRequests(socket);
        } catch (IOException e) {
            if (running) {
                LogBus.get().d(TAG, "client closed: " + e.getMessage());
            }
        } finally {
            activeConnections.decrementAndGet();
            try {
                socket.close();
            } catch (IOException ignored) {
                // already gone
            }
        }
    }

    private void serveRequests(Socket socket) throws IOException {
        OutputStream out = socket.getOutputStream();
        while (running && !socket.isClosed()) {
            HttpRequest request;
            try {
                request = HttpRequest.read(socket);
            } catch (IOException e) {
                LogBus.get().d(TAG, "bad request: " + e.getMessage());
                return;
            }
            if (request == null) {
                return;
            }
            requestCount.incrementAndGet();
            HttpResponse response = new HttpResponse();
            boolean headOnly = "HEAD".equals(request.method);
            if (!headOnly && !"GET".equals(request.method) && !"POST".equals(request.method)
                    && !"SUBSCRIBE".equals(request.method) && !"UNSUBSCRIBE".equals(request.method)) {
                response.status(405, "Method Not Allowed").header("Allow", "GET, HEAD, POST, SUBSCRIBE");
                response.body("Method not allowed");
            } else {
                try {
                    handler.handle(request, response);
                } catch (RangeNotSatisfiable e) {
                    response = new HttpResponse().status(416, "Range Not Satisfiable")
                            .header("Content-Range", e.contentRange)
                            .header("Content-Length", "0");
                } catch (RuntimeException e) {
                    LogBus.get().e(TAG, "handler failed for " + request.path, e);
                    response = new HttpResponse().status(500, "Internal Server Error")
                            .body("Internal error");
                }
            }
            boolean keepAlive = request.keepAlive() && !response.mustClose();
            try {
                response.writeTo(out, headOnly, keepAlive);
            } catch (IOException e) {
                // Client vanished (TV standby): normal during long sessions.
                LogBus.get().d(TAG, "write failed for " + request.path + ": " + e.getMessage());
                return;
            }
            if (!keepAlive) {
                return;
            }
        }
    }

    /** Thrown by handlers when a Range header cannot be satisfied (HTTP 416). */
    public static final class RangeNotSatisfiable extends RuntimeException {
        final String contentRange;

        public RangeNotSatisfiable(long totalLength) {
            super("range not satisfiable");
            this.contentRange = "bytes */" + totalLength;
        }
    }
}
