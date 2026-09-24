package com.lgmediabridge.dlna;

import com.lgmediabridge.core.LogBus;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSDP presence: makes the phone show up in the TV's device list.
 *
 * Two mechanisms, both implemented because a TV may rely on either:
 *   * respond to {@code M-SEARCH} (the TV actively searching for media servers);
 *   * send {@code NOTIFY ssdp:alive} announcements periodically, plus a
 *     {@code ssdp:byebye} when sharing stops.
 *
 * The message format itself lives in {@link SsdpMessages} (pure Java, verified
 * on the JVM); this class only owns sockets, threads and locking.
 *
 * M-SEARCH reception requires a multicast lock on Android
 * (CHANGE_WIFI_MULTICAST_STATE) which the foreground service holds while
 * sharing is on; if port 1900 cannot be bound (another DLNA server is running)
 * the server keeps working: it still sends announcements, and logs why search
 * responses are unavailable.
 */
public final class SsdpServer {

    private static final String TAG = "Ssdp";
    public static final String SSDP_ADDRESS = SsdpMessages.MULTICAST_ADDRESS;
    public static final int SSDP_PORT = SsdpMessages.MULTICAST_PORT;
    private static final Charset ASCII = Charset.forName("ISO-8859-1");
    private static final long ANNOUNCE_INTERVAL_MS = 5 * 60 * 1000;
    private static final long INITIAL_ANNOUNCE_DELAY_MS = 600;

    private final String uuid;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong searchCount = new AtomicLong();
    private final Random random = new Random();

    private volatile String location;
    private volatile String serverHeader = "Android/10 UPnP/1.0 MediaBridge/1.0";
    private MulticastSocket socket;
    private NetworkInterface networkInterface;
    private Thread receiveThread;
    private Thread announceThread;

    public SsdpServer(String uuid, int port) {
        this.uuid = uuid;
        this.port = port;
    }

    public long searchCount() {
        return searchCount.get();
    }

    public boolean isListening() {
        return running.get() && socket != null && !socket.isClosed();
    }

    public void updateLocation(String host, String friendlyName) {
        this.location = "http://" + host + ":" + port + DeviceDescription.PATH_DEVICE;
    }

    public void start(String bindAddress, String interfaceName, String locationUrl, String serverHeader) {
        this.location = locationUrl;
        this.serverHeader = serverHeader;
        if (running.get()) {
            return;
        }
        running.set(true);
        try {
            networkInterface = interfaceName == null
                    ? NetworkInterface.getByInetAddress(InetAddress.getByName(bindAddress))
                    : NetworkInterface.getByName(interfaceName);
        } catch (Exception e) {
            networkInterface = null;
        }
        try {
            MulticastSocket multicastSocket = new MulticastSocket(SSDP_PORT);
            multicastSocket.setReuseAddress(true);
            multicastSocket.setTimeToLive(4);
            if (networkInterface != null) {
                multicastSocket.setNetworkInterface(networkInterface);
            }
            multicastSocket.joinGroup(InetAddress.getByName(SSDP_ADDRESS));
            this.socket = multicastSocket;
            LogBus.get().i(TAG, "listening for SSDP searches on port " + SSDP_PORT);
        } catch (Exception e) {
            this.socket = null;
            LogBus.get().w(TAG, "SSDP port " + SSDP_PORT + " unavailable (" + e.getMessage()
                    + "). Announcements still work; the TV may need a moment to list this device.");
        }
        receiveThread = new Thread(this::receiveLoop, "ssdp-receive");
        receiveThread.setDaemon(true);
        receiveThread.start();
        announceThread = new Thread(this::announceLoop, "ssdp-announce");
        announceThread.setDaemon(true);
        announceThread.start();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        sendAnnouncements("ssdp:byebye");
        MulticastSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.leaveGroup(InetAddress.getByName(SSDP_ADDRESS));
            } catch (IOException ignored) {
                // leaving a multicast group is best effort
            }
            current.close();
        }
        LogBus.get().i(TAG, "stopped");
    }

    private void receiveLoop() {
        byte[] buffer = new byte[2048];
        while (running.get()) {
            MulticastSocket current = socket;
            if (current == null) {
                return;
            }
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                current.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength(), ASCII);
                if (message.startsWith("M-SEARCH")) {
                    handleSearch(message, packet);
                }
            } catch (IOException e) {
                if (running.get()) {
                    LogBus.get().d(TAG, "receive error: " + e.getMessage());
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void handleSearch(String message, DatagramPacket packet) {
        String st = header(message, "ST");
        if (st == null) {
            return;
        }
        String[][] answers = SsdpMessages.responsesFor(st, uuid);
        if (answers.length == 0) {
            return;
        }
        searchCount.incrementAndGet();
        LogBus.get().d(TAG, "M-SEARCH from " + packet.getAddress().getHostAddress() + " for " + st);
        // The spec asks for a random delay of 0-100 ms to avoid bursts.
        try {
            Thread.sleep(random.nextInt(90));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        MulticastSocket current = socket;
        if (current == null) {
            return;
        }
        for (String[] answer : answers) {
            byte[] bytes = response(answer[0], answer[1]).getBytes(ASCII);
            try {
                current.send(new DatagramPacket(bytes, bytes.length,
                        new InetSocketAddress(packet.getAddress(), packet.getPort())));
            } catch (IOException e) {
                LogBus.get().d(TAG, "could not answer search: " + e.getMessage());
                return;
            }
        }
    }

    private String response(String st, String usn) {
        return SsdpMessages.searchResponse(st, usn, location);
    }

    private void announceLoop() {
        sleep(INITIAL_ANNOUNCE_DELAY_MS);
        while (running.get()) {
            sendAnnouncements("ssdp:alive");
            sleep(ANNOUNCE_INTERVAL_MS);
        }
    }

    private void sendAnnouncements(String nt) {
        if (location == null) {
            return;
        }
        List<String[]> targets = new ArrayList<>();
        Collections.addAll(targets, SsdpMessages.announcementTargets(uuid));

        MulticastSocket current = socket;
        if (current == null) {
            // Port 1900 is taken; send from a temporary socket so announcements
            // still reach the network (binding is only needed to receive).
            try {
                current = new MulticastSocket();
                if (networkInterface != null) {
                    current.setNetworkInterface(networkInterface);
                }
                current.setTimeToLive(4);
            } catch (IOException e) {
                LogBus.get().w(TAG, "could not open announcement socket: " + e.getMessage());
                return;
            }
        }
        try {
            InetAddress group = InetAddress.getByName(SSDP_ADDRESS);
            for (String[] target : targets) {
                String message = notifyMessage(target[0], target[1], nt);
                byte[] bytes = message.getBytes(ASCII);
                try {
                    current.send(new DatagramPacket(bytes, bytes.length, group, SSDP_PORT));
                } catch (IOException e) {
                    LogBus.get().d(TAG, "announce failed: " + e.getMessage());
                }
            }
            LogBus.get().d(TAG, "sent " + nt + " for " + targets.size() + " targets at " + location);
        } catch (IOException e) {
            LogBus.get().w(TAG, "announce failed: " + e.getMessage());
        } finally {
            if (socket == null) {
                current.close();
            }
        }
    }

    private String notifyMessage(String nt, String usn, String nts) {
        return SsdpMessages.notify(nt, usn, nts, location);
    }

    private boolean matches(String st) {
        return st.equals("ssdp:all")
                || st.equals("upnp:rootdevice")
                || st.contains("MediaServer")
                || st.contains("ContentDirectory")
                || st.contains("ConnectionManager")
                || st.contains(uuid);
    }

    private static String header(String message, String name) {
        return SsdpMessages.header(message, name);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** True when this device can receive multicast (multicast lock held). */
    public static boolean canReceiveMulticast() {
        try {
            MulticastSocket probe = new MulticastSocket(0);
            probe.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
