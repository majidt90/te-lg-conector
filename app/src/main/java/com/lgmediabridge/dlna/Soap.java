package com.lgmediabridge.dlna;

import com.lgmediabridge.core.Xml;

import java.util.Map;

/**
 * SOAP 1.1 envelopes for UPnP control, in one place.
 *
 * Both directions live here: the requests this app sends as a control point, and
 * the responses it returns as a server. Keeping the wire format in a pure class
 * (no Android types) means the exact XML can be unit-tested on the JVM instead of
 * only on a TV.
 */
public final class Soap {

    public static final String ENVELOPE_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    public static final String ENCODING_STYLE = "http://schemas.xmlsoap.org/soap/encoding/";
    public static final String CONTROL_NS = "urn:schemas-upnp-org:control-1-0";

    private Soap() {
    }

    /** A request envelope, as sent to a renderer's control URL. */
    public static String call(String serviceType, String action, Map<String, String> arguments) {
        StringBuilder body = new StringBuilder(512);
        body.append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">");
        if (arguments != null) {
            for (Map.Entry<String, String> entry : arguments.entrySet()) {
                body.append(tag(entry.getKey(), entry.getValue() == null ? "" : entry.getValue()));
            }
        }
        body.append("</u:").append(action).append(">");
        return envelope(body.toString());
    }

    /** A response envelope carrying one action's output arguments. */
    public static String response(String bodyFragment) {
        return envelope(bodyFragment);
    }

    /** A UPnP error response: HTTP 500 with an errorCode/errorDescription pair. */
    public static String fault(int code, String description) {
        return envelope("<s:Fault>"
                + "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>"
                + "<detail><UPnPError xmlns=\"" + CONTROL_NS + "\">"
                + "<errorCode>" + code + "</errorCode>"
                + "<errorDescription>" + Xml.escape(description) + "</errorDescription>"
                + "</UPnPError></detail></s:Fault>");
    }

    public static String envelope(String bodyFragment) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"" + ENVELOPE_NS + "\" s:encodingStyle=\"" + ENCODING_STYLE + "\">"
                + "<s:Body>" + bodyFragment + "</s:Body></s:Envelope>";
    }

    /** Element with escaped content. */
    public static String tag(String name, String value) {
        return "<" + name + ">" + Xml.escape(value) + "</" + name + ">";
    }

    /** Response wrapper for one service action. */
    public static String actionResponse(String serviceType, String action, String arguments) {
        return response("<u:" + action + "Response xmlns:u=\"" + serviceType + "\">"
                + arguments + "</u:" + action + "Response>");
    }
}
