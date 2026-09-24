package com.lgmediabridge.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import com.lgmediabridge.core.LogBus;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Answers the only networking question this app cares about: "what is my LAN
 * address right now, and when does that change?"
 *
 * The answer drives everything: the interface the HTTP server binds to, the URL
 * advertised over SSDP, and the reconnect logic. Watching it continuously makes
 * IP changes, Wi-Fi reconnections and TV reboots recoverable instead of fatal.
 */
public final class LocalNetwork {

    public interface Listener {
        void onNetworkChanged(State state);
    }

    public static final class State {
        public final boolean connected;
        public final boolean wifi;
        public final String ipAddress;
        public final int prefixLength;
        public final String interfaceName;
        public final String ssid;

        State(boolean connected, boolean wifi, String ipAddress, int prefixLength,
              String interfaceName, String ssid) {
            this.connected = connected;
            this.wifi = wifi;
            this.ipAddress = ipAddress;
            this.prefixLength = prefixLength;
            this.interfaceName = interfaceName;
            this.ssid = ssid;
        }

        public boolean hasAddress() {
            return ipAddress != null && !ipAddress.isEmpty();
        }

        public String describe() {
            if (!connected) {
                return "No network";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(wifi ? "Wi-Fi" : "Network");
            if (ssid != null && !ssid.isEmpty()) {
                sb.append(" · ").append(ssid);
            }
            if (hasAddress()) {
                sb.append(" · ").append(ipAddress);
            }
            return sb.toString();
        }
    }

    private static final String TAG = "LocalNetwork";
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static ConnectivityManager.NetworkCallback callback;
    private static volatile State lastState = new State(false, false, null, 0, null, null);

    private LocalNetwork() {
    }

    public static State state(Context context) {
        return probe(context);
    }

    public static synchronized void register(final Context context) {
        if (callback != null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        ConnectivityManager manager =
                (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return;
        }
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                publish(appContext, "network available");
            }

            @Override public void onLost(Network network) {
                publish(appContext, "network lost");
            }

            @Override public void onLinkPropertiesChanged(Network network, LinkProperties props) {
                publish(appContext, "link properties changed");
            }

            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                publish(appContext, "capabilities changed");
            }
        };
        try {
            manager.registerDefaultNetworkCallback(callback);
            LogBus.get().d(TAG, "watching network changes");
        } catch (RuntimeException e) {
            LogBus.get().e(TAG, "could not register network callback", e);
            callback = null;
        }
    }

    public static synchronized void unregister(Context context) {
        if (callback == null) {
            return;
        }
        ConnectivityManager manager =
                (ConnectivityManager) context.getApplicationContext()
                        .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager != null) {
            try {
                manager.unregisterNetworkCallback(callback);
            } catch (RuntimeException ignored) {
                // already unregistered
            }
        }
        callback = null;
    }

    private static void publish(Context context, String reason) {
        State state = probe(context);
        boolean changed = changed(lastState, state);
        lastState = state;
        if (!changed) {
            return;
        }
        LogBus.get().i(TAG, reason + " → " + state.describe());
        for (Listener listener : LISTENERS) {
            try {
                listener.onNetworkChanged(state);
            } catch (RuntimeException e) {
                LogBus.get().e(TAG, "listener failed", e);
            }
        }
    }

    private static boolean changed(State a, State b) {
        return a.connected != b.connected
                || !equal(a.ipAddress, b.ipAddress)
                || !equal(a.interfaceName, b.interfaceName)
                || a.wifi != b.wifi;
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public static void addListener(Listener listener) {
        LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    private static State probe(Context context) {
        ConnectivityManager manager =
                (ConnectivityManager) context.getApplicationContext()
                        .getSystemService(Context.CONNECTIVITY_SERVICE);
        String ssid = currentSsid(context);
        if (manager == null) {
            return new State(false, false, null, 0, null, ssid);
        }
        Network network = manager.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
        LinkProperties props = network == null ? null : manager.getLinkProperties(network);
        boolean connected = caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        boolean wifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        boolean ethernet = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        if (props == null) {
            return new State(false, wifi, null, 0, null, ssid);
        }
        String ipv4 = null;
        int prefix = 0;
        for (LinkAddress address : props.getLinkAddresses()) {
            InetAddress raw = address.getAddress();
            if (raw instanceof Inet4Address && !raw.isLoopbackAddress()) {
                ipv4 = raw.getHostAddress();
                prefix = address.getPrefixLength();
                break;
            }
        }
        // Wi-Fi can be up with no internet capability (captive portal/router
        // without uplink); local sharing still works in that case, so treat a
        // usable address on a LAN transport as connected.
        boolean usable = ipv4 != null && (wifi || ethernet || connected);
        return new State(usable, wifi, ipv4, prefix, props.getInterfaceName(), ssid);
    }

    /**
     * The router's address on the active network.
     *
     * Used for unicast SSDP searches: some access points drop multicast between
     * wireless clients, but a search sent straight to the router still reaches
     * every device behind it.
     */
    public static InetSocketAddress gatewayAddress(Context context) {
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return null;
            }
            Network network = manager.getActiveNetwork();
            if (network == null) {
                return null;
            }
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties == null) {
                return null;
            }
            for (android.net.RouteInfo route : properties.getRoutes()) {
                if (route.isDefaultRoute() && route.getGateway() != null
                        && route.getGateway() instanceof java.net.Inet4Address) {
                    return new InetSocketAddress(route.getGateway(), 1900);
                }
            }
        } catch (Exception e) {
            LogBus.get().d(TAG, "could not read the default gateway: " + e.getMessage());
        }
        return null;
    }

    private static String currentSsid(Context context) {
        try {
            WifiManager wifiManager =
                    (WifiManager) context.getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return null;
            }
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null || info.getSSID() == null) {
                return null;
            }
            String ssid = info.getSSID().replace("\"", "");
            return "<unknown ssid>".equals(ssid) ? null : ssid;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
