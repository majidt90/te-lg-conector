package com.lgmediabridge.control;

import com.lgmediabridge.core.LogBus;
import com.lgmediabridge.core.Xml;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal SOAP-over-HTTP client for UPnP AV control (AVTransport,
 * RenderingControl).
 *
 * This is the document-standard way a controller drives a renderer: no vendor
 * extension, no undocumented endpoint. The response body is kept as text so
 * callers can pull out the arguments they need with the same XML helpers used
 * everywhere else in the app.
 */
public final class SoapClient {

    private static final String TAG = "Soap";
    private static final int TIMEOUT_MS = 6_000;

    public static final class Response {
        public final boolean success;
        public final String body;
        public final int statusCode;
        public final String error;

        Response(boolean success, String body, int statusCode, String error) {
            this.success = success;
            this.body = body;
            this.statusCode = statusCode;
            this.error = error;
        }

        public String argument(String name) {
            return Xml.element(body, name);
        }

        public boolean returnValue() {
            String value = argument("returnValue");
            return value == null || "1".equals(value) || "true".equalsIgnoreCase(value);
        }
    }

    private SoapClient() {
    }

    public static Response call(String controlUrl, String serviceType, String action,
                                Map<String, String> arguments, String clientTag) {
        StringBuilder body = new StringBuilder(512);
        body.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            .append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"")
            .append(" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            .append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">");
        if (arguments != null) {
            for (Map.Entry<String, String> entry : arguments.entrySet()) {
                body.append("<").append(entry.getKey()).append(">")
                    .append(Xml.escape(entry.getValue() == null ? "" : entry.getValue()))
                    .append("</").append(entry.getKey()).append(">");
            }
        }
        body.append("</u:").append(action).append("></s:Body></s:Envelope>");

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(controlUrl).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            connection.setRequestProperty("SOAPAction", "\"" + serviceType + "#" + action + "\"");
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("User-Agent", clientTag);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setDoOutput(true);
            byte[] payload = body.toString().getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseBody = stream == null ? "" : readAll(stream);
            boolean ok = status >= 200 && status < 300;
            if (!ok) {
                LogBus.get().d(TAG, action + " → HTTP " + status + " " + shorten(responseBody));
            }
            return new Response(ok, responseBody, status, ok ? null : "HTTP " + status);
        } catch (Exception e) {
            LogBus.get().d(TAG, "call failed: " + action + " " + e.getMessage());
            return new Response(false, "", 0, e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static Map<String, String> args(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static String readAll(InputStream stream) throws java.io.IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(2048);
        byte[] chunk = new byte[4096];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), "UTF-8");
    }

    private static String shorten(String value) {
        if (value == null) {
            return "";
        }
        String single = value.replaceAll("\\s+", " ");
        return single.length() > 180 ? single.substring(0, 180) + "…" : single;
    }
}
