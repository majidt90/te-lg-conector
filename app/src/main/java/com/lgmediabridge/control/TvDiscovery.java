package com.lgmediabridge.control;

import android.content.Context;
import android.net.wifi.WifiManager;

import com.lgmediabridge.core.Formats;
import com.lgmediabridge.dlna.SsdpMessages;
import com.lgmediabridge.core.LogBus;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds TVs and other UPnP devices on the local network.
 *
 * Three probes run in parallel because a single one is not reliable on every
 * network:
 * <ol>
 *   <li>unicast {@code M-SEARCH} to the router's address (multicast is often
 *       filtered by cheap access points);</li>
 *   <li>multicast {@code M-SEARCH} to 239.255.255.250:1900, which any DLNA
 *       device (LG TVs included) must answer;</li>
 *   <li>the LG second-screen service type, which is how webOS TVs identify
 *       themselves for remote control.</li>
 * </ol>
 *
 * Responses are fetched and merged into {@link TvDevice} records, so a TV seen
 * through both SSDP and its device description becomes a single entry with
 * capabilities filled in. Discovery is passive listening plus these probes: the
 * app never scans ports or sends anything to the wider network.
 */
public final class TvDiscovery {

    private static final String TAG = "TvDiscovery";
    private static final String SSDP_ADDRESS = SsdpMessages.MULTICAST_ADDRESS;
    private static final int SSDP_PORT = SsdpMessages.MULTICAST_PORT;
    private static final Charset ASCII = Charset.forName("ISO-8859-1");
    private static final long DEVICE_TTL_MS = 20_000;

    /** LG television / second screen, the type webOS TVs answer to. */
    public static final String ST_LG_SECOND_SCREEN = "urn:lge-com:service:webos-second-screen:1";
    public static final String ST_MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1";
    public static final String ST_MEDIA_SERVER = "urn:schemas-upnp-org:device:MediaServer:1";

    public interface Listener {
        /** Called for every discovered or updated device. */
        void onDevice(TvDevice device);

        /** Called when a discovery cycle finishes (all sockets closed). */
        void onScanFinished(int deviceCount);

        void onStatus(String message);
    }

    private final Context context;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, TvDevice> found = new LinkedHashMap<>();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final ScheduledExecutorService scheduler =
            Formats.newScheduler("tv-discovery", 1);

    private WifiManager.MulticastLock multicastLock;
    private MulticastSocket multicastSocket;
    private Thread listenThread;
    private ScheduledFuture<?> scanTimeout;
    private long lastScanAt;

    public TvDiscovery(Context context) {
        this.context = context.getApplicationContext();
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized List<TvDevice> devices() {
        return new ArrayList<>(found.values());
    }

    public boolean isScanning() {
        return scanning.get();
    }

    public long lastScanAt() {
        return lastScanAt;
    }

    // ------------------------------------------------------------ scanning

    public void scan() {
        scan(2);
    }

    /** Starts a search; {@code seconds} is how long replies are accepted. */
    public void scan(int seconds) {
        if (!scanning.compareAndSet(false, true)) {
            LogBus.get().d(TAG, "scan already running");
            return;
        }
        lastScanAt = System.currentTimeMillis();
        LogBus.get().i(TAG, "searching for devices on the local network…");
        notifyStatus("Searching for devices…");
        pruneStale();
        openSocket();
        sendSearches();
        java.util.concurrent.ExecutorService pool = Formats.newPool("tv-discovery-worker", 2);
        pool.execute(this::fetchPendingDescriptions);
        final long millis = Math.max(1, seconds) * 1000L;
        scanTimeout = scheduler.schedule(() -> {
            sendSearches();
        }, Math.min(1500, millis / 2), TimeUnit.MILLISECONDS);
        scheduler.schedule(this::finishScan, millis, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (scanTimeout != null) {
            scanTimeout.cancel(false);
        }
        closeSocket();
        scanning.set(false);
    }

    private void finishScan() {
        closeSocket();
        if (scanning.compareAndSet(true, false)) {
            int count = devices().size();
            LogBus.get().i(TAG, "search finished: " + count + " device(s)");
            notifyStatus(count == 0 ? "No devices replied yet" : count + " device(s) found");
            for (Listener listener : listeners) {
                listener.onScanFinished(count);
            }
        }
    }

    private void openSocket() {
        if (multicastSocket != null) {
            return;
        }
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            try {
                multicastLock = wifi.createMulticastLock("mediabridge-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            } catch (Exception e) {
                LogBus.get().d(TAG, "multicast lock unavailable: " + e.getMessage());
            }
        }
        try {
            MulticastSocket socket = new MulticastSocket(0);
            socket.setReuseAddress(true);
            socket.setSoTimeout(1000);
            socket.setTimeToLive(4);
            socket.joinGroup(InetAddress.getByName(SSDP_ADDRESS));
            multicastSocket = socket;
            listenThread = new Thread(this::listenLoop, "tv-discovery-listen");
            listenThread.setDaemon(true);
            listenThread.start();
        } catch (Exception e) {
            LogBus.get().w(TAG, "could not open multicast socket: " + e.getMessage());
        }
    }

    private void closeSocket() {
        MulticastSocket socket = multicastSocket;
        multicastSocket = null;
        if (socket != null) {
            try {
                socket.leaveGroup(InetAddress.getByName(SSDP_ADDRESS));
            } catch (IOException ignored) {
                // best effort
            }
            socket.close();
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            try {
                multicastLock.release();
            } catch (Exception ignored) {
                // nothing to do
            }
        }
        multicastLock = null;
    }

    private void sendSearches() {
        List<String> targets = new ArrayList<>();
        targets.add(SsdpMessages.ALL);
        targets.add(ST_MEDIA_RENDERER);
        targets.add(ST_LG_SECOND_SCREEN);
        targets.add(ST_MEDIA_SERVER);
        for (String target : targets) {
            byte[] payload = SsdpMessages.searchRequest(target, 2).getBytes(ASCII);
            sendToMulticast(payload);
            sendToRouter(payload);
        }
    }

    private void sendToMulticast(byte[] payload) {
        MulticastSocket socket = multicastSocket;
        if (socket == null) {
            return;
        }
        try {
            socket.send(new DatagramPacket(payload, payload.length,
                    InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT));
        } catch (IOException e) {
            LogBus.get().d(TAG, "multicast search failed: " + e.getMessage());
        }
    }

    /** Sends to the router's unicast address as well, in case multicast is filtered. */
    private void sendToRouter(byte[] payload) {
        InetSocketAddress router = com.lgmediabridge.net.LocalNetwork.gatewayAddress(context);
        if (router == null) {
            return;
        }
        MulticastSocket socket = multicastSocket;
        if (socket == null) {
            return;
        }
        try {
            socket.send(new DatagramPacket(payload, payload.length, router.getAddress(), SSDP_PORT));
        } catch (IOException e) {
            LogBus.get().d(TAG, "unicast search failed: " + e.getMessage());
        }
    }

    private void listenLoop() {
        byte[] buffer = new byte[4096];
        while (multicastSocket != null && !multicastSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                multicastSocket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength(), ASCII);
                if (message.startsWith("HTTP/1.1 200")) {
                    handleResponse(message, packet.getAddress().getHostAddress());
                } else if (message.startsWith("NOTIFY")) {
                    if (SsdpMessages.isByeBye(message)) {
                        handleByeBye(message);
                    } else {
                        handleResponse(message, packet.getAddress().getHostAddress());
                    }
                }
            } catch (java.net.SocketTimeoutException e) {
                // normal: the socket times out so the loop can notice stop()
            } catch (IOException e) {
                if (multicastSocket != null && !multicastSocket.isClosed()) {
                    LogBus.get().d(TAG, "listen error: " + e.getMessage());
                }
            }
        }
    }

    private void handleResponse(String message, String senderAddress) {
        String location = header(message, "LOCATION");
        String server = header(message, "SERVER");
        String usn = header(message, "USN");
        String st = header(message, "ST");
        String nt = header(message, "NT");
        String type = st != null ? st : nt;
        if (location == null && type == null) {
            return;
        }
        boolean interesting = location != null
                || (type != null && (type.contains("MediaRenderer") || type.contains("MediaServer")
                || type.contains("webos-second-screen") || type.contains("lg")));
        if (!interesting) {
            return;
        }
        TvDevice candidate = location != null ? TvDevice.fromLocation(location, server)
                : new TvDevice();
        candidate.serverHeader = server;
        if (server != null && server.toLowerCase().contains("webos")) {
            candidate.lgSecondScreen = true;
        }
        if (type != null && type.toLowerCase().contains("webos-second-screen")) {
            candidate.lgSecondScreen = true;
        }
        if (usn != null) {
            candidate.udn = usn;
        }
        if (candidate.address == null) {
            candidate.address = senderAddress;
        }
        candidate.lastSeenAt = System.currentTimeMillis();
        LogBus.get().d(TAG, "reply from " + candidate.address + " "
                + (type == null ? "" : type) + (server == null ? "" : " [" + server + "]"));
        synchronized (this) {
            found.put(candidate.effectiveId(), candidate);
        }
        if (location != null) {
            fetchDescription(candidate);
        } else {
            notifyDevice(candidate, false);
        }
    }

    /**
     * A device announcing {@code ssdp:byebye} is gone (TV switched off, or its
     * network changed). Removing it immediately is what keeps the device list
     * honest instead of showing a TV that has been off for ten minutes.
     */
    private void handleByeBye(String message) {
        String usn = header(message, "USN");
        if (usn == null) {
            return;
        }
        String id = usn.startsWith("uuid:") ? usn.substring(5) : usn;
        int doubleColon = id.indexOf("::");
        if (doubleColon > 0) {
            id = id.substring(0, doubleColon);
        }
        TvDevice removed;
        synchronized (this) {
            removed = found.remove(id);
        }
        if (removed != null) {
            LogBus.get().i(TAG, removed.displayName() + " left the network");
            notifyStatus(removed.displayName() + " disconnected");
        }
    }

    private void fetchPendingDescriptions() {
        List<TvDevice> pending;
        synchronized (this) {
            pending = new ArrayList<>(found.values());
        }
        for (TvDevice device : pending) {
            fetchDescription(device);
        }
    }

    private void fetchDescription(TvDevice device) {
        if (device == null || device.location == null) {
            return;
        }
        try {
            java.net.HttpURLConnection connection =
                    (java.net.HttpURLConnection) new java.net.URL(device.location).openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(4000);
            connection.setRequestProperty("User-Agent", "Android/MediaBridge UPnP/1.0");
            int status = connection.getResponseCode();
            if (status != 200) {
                connection.disconnect();
                notifyDevice(device, false);
                return;
            }
            StringBuilder xml = new StringBuilder(4096);
            try (java.io.InputStream stream = connection.getInputStream()) {
                byte[] chunk = new byte[4096];
                int read;
                while ((read = stream.read(chunk)) > 0) {
                    xml.append(new String(chunk, 0, read, "UTF-8"));
                    if (xml.length() > 128 * 1024) {
                        break;
                    }
                }
            } finally {
                connection.disconnect();
            }
            TvDevice parsed = TvDevice.parseDescription(device.location, xml.toString(),
                    device.serverHeader);
            synchronized (this) {
                TvDevice existing = found.get(device.effectiveId());
                if (existing == null || existing == device) {
                    found.put(parsed.effectiveId(), parsed);
                } else {
                    existing.mergeFrom(parsed);
                }
            }
            notifyDevice(parsed, true);
        } catch (Exception e) {
            LogBus.get().d(TAG, "description fetch failed for " + device.address + ": "
                    + e.getMessage());
            notifyDevice(device, false);
        }
    }

    private void notifyDevice(TvDevice device, boolean described) {
        if (described) {
            LogBus.get().i(TAG, "found " + device.displayName() + " at " + device.address
                    + (device.isLg() ? " (LG webOS" + (device.supportsRemotePlayback()
                    ? ", playback control" : "") + ")" : ""));
        }
        for (Listener listener : listeners) {
            listener.onDevice(device);
        }
    }

    private void notifyStatus(String message) {
        for (Listener listener : listeners) {
            listener.onStatus(message);
        }
    }

    private synchronized void pruneStale() {
        long now = System.currentTimeMillis();
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, TvDevice> entry : found.entrySet()) {
            if (now - entry.getValue().lastSeenAt > DEVICE_TTL_MS && !entry.getValue().paired) {
                stale.add(entry.getKey());
            }
        }
        for (String key : stale) {
            found.remove(key);
        }
    }

    private static String header(String message, String name) {
        return SsdpMessages.header(message, name);
    }

    /**
     * Answers "which TV is at this address?" without a full network scan.
     *
     * Runs entirely on a background thread and reports through the normal
     * listener callbacks, so the UI thread is never blocked on a socket.
     */
    public void probeAddress(final String address) {
        if (address == null || address.trim().isEmpty()) {
            return;
        }
        String trimmed = address.trim();
        if (!trimmed.startsWith("http")) {
            trimmed = "http://" + trimmed + ":80/";
        }
        final String location = trimmed;
        Formats.newPool("tv-probe", 1).execute(() -> {
            try {
                java.net.HttpURLConnection connection =
                        (java.net.HttpURLConnection) new java.net.URL(location).openConnection();
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(4000);
                connection.setRequestProperty("User-Agent", "Android/MediaBridge UPnP/1.0");
                int status = connection.getResponseCode();
                if (status != 200) {
                    connection.disconnect();
                    notifyStatus("No UPnP device at this address");
                    return;
                }
                StringBuilder xml = new StringBuilder(4096);
                try (java.io.InputStream stream = connection.getInputStream()) {
                    byte[] chunk = new byte[4096];
                    int read;
                    while ((read = stream.read(chunk)) > 0) {
                        xml.append(new String(chunk, 0, read, "UTF-8"));
                        if (xml.length() > 128 * 1024) {
                            break;
                        }
                    }
                } finally {
                    connection.disconnect();
                }
                TvDevice device = TvDevice.parseDescription(location, xml.toString(), null);
                if (device.friendlyName == null || device.friendlyName.trim().isEmpty()) {
                    LogBus.get().d(TAG, "address did not answer with a UPnP description");
                    notifyStatus("Not a UPnP device");
                    return;
                }
                device.lastSeenAt = System.currentTimeMillis();
                synchronized (this) {
                    found.put(device.effectiveId(), device);
                }
                notifyDevice(device, true);
            } catch (Exception e) {
                LogBus.get().d(TAG, "probe failed: " + e.getMessage());
                notifyStatus("Could not reach " + location + ": " + e.getMessage());
            }
        });
    }
}
