package com.lgmediabridge.dlna;

import com.lgmediabridge.core.Xml;

/**
 * UPnP device and service descriptions for the phone acting as a Digital Media
 * Server (DMS).
 *
 * The device type is {@code urn:schemas-upnp-org:device:MediaServer:1} - the
 * standard that LG's SmartShare / "Photos &amp; Videos" browser looks for - and
 * the description carries {@code dlna:X_DLNADOC DMS-1.50} plus the two services
 * every DLNA server must expose (ContentDirectory and ConnectionManager).
 * Everything here is standard UPnP AV; no LG-specific or undocumented extension
 * is required for the TV to browse this server.
 */
public final class DeviceDescription {

    public static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaServer:1";
    public static final String CONTENT_DIRECTORY_TYPE =
            "urn:schemas-upnp-org:service:ContentDirectory:1";
    public static final String CONNECTION_MANAGER_TYPE =
            "urn:schemas-upnp-org:service:ConnectionManager:1";

    public static final String PATH_DEVICE = "/rootDesc.xml";
    public static final String PATH_CONTENT_SCPD = "/upnp/scpd/content-directory.xml";
    public static final String PATH_CONNECTION_SCPD = "/upnp/scpd/connection-manager.xml";
    public static final String PATH_CONTENT_CONTROL = "/upnp/control/content-directory";
    public static final String PATH_CONNECTION_CONTROL = "/upnp/control/connection-manager";
    public static final String PATH_CONTENT_EVENT = "/upnp/event/content-directory";
    public static final String PATH_CONNECTION_EVENT = "/upnp/event/connection-manager";

    private DeviceDescription() {
    }

    public static String build(String uuid, String friendlyName, String baseUrl, String serverHeader) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\""
                + " xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<device>"
                + "<deviceType>" + DEVICE_TYPE + "</deviceType>"
                + "<friendlyName>" + Xml.escape(friendlyName) + "</friendlyName>"
                + "<manufacturer>MediaBridge</manufacturer>"
                + "<manufacturerURL>https://mediabridge.local/</manufacturerURL>"
                + "<modelDescription>DLNA media server for local network players</modelDescription>"
                + "<modelName>MediaBridge</modelName>"
                + "<modelNumber>1.0.0</modelNumber>"
                + "<serialNumber>" + Xml.escape(uuid) + "</serialNumber>"
                + "<UDN>uuid:" + Xml.escape(uuid) + "</UDN>"
                + "<dlna:X_DLNADOC>DMS-1.50</dlna:X_DLNADOC>"
                + "<X_DLNADOC xmlns=\"urn:schemas-dlna-org:device-1-0\">DMS-1.50</X_DLNADOC>"
                + "<presentationURL>" + Xml.escape(baseUrl) + "/</presentationURL>"
                + "<serviceList>"
                + service(CONTENT_DIRECTORY_TYPE, "urn:upnp-org:serviceId:ContentDirectory",
                        PATH_CONTENT_SCPD, PATH_CONTENT_CONTROL, PATH_CONTENT_EVENT)
                + service(CONNECTION_MANAGER_TYPE, "urn:upnp-org:serviceId:ConnectionManager",
                        PATH_CONNECTION_SCPD, PATH_CONNECTION_CONTROL, PATH_CONNECTION_EVENT)
                + "</serviceList>"
                + "</device>"
                + "</root>";
    }

    private static String service(String type, String id, String scpd, String control, String event) {
        return "<service>"
                + "<serviceType>" + type + "</serviceType>"
                + "<serviceId>" + id + "</serviceId>"
                + "<SCPDURL>" + scpd + "</SCPDURL>"
                + "<controlURL>" + control + "</controlURL>"
                + "<eventSubURL>" + event + "</eventSubURL>"
                + "</service>";
    }

    public static String contentDirectoryScpd() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<actionList>"
                + action("GetSearchCapabilities",
                        out("SearchCaps", "SearchCapabilities"))
                + action("GetSortCapabilities",
                        out("SortCaps", "SortCapabilities"))
                + action("GetSystemUpdateID",
                        out("Id", "SystemUpdateID"))
                + action("Browse",
                        in("ObjectID", "A_ARG_TYPE_ObjectID"),
                        in("BrowseFlag", "A_ARG_TYPE_BrowseFlag"),
                        in("Filter", "A_ARG_TYPE_Filter"),
                        in("StartingIndex", "A_ARG_TYPE_Index"),
                        in("RequestedCount", "A_ARG_TYPE_Count"),
                        in("SortCriteria", "A_ARG_TYPE_SortCriteria"),
                        out("Result", "A_ARG_TYPE_Result"),
                        out("NumberReturned", "A_ARG_TYPE_Count"),
                        out("TotalMatches", "A_ARG_TYPE_Count"),
                        out("UpdateID", "A_ARG_TYPE_UpdateID"))
                + action("Search",
                        in("ContainerID", "A_ARG_TYPE_ObjectID"),
                        in("SearchCriteria", "A_ARG_TYPE_SearchCriteria"),
                        in("Filter", "A_ARG_TYPE_Filter"),
                        in("StartingIndex", "A_ARG_TYPE_Index"),
                        in("RequestedCount", "A_ARG_TYPE_Count"),
                        in("SortCriteria", "A_ARG_TYPE_SortCriteria"),
                        out("Result", "A_ARG_TYPE_Result"),
                        out("NumberReturned", "A_ARG_TYPE_Count"),
                        out("TotalMatches", "A_ARG_TYPE_Count"),
                        out("UpdateID", "A_ARG_TYPE_UpdateID"))
                + "</actionList>"
                + "<serviceStateTable>"
                + state("A_ARG_TYPE_ObjectID", "string", false)
                + state("A_ARG_TYPE_Result", "string", false)
                + state("A_ARG_TYPE_BrowseFlag", "string", false)
                + state("A_ARG_TYPE_Filter", "string", false)
                + state("A_ARG_TYPE_SortCriteria", "string", false)
                + state("A_ARG_TYPE_Index", "ui4", false)
                + state("A_ARG_TYPE_Count", "ui4", false)
                + state("A_ARG_TYPE_UpdateID", "ui4", false)
                + state("A_ARG_TYPE_SearchCriteria", "string", false)
                + state("SearchCapabilities", "string", false)
                + state("SortCapabilities", "string", false)
                + state("SystemUpdateID", "ui4", true)
                + "</serviceStateTable>"
                + "</scpd>";
    }

    public static String connectionManagerScpd() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<actionList>"
                + action("GetProtocolInfo",
                        out("Source", "SourceProtocolInfo"),
                        out("Sink", "SinkProtocolInfo"))
                + action("GetCurrentConnectionIDs",
                        out("ConnectionIDs", "CurrentConnectionIDs"))
                + action("GetCurrentConnectionInfo",
                        in("ConnectionID", "A_ARG_TYPE_ConnectionID"),
                        out("RcsID", "A_ARG_TYPE_RcsID"),
                        out("AVTransportID", "A_ARG_TYPE_AVTransportID"),
                        out("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo"),
                        out("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"),
                        out("PeerConnectionID", "A_ARG_TYPE_ConnectionID"),
                        out("Direction", "A_ARG_TYPE_Direction"),
                        out("Status", "A_ARG_TYPE_ConnectionStatus"))
                + "</actionList>"
                + "<serviceStateTable>"
                + state("SourceProtocolInfo", "string", true)
                + state("SinkProtocolInfo", "string", true)
                + state("CurrentConnectionIDs", "string", true)
                + state("A_ARG_TYPE_ConnectionStatus", "string", false)
                + state("A_ARG_TYPE_ConnectionManager", "string", false)
                + state("A_ARG_TYPE_Direction", "string", false)
                + state("A_ARG_TYPE_ProtocolInfo", "string", false)
                + state("A_ARG_TYPE_ConnectionID", "i4", false)
                + state("A_ARG_TYPE_AVTransportID", "i4", false)
                + state("A_ARG_TYPE_RcsID", "i4", false)
                + "</serviceStateTable>"
                + "</scpd>";
    }

    private static String action(String name, String... arguments) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<action><name>").append(name).append("</name>");
        if (arguments.length > 0) {
            sb.append("<argumentList>");
            for (String argument : arguments) {
                sb.append(argument);
            }
            sb.append("</argumentList>");
        }
        return sb.append("</action>").toString();
    }

    private static String in(String name, String stateVariable) {
        return "<argument><name>" + name + "</name><direction>in</direction>"
                + "<relatedStateVariable>" + stateVariable + "</relatedStateVariable></argument>";
    }

    private static String out(String name, String stateVariable) {
        return "<argument><name>" + name + "</name><direction>out</direction>"
                + "<relatedStateVariable>" + stateVariable + "</relatedStateVariable></argument>";
    }

    private static String state(String name, String dataType, boolean sendsEvents) {
        return "<stateVariable sendEvents=\"" + (sendsEvents ? "yes" : "no") + "\">"
                + "<name>" + name + "</name><dataType>" + dataType + "</dataType></stateVariable>";
    }
}
