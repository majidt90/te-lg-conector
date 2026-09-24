package com.lgmediabridge.server;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;

import com.lgmediabridge.catalog.MediaCatalog;
import com.lgmediabridge.catalog.MediaItem;
import com.lgmediabridge.compat.CompatResult;
import com.lgmediabridge.compat.MediaCompat;
import com.lgmediabridge.core.Formats;
import com.lgmediabridge.core.LogBus;
import com.lgmediabridge.core.Xml;
import com.lgmediabridge.dlna.ContentDirectory;
import com.lgmediabridge.dlna.DeviceDescription;
import com.lgmediabridge.dlna.Dlna;
import com.lgmediabridge.dlna.SsdpServer;
import com.lgmediabridge.net.HttpRange;
import com.lgmediabridge.net.HttpRequest;
import com.lgmediabridge.net.HttpResponse;
import com.lgmediabridge.net.HttpServer;
import com.lgmediabridge.settings.Settings;
import com.lgmediabridge.stream.StreamRegistry;
import com.lgmediabridge.stream.StreamSession;
import com.lgmediabridge.transcode.AudioTranscoder;
import com.lgmediabridge.transcode.PhotoTranscoder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The media server itself: HTTP endpoints + SSDP presence.
 *
 * Routes:
 * <pre>
 *   GET  /                            human readable status page (diagnostics)
 *   GET  /rootDesc.xml                UPnP device description (announced via SSDP)
 *   GET  /upnp/scpd/*.xml             service descriptions
 *   POST /upnp/control/*              SOAP: ContentDirectory + ConnectionManager
 *   SUBSCRIBE /upnp/event/*           GENA subscription (update notifications)
 *   GET  /media/&lt;id&gt;/&lt;name&gt;.&lt;ext&gt;   the media itself (Range + TimeSeekRange aware)
 *   GET  /thumb/&lt;id&gt;.jpg             JPEG thumbnail for the TV's grid view
 * </pre>
 *
 * Streaming correctness notes:
 *   * every media response advertises {@code Accept-Ranges: bytes} and answers
 *     {@code Range} with 206/Content-Range - this is what makes a webOS seek bar
 *     usable at all;
 *   * {@code contentFeatures.dlna.org} repeats the same DLNA tokens that the
 *     browse response declared, so the renderer's opinion of the resource does
 *     not change when it starts fetching;
 *   * files are streamed straight from the content resolver (or from a converted
 *     cache file); nothing is copied to the TV and nothing is buffered in memory.
 */
public final class MediaServerRuntime {

    private static final String TAG = "Server";

    private final Context context;
    private final Settings settings;
    private final MediaCatalog catalog;
    private final MediaCompat compat;
    private final ContentDirectory contentDirectory;
    private final StreamRegistry streams;
    private final PhotoTranscoder photos;
    private final AudioTranscoder audio;
    private final SsdpServer ssdp;
    private final HttpServer http;
    private final EventSubscriptions subscriptions = new EventSubscriptions();
    private final AtomicLong servedRequests = new AtomicLong();

    private volatile String address;
    private volatile String baseUrl;
    private volatile boolean running;
    private volatile long startedAt;
    private volatile String lastError;

    public MediaServerRuntime(Context context, Settings settings, MediaCatalog catalog,
                              MediaCompat compat, ContentDirectory contentDirectory,
                              StreamRegistry streams, PhotoTranscoder photos, AudioTranscoder audio) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.catalog = catalog;
        this.compat = compat;
        this.contentDirectory = contentDirectory;
        this.streams = streams;
        this.photos = photos;
        this.audio = audio;
        this.http = new HttpServer(this::route);
        this.ssdp = new SsdpServer(settings.deviceUuid(), settings.port());
    }

    // ------------------------------------------------------------ lifecycle

    public void start(String bindAddress, String interfaceName) throws IOException {
        this.address = bindAddress;
        this.baseUrl = "http://" + bindAddress + ":" + settings.port();
        this.lastError = null;
        http.start(bindAddress, settings.port());
        String location = baseUrl + DeviceDescription.PATH_DEVICE;
        ssdp.updateLocation(bindAddress, settings.name());
        ssdp.start(bindAddress, interfaceName, location, serverHeader());
        running = true;
        startedAt = System.currentTimeMillis();
        LogBus.get().i(TAG, "media server up at " + baseUrl);
    }

    public void stop() {
        running = false;
        ssdp.stop();
        http.stop();
        LogBus.get().i(TAG, "media server down");
    }

    /** Called when the LAN address changes: rebind and re-announce, without losing the port. */
    public void rebind(String newAddress, String interfaceName) throws IOException {
        LogBus.get().i(TAG, "rebinding to " + newAddress);
        ssdp.stop();
        http.stop();
        start(newAddress, interfaceName);
    }

    public ServerStatus status() {
        if (!running && lastError != null) {
            return ServerStatus.error(lastError);
        }
        if (!running) {
            return ServerStatus.stopped();
        }
        return ServerStatus.running(address, http.port(), baseUrl, startedAt,
                ssdp.isListening(), ssdp.searchCount(), http.requestCount(),
                http.activeConnections());
    }

    public SsdpServer ssdp() {
        return ssdp;
    }

    public void onLibraryChanged() {
        contentDirectory.onLibraryChanged();
        subscriptions.notifyUpdate(contentDirectory.systemUpdateId(), baseUrl);
    }

    public void setLastError(String message) {
        this.lastError = message;
    }

    private String serverHeader() {
        return "Android/" + android.os.Build.VERSION.RELEASE
                + " UPnP/1.0 MediaBridge/1.0";
    }

    // --------------------------------------------------------------- router

    private void route(HttpRequest request, HttpResponse response) throws IOException {
        String path = request.path == null ? "/" : request.path;
        servedRequests.incrementAndGet();
        if (path.equals("/") || path.isEmpty()) {
            response.header("Content-Type", "text/html; charset=utf-8");
            response.body(infoPage());
            return;
        }
        if (path.equals(DeviceDescription.PATH_DEVICE) || path.equals("/device.xml")) {
            String xml = DeviceDescription.build(settings.deviceUuid(), settings.name(),
                    baseUrl, serverHeader());
            response.header("Content-Type", "text/xml; charset=\"utf-8\"");
            response.body(xml);
            return;
        }
        if (path.equals(DeviceDescription.PATH_CONTENT_SCPD)) {
            response.header("Content-Type", "text/xml; charset=\"utf-8\"");
            response.body(DeviceDescription.contentDirectoryScpd());
            return;
        }
        if (path.equals(DeviceDescription.PATH_CONNECTION_SCPD)) {
            response.header("Content-Type", "text/xml; charset=\"utf-8\"");
            response.body(DeviceDescription.connectionManagerScpd());
            return;
        }
        if (path.equals(DeviceDescription.PATH_CONTENT_CONTROL)
                || path.equals(DeviceDescription.PATH_CONNECTION_CONTROL)) {
            handleSoap(path, request, response);
            return;
        }
        if (path.startsWith("/upnp/event/")) {
            subscriptions.subscribe(request, response, settings.deviceUuid());
            return;
        }
        if (path.startsWith("/media/")) {
            serveMedia(request, response, path);
            return;
        }
        if (path.startsWith("/thumb/")) {
            serveThumbnail(request, response, path);
            return;
        }
        if (path.equals("/favicon.ico")) {
            response.status(204, "No Content");
            return;
        }
        response.status(404, "Not Found");
        response.body("Not found: " + path);
    }

    // ------------------------------------------------------------ SOAP calls

    private void handleSoap(String path, HttpRequest request, HttpResponse response) {
        String soapAction = request.header("soapaction");
        String action = soapAction == null ? "" : soapAction.replace("\"", "").trim();
        int hash = action.indexOf('#');
        if (hash >= 0) {
            action = action.substring(hash + 1);
        }
        String body = request.bodyText();
        String client = request.clientLabel();
        LogBus.get().d(TAG, "SOAP " + action + " from " + client);
        if (action.isEmpty()) {
            soapFault(response, 401, "Invalid Action");
            return;
        }
        if (path.equals(DeviceDescription.PATH_CONNECTION_CONTROL)) {
            handleConnectionManager(action, response);
            return;
        }
        switch (action) {
            case "Browse": {
                String objectId = Xml.element(body, "ObjectID");
                String browseFlag = Xml.element(body, "BrowseFlag");
                String sortCriteria = Xml.element(body, "SortCriteria");
                int startingIndex = Xml.intOrDefault(Xml.element(body, "StartingIndex"), 0);
                int requestedCount = Xml.intOrDefault(Xml.element(body, "RequestedCount"), 0);
                ContentDirectory.BrowseResult result;
                boolean metadata = browseFlag != null && browseFlag.contains("Metadata");
                if (metadata) {
                    result = contentDirectory.browseMetadata(objectId, baseUrl);
                    if (result == null) {
                        soapFault(response, 701, "No such object");
                        return;
                    }
                } else {
                    if (!contentDirectory.isKnownObject(objectId)) {
                        soapFault(response, 701, "No such object");
                        return;
                    }
                    result = contentDirectory.browseChildren(objectId, sortCriteria,
                            startingIndex, requestedCount, baseUrl);
                }
                soapResponse(response, "<u:BrowseResponse xmlns:u=\""
                        + DeviceDescription.CONTENT_DIRECTORY_TYPE + "\">"
                        + tag("Result", Xml.escape(result.didl))
                        + tag("NumberReturned", String.valueOf(result.numberReturned))
                        + tag("TotalMatches", String.valueOf(result.totalMatches))
                        + tag("UpdateID", String.valueOf(result.updateId))
                        + "</u:BrowseResponse>");
                return;
            }
            case "Search": {
                String containerId = Xml.element(body, "ContainerID");
                String criteria = Xml.element(body, "SearchCriteria");
                int startingIndex = Xml.intOrDefault(Xml.element(body, "StartingIndex"), 0);
                int requestedCount = Xml.intOrDefault(Xml.element(body, "RequestedCount"), 0);
                ContentDirectory.BrowseResult result = contentDirectory.search(containerId,
                        criteria, startingIndex, requestedCount, baseUrl);
                soapResponse(response, "<u:SearchResponse xmlns:u=\""
                        + DeviceDescription.CONTENT_DIRECTORY_TYPE + "\">"
                        + tag("Result", Xml.escape(result.didl))
                        + tag("NumberReturned", String.valueOf(result.numberReturned))
                        + tag("TotalMatches", String.valueOf(result.totalMatches))
                        + tag("UpdateID", String.valueOf(result.updateId))
                        + "</u:SearchResponse>");
                return;
            }
            case "GetSortCapabilities":
                soapResponse(response, "<u:GetSortCapabilitiesResponse xmlns:u=\""
                        + DeviceDescription.CONTENT_DIRECTORY_TYPE + "\">"
                        + tag("SortCaps", "dc:title,dc:date,upnp:class,upnp:album")
                        + "</u:GetSortCapabilitiesResponse>");
                return;
            case "GetSearchCapabilities":
                soapResponse(response, "<u:GetSearchCapabilitiesResponse xmlns:u=\""
                        + DeviceDescription.CONTENT_DIRECTORY_TYPE + "\">"
                        + tag("SearchCaps", "dc:title,upnp:class,upnp:artist,upnp:album")
                        + "</u:GetSearchCapabilitiesResponse>");
                return;
            case "GetSystemUpdateID":
                soapResponse(response, "<u:GetSystemUpdateIDResponse xmlns:u=\""
                        + DeviceDescription.CONTENT_DIRECTORY_TYPE + "\">"
                        + tag("Id", String.valueOf(contentDirectory.systemUpdateId()))
                        + "</u:GetSystemUpdateIDResponse>");
                return;
            default:
                soapFault(response, 401, "Invalid Action");
        }
    }

    private void handleConnectionManager(String action, HttpResponse response) {
        switch (action) {
            case "GetProtocolInfo":
                soapResponse(response, "<u:GetProtocolInfoResponse xmlns:u=\""
                        + DeviceDescription.CONNECTION_MANAGER_TYPE + "\">"
                        + tag("Source", Xml.escape(contentDirectory.sourceProtocolInfo()))
                        + tag("Sink", "")
                        + "</u:GetProtocolInfoResponse>");
                return;
            case "GetCurrentConnectionIDs":
                soapResponse(response, "<u:GetCurrentConnectionIDsResponse xmlns:u=\""
                        + DeviceDescription.CONNECTION_MANAGER_TYPE + "\">"
                        + tag("ConnectionIDs", "0")
                        + "</u:GetCurrentConnectionIDsResponse>");
                return;
            case "GetCurrentConnectionInfo":
                soapResponse(response, "<u:GetCurrentConnectionInfoResponse xmlns:u=\""
                        + DeviceDescription.CONNECTION_MANAGER_TYPE + "\">"
                        + tag("RcsID", "0") + tag("AVTransportID", "0")
                        + tag("ProtocolInfo", "") + tag("PeerConnectionManager", "")
                        + tag("PeerConnectionID", "-1") + tag("Direction", "Output")
                        + tag("Status", "OK")
                        + "</u:GetCurrentConnectionInfoResponse>");
                return;
            default:
                soapFault(response, 401, "Invalid Action");
        }
    }

    private void soapResponse(HttpResponse response, String bodyFragment) {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body>" + bodyFragment + "</s:Body></s:Envelope>";
        response.header("Content-Type", "text/xml; charset=\"utf-8\"");
        response.header("EXT", "");
        response.body(xml);
    }

    private void soapFault(HttpResponse response, int code, String description) {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><s:Fault>"
                + "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>"
                + "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">"
                + "<errorCode>" + code + "</errorCode>"
                + "<errorDescription>" + Xml.escape(description) + "</errorDescription>"
                + "</UPnPError></detail></s:Fault></s:Body></s:Envelope>";
        response.status(500, "Internal Server Error");
        response.header("Content-Type", "text/xml; charset=\"utf-8\"");
        response.body(xml);
        LogBus.get().d(TAG, "SOAP fault " + code + " " + description);
    }

    private static String tag(String name, String value) {
        return "<" + name + ">" + value + "</" + name + ">";
    }

    // -------------------------------------------------------------- streaming

    private void serveMedia(HttpRequest request, HttpResponse response, String path) throws IOException {
        String objectId = objectIdFromPath(path);
        if (objectId == null) {
            response.status(404, "Not Found").body("Malformed media URL");
            return;
        }
        if (!isTrusted(request)) {
            LogBus.get().w(TAG, "blocked untrusted client " + request.remoteAddress);
            response.status(403, "Forbidden")
                    .body("This device is not in the trusted list on the phone.");
            return;
        }
        MediaItem item = catalog.byObjectId(objectId);
        if (item == null) {
            response.status(404, "Not Found").body("Unknown media object " + objectId);
            LogBus.get().w(TAG, "requested object " + objectId + " is no longer in the library");
            return;
        }
        rememberClient(request);
        CompatResult result = item.kind == MediaItem.Kind.AUDIO
                ? compat.analyze(context, item) : compat.quick(item);
        if (result.verdict == CompatResult.Verdict.UNSUPPORTED) {
            String message = "This TV cannot play the file directly.\n" + result.reasonText();
            LogBus.get().w(TAG, "refused " + item.displayName + ": "
                    + result.verdict + " (" + request.clientLabel() + ")");
            response.status(415, "Unsupported Media Type")
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .body(message);
            return;
        }

        boolean converted = result.verdict != CompatResult.Verdict.DIRECT;
        String mime = MediaCompat.effectiveMime(item, result);
        boolean seekable = Dlna.seekable(result);
        String profile = Dlna.profileFor(mime, item.kind, false, converted);
        StreamSession session = streams.create(request.remoteAddress, request.clientLabel(),
                item.objectId(), item.title, mime, item.sizeBytes, converted);
        session.setState(converted ? "converting" : "streaming");

        if (result.verdict == CompatResult.Verdict.PHOTO_CONVERT && !settings.photoConvert()) {
            LogBus.get().w(TAG, "refused " + item.displayName()
                    + ": photo conversion is switched off");
            response.status(415, "Unsupported Media Type")
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .body("Photo conversion is turned off in MediaBridge settings.");
            streams.finish(session, 415);
            return;
        }

        try {
            if (result.verdict == CompatResult.Verdict.PHOTO_CONVERT) {
                int maxDimension = PhotoTranscoder.TV_MAX_DIMENSION;
                File file = photos.ensure(item, maxDimension, settings.photoConvert()).file;
                serveFile(request, response, session, file, mime, item, profile, converted, seekable);
            } else if (result.verdict == CompatResult.Verdict.AUDIO_CONVERT) {
                if (!settings.audioConvert() && !audio.isCached(item)) {
                    response.status(415, "Unsupported Media Type")
                            .body("Audio conversion is turned off in MediaBridge settings.");
                    streams.finish(session, 415);
                    return;
                }
                File file = audio.ensureConverted(item, (percent, stage) ->
                        session.setState("converting " + percent + "%"));
                serveFile(request, response, session, file, mime, item, profile, converted, seekable);
            } else {
                serveContent(request, response, session, item, mime, profile, seekable);
            }
            settings.recordStream(item.title);
        } catch (IOException e) {
            LogBus.get().e(TAG, "streaming " + item.displayName + " failed", e);
            streams.finish(session, 500);
            if (!response.isStreaming() && response.status == 200) {
                response.status(500, "Internal Server Error")
                        .body("Could not read the file: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            // e.g. a Range that cannot be satisfied: close the session so the
            // UI does not show a transfer that is no longer happening.
            streams.finish(session, 416);
            throw e;
        }
    }

    private void serveContent(HttpRequest request, HttpResponse response, StreamSession session,
                              MediaItem item, String mime, String profile, boolean seekable)
            throws IOException {
        long totalLength = item.sizeBytes;
        AssetFileDescriptor descriptor = null;
        InputStream stream = null;
        long start = 0;
        try {
            descriptor = context.getContentResolver()
                    .openAssetFileDescriptor(Uri.parse(item.uri), "r");
            long descriptorLength = descriptor == null ? -1 : descriptor.getLength();
            if (descriptorLength > 0) {
                totalLength = descriptorLength;
            }
            HttpRange.ByteRange byteRange = HttpRange.parseBytes(request.header("range"), totalLength);
            HttpRange.TimeRange timeRange = HttpRange.parseTimeSeek(
                    request.header(Dlna.TIME_SEEK_RANGE));
            if (byteRange == null && timeRange != null && item.durationMs > 0 && totalLength > 0) {
                long startBytes = timeRange.startMillis * totalLength / item.durationMs;
                long endBytes = timeRange.endMillis > 0
                        ? Math.min(totalLength - 1, timeRange.endMillis * totalLength / item.durationMs)
                        : totalLength - 1;
                long clampedStart = Math.max(0, Math.min(startBytes, totalLength - 1));
                if (endBytes >= clampedStart) {
                    byteRange = new HttpRange.ByteRange(clampedStart, endBytes);
                }
            }
            if (byteRange != null) {
                start = byteRange.start;
                response.status(206, "Partial Content")
                        .header("Content-Range", HttpRange.contentRange(byteRange.start,
                                byteRange.end, totalLength));
                session.onRangeRequest(start);
            } else if (request.header("range") != null && totalLength > 0) {
                throw new HttpServer.RangeNotSatisfiable(totalLength);
            }

            if ("HEAD".equals(request.method)) {
                finishHeaders(request, response, item, mime, profile, seekable, false,
                        totalLength, byteRange);
                streams.finish(session, 200);
                return;
            }
            if (descriptor == null) {
                throw new IOException("content provider returned no descriptor");
            }
            stream = openStream(descriptor, start);
            long length = byteRange != null ? byteRange.length() : totalLength - start;
            if (length <= 0) {
                throw new IOException("empty resource");
            }
            finishHeaders(request, response, item, mime, profile, seekable, false, totalLength,
                    byteRange);
            response.stream(stream, length, mime)
                    .progress((total, chunk) -> session.onBytes(total, chunk));
            stream = null;
            session.setState("streaming");
            LogBus.get().i(TAG, "streaming " + item.displayName + " to " + request.clientLabel()
                    + (byteRange != null ? " from byte " + start : ""));
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    private void serveFile(HttpRequest request, HttpResponse response, StreamSession session,
                           File file, String mime, MediaItem item, String profile, boolean converted,
                           boolean seekable) throws IOException {
        long totalLength = file.length();
        HttpRange.ByteRange byteRange = HttpRange.parseBytes(request.header("range"), totalLength);
        if (byteRange != null) {
            response.status(206, "Partial Content")
                    .header("Content-Range", HttpRange.contentRange(byteRange.start, byteRange.end,
                            totalLength));
            session.onRangeRequest(byteRange.start);
        } else if (request.header("range") != null) {
            throw new HttpServer.RangeNotSatisfiable(totalLength);
        }
        finishHeaders(request, response, item, mime, profile, seekable, converted, totalLength,
                byteRange);
        if ("HEAD".equals(request.method)) {
            streams.finish(session, 200);
            return;
        }
        RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
        long start = byteRange == null ? 0 : byteRange.start;
        long length = byteRange == null ? totalLength : byteRange.length();
        randomAccessFile.seek(start);
        InputStream stream = new StreamingFileInputStream(randomAccessFile, length);
        response.stream(stream, length, mime)
                .progress((total, chunk) -> session.onBytes(total, chunk));
        LogBus.get().i(TAG, "streaming " + item.displayName + (converted ? " (converted)" : "")
                + " to " + request.clientLabel() + " [" + Formats.bytes(length) + "]");
    }

    private void finishHeaders(HttpRequest request, HttpResponse response, MediaItem item,
                               String mime, String profile, boolean seekable, boolean converted,
                               long totalLength, HttpRange.ByteRange byteRange) {
        String features = Dlna.contentFeatures(mime, seekable, converted, profile);
        response.header("Accept-Ranges", "bytes");
        // Sent unconditionally: players only send the request header on some
        // fetches, and renderers inspect the response tokens when deciding
        // whether the resource is seekable.
        response.header(Dlna.CONTENT_FEATURES, features);
        String transferMode = request.header(Dlna.TRANSFER_MODE);
        response.header(Dlna.TRANSFER_MODE,
                transferMode != null ? transferMode : Dlna.transferMode(item.kind));
        if (seekable && byteRange != null && item.durationMs > 0 && totalLength > 0) {
            long startMillis = byteRange.start * item.durationMs / totalLength;
            long endMillis = byteRange.end * item.durationMs / totalLength;
            response.header(Dlna.TIME_SEEK_RANGE,
                    HttpRange.timeSeekRange(startMillis, endMillis, item.durationMs));
        }
    }

    /** Opens the content stream, positioned at {@code start} when possible. */
    private InputStream openStream(AssetFileDescriptor descriptor, long start) throws IOException {
        if (start <= 0) {
            return descriptor.createInputStream();
        }
        try {
            FileInputStream fileStream = new FileInputStream(
                    descriptor.getParcelFileDescriptor().getFileDescriptor());
            FileChannel channel = fileStream.getChannel();
            channel.position(start);
            return fileStream;
        } catch (Exception e) {
            // Pipe-backed providers cannot be positioned; skip instead.
            InputStream stream = descriptor.createInputStream();
            long skipped = 0;
            while (skipped < start) {
                long step = stream.skip(start - skipped);
                if (step <= 0) {
                    if (stream.read() < 0) {
                        throw new IOException("could not position stream at " + start);
                    }
                    step = 1;
                }
                skipped += step;
            }
            return stream;
        }
    }

    private void serveThumbnail(HttpRequest request, HttpResponse response, String path)
            throws IOException {
        String objectId = objectIdFromPath(path);
        if (!isTrusted(request)) {
            response.status(403, "Forbidden").body("Untrusted device");
            return;
        }
        MediaItem item = objectId == null ? null : catalog.byObjectId(objectId);
        if (item == null) {
            response.status(404, "Not Found").body("Unknown object");
            return;
        }
        byte[] jpeg = null;
        int size = item.kind == MediaItem.Kind.PHOTO ? 256 : 160;
        if (item.kind == MediaItem.Kind.PHOTO) {
            // Prefer the converted JPEG: the TV can decode it (HEIC cannot be).
            File converted = photos.cacheFile(item, PhotoTranscoder.TV_MAX_DIMENSION);
            if (converted.exists()) {
                jpeg = ThumbnailFiles.readAll(converted, 512 * 1024);
            } else if (compat.quick(item).verdict == CompatResult.Verdict.DIRECT) {
                jpeg = com.lgmediabridge.catalog.Thumbnails.jpeg(context, item, 256, 82);
            } else if (settings.photoConvert()) {
                try {
                    File file = photos.ensure(item, PhotoTranscoder.TV_MAX_DIMENSION, true).file;
                    jpeg = ThumbnailFiles.readAll(file, 512 * 1024);
                } catch (IOException e) {
                    LogBus.get().d(TAG, "thumbnail conversion failed: " + e.getMessage());
                }
            }
        } else {
            jpeg = com.lgmediabridge.catalog.Thumbnails.jpeg(context, item, size, 82);
        }
        if (jpeg == null) {
            response.status(404, "Not Found").body("No thumbnail available");
            return;
        }
        response.header("Content-Type", "image/jpeg");
        response.header("Content-Length", String.valueOf(jpeg.length));
        response.body(jpeg);
    }

    private static String objectIdFromPath(String path) {
        String[] parts = path.split("/");
        // "/media/<id>/<name>" and "/thumb/<id>.jpg" both end up with the id at index 2.
        if (parts.length < 3 || parts[2].isEmpty()) {
            return null;
        }
        String candidate = parts[2];
        int dot = candidate.indexOf('.');
        if (path.startsWith("/thumb/") && dot > 0) {
            candidate = candidate.substring(0, dot);
        }
        return candidate.matches("[vpa]\\d+") ? candidate : null;
    }

    /**
     * Records a device that is actively using the library, so the "trusted
     * devices only" switch can be turned on later without breaking a TV that
     * already works.
     */
    private void rememberClient(HttpRequest request) {
        if (settings.trustedOnly() || request.remoteAddress == null) {
            return;
        }
        settings.trustIp(request.remoteAddress);
    }

    private boolean isTrusted(HttpRequest request) {
        if (!settings.trustedOnly()) {
            return true;
        }
        String remote = request.remoteAddress;
        if (remote == null) {
            return false;
        }
        return settings.trustedIps().contains(remote);
    }

    // ---------------------------------------------------------------- info

    private String infoPage() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
          .append("<title>").append(Xml.escape(settings.name())).append("</title>")
          .append("<style>body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;")
          .append("background:#0d1220;color:#e8edf9;margin:0;padding:32px;line-height:1.5}")
          .append("h1{font-size:22px;margin:0 0 4px}p{color:#9fb0cd;margin:4px 0 20px}")
          .append("table{border-collapse:collapse;width:100%;max-width:640px}")
          .append("td{padding:10px 12px;border-bottom:1px solid #1c2536}")
          .append("td:first-child{color:#9fb0cd;width:200px}")
          .append(".ok{color:#4ed08a;font-weight:600}</style></head><body>")
          .append("<h1>").append(Xml.escape(settings.name())).append("</h1>")
          .append("<p>DLNA media server running on this phone. ")
          .append("<span class=\"ok\">Visible to players on this network</span></p><table>")
          .append(row("Address", baseUrl))
          .append(row("Library", catalog.statsLine()))
          .append(row("Device description", baseUrl + DeviceDescription.PATH_DEVICE))
          .append(row("Searches answered", String.valueOf(ssdp.searchCount())))
          .append(row("HTTP requests", String.valueOf(http.requestCount())))
          .append(row("Active streams", String.valueOf(streams.activeCount())))
          .append(row("Uptime", Formats.elapsed(System.currentTimeMillis() - startedAt)))
          .append("</table><p>Open <em>Photos &amp; Videos</em> or <em>Music</em> on the TV, ")
          .append("choose the device list and select this phone.</p></body></html>");
        return sb.toString();
    }

    private static String row(String label, String value) {
        return "<tr><td>" + Xml.escape(label) + "</td><td>" + Xml.escape(value == null ? "—" : value)
                + "</td></tr>";
    }

    /** GENA subscriptions: keeps the TV's browse cache fresh after library changes. */
    private static final class EventSubscriptions {
        private final List<Subscription> subscriptions = new ArrayList<>();

        synchronized void subscribe(HttpRequest request, HttpResponse response, String uuid) {
            String callback = request.header("callback");
            String nt = request.header("nt");
            if (callback == null || nt == null) {
                response.status(412, "Precondition Failed").body("Missing CALLBACK or NT");
                return;
            }
            String sid = "uuid:" + java.util.UUID.randomUUID();
            Subscription subscription = new Subscription(sid,
                    callback.replace("<", "").replace(">", ""), System.currentTimeMillis());
            subscriptions.add(subscription);
            response.status(200, "OK")
                    .header("SID", sid)
                    .header("TIMEOUT", "Second-1800")
                    .header("Content-Length", "0");
            LogBus.get().d(TAG, "event subscription from " + request.remoteAddress + " → " + sid);
        }

        void notifyUpdate(int updateId, String baseUrl) {
            List<Subscription> snapshot;
            synchronized (this) {
                snapshot = new ArrayList<>(subscriptions);
                subscriptions.removeIf(subscription ->
                        System.currentTimeMillis() - subscription.createdAt > 1_800_000L);
            }
            for (Subscription subscription : snapshot) {
                subscription.send(updateId, baseUrl);
            }
        }
    }

    private static final class Subscription {
        final String sid;
        final String callback;
        final long createdAt;
        long sequence;

        Subscription(String sid, String callback, long createdAt) {
            this.sid = sid;
            this.callback = callback;
            this.createdAt = createdAt;
        }

        void send(int updateId, String baseUrl) {
            sequence++;
            String body = "<?xml version=\"1.0\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">"
                    + "<e:property><SystemUpdateID>" + updateId + "</SystemUpdateID></e:property>"
                    + "</e:propertyset>";
            java.net.HttpURLConnection connection = null;
            try {
                java.net.URL url = new java.net.URL(callback);
                connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setRequestMethod("NOTIFY");
                connection.setRequestProperty("CONTENT-TYPE", "text/xml; charset=\"utf-8\"");
                connection.setRequestProperty("NT", "upnp:event");
                connection.setRequestProperty("NTS", "upnp:propchange");
                connection.setRequestProperty("SID", sid);
                connection.setRequestProperty("SEQ", String.valueOf(sequence));
                connection.setDoOutput(true);
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                byte[] payload = body.getBytes("UTF-8");
                connection.setFixedLengthStreamingMode(payload.length);
                connection.getOutputStream().write(payload);
                connection.getResponseCode();
            } catch (Exception e) {
                LogBus.get().d(TAG, "event notify failed: " + e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    /** Reads a small file fully, for thumbnail payloads. */
    static final class ThumbnailFiles {
        static byte[] readAll(File file, long maxBytes) {
            if (file.length() > maxBytes) {
                return null;
            }
            try (FileInputStream stream = new FileInputStream(file)) {
                byte[] buffer = new byte[(int) file.length()];
                int read = 0;
                while (read < buffer.length) {
                    int count = stream.read(buffer, read, buffer.length - read);
                    if (count < 0) {
                        break;
                    }
                    read += count;
                }
                return read == buffer.length ? buffer : null;
            } catch (IOException e) {
                return null;
            }
        }
    }

    /** Bounded reader over a file region, so ranges never read past their end. */
    private static final class StreamingFileInputStream extends InputStream {
        private final RandomAccessFile file;
        private long remaining;

        StreamingFileInputStream(RandomAccessFile file, long length) {
            this.file = file;
            this.remaining = length;
        }

        @Override public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = file.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(length, remaining);
            int read = file.read(buffer, offset, toRead);
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override public int available() {
            return remaining > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
        }

        @Override public void close() throws IOException {
            file.close();
        }
    }
}
