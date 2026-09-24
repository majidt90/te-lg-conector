package com.lgmediabridge.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A parsed HTTP/1.1 request.
 *
 * Only what a DLNA player and a UPnP control point actually send is supported:
 * GET, HEAD, POST (SOAP), SUBSCRIBE/UNSUBSCRIBE (GENA), plus byte range and time
 * seek headers. Header lookup is case-insensitive, as required by RFC 7230.
 */
public final class HttpRequest {

    private static final Charset ASCII = Charset.forName("ISO-8859-1");
    private static final int MAX_LINE = 8 * 1024;
    private static final int MAX_HEADERS = 64;
    private static final int MAX_BODY = 4 * 1024 * 1024;

    public final String method;
    public final String rawTarget;
    public final String path;
    public final String query;
    public final String httpVersion;
    public final Map<String, String> headers;
    public final byte[] body;
    public final String remoteAddress;
    public final String userAgent;
    public final String localAddress;

    private HttpRequest(String method, String rawTarget, String httpVersion,
                        Map<String, String> headers, byte[] body,
                        String remoteAddress, String localAddress) {
        this.method = method;
        this.rawTarget = rawTarget;
        this.httpVersion = httpVersion;
        this.headers = headers;
        this.body = body;
        int q = rawTarget.indexOf('?');
        this.path = q < 0 ? rawTarget : rawTarget.substring(0, q);
        this.query = q < 0 ? "" : rawTarget.substring(q + 1);
        this.remoteAddress = remoteAddress;
        this.localAddress = localAddress;
        String ua = headers.get("user-agent");
        this.userAgent = ua == null ? "" : ua;
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.US));
    }

    public long headerLong(String name, long fallback) {
        String value = header(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String queryParam(String name) {
        if (query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                return eq < 0 ? "" : urlDecode(pair.substring(eq + 1));
            }
        }
        return null;
    }

    public boolean keepAlive() {
        String connection = header("connection");
        if (connection != null && connection.toLowerCase(Locale.US).contains("close")) {
            return false;
        }
        return true;
    }

    public String bodyText() {
        return body == null ? "" : new String(body, Charset.forName("UTF-8"));
    }

    public boolean isSoap() {
        String contentType = header("content-type");
        return contentType != null && contentType.toLowerCase(Locale.US).contains("xml");
    }

    /** True for requests that carry a body the renderer wants bytes for. */
    public boolean isRangeRequest() {
        return header("range") != null || header("timeseekrange.dlna.org") != null;
    }

    /** Identifies the DLNA/UPnP client in diagnostics: LG TVs are recognisable. */
    public String clientLabel() {
        String dlnaDevice = header("dlnadevicename.lge.com");
        if (dlnaDevice != null) {
            return dlnaDevice;
        }
        if (userAgent.isEmpty()) {
            return remoteAddress;
        }
        // A typical LG UA looks like:
        //   Linux/3.0.13 UPnP/1.0 LGE_DLNA_SDK/1.6.0 [TV][LG]55NANO86VPA/04.10.25 DLNADOC/1.50
        int model = userAgent.indexOf("[TV]");
        if (model >= 0) {
            int end = userAgent.indexOf(' ', model);
            return end > model ? userAgent.substring(model, end) : userAgent.substring(model);
        }
        return userAgent.length() > 64 ? userAgent.substring(0, 64) + "…" : userAgent;
    }

    public static String urlDecode(String value) {
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    public static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * Reads one request from the socket. Returns null on a clean end of stream
     * (client closed the connection between keep-alive requests).
     */
    static HttpRequest read(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 3) {
            throw new IOException("malformed request line: " + requestLine);
        }
        Map<String, String> headers = new TreeMap<>();
        for (int i = 0; i < MAX_HEADERS; i++) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                        line.substring(colon + 1).trim());
            }
        }
        byte[] body = null;
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.US).contains("chunked")) {
            body = readChunked(in);
        } else {
            String contentLength = headers.get("content-length");
            if (contentLength != null) {
                int length;
                try {
                    length = Integer.parseInt(contentLength.trim());
                } catch (NumberFormatException e) {
                    length = 0;
                }
                if (length > MAX_BODY) {
                    throw new IOException("body too large: " + length);
                }
                if (length > 0) {
                    body = new byte[length];
                    readFully(in, body, 0, length);
                }
            }
        }
        InetSocketAddress remote = (InetSocketAddress) socket.getRemoteSocketAddress();
        InetSocketAddress local = (InetSocketAddress) socket.getLocalSocketAddress();
        return new HttpRequest(parts[0].toUpperCase(Locale.US), parts[1], parts[2], headers, body,
                remote == null ? null : remote.getAddress().getHostAddress(),
                local == null ? null : local.getAddress().getHostAddress());
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buffer.write(b);
            }
            if (buffer.size() > MAX_LINE) {
                throw new IOException("header line too long");
            }
        }
        if (b == -1 && buffer.size() == 0) {
            return null;
        }
        return new String(buffer.toByteArray(), ASCII);
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(1024);
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                break;
            }
            int semi = sizeLine.indexOf(';');
            String hex = (semi < 0 ? sizeLine : sizeLine.substring(0, semi)).trim();
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("bad chunk size: " + hex);
            }
            if (size == 0) {
                readLine(in); // trailing CRLF
                break;
            }
            if (body.size() + size > MAX_BODY) {
                throw new IOException("chunked body too large");
            }
            byte[] chunk = new byte[size];
            readFully(in, chunk, 0, size);
            body.write(chunk, 0, size);
            readLine(in);
        }
        return body.toByteArray();
    }

    private static void readFully(InputStream in, byte[] target, int offset, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int count = in.read(target, offset + read, length - read);
            if (count < 0) {
                throw new IOException("unexpected end of stream");
            }
            read += count;
        }
    }
}
