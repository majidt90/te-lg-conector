package com.lgmediabridge.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A streaming-friendly HTTP/1.1 response.
 *
 * Bodies can be a byte array, a string or an open stream with a known length -
 * the streaming case is what makes multi-gigabyte videos and byte-range seeking
 * work without buffering anything in memory.
 */
public final class HttpResponse {

    public interface ProgressListener {
        /** Called after each chunk is handed to the socket. */
        void onBytes(long totalWritten, int chunkSize);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public int status = 200;
    public String statusText = "OK";
    public final Map<String, String> headers = new LinkedHashMap<>();

    private byte[] byteBody;
    private InputStream streamBody;
    private long streamLength = -1;
    private ProgressListener progressListener;
    private boolean forceClose;

    public HttpResponse status(int status, String text) {
        this.status = status;
        this.statusText = text;
        return this;
    }

    public HttpResponse header(String name, String value) {
        if (value != null) {
            headers.put(name, value);
        }
        return this;
    }

    public HttpResponse body(byte[] body) {
        this.byteBody = body;
        return this;
    }

    public HttpResponse body(String body) {
        this.byteBody = body == null ? new byte[0] : body.getBytes(UTF8);
        if (body != null) {
            header("Content-Type", "text/plain; charset=utf-8");
        }
        return this;
    }

    public HttpResponse xml(String body) {
        this.byteBody = body == null ? new byte[0] : body.getBytes(UTF8);
        header("Content-Type", "text/xml; charset=\"utf-8\"");
        return this;
    }

    /**
     * Streams {@code length} bytes from {@code stream}. The stream is closed by
     * the server once it has been consumed.
     */
    public HttpResponse stream(InputStream stream, long length, String contentType) {
        this.streamBody = stream;
        this.streamLength = length;
        header("Content-Type", contentType);
        header("Content-Length", String.valueOf(length));
        return this;
    }

    public HttpResponse progress(ProgressListener listener) {
        this.progressListener = listener;
        return this;
    }

    /** Forces {@code Connection: close} - used for streams that may be re-ranged. */
    public HttpResponse closeConnection() {
        this.forceClose = true;
        return this;
    }

    public boolean isStreaming() {
        return streamBody != null;
    }

    public long contentLength() {
        if (streamBody != null) {
            return streamLength;
        }
        return byteBody == null ? 0 : byteBody.length;
    }

    public boolean mustClose() {
        return forceClose;
    }

    /** Writes the response. {@code headOnly} suppresses the body for HEAD requests. */
    public void writeTo(OutputStream out, boolean headOnly, boolean keepAlive) throws IOException {
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(status).append(' ').append(statusText).append("\r\n");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        long length = contentLength();
        if (!headers.containsKey("Content-Length") && streamBody == null) {
            head.append("Content-Length: ").append(length).append("\r\n");
        }
        head.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes("ISO-8859-1"));
        if (headOnly) {
            closeBody();
            return;
        }
        if (streamBody != null) {
            copyStream(out);
            closeBody();
        } else if (byteBody != null) {
            out.write(byteBody);
            if (progressListener != null) {
                progressListener.onBytes(byteBody.length, byteBody.length);
            }
        }
        out.flush();
    }

    private void copyStream(OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long written = 0;
        try {
            int count;
            while ((count = streamBody.read(buffer)) != -1) {
                out.write(buffer, 0, count);
                written += count;
                if (progressListener != null) {
                    progressListener.onBytes(written, count);
                }
            }
        } finally {
            if (streamLength > written) {
                // The caller promised more bytes than the source produced
                // (file changed under us). The client will see a truncated
                // stream, which is the only honest signal available here.
                throw new IOException("stream ended early: " + written + "/" + streamLength);
            }
        }
        out.flush();
    }

    private void closeBody() {
        if (streamBody != null) {
            try {
                streamBody.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
            streamBody = null;
        }
    }
}
