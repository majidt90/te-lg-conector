package com.lgmediabridge.dlna;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * SSDP wire format, as pure text.
 *
 * Split out of {@link SsdpServer} so the exact bytes the phone sends - which is
 * all the television ever sees of the discovery step - can be verified on a JVM
 * and diffed against the UPnP specification and real device captures.
 */
public final class SsdpMessages {

    public static final String MULTICAST_ADDRESS = "239.255.255.250";
    public static final int MULTICAST_PORT = 1900;
    public static final String ROOT_DEVICE = "upnp:rootdevice";
    public static final String ALL = "ssdp:all";
    public static final int MAX_AGE_SECONDS = 1800;
    public static final String SERVER_HEADER = "Android/10 UPnP/1.0 MediaBridge/1.0";

    private SsdpMessages() {
    }

    /** The five targets a DLNA server must announce (device + two services + root + UUID). */
    public static String[][] announcementTargets(String uuid) {
        String usn = "uuid:" + uuid;
        return new String[][]{
                {ROOT_DEVICE, usn + "::" + ROOT_DEVICE},
                {usn, usn},
                {DeviceDescription.DEVICE_TYPE, usn + "::" + DeviceDescription.DEVICE_TYPE},
                {DeviceDescription.CONTENT_DIRECTORY_TYPE,
                        usn + "::" + DeviceDescription.CONTENT_DIRECTORY_TYPE},
                {DeviceDescription.CONNECTION_MANAGER_TYPE,
                        usn + "::" + DeviceDescription.CONNECTION_MANAGER_TYPE},
        };
    }

    /** A search request, as sent by a control point (the phone looks for renderers with it). */
    public static String searchRequest(String target, int mxSeconds) {
        return "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + MULTICAST_ADDRESS + ":" + MULTICAST_PORT + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: " + Math.max(1, mxSeconds) + "\r\n"
                + "ST: " + target + "\r\n"
                + "USER-AGENT: " + SERVER_HEADER + "\r\n\r\n";
    }

    /** The reply to a search: unicast to the searcher, with the location of the description. */
    public static String searchResponse(String st, String usn, String location) {
        return "HTTP/1.1 200 OK\r\n"
                + "CACHE-CONTROL: max-age=" + MAX_AGE_SECONDS + "\r\n"
                + "DATE: " + httpDate() + "\r\n"
                + "EXT:\r\n"
                + "LOCATION: " + location + "\r\n"
                + "SERVER: " + SERVER_HEADER + "\r\n"
                + "ST: " + st + "\r\n"
                + "USN: " + usn + "\r\n"
                + "BOOTID.UPNP.ORG: 1\r\n"
                + "CONFIGID.UPNP.ORG: 1\r\n\r\n";
    }

    /** An announcement: {@code ssdp:alive} while sharing, {@code ssdp:byebye} on shutdown. */
    public static String notify(String nt, String usn, String nts, String location) {
        boolean alive = "ssdp:alive".equals(nts);
        StringBuilder sb = new StringBuilder(512);
        sb.append("NOTIFY * HTTP/1.1\r\n");
        sb.append("HOST: ").append(MULTICAST_ADDRESS).append(':').append(MULTICAST_PORT)
                .append("\r\n");
        if (alive) {
            sb.append("CACHE-CONTROL: max-age=").append(MAX_AGE_SECONDS).append("\r\n");
        }
        sb.append("LOCATION: ").append(location).append("\r\n");
        sb.append("NT: ").append(nt).append("\r\n");
        sb.append("NTS: ").append(nts).append("\r\n");
        sb.append("SERVER: ").append(SERVER_HEADER).append("\r\n");
        sb.append("USN: ").append(usn).append("\r\n");
        sb.append("BOOTID.UPNP.ORG: 1\r\n");
        sb.append("CONFIGID.UPNP.ORG: 1\r\n\r\n");
        return sb.toString();
    }

    /** RFC 1123 date in GMT; SSDP requires an English locale regardless of the device locale. */
    public static String httpDate() {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date());
    }

    /** Case-insensitive header lookup over a raw SSDP message. */
    public static String header(String message, String name) {
        if (message == null) {
            return null;
        }
        for (String line : message.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /** True when this announcement means a device is going away. */
    public static boolean isByeBye(String message) {
        String nts = header(message, "NTS");
        return nts != null && nts.contains("byebye");
    }
}
