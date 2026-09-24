package com.lgmediabridge.control;

import com.lgmediabridge.core.Json;
import com.lgmediabridge.core.Xml;

import java.util.Map;

/**
 * A TV discovered on the LAN, merged from every source that describes it:
 * SSDP announcements, the UPnP device description, and the webOS second-screen
 * service (SSAP).
 *
 * Capability flags are filled in as facts are observed rather than assumed: a
 * TV is only marked as supporting remote playback once an AVTransport control
 * URL has actually been found in its description, and only marked as paired once
 * the TV itself has returned a client key.
 */
public final class TvDevice {

    public String id;
    public String udn;
    public String friendlyName;
    public String modelName;
    public String manufacturer;
    public String address;
    public int port = 80;
    public String location;
    public String serverHeader;
    public String deviceType;

    /** Discovered with the LG-specific second-screen service type. */
    public boolean lgSecondScreen;
    /** Device description exposed AVTransport → remote playback control is possible. */
    public boolean mediaRenderer;
    /** Device description exposed RenderingControl → remote volume control is possible. */
    public boolean renderingControl;
    public String avTransportUrl;
    public String renderingControlUrl;
    public String avTransportType = "urn:schemas-upnp-org:service:AVTransport:1";
    public String renderingControlType = "urn:schemas-upnp-org:service:RenderingControl:1";

    /** webOS pairing state (SSAP client key stored encrypted). */
    public boolean paired;
    public String clientKey;

    public long lastSeenAt;

    public static TvDevice fromLocation(String location, String serverHeader) {
        TvDevice device = new TvDevice();
        device.location = location;
        device.serverHeader = serverHeader;
        device.address = hostOf(location);
        device.port = portOf(location);
        device.lastSeenAt = System.currentTimeMillis();
        return device;
    }

    public String effectiveId() {
        if (udn != null && !udn.isEmpty()) {
            String trimmed = udn.startsWith("uuid:") ? udn.substring(5) : udn;
            int doubleColon = trimmed.indexOf("::");
            return doubleColon > 0 ? trimmed.substring(0, doubleColon) : trimmed;
        }
        return (address == null ? "unknown" : address) + ":" + port;
    }

    public String displayName() {
        if (friendlyName != null && !friendlyName.trim().isEmpty()) {
            return friendlyName.trim();
        }
        if (modelName != null && !modelName.trim().isEmpty()) {
            return modelName.trim();
        }
        return address == null ? "TV" : address;
    }

    public boolean isLg() {
        String haystack = ((serverHeader == null ? "" : serverHeader) + " "
                + (modelName == null ? "" : modelName) + " "
                + (manufacturer == null ? "" : manufacturer) + " "
                + (friendlyName == null ? "" : friendlyName)).toLowerCase();
        return lgSecondScreen || haystack.contains("lg") || haystack.contains("webos");
    }

    /** True when the phone can start/pause/seek playback on this TV over UPnP. */
    public boolean supportsRemotePlayback() {
        return mediaRenderer && avTransportUrl != null && !avTransportUrl.isEmpty();
    }

    public boolean supportsRemoteVolume() {
        return renderingControl && renderingControlUrl != null && !renderingControlUrl.isEmpty();
    }

    /** True when the TV can be controlled through the webOS socket (pairing/SSAP). */
    public boolean supportsWebOsControl() {
        return lgSecondScreen || paired;
    }

    public String capabilitySummary() {
        StringBuilder sb = new StringBuilder();
        if (supportsRemotePlayback()) {
            sb.append("playback control");
        }
        if (supportsRemoteVolume()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("volume");
        }
        if (paired) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("paired");
        }
        return sb.length() == 0 ? "browse only" : sb.toString();
    }

    public void mergeFrom(TvDevice other) {
        if (other == null) {
            return;
        }
        if (other.udn != null && !other.udn.isEmpty()) {
            udn = other.udn;
        }
        if (other.friendlyName != null && !other.friendlyName.isEmpty()) {
            friendlyName = other.friendlyName;
        }
        if (other.modelName != null && !other.modelName.isEmpty()) {
            modelName = other.modelName;
        }
        if (other.manufacturer != null && !other.manufacturer.isEmpty()) {
            manufacturer = other.manufacturer;
        }
        if (other.location != null && !other.location.isEmpty()) {
            location = other.location;
            address = other.address;
            port = other.port;
        }
        if (other.serverHeader != null) {
            serverHeader = other.serverHeader;
        }
        lgSecondScreen |= other.lgSecondScreen;
        mediaRenderer |= other.mediaRenderer;
        renderingControl |= other.renderingControl;
        if (other.avTransportUrl != null) {
            avTransportUrl = other.avTransportUrl;
        }
        if (other.renderingControlUrl != null) {
            renderingControlUrl = other.renderingControlUrl;
        }
        if (other.avTransportType != null) {
            avTransportType = other.avTransportType;
        }
        if (other.renderingControlType != null) {
            renderingControlType = other.renderingControlType;
        }
        if (other.deviceType != null) {
            deviceType = other.deviceType;
        }
        lastSeenAt = Math.max(lastSeenAt, other.lastSeenAt);
    }

    /** Parses a UPnP device description document. */
    public static TvDevice parseDescription(String location, String xml, String serverHeader) {
        TvDevice device = fromLocation(location, serverHeader);
        if (xml == null) {
            return device;
        }
        device.udn = Xml.element(xml, "UDN");
        device.friendlyName = Xml.element(xml, "friendlyName");
        device.modelName = Xml.element(xml, "modelName");
        device.manufacturer = Xml.element(xml, "manufacturer");
        device.deviceType = Xml.element(xml, "deviceType");

        int index = 0;
        while (true) {
            int start = xml.indexOf("<service>", index);
            if (start < 0) {
                break;
            }
            int end = xml.indexOf("</service>", start);
            if (end < 0) {
                break;
            }
            String block = xml.substring(start, end);
            String type = Xml.element(block, "serviceType");
            String controlUrl = Xml.element(block, "controlURL");
            if (type != null && controlUrl != null) {
                String absolute = absoluteUrl(location, controlUrl);
                if (type.contains("AVTransport")) {
                    device.mediaRenderer = true;
                    device.avTransportUrl = absolute;
                    device.avTransportType = type;
                } else if (type.contains("RenderingControl")) {
                    device.renderingControl = true;
                    device.renderingControlUrl = absolute;
                    device.renderingControlType = type;
                }
            }
            index = end + 1;
        }
        return device;
    }

    public static String absoluteUrl(String base, String path) {
        if (path == null) {
            return null;
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        try {
            java.net.URL url = new java.net.URL(base);
            String root = url.getProtocol() + "://" + url.getHost()
                    + (url.getPort() > 0 ? ":" + url.getPort() : "");
            return path.startsWith("/") ? root + path : root + "/" + path;
        } catch (Exception e) {
            return path;
        }
    }

    public static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    public static int portOf(String url) {
        try {
            java.net.URL parsed = new java.net.URL(url);
            return parsed.getPort() > 0 ? parsed.getPort() : 80;
        } catch (Exception e) {
            return 80;
        }
    }

    public String toJson() {
        Map<String, Object> map = Json.obj(
                "id", effectiveId(),
                "udn", udn,
                "name", friendlyName,
                "model", modelName,
                "manufacturer", manufacturer,
                "address", address,
                "port", port,
                "location", location,
                "avTransport", avTransportUrl,
                "renderingControl", renderingControlUrl,
                "lg", lgSecondScreen,
                "paired", paired,
                "lastSeen", lastSeenAt);
        return Json.write(map);
    }

    public static TvDevice fromJson(String json) {
        Map<String, Object> map = Json.parseObject(json);
        if (map == null) {
            return null;
        }
        TvDevice device = new TvDevice();
        device.udn = Json.getString(map, "udn", null);
        device.id = Json.getString(map, "id", null);
        device.friendlyName = Json.getString(map, "name", null);
        device.modelName = Json.getString(map, "model", null);
        device.manufacturer = Json.getString(map, "manufacturer", null);
        device.address = Json.getString(map, "address", null);
        device.port = Json.getInt(map, "port", 80);
        device.location = Json.getString(map, "location", null);
        device.avTransportUrl = Json.getString(map, "avTransport", null);
        device.renderingControlUrl = Json.getString(map, "renderingControl", null);
        device.lgSecondScreen = Json.getBoolean(map, "lg", false);
        device.paired = Json.getBoolean(map, "paired", false);
        device.lastSeenAt = (long) Json.getInt(map, "lastSeen", 0);
        device.mediaRenderer = device.avTransportUrl != null;
        device.renderingControl = device.renderingControlUrl != null;
        return device;
    }
}
