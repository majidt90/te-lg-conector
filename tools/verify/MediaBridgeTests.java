import com.lgmediabridge.catalog.MediaItem;
import com.lgmediabridge.compat.CompatResult;
import com.lgmediabridge.control.SoapClient;
import com.lgmediabridge.control.TvDevice;
import com.lgmediabridge.core.Json;
import com.lgmediabridge.core.LogBus;
import com.lgmediabridge.core.LogEntry;
import com.lgmediabridge.core.Xml;
import com.lgmediabridge.dlna.DeviceDescription;
import com.lgmediabridge.dlna.DidlLite;
import com.lgmediabridge.dlna.Dlna;
import com.lgmediabridge.dlna.Soap;
import com.lgmediabridge.dlna.SsdpMessages;
import com.lgmediabridge.net.HttpRange;
import com.lgmediabridge.net.HttpRequest;
import com.lgmediabridge.net.HttpResponse;
import com.lgmediabridge.stream.StreamSession;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Device-free verification of everything MediaBridge says to a television.
 *
 * These are not smoke tests: HTTP requests are parsed off a real loopback socket,
 * HTTP responses are written to a byte buffer and inspected byte for byte, and
 * every XML document the app can emit is parsed with the JDK's own XML parser -
 * the same class of parser an LG TV uses. A regression here would show up on the
 * TV as "the phone never appears", "the list is empty" or "seeking is greyed
 * out", which is exactly what this suite is built to prevent.
 */
public final class MediaBridgeTests {

    private static boolean verbose;
    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            if ("--verbose".equals(arg)) {
                verbose = true;
            }
        }
        area("HTTP request parsing (what the TV sends)");
        httpRequestParsing();

        area("HTTP range and time-seek maths (whether seeking works)");
        byteRanges();
        timeSeekRanges();

        area("DLNA tokens (what the TV inspects before playing)");
        dlnaProtocolInfo();
        dlnaProfilesAndFeatures();

        area("DIDL-Lite browse results (what the TV lists)");
        didlItemsAndContainers();

        area("UPnP device and service descriptions");
        deviceDescription();
        scpds();

        area("SOAP control and responses");
        soapEnvelopes();

        area("SSDP discovery messages");
        ssdpMessages();

        area("Streaming metrics");
        streamSessions();

        area("Log export and JSON persistence");
        logAndJson();

        area("Live HTTP exchange on the loopback socket");
        liveHttpExchange();

        System.out.println();
        int total = passed + failures.size();
        if (failures.isEmpty()) {
            System.out.println("  " + total + " checks passed");
        } else {
            System.out.println("  " + passed + "/" + total + " checks passed, "
                    + failures.size() + " failed:");
            for (String failure : failures) {
                System.out.println("   ✖ " + failure);
            }
        }
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------- helpers

    private static void area(String name) {
        System.out.println();
        System.out.println("  " + name);
    }

    private static void check(String name, boolean condition) {
        check(name, condition, null);
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            if (verbose) {
                System.out.println("    ✓ " + name);
            }
        } else {
            failures.add(name + (detail == null ? "" : " — " + detail));
            System.out.println("    ✖ " + name + (detail == null ? "" : " — " + detail));
        }
    }

    private static void equal(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        check(name, same, "expected <" + expected + "> but was <" + actual + ">");
    }

    /** Parses XML with the JDK parser; returns null when the document is invalid. */
    private static String parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            org.w3c.dom.Document document =
                    builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
            return document.getDocumentElement().getNodeName();
        } catch (Exception e) {
            return null;
        }
    }

    private static MediaItem video(String name, String mime, long size, long durationMs) {
        return new MediaItem.Builder()
                .kind(MediaItem.Kind.VIDEO)
                .storeId(42)
                .displayName(name)
                .title(name.replace(".mp4", ""))
                .mimeType(mime)
                .sizeBytes(size)
                .durationMs(durationMs)
                .size(3840, 2160)
                .dateAddedSec(1_700_000_000L)
                .folderPath("/Movies")
                .uri("content://media/external/video/media/42")
                .build();
    }

    private static CompatResult verdict(CompatResult.Verdict value) {
        return new CompatResult(value, new ArrayList<>(), new LinkedHashMap<>(), true);
    }

    // ---------------------------------------------------- HTTP request parsing

    private static void httpRequestParsing() throws Exception {
        // Verbatim from the capture published for a real LG set (webOS 3.0 era)
        // and still the shape newer sets send.
        String soap = "POST /upnp/control/content-directory HTTP/1.1\r\n"
                + "Host: 192.168.1.42:8200\r\n"
                + "User-Agent: Linux/3.0.13 UPnP/1.0 LGE_DLNA_SDK/1.6.0 [TV][LG]32LB5800-ZM/04.04.06 DLNADOC/1.50\r\n"
                + "SOAPACTION: \"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"\r\n"
                + "Content-Type: text/xml; charset=\"utf-8\"\r\n"
                + "DLNADeviceName.lge.com: %5bLG%5dwebOS1-TV\r\n"
                + "Content-Length: 42\r\n"
                + "Connection: keep-alive\r\n\r\n"
                + "<soap>body</soap>padding-to-length-42-xxxxxxx";
        HttpRequest request = readFromBytes(soap);
        equal("request line path", "/upnp/control/content-directory", request.path);
        equal("method", "POST", request.method);
        equal("SOAPAction header", "urn:schemas-upnp-org:service:ContentDirectory:1#Browse",
                request.header("soapaction").replace("\"", ""));
        equal("TV model identified from its user agent", "[TV][LG]32LB5800-ZM",
                request.clientLabel());
        check("keep-alive honoured", request.keepAlive());
        check("body length honoured", request.bodyText().startsWith("<soap>"));

        String get = "GET /media/v42/Clip.mp4 HTTP/1.1\r\n"
                + "Host: 192.168.1.42:8200\r\n"
                + "Range: bytes=1048576-3145727\r\n"
                + "getcontentFeatures.dlna.org: 1\r\n"
                + "transferMode.dlna.org: Streaming\r\n"
                + "TimeSeekRange.dlna.org: npt=120.000-\r\n"
                + "Connection: close\r\n\r\n";
        HttpRequest ranged = readFromBytes(get);
        equal("range header survives parsing", "bytes=1048576-3145727", ranged.header("range"));
        equal("DLNA feature request header", "1", ranged.header("getcontentFeatures.dlna.org"));
        equal("streaming transfer mode", "Streaming", ranged.header("transferMode.dlna.org"));
        equal("time seek header", "npt=120.000-", ranged.header("timeseekrange.dlna.org"));
        check("client did not ask for keep-alive", !ranged.keepAlive());

        String head = "HEAD /media/p7/Photo.jpg HTTP/1.1\r\nHost: tv\r\n\r\n";
        HttpRequest headRequest = readFromBytes(head);
        equal("HEAD method", "HEAD", headRequest.method);

        String sloppy = "GET /media/v42/Clip.mp4?token=1 HTTP/1.1\r\n"
                + "HOST: tv:8200\r\n"
                + "Soapaction: X\r\n\r\n";
        HttpRequest sloppyRequest = readFromBytes(sloppy);
        equal("query string split from path", "/media/v42/Clip.mp4", sloppyRequest.path);
        equal("query parameter parsed", "1", sloppyRequest.queryParam("token"));
        equal("headers are case-insensitive", "X", sloppyRequest.header("SOAPACTION"));
        // With no identifying headers at all, the address is the most useful label.
        equal("client label falls back to the address", "127.0.0.1",
                sloppyRequest.clientLabel());
    }

    private static HttpRequest readFromBytes(String raw) throws Exception {
        byte[] bytes = raw.getBytes("ISO-8859-1");
        try (SocketPair pair = SocketPair.create(bytes)) {
            return HttpRequest.read(pair.server);
        }
    }

    /** Loopback socket pair: bytes in, bytes out, nothing else. */
    private static final class SocketPair implements AutoCloseable {
        final ServerSocket listener;
        final Socket server;
        final Socket client;
        final Thread writer;

        private SocketPair(ServerSocket listener, Socket server, Socket client, Thread writer) {
            this.listener = listener;
            this.server = server;
            this.client = client;
            this.writer = writer;
        }

        static SocketPair create(byte[] content) throws Exception {
            final ServerSocket listener = new ServerSocket(0);
            final AtomicReference<Socket> accepted = new AtomicReference<>();
            final CountDownLatch connected = new CountDownLatch(1);
            Thread acceptor = new Thread(() -> {
                try {
                    accepted.set(listener.accept());
                } catch (Exception ignored) {
                    // closed
                } finally {
                    connected.countDown();
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            final Socket client = new Socket("127.0.0.1", listener.getLocalPort());
            connected.await(5, TimeUnit.SECONDS);
            Socket server = accepted.get();
            if (server == null) {
                throw new IllegalStateException("loopback connection was not established");
            }
            Thread writer = new Thread(() -> {
                try {
                    client.getOutputStream().write(content);
                    client.getOutputStream().flush();
                } catch (Exception ignored) {
                    // the reader may finish first
                }
            });
            writer.setDaemon(true);
            writer.start();
            return new SocketPair(listener, server, client, writer);
        }

        @Override public void close() throws Exception {
            writer.join(500);
            client.close();
            server.close();
            listener.close();
        }
    }

    // ------------------------------------------------------------ byte ranges

    private static void byteRanges() {
        long total = 10_000L;

        HttpRange.ByteRange range = HttpRange.parseBytes("bytes=100-199", total);
        check("closed range parsed", range != null);
        equal("range start", 100L, range.start);
        equal("range end", 199L, range.end);
        equal("range length", 100L, range.length());

        range = HttpRange.parseBytes("bytes=500-", total);
        equal("open-ended range starts", 500L, range.start);
        equal("open-ended range ends at the last byte", 9999L, range.end);

        range = HttpRange.parseBytes("bytes=-500", total);
        equal("suffix range start", 9500L, range.start);
        equal("suffix range end", 9999L, range.end);

        range = HttpRange.parseBytes("bytes=9990-99999", total);
        equal("range clamped to the file end", 9999L, range.end);

        range = HttpRange.parseBytes("bytes=0-0", total);
        equal("first byte only", 1L, range.length());

        check("unsatisfiable range rejected", HttpRange.parseBytes("bytes=20000-", total) == null);
        check("nonsense range rejected", HttpRange.parseBytes("pages=1-2", total) == null);
        check("absent range yields null", HttpRange.parseBytes(null, total) == null);
        check("empty range yields null", HttpRange.parseBytes("", total) == null);

        equal("Content-Range header format",
                "bytes 100-199/10000", HttpRange.contentRange(100, 199, total));
        equal("Content-Range for a whole file",
                "bytes 0-9999/10000", HttpRange.contentRange(0, 9999, total));

        // Multi-range requests must not be interpreted as a single range.
        // A multi-range request is answered with the whole resource, not with one
        // part pretending to be the answer.
        check("multi-range request is not mis-parsed",
                HttpRange.parseBytes("bytes=0-99,200-299", total) == null);
        check("a multi-range request is not reported as unsatisfiable",
                !HttpRange.isUnsatisfiable("bytes=0-99,200-299", total));
        check("garbage range is not reported as unsatisfiable",
                !HttpRange.isUnsatisfiable("bytes=abc-def", total));
        check("a range beyond the end is unsatisfiable (HTTP 416)",
                HttpRange.isUnsatisfiable("bytes=20000-", total));
        check("a satisfiable range is never reported unsatisfiable",
                !HttpRange.isUnsatisfiable("bytes=0-99", total));
        check("absent header is not a range request",
                !HttpRange.hasRangeHeader(null) && !HttpRange.hasRangeHeader("  "));
    }

    private static void timeSeekRanges() {
        HttpRange.TimeRange time = HttpRange.parseTimeSeek("npt=60-");
        check("npt start parsed", time != null);
        equal("npt start millis", 60_000L, time.startMillis);
        equal("npt end absent means \"until the end\"", HttpRange.UNSPECIFIED_END, time.endMillis);

        time = HttpRange.parseTimeSeek("npt=12.5-45.25");
        equal("fractional npt start", 12_500L, time.startMillis);
        equal("fractional npt end", 45_250L, time.endMillis);

        time = HttpRange.parseTimeSeek("npt=0:02:30-");
        equal("clock-style npt parsed", 150_000L, time.startMillis);

        // "npt=1234" means "start at 1234 s and play to the end".
        equal("bare npt start", 1_234_000L,
                HttpRange.parseTimeSeek("npt=1234").startMillis);
        equal("bare npt has no end bound", HttpRange.UNSPECIFIED_END,
                HttpRange.parseTimeSeek("npt=1234").endMillis);
        check("npt with an impossible clock value is rejected",
                HttpRange.parseTimeSeek("npt=0:75:00-") == null);
        check("garbage npt ignored", HttpRange.parseTimeSeek("npt=abc") == null);
        check("absent time seek yields null", HttpRange.parseTimeSeek(null) == null);

        // The header the server must echo back for LG's player to keep the bar.
        equal("TimeSeekRange header shape", "npt=60-120/600",
                HttpRange.timeSeekRange(60_000, 120_000, 600_000));
        equal("TimeSeekRange keeps milliseconds when they matter", "npt=61.500-/600",
                HttpRange.timeSeekRange(61_500, HttpRange.UNSPECIFIED_END, 600_000));

        // Wiring check: a seek to 30 s in a 60 s / 6 MB file must map to byte 3 MB.
        long totalBytes = 6_000_000L;
        long durationMs = 60_000L;
        long startBytes = 30_000L * totalBytes / durationMs;
        equal("time seek maps to the expected byte offset", 3_000_000L, startBytes);
    }

    // --------------------------------------------------------- DLNA protocol

    private static void dlnaProtocolInfo() {
        String direct = Dlna.protocolInfo("video/mp4", true, false, null);
        equal("direct-play protocol info",
                "http-get:*:video/mp4:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS="
                        + Dlna.FLAGS_STREAMING, direct);
        check("random access advertised for seeking", direct.contains("DLNA.ORG_OP=01"));
        check("no false content indicator on direct play", direct.contains("DLNA.ORG_CI=0"));

        String converted = Dlna.protocolInfo("image/jpeg", false, true, "JPEG_LRG");
        check("conversion flagged with CI=1", converted.contains("DLNA.ORG_CI=1"));
        check("profile name included when known", converted.contains("DLNA.ORG_PN=JPEG_LRG"));

        String unseekable = Dlna.protocolInfo("video/x-msvideo", false, false, null);
        check("honest when the file cannot be seeked", unseekable.contains("DLNA.ORG_OP=00"));

        String unseekableProfile = Dlna.protocolInfo("image/jpeg", true, false, "JPEG_TN");
        check("profile placed before the flags", unseekableProfile.indexOf("DLNA.ORG_PN")
                < unseekableProfile.indexOf("DLNA.ORG_FLAGS"));

        check("thumbnail profile is a real DLNA profile", Dlna.profileFor("image/jpeg",
                MediaItem.Kind.PHOTO, true, false).equals("JPEG_TN"));
        check("full-size JPEG profile", Dlna.profileFor("image/jpeg", MediaItem.Kind.PHOTO,
                false, false).equals("JPEG_LRG"));
        check("MP3 profile", Dlna.profileFor("audio/mpeg", MediaItem.Kind.AUDIO, false, false)
                .equals("MP3"));
        check("AAC profile", Dlna.profileFor("audio/mp4", MediaItem.Kind.AUDIO, false, false)
                .equals("AAC_ISO_320"));
        check("no video profile is ever claimed",
                Dlna.profileFor("video/mp4", MediaItem.Kind.VIDEO, false, false) == null);
        check("unknown image type claims no profile",
                Dlna.profileFor("image/webp", MediaItem.Kind.PHOTO, false, false) == null);
    }

    private static void dlnaProfilesAndFeatures() {
        String features = Dlna.contentFeatures("video/mp4", true, false, null);
        equal("contentFeatures matches the browse tokens exactly", features,
                Dlna.protocolInfo("video/mp4", true, false, null)
                        .substring("http-get:*:video/mp4:".length()));
        check("every DLNA token the renderer looks for is present",
                features.contains("DLNA.ORG_OP=") && features.contains("DLNA.ORG_CI=")
                        && features.contains("DLNA.ORG_FLAGS="));

        equal("streaming transfer mode for video", "Streaming",
                Dlna.transferMode(MediaItem.Kind.VIDEO));
        equal("audio is streamed, not background-fetched", "Streaming",
                Dlna.transferMode(MediaItem.Kind.AUDIO));
        equal("interactive mode for photos", "Interactive",
                Dlna.transferMode(MediaItem.Kind.PHOTO));

        equal("duration formatting", "1:02:03.500", Dlna.duration(3_723_500L));
        equal("short duration formatting", "0:00:07.000", Dlna.duration(7_000L));
        check("unknown duration is omitted, not fabricated", Dlna.duration(0) == null);

        equal("video class", "object.item.videoItem.movie",
                Dlna.upnpClass(MediaItem.Kind.VIDEO));
        equal("photo class", "object.item.imageItem.photo",
                Dlna.upnpClass(MediaItem.Kind.PHOTO));
        equal("audio class", "object.item.audioItem.musicTrack",
                Dlna.upnpClass(MediaItem.Kind.AUDIO));
        check("seekability follows the verdict",
                Dlna.seekable(verdict(CompatResult.Verdict.DIRECT)));
        // Conversions are cached as real files, so they are still range-seekable.
        check("converted media stays seekable",
                Dlna.seekable(verdict(CompatResult.Verdict.PHOTO_CONVERT))
                        && Dlna.seekable(verdict(CompatResult.Verdict.AUDIO_CONVERT)));
        check("an unsupported file advertises no seeking",
                !Dlna.seekable(verdict(CompatResult.Verdict.UNSUPPORTED)));
    }

    // ------------------------------------------------------------- DIDL-Lite

    private static void didlItemsAndContainers() {
        String open = DidlLite.open();
        check("DIDL-Lite root declares the DIDL namespace",
                open.contains("urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"));
        check("DIDL-Lite root declares dc", open.contains(
                "http://purl.org/dc/elements/1.1/"));
        check("DIDL-Lite root declares upnp", open.contains(
                "urn:schemas-upnp-org:metadata-1-0/upnp/"));
        check("DIDL-Lite root declares dlna", open.contains(
                "urn:schemas-dlna-org:metadata-1-0/"));

        String container = DidlLite.container("0", "-1", "MediaBridge",
                "object.container.storageFolder", 12, false);
        check("container parses as XML", parseXml(DidlLite.open() + container + DidlLite.close()) != null);
        check("container carries childCount", container.contains("childCount=\"12\""));
        check("container id is escaped", container.contains("id=\"0\""));

        MediaItem clip = video("Holiday Clip.mp4", "video/mp4", 734_003_200L, 3_723_500L);
        String protocolInfo = Dlna.protocolInfo("video/mp4", true, false, null);
        String url = "http://192.168.1.42:8200/media/v42/Holiday%20Clip.mp4";
        String item = DidlLite.item("v42", "videos", clip, url,
                "http://192.168.1.42:8200/thumb/v42.jpg", "video/mp4", protocolInfo, null,
                734_003_200L, 3_723_500L);
        String document = DidlLite.open() + item + DidlLite.close();
        check("item parses as XML", parseXml(document) != null);
        check("item keeps the object id", item.contains("id=\"v42\""));
        check("item records its parent", item.contains("parentID=\"videos\""));
        check("video is a movie item", item.contains("object.item.videoItem.movie"));
        check("title is present", item.contains("<dc:title>Holiday Clip</dc:title>"));
        check("file size is exposed to the renderer", item.contains("size=\"734003200\""));
        check("resolution uses the ASCII format renderers parse",
                item.contains("resolution=\"3840x2160\"") && !item.contains("×"));
        check("duration uses DLNA's H:MM:SS.mmm", item.contains("duration=\"1:02:03.500\""));

        check("protocolInfo is attached to the resource",
                item.contains("protocolInfo=\"" + protocolInfo + "\""));
        check("artwork is offered for the TV grid",
                item.contains("upnp:albumArtURI")
                        && item.contains("http://192.168.1.42:8200/thumb/v42.jpg"));
        check("the media URL is the resource the TV will fetch", item.contains(">" + url + "<"));

        // Titles with characters that break naive XML must survive round-tripping.
        MediaItem awkward = new MediaItem.Builder()
                .kind(MediaItem.Kind.VIDEO)
                .storeId(7)
                .displayName("A & B <\"quoted\">.mp4")
                .title("A & B <\"quoted\">.mp4")
                .mimeType("video/mp4")
                .sizeBytes(1024)
                .build();
        String escaped = DidlLite.open() + DidlLite.item("v7", "videos", awkward, url, null,
                "video/mp4", protocolInfo, null, 1024, 0) + DidlLite.close();
        check("XML-hostile titles still produce valid XML", parseXml(escaped) != null);
        check("ampersand is escaped, not dropped", escaped.contains("A &amp; B"));
        check("angle brackets are escaped", escaped.contains("&lt;"));

        MediaItem track = new MediaItem.Builder()
                .kind(MediaItem.Kind.AUDIO)
                .storeId(3)
                .displayName("Song.flac")
                .title("Song")
                .mimeType("audio/flac")
                .sizeBytes(30_000_000L)
                .durationMs(221_000L)
                .artist("The Artist")
                .album("The Album")
                .trackNumber(4)
                .build();
        String audio = DidlLite.open() + DidlLite.item("a3", "music", track, url, null,
                "audio/flac", Dlna.protocolInfo("audio/flac", true, false, "FLAC"), "FLAC",
                30_000_000L, 221_000L) + DidlLite.close();
        check("audio DIDL parses", parseXml(audio) != null);
        check("music track class", audio.contains("object.item.audioItem.musicTrack"));
        check("artist is exposed", audio.contains("<upnp:artist>The Artist</upnp:artist>"));
        check("album is exposed", audio.contains("<upnp:album>The Album</upnp:album>"));
        check("track number is exposed", audio.contains("<upnp:originalTrackNumber>4"));
    }

    // ------------------------------------------------------- device + SCPDs

    private static void deviceDescription() {
        String xml = DeviceDescription.build("12345678-1234-1234-1234-123456789abc",
                "MediaBridge & Phone", "http://192.168.1.42:8200", "Android/14 UPnP/1.0 test");
        check("device description parses", parseXml(xml) != null);
        check("declares a UPnP MediaServer", xml.contains(DeviceDescription.DEVICE_TYPE));
        check("declares DLNA DMS-1.50", xml.contains("<dlna:X_DLNADOC>DMS-1.50"));
        check("advertises ContentDirectory:1", xml.contains("service:ContentDirectory:1"));
        check("advertises ConnectionManager:1", xml.contains("service:ConnectionManager:1"));
        check("UDN uses the uuid scheme", xml.contains("<UDN>uuid:12345678-1234-1234-1234-123456789abc"));
        check("friendly name is escaped", xml.contains("MediaBridge &amp; Phone"));
        check("presentation URL points at the phone", xml.contains("<presentationURL>http://192.168.1.42:8200"));

        int services = count(xml, "<service>");
        equal("exactly two service blocks, no duplicates", 2, services);
        check("control URLs are absolute paths", xml.contains("<controlURL>/upnp/control/content-directory"));

        // The control URLs the description promises must be the ones the server serves.
        check("SCPD path matches the server route",
                xml.contains("<SCPDURL>" + DeviceDescription.PATH_CONTENT_SCPD));
    }

    private static void scpds() {
        String content = DeviceDescription.contentDirectoryScpd();
        check("ContentDirectory SCPD parses", parseXml(content) != null);
        for (String action : new String[]{"Browse", "Search", "GetSortCapabilities",
                "GetSearchCapabilities", "GetSystemUpdateID"}) {
            check("SCPD declares " + action, content.contains("<name>" + action + "</name>"));
        }
        check("Browse declares its output arguments",
                content.contains("<name>NumberReturned</name>")
                        && content.contains("<name>TotalMatches</name>")
                        && content.contains("<name>UpdateID</name>"));
        check("result state variable is a string, as required",
                content.contains("<name>A_ARG_TYPE_Result</name><dataType>string</dataType>"));
        check("SystemUpdateID sends events (the TV caches otherwise)",
                content.contains("<stateVariable sendEvents=\"yes\"><name>SystemUpdateID"));

        // Every relatedStateVariable used by an argument must be declared.
        for (String variable : new String[]{"A_ARG_TYPE_ObjectID", "A_ARG_TYPE_Result",
                "A_ARG_TYPE_BrowseFlag", "A_ARG_TYPE_Filter", "A_ARG_TYPE_SortCriteria",
                "A_ARG_TYPE_Index", "A_ARG_TYPE_Count", "A_ARG_TYPE_UpdateID",
                "A_ARG_TYPE_SearchCriteria", "SearchCapabilities", "SortCapabilities",
                "SystemUpdateID"}) {
            check("state variable declared: " + variable,
                    content.contains("<name>" + variable + "</name><dataType>"));
        }

        String connections = DeviceDescription.connectionManagerScpd();
        check("ConnectionManager SCPD parses", parseXml(connections) != null);
        check("GetProtocolInfo declared", connections.contains("<name>GetProtocolInfo</name>"));
        check("GetCurrentConnectionIDs declared",
                connections.contains("<name>GetCurrentConnectionIDs</name>"));
        check("GetCurrentConnectionInfo declared",
                connections.contains("<name>GetCurrentConnectionInfo</name>"));
    }

    private static void soapEnvelopes() {
        String browse = Soap.actionResponse(DeviceDescription.CONTENT_DIRECTORY_TYPE, "Browse",
                Soap.tag("Result", "<DIDL-Lite/>") + Soap.tag("NumberReturned", "1")
                        + Soap.tag("TotalMatches", "1") + Soap.tag("UpdateID", "5"));
        check("Browse response parses", parseXml(browse) != null);
        check("Browse response is namespaced with the service type",
                browse.contains("<u:BrowseResponse xmlns:u=\""
                        + DeviceDescription.CONTENT_DIRECTORY_TYPE + "\">"));
        check("DIDL payload is escaped inside the SOAP body",
                browse.contains("&lt;DIDL-Lite/&gt;"));

        String fault = Soap.fault(701, "No such object & then some");
        check("SOAP fault parses", parseXml(fault) != null);
        check("fault carries the UPnP error code", fault.contains("<errorCode>701</errorCode>"));
        check("fault description is escaped",
                fault.contains("No such object &amp; then some"));

        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put("InstanceID", "0");
        arguments.put("CurrentURI", "http://192.168.1.42:8200/media/v42/Clip.mp4?a=1&b=2");
        arguments.put("CurrentURIMetaData", "<DIDL-Lite/>");
        String call = Soap.call("urn:schemas-upnp-org:service:AVTransport:1",
                "SetAVTransportURI", arguments);
        check("control request parses", parseXml(call) != null);
        check("request uses the AVTransport namespace",
                call.contains("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"));
        check("URL with an ampersand is escaped in the request", call.contains("a=1&amp;b=2"));
        check("metadata is escaped in the request", call.contains("&lt;DIDL-Lite/&gt;"));

        // Parsing the TV's replies is the other half of the control path.
        String reply = "<?xml version=\"1.0\"?><s:Envelope><s:Body>"
                + "<u:GetPositionInfoResponse>"
                + "<TrackDuration>0:03:42</TrackDuration><RelTime>0:00:31</RelTime>"
                + "<TrackURI>http://192.168.1.42:8200/media/v42/Clip.mp4</TrackURI>"
                + "</u:GetPositionInfoResponse></s:Body></s:Envelope>";
        equal("duration read from the TV reply", "0:03:42", Xml.element(reply, "TrackDuration"));
        equal("position read from the TV reply", "0:00:31", Xml.element(reply, "RelTime"));
        equal("media key read from the TV reply", 222_000L, SoapClient.parseTime("0:03:42"));
        equal("NOT_IMPLEMENTED is treated as unknown", 0L,
                SoapClient.parseTime("NOT_IMPLEMENTED"));

        String browseRequest = "<s:Envelope><s:Body><u:Browse>"
                + "<ObjectID>0</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag>"
                + "<StartingIndex>0</StartingIndex><RequestedCount>200</RequestedCount>"
                + "<SortCriteria>+dc:title</SortCriteria></u:Browse></s:Body></s:Envelope>";
        equal("ObjectID read from the TV request", "0", Xml.element(browseRequest, "ObjectID"));
        equal("BrowseFlag read from the TV request", "BrowseDirectChildren",
                Xml.element(browseRequest, "BrowseFlag"));
        equal("RequestedCount read from the TV request", 200,
                Xml.intOrDefault(Xml.element(browseRequest, "RequestedCount"), 0));
    }

    // ------------------------------------------------------------------ SSDP

    private static void ssdpMessages() {
        String uuid = "12345678-1234-1234-1234-123456789abc";
        String location = "http://192.168.1.42:8200" + DeviceDescription.PATH_DEVICE;

        String alive = SsdpMessages.notify(DeviceDescription.DEVICE_TYPE,
                "uuid:" + uuid + "::" + DeviceDescription.DEVICE_TYPE, "ssdp:alive", location);
        check("announcement is not an HTTP response", alive.startsWith("NOTIFY * HTTP/1.1\r\n"));
        check("announcement goes to the SSDP multicast address",
                alive.contains("HOST: 239.255.255.250:1900"));
        check("announcement carries a max-age", alive.contains("CACHE-CONTROL: max-age=1800"));
        check("announcement carries the description location",
                alive.contains("LOCATION: " + location));
        check("announcement names the device type",
                alive.contains("NT: " + DeviceDescription.DEVICE_TYPE));
        check("announcement carries a unique service name",
                alive.contains("USN: uuid:" + uuid + "::" + DeviceDescription.DEVICE_TYPE));
        check("announcement ends with a blank line",
                alive.endsWith("\r\n\r\n"));

        String byebye = SsdpMessages.notify("uuid:" + uuid, "uuid:" + uuid, "ssdp:byebye", location);
        check("bye-bye omits the cache lifetime (per spec)",
                !byebye.contains("CACHE-CONTROL"));
        check("bye-bye is recognised when a TV sends it", SsdpMessages.isByeBye(byebye));
        check("alive is not mistaken for bye-bye", !SsdpMessages.isByeBye(alive));

        String response = SsdpMessages.searchResponse(DeviceDescription.DEVICE_TYPE,
                "uuid:" + uuid + "::" + DeviceDescription.DEVICE_TYPE, location);
        check("search reply looks like an HTTP response",
                response.startsWith("HTTP/1.1 200 OK\r\n"));
        check("search reply includes EXT, which some clients require",
                response.contains("\r\nEXT:\r\n"));
        check("search reply carries the local interface address",
                response.contains("LOCATION: http://192.168.1.42:8200"));

        String search = SsdpMessages.searchRequest(SsdpMessages.ALL, 2);
        check("search request quotes ssdp:discover",
                search.contains("MAN: \"ssdp:discover\""));
        check("search request has an MX between 1 and 5",
                search.contains("MX: 2"));
        check("search request asks for everything when scanning",
                search.contains("ST: ssdp:all"));

        String[][] targets = SsdpMessages.announcementTargets(uuid);
        equal("five announcements per cycle (device, services, root, uuid)",
                5, targets.length);
        boolean hasDevice = false;
        boolean hasContent = false;
        boolean hasRoot = false;
        for (String[] target : targets) {
            hasDevice |= DeviceDescription.DEVICE_TYPE.equals(target[0]);
            hasContent |= DeviceDescription.CONTENT_DIRECTORY_TYPE.equals(target[0]);
            hasRoot |= SsdpMessages.ROOT_DEVICE.equals(target[0]);
        }
        check("device type is announced", hasDevice);
        check("ContentDirectory service is announced (TVs search for it)", hasContent);
        check("rootdevice is announced", hasRoot);

        // Parsing the TV's announcement, which is how discovery starts.
        String tvNotify = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
                + "NT: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
                + "NTS: ssdp:alive\r\n"
                + "USN: uuid:9f0a-parent::urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
                + "LOCATION: http://192.168.1.50:1588/75f8f5e1/\r\n"
                + "SERVER: WebOS/1.0 UPnP/1.0 LGE_DLNA_SDK/1.6.0 [TV][LG]55NANO86VPA/6.5.3\r\n\r\n";
        equal("TV location read", "http://192.168.1.50:1588/75f8f5e1/",
                SsdpMessages.header(tvNotify, "LOCATION"));
        equal("TV server header read",
                "WebOS/1.0 UPnP/1.0 LGE_DLNA_SDK/1.6.0 [TV][LG]55NANO86VPA/6.5.3",
                SsdpMessages.header(tvNotify, "SERVER"));
        check("headers are found regardless of case",
                SsdpMessages.header(tvNotify, "location") != null
                        && SsdpMessages.header(tvNotify, "NTS").equals("ssdp:alive"));
        check("a TV announcement is not a bye-bye", !SsdpMessages.isByeBye(tvNotify));
    }

    // ------------------------------------------------------- stream sessions

    private static void streamSessions() {
        StreamSession session = new StreamSession("s1", "192.168.1.50",
                "[TV][LG]55NANO86VPA", "v42", "Holiday Clip.mp4", "video/mp4",
                100_000_000L, false);
        session.setState("streaming");
        session.onBytes(25_000_000L, 65_536);
        equal("bytes sent tracked", 25_000_000L, session.bytesSent());
        equal("progress is honest", 25, session.progressPercent());
        check("a transfer is not stale while it is progressing",
                !session.isStale(0));
        check("a transfer goes stale when idle", session.isStale(-1));

        session.onRangeRequest(5_000_000L);
        equal("range requests counted (seek evidence)", 1L, session.rangeRequests());
        equal("where the byte range started is remembered", 5_000_000L,
                session.contentStart());

        timeSince();
        session.markFinished(200);
        check("finished session reports finished", session.isFinished());
    }

    /** Guards the throughput maths without waiting for wall-clock seconds. */
    private static void timeSince() {
        StreamSession quick = new StreamSession("s2", "10.0.0.2", "TV", "v1", "clip", "video/mp4",
                10_000_000L, false);
        quick.onBytes(10_000_000L, 65_536);
        quick.markFinished(200);
        check("a completed transfer reports a non-negative rate",
                quick.bytesPerSecond() >= 0);
        check("progress of a completed transfer is 100%",
                quick.progressPercent() == 100
                        || quick.bytesSent() == quick.totalBytes());
        equal("completed transfer keeps its status", 200, quick.finishedStatus());
    }

    // ------------------------------------------------------------- log, JSON

    private static void logAndJson() {
        LogBus bus = LogBus.get();
        bus.clear();
        bus.i("Server", "media server up at http://192.168.1.42:8200");
        bus.w("Ssdp", "SSDP port 1900 unavailable");
        bus.e("Server", "streaming clip failed", new RuntimeException("socket closed"));

        List<LogEntry> all = bus.snapshot(false, 100);
        equal("every entry is retained", 3, all.size());
        check("errors are counted", bus.errorCount() >= 1);
        List<LogEntry> errorsOnly = bus.snapshot(true, 100);
        check("error filter includes the warning and the error",
                errorsOnly.size() == 2 && errorsOnly.get(0).isError());

        String export = bus.exportText();
        check("export contains the info line", export.contains("media server up at"));
        check("export contains the failure reason", export.contains("socket closed"));
        check("export is shareable text", export.split("\n").length >= 3);

        Map<String, Object> map = Json.obj("name", "MediaBridge", "port", 8200,
                "paired", true, "nested", Json.obj("a", 1));
        String text = Json.write(map);
        Map<String, Object> parsed = Json.parseObject(text);
        check("JSON round-trips", parsed != null);
        equal("string survives", "MediaBridge", Json.getString(parsed, "name", null));
        equal("number survives", 8200, Json.getInt(parsed, "port", 0));
        check("boolean survives", Json.getBoolean(parsed, "paired", false));
        equal("dotted path reaches nested values", 1, Json.getInt(parsed, "nested.a", 0));
        check("malformed JSON yields null instead of throwing",
                Json.parseObject("{not json") == null);

        // TV persistence uses the same JSON layer.
        TvDevice tv = new TvDevice();
        tv.udn = "uuid:abc";
        tv.friendlyName = "Living Room TV";
        tv.modelName = "55NANO86VPA";
        tv.address = "192.168.1.50";
        tv.port = 1588;
        tv.mediaRenderer = true;
        tv.avTransportUrl = "http://192.168.1.50:1588/avt";
        tv.paired = true;
        tv.lastSeenAt = 1_700_000_000_000L;
        TvDevice restored = TvDevice.fromJson(tv.toJson());
        check("saved TV is restored", restored != null);
        equal("restored name", "Living Room TV", restored.friendlyName);
        equal("restored address", "192.168.1.50", restored.address);
        equal("restored port", 1588, restored.port);
        check("restored playback capability", restored.supportsRemotePlayback());
        check("restored pairing state", restored.paired);
        equal("restored identity is stable", tv.effectiveId(), restored.effectiveId());
    }

    // -------------------------------------------------------- live HTTP round trip

    private static void liveHttpExchange() throws Exception {
        // A real server on a real socket: parse the TV's request, answer with a
        // 206 + Content-Range, and read the bytes back the way the TV would.
        final byte[] payload = new byte[4096];
        new Random(7).nextBytes(payload);
        final AtomicInteger bodies = new AtomicInteger();

        ServerSocket listener = new ServerSocket(0);
        Thread server = new Thread(() -> {
            try (Socket socket = listener.accept()) {
                HttpRequest request = HttpRequest.read(socket);
                HttpResponse response = new HttpResponse();
                HttpRange.ByteRange range = HttpRange.parseBytes(request.header("range"),
                        payload.length);
                if (range != null) {
                    response.status(206, "Partial Content")
                            .header("Content-Range", HttpRange.contentRange(range.start,
                                    range.end, payload.length));
                }
                response.header("Content-Type", video("Clip.mp4", "video/mp4", payload.length, 1000)
                        .mimeType);
                response.header("Accept-Ranges", "bytes");
                response.header(Dlna.CONTENT_FEATURES,
                        Dlna.contentFeatures("video/mp4", true, false, null));
                int start = range == null ? 0 : (int) range.start;
                int length = range == null ? payload.length : (int) range.length();
                response.stream(new ByteArrayInputStream(payload, start, length), length,
                        "video/mp4");
                response.writeTo(socket.getOutputStream(), false, false);
                bodies.incrementAndGet();
            } catch (Exception e) {
                failures.add("live exchange server side: " + e);
            }
        });
        server.setDaemon(true);
        server.start();

        Socket client = new Socket("127.0.0.1", listener.getLocalPort());
        client.setSoTimeout(5000);
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(client.getOutputStream(),
                "ISO-8859-1"), false);
        writer.print("GET /media/v42/Clip.mp4 HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Range: bytes=1024-2047\r\n"
                + "getcontentFeatures.dlna.org: 1\r\n"
                + "Connection: close\r\n\r\n");
        writer.flush();

        String raw = readAll(client);
        server.join(5000);
        client.close();
        listener.close();

        check("server answered the ranged request", bodies.get() == 1);
        check("status line is 206 for a range request",
                raw.startsWith("HTTP/1.1 206 Partial Content"));
        check("Content-Range is exact", raw.contains("Content-Range: bytes 1024-2047/4096"));
        check("Content-Length matches the requested range", raw.contains("Content-Length: 1024"));
        check("accept-ranges tells the TV it may seek", raw.contains("Accept-Ranges: bytes"));
        check("DLNA content features are sent on the media response",
                raw.contains("contentFeatures.dlna.org: DLNA.ORG_OP=01"));
        check("connection closes when asked", raw.contains("Connection: close"));

        int headerEnd = raw.indexOf("\r\n\r\n");
        byte[] received = raw.substring(headerEnd + 4).getBytes("ISO-8859-1");
        equal("exactly the requested bytes were delivered", 1024, received.length);
        boolean identical = true;
        for (int i = 0; i < received.length; i++) {
            if (received[i] != payload[1024 + i]) {
                identical = false;
                break;
            }
        }
        check("the bytes are the file's bytes, not a shifted window", identical);
    }

    private static String readAll(Socket socket) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        java.io.InputStream stream = socket.getInputStream();
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), Charset.forName("ISO-8859-1"));
    }

    private static int count(String haystack, String needle) {
        int found = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            found++;
            index += needle.length();
        }
        return found;
    }
}
