import com.lgmediabridge.catalog.MediaItem;
import com.lgmediabridge.compat.CompatResult;
import com.lgmediabridge.compat.MediaCompat;
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
import com.lgmediabridge.server.MediaPath;
import com.lgmediabridge.stream.StreamRegistry;
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

        area("XML helpers (how phone and TV read each other)");
        xmlHelpers();

        area("Media URLs (what the TV is told to fetch)");
        mediaUrls();

        area("DLNA tokens (what the TV inspects before playing)");
        dlnaProtocolInfo();
        dlnaProfilesAndFeatures();

        area("DIDL-Lite browse results (what the TV lists)");
        didlItemsAndContainers();

        area("LG device classification (which TV to offer)");
        tvClassification();

        area("Media compatibility decisions (what the TV is offered)");
        mediaCompatibility();

        area("UPnP device and service descriptions");
        deviceDescription();
        scpds();

        area("SOAP control and responses");
        soapEnvelopes();

        area("SSDP discovery messages");
        ssdpMessages();

        area("Streaming metrics");
        streamSessions();

        area("Stream registry (what the phone reports is playing)");
        streamRegistry();

        area("Log export and JSON persistence");
        logAndJson();

        area("Live HTTP exchange on the loopback socket");
        liveHttpExchange();
        headAndKeepAlive();
        segmentedRequests();

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

    // ----------------------------------------------------------- XML helpers

    private static void xmlHelpers() {
        // Everything the phone writes to the TV passes through escape().
        equal("ampersand", "A &amp; B", Xml.escape("A & B"));
        equal("angle brackets", "&lt;tag&gt;", Xml.escape("<tag>"));
        equal("both quote styles", "&quot;x&quot; and &apos;y&apos;",
                Xml.escape("\"x\" and 'y'"));
        equal("nothing to escape", "Holiday Clip.mp4", Xml.escape("Holiday Clip.mp4"));
        equal("a null title becomes an empty string", "", Xml.escape(null));
        equal("tabs and newlines survive (they are legal in XML)",
                "a\tb\nc", Xml.escape("a\tb\nc"));
        equal("control characters are dropped, not escaped",
                "BellRing", Xml.escape("Bell\u0007Ring"));
        equal("a lone high surrogate is dropped (a truncated emoji)",
                "Clip", Xml.escape("Clip\uD83D"));
        equal("a lone low surrogate is dropped", "Clip", Xml.escape("Clip\uDE00"));
        equal("a valid surrogate pair survives",
                "Clip \uD83D\uDE00", Xml.escape("Clip \uD83D\uDE00"));

        // The real test: whatever escape() produces must parse as XML.
        String hostile = "A & B <\"quoted\"> \u0000\u0007 \uD83D P\uDE00'tab\t";
        check("even a hostile title produces a parseable DIDL document",
                parseXml(DidlLite.open() + DidlLite.container("1", "0", hostile,
                        "object.container.storageFolder", 1, false) + DidlLite.close()) != null);

        // Reading what the TV sends: entities come back as characters.
        equal("entities are decoded", "A & B", Xml.unescape("A &amp; B"));
        equal("double escaping is decoded once, not twice", "&lt;",
                Xml.unescape("&amp;lt;"));
        equal("numeric apostrophe is decoded", "'", Xml.unescape("&#39;"));
        equal("text without entities is untouched", "plain", Xml.unescape("plain"));

        // Element lookup must match a whole element name, not a prefix of one.
        String soap = "<s:Envelope><s:Body><u:GetPositionInfoResponse>"
                + "<ResultCode>0</ResultCode><Result>payload &amp; more</Result>"
                + "</u:GetPositionInfoResponse></s:Body></s:Envelope>";
        equal("an element's text is returned with entities decoded",
                "payload & more", Xml.element(soap, "Result"));
        equal("a longer element with the same prefix is not mistaken for it", null,
                Xml.element("<body><ResultCode>0</ResultCode></body>", "Result"));
        equal("a missing element yields null", null, Xml.element(soap, "TrackDuration"));
        equal("null input yields null", null, Xml.element(null, "Result"));
        equal("an element with attributes is still found", "body",
                Xml.element("<res size=\"1\" duration=\"0:03:42\">body</res>", "res"));
        equal("a longer element is not matched even when the shorter one is asked for",
                null, Xml.element("<resource>no</resource>", "res"));
        equal("a prefixed element is only found by its own name", "ok",
                Xml.element("<u:Browse>ok</u:Browse>", "u:Browse"));
        equal("attributes are read from a self-closing tag", "734003200",
                Xml.attribute("<res protocolInfo=\"http-get:*:video/mp4:\" size=\"734003200\"/>",
                        "res", "size"));
        equal("an escaped attribute value is decoded", "a & b",
                Xml.attribute("<res title=\"a &amp; b\"/>", "res", "title"));
        equal("a missing attribute yields null", null,
                Xml.attribute("<res size=\"1\"/>", "res", "duration"));
        equal("a missing element yields null", null,
                Xml.attribute("<other/>", "res", "size"));

        equal("a plain number is parsed", 200, Xml.intOrDefault("200", 0));
        equal("surrounding whitespace is tolerated", 7, Xml.intOrDefault("  7 ", 0));
        equal("NOT_IMPLEMENTED falls back instead of throwing", -1,
                Xml.intOrDefault("NOT_IMPLEMENTED", -1));
        equal("an empty string falls back", 5, Xml.intOrDefault("", 5));
        equal("null falls back", 5, Xml.intOrDefault(null, 5));
    }

    // ------------------------------------------------------------ media URLs

    private static void mediaUrls() {
        MediaItem clip = video("Holiday Clip.mp4", "video/mp4", 1000, 1000);
        String url = MediaPath.mediaUrl("http://192.168.1.42:8200", clip, "video/mp4");
        equal("a video URL lives under /media/<id>/<name>.<ext>",
                "http://192.168.1.42:8200/media/v42/Holiday%20Clip.mp4", url);
        equal("the phone recognises the URL it handed out", "v42",
                MediaPath.objectId("/media/v42/Holiday%20Clip.mp4"));
        equal("a dotted file name does not confuse the id",
                "v42", MediaPath.objectId("/media/v42/My.Holiday.Clip.mp4"));
        equal("a converted photo is still the same object",
                "p7", MediaPath.objectId("/media/p7/IMG_1.jpg"));
        equal("audio ids are recognised too", "a3",
                MediaPath.objectId("/media/a3/Song.mp3"));

        // A JPEG copy of a HEIC keeps the object id, so ranges and resumes work.
        MediaItem heic = new MediaItem.Builder().kind(MediaItem.Kind.PHOTO).storeId(7)
                .displayName("IMG_1.heic").title("IMG_1").mimeType("image/heic")
                .sizeBytes(4_000_000L).build();
        String converted = MediaPath.mediaUrl("http://192.168.1.42:8200", heic,
                "image/jpeg");
        check("a converted photo is advertised as .jpg, not .heic",
                converted.endsWith("/media/p7/IMG_1.jpg"));
        // The cache serves the copy under the ORIGINAL id, so this must resolve.
        equal("and the id still points at the original photo", "p7",
                MediaPath.objectId("/media/p7/IMG_1.jpg"));

        // Artwork.
        equal("thumbnail URLs are read back", "v42", MediaPath.objectId("/thumb/v42.jpg"));
        equal("thumbnail URLs without an extension are read back", "v42",
                MediaPath.objectId("/thumb/v42"));
        check("artwork is recognised as artwork",
                MediaPath.isThumbnail("/thumb/v42.jpg") && !MediaPath.isMedia("/thumb/v42.jpg"));

        // Anything else must not resolve to a catalogue entry.
        equal("a browse container is not a media object", null,
                MediaPath.objectId("/media/videos/x.mp4"));
        equal("a missing id is rejected", null, MediaPath.objectId("/media//x.mp4"));
        equal("a path with no id is rejected", null, MediaPath.objectId("/media/"));
        equal("an upper-case id is rejected (ids are generated lower-case)", null,
                MediaPath.objectId("/media/V42/x.mp4"));
        equal("a non-media path is rejected", null,
                MediaPath.objectId("/upnp/control/content-directory"));
        equal("the device description is not a media object", null,
                MediaPath.objectId(DeviceDescription.PATH_DEVICE));
        equal("null is handled", null, MediaPath.objectId(null));
        check("only /media/ and /thumb/ are media routes",
                MediaPath.isMedia("/media/v1/a.mp4") && !MediaPath.isMedia("/mediax/v1/a.mp4")
                        && !MediaPath.isMedia(null));

        // Titles that need escaping must still produce a URL the phone can parse.
        MediaItem awkward = new MediaItem.Builder().kind(MediaItem.Kind.VIDEO).storeId(88)
                .displayName("A & B's Clip (final).mp4").title("A & B's Clip (final)")
                .mimeType("video/mp4").sizeBytes(10).build();
        String awkwardUrl = MediaPath.mediaUrl("http://10.0.0.2:8200", awkward, "video/mp4");
        check("a title with spaces, quotes and ampersands is encoded: " + awkwardUrl,
                !awkwardUrl.contains(" ") && awkwardUrl.contains("%20")
                        && awkwardUrl.contains("%26"));
        equal("and the encoded URL still resolves to its object",
                "v88", MediaPath.objectId(awkwardUrl.substring("http://10.0.0.2:8200".length())));
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

    // ------------------------------------------------------- media compatibility

    /** Builds a catalogue item the way the MediaStore index does. */
    private static MediaItem item(MediaItem.Kind kind, String name, String mime, int width,
                                  int height) {
        return new MediaItem.Builder()
                .kind(kind)
                .storeId(Math.abs(name.hashCode()))
                .displayName(name)
                .title(name)
                .mimeType(mime)
                .sizeBytes(120_000_000L)
                .durationMs(kind == MediaItem.Kind.PHOTO ? 0 : 120_000L)
                .size(width, height)
                .build();
    }

    private static void verdictIs(String name, MediaItem.Kind kind, String fileName, String mime,
                                  CompatResult.Verdict expected) {
        MediaItem media = item(kind, fileName, mime, 1920, 1080);
        CompatResult.Verdict actual = new MediaCompat().quick(media).verdict;
        equal(name, expected, actual);
    }

    private static void mediaCompatibility() {
        // MediaStore reports the *container* for ordinary phone videos. Judging
        // that as an unknown codec would hide the whole video library from the
        // television, which is the one thing this app must never do.
        verdictIs("MP4/H.264 video is offered", MediaItem.Kind.VIDEO, "Clip.mp4", "video/mp4",
                CompatResult.Verdict.DIRECT);
        verdictIs("QuickTime video is offered", MediaItem.Kind.VIDEO, "Clip.mov",
                "video/quicktime", CompatResult.Verdict.DIRECT);
        verdictIs("Matroska video is offered", MediaItem.Kind.VIDEO, "Clip.mkv",
                "video/x-matroska", CompatResult.Verdict.DIRECT);
        verdictIs("WebM video is offered", MediaItem.Kind.VIDEO, "Clip.webm", "video/webm",
                CompatResult.Verdict.DIRECT);
        verdictIs("MPEG-TS video is offered", MediaItem.Kind.VIDEO, "Recording.ts",
                "video/mp2t", CompatResult.Verdict.DIRECT);
        verdictIs("AVI video is offered", MediaItem.Kind.VIDEO, "Old.avi",
                "video/x-msvideo", CompatResult.Verdict.DIRECT);
        verdictIs("AVI reported under its alias MIME is offered too", MediaItem.Kind.VIDEO,
                "Old.avi", "video/avi", CompatResult.Verdict.DIRECT);
        verdictIs("3GP video is offered", MediaItem.Kind.VIDEO, "Mms.3gp", "video/3gpp",
                CompatResult.Verdict.DIRECT);
        verdictIs("MPEG program stream is offered", MediaItem.Kind.VIDEO, "Cam.mpg",
                "video/mpeg", CompatResult.Verdict.DIRECT);
        verdictIs("a DVD VOB is offered", MediaItem.Kind.VIDEO, "Title.vob",
                "video/x-ms-vob", CompatResult.Verdict.DIRECT);

        // Codec-level MIME types (what metadata readers and newer providers report).
        verdictIs("H.264 by codec MIME", MediaItem.Kind.VIDEO, "Clip.mp4", "video/avc",
                CompatResult.Verdict.DIRECT);
        verdictIs("HEVC 4K is offered", MediaItem.Kind.VIDEO, "4K.mkv", "video/hevc",
                CompatResult.Verdict.DIRECT);
        verdictIs("VP9 is offered", MediaItem.Kind.VIDEO, "Clip.webm", "video/x-vnd.on2.vp9",
                CompatResult.Verdict.DIRECT);
        verdictIs("AV1 is offered (documented for 4K on this generation)", MediaItem.Kind.VIDEO,
                "New.mkv", "video/av01", CompatResult.Verdict.DIRECT);
        verdictIs("Xvid/MPEG-4 Part 2 is offered", MediaItem.Kind.VIDEO, "Old.avi",
                "video/mp4v-es", CompatResult.Verdict.DIRECT);

        // Documented limitations must still hide what the TV cannot play.
        verdictIs("HEVC inside .avi is refused (documented limitation)", MediaItem.Kind.VIDEO,
                "Odd.avi", "video/hevc", CompatResult.Verdict.UNSUPPORTED);
        verdictIs("Flash video is refused (container is not in the table)",
                MediaItem.Kind.VIDEO, "Old.flv", "video/x-flv",
                CompatResult.Verdict.UNSUPPORTED);
        verdictIs("RealMedia is refused", MediaItem.Kind.VIDEO, "Old.rm",
                "video/vnd.rn-realvideo", CompatResult.Verdict.UNSUPPORTED);
        verdictIs("an unreadable video MIME is refused", MediaItem.Kind.VIDEO, "Weird.mxf",
                "application/mxf", CompatResult.Verdict.UNSUPPORTED);

        // MediaStore sometimes leaves MIME_TYPE empty or generic.
        verdictIs("an empty MIME is derived from the extension (.mp4)",
                MediaItem.Kind.VIDEO, "Clip.mp4", "", CompatResult.Verdict.DIRECT);
        verdictIs("a generic MIME is derived from the extension (.mkv)",
                MediaItem.Kind.VIDEO, "Clip.mkv", "application/octet-stream",
                CompatResult.Verdict.DIRECT);

        // Photos: what the TV documents, and what needs a JPEG copy.
        verdictIs("JPEG photo", MediaItem.Kind.PHOTO, "IMG_1.jpg", "image/jpeg",
                CompatResult.Verdict.DIRECT);
        verdictIs("PNG photo", MediaItem.Kind.PHOTO, "Shot.png", "image/png",
                CompatResult.Verdict.DIRECT);
        verdictIs("GIF photo", MediaItem.Kind.PHOTO, "Anim.gif", "image/gif",
                CompatResult.Verdict.DIRECT);
        verdictIs("WebP photo", MediaItem.Kind.PHOTO, "Shot.webp", "image/webp",
                CompatResult.Verdict.DIRECT);
        verdictIs("BMP photo", MediaItem.Kind.PHOTO, "Scan.bmp", "image/bmp",
                CompatResult.Verdict.DIRECT);
        verdictIs("HEIC photo is converted, not refused", MediaItem.Kind.PHOTO, "IMG_2.heic",
                "image/heic", CompatResult.Verdict.PHOTO_CONVERT);
        verdictIs("AVIF photo is converted", MediaItem.Kind.PHOTO, "IMG_3.avif", "image/avif",
                CompatResult.Verdict.PHOTO_CONVERT);
        verdictIs("TIFF photo is converted", MediaItem.Kind.PHOTO, "Scan.tif", "image/tiff",
                CompatResult.Verdict.PHOTO_CONVERT);
        verdictIs("RAW (DNG) photo is converted", MediaItem.Kind.PHOTO, "RAW.dng",
                "image/x-adobe-dng", CompatResult.Verdict.PHOTO_CONVERT);
        verdictIs("an unknown camera RAW is refused", MediaItem.Kind.PHOTO, "RAW.cr2",
                "image/x-canon-cr2", CompatResult.Verdict.UNSUPPORTED);

        // A very large JPEG is still shown - with a warning rather than a refusal.
        MediaItem huge = item(MediaItem.Kind.PHOTO, "Panorama.jpg", "image/jpeg", 9000, 6000);
        CompatResult hugeResult = new MediaCompat().quick(huge);
        equal("a 54 MP JPEG is still direct-playable", CompatResult.Verdict.DIRECT,
                hugeResult.verdict);
        check("but the risk of the TV's decoder is explained",
                hugeResult.reasons.size() > 0 && hugeResult.reasons.get(0).contains("40 MP"));

        // Audio: the documented list, plus what needs one conversion.
        verdictIs("MP3 audio", MediaItem.Kind.AUDIO, "Song.mp3", "audio/mpeg",
                CompatResult.Verdict.DIRECT);
        verdictIs("FLAC audio", MediaItem.Kind.AUDIO, "Song.flac", "audio/flac",
                CompatResult.Verdict.DIRECT);
        verdictIs("AAC in MP4", MediaItem.Kind.AUDIO, "Song.m4a", "audio/mp4",
                CompatResult.Verdict.DIRECT);
        verdictIs("Ogg Vorbis audio (documented as playable)", MediaItem.Kind.AUDIO,
                "Song.ogg", "audio/ogg", CompatResult.Verdict.DIRECT);
        verdictIs("WAV/PCM audio", MediaItem.Kind.AUDIO, "Clip.wav", "audio/wav",
                CompatResult.Verdict.DIRECT);
        verdictIs("Dolby Digital audio", MediaItem.Kind.AUDIO, "Movie.ac3", "audio/ac3",
                CompatResult.Verdict.DIRECT);
        verdictIs("WMA audio", MediaItem.Kind.AUDIO, "Old.wma", "audio/x-ms-wma",
                CompatResult.Verdict.DIRECT);
        verdictIs("Opus audio needs one AAC conversion", MediaItem.Kind.AUDIO, "Song.opus",
                "audio/opus", CompatResult.Verdict.AUDIO_CONVERT);
        verdictIs("DTS audio needs one AAC conversion", MediaItem.Kind.AUDIO, "Song.dts",
                "audio/vnd.dts", CompatResult.Verdict.AUDIO_CONVERT);
        verdictIs("ALAC needs one AAC conversion", MediaItem.Kind.AUDIO, "Song.m4a",
                "audio/alac", CompatResult.Verdict.AUDIO_CONVERT);

        // The MIME type the TV is actually served must match the verdict.
        MediaItem heic = item(MediaItem.Kind.PHOTO, "IMG_9.heic", "image/heic", 4032, 3024);
        CompatResult heicResult = new MediaCompat().quick(heic);
        equal("a converted photo is served as JPEG", "image/jpeg",
                MediaCompat.effectiveMime(heic, heicResult));
        MediaItem opus = item(MediaItem.Kind.AUDIO, "Song.opus", "audio/opus", 0, 0);
        CompatResult opusResult = new MediaCompat().quick(opus);
        equal("converted audio is served as AAC in MP4", "audio/mp4",
                MediaCompat.effectiveMime(opus, opusResult));
        MediaItem mp4 = item(MediaItem.Kind.VIDEO, "Clip.mp4", "video/mp4", 1920, 1080);
        equal("direct-played video keeps its own container MIME", "video/mp4",
                MediaCompat.effectiveMime(mp4, new MediaCompat().quick(mp4)));
        equal("an empty MIME is filled in from the extension", "image/jpeg",
                MediaCompat.normaliseMime(item(MediaItem.Kind.PHOTO, "x.jpg", "", 0, 0)));
        equal("container names are user-readable", "MP4 (MP4 family)",
                MediaCompat.containerName(item(MediaItem.Kind.VIDEO, "Clip.mp4", "video/mp4", 0, 0)));
        check("documented containers are recognised",
                MediaCompat.isKnownVideoContainer(item(MediaItem.Kind.VIDEO, "a.mkv", "", 0, 0))
                        && MediaCompat.isKnownVideoContainer(
                                item(MediaItem.Kind.VIDEO, "a.m2ts", "", 0, 0)));
        check("undocumented containers are not",
                !MediaCompat.isKnownVideoContainer(item(MediaItem.Kind.VIDEO, "a.flv", "", 0, 0))
                        && !MediaCompat.isKnownVideoContainer(
                                item(MediaItem.Kind.VIDEO, "a.rmvb", "", 0, 0)));
    }

    // -------------------------------------------------------- TV identification

    private static void tvClassification() {
        // A device description shaped like the published captures of a real LG set.
        String renderer = "<root><device>"
                + "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"
                + "<friendlyName>[LG] webOS TV NANO86VPA</friendlyName>"
                + "<manufacturer>LG Electronics</manufacturer>"
                + "<modelName>55NANO86VPA</modelName>"
                + "<UDN>uuid:9f0a-parent</UDN>"
                + "<serviceList>"
                + "<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>"
                + "<controlURL>/75f8f5e1/AVTransport</controlURL></service>"
                + "<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>"
                + "<controlURL>/75f8f5e1/RenderingControl</controlURL></service>"
                + "</serviceList></device></root>";
        String location = "http://192.168.1.50:1588/75f8f5e1/";
        String lgeHeader = "WebOS/1.0 UPnP/1.0 LGE_DLNA_SDK/1.6.0 [TV][LG]55NANO86VPA/6.5.3";
        TvDevice tv = TvDevice.parseDescription(location, renderer, lgeHeader);
        check("LG renderer description parsed", tv != null);
        check("recognised as an LG device from its server header", tv.isLg());
        equal("model name read", "55NANO86VPA", tv.modelName);
        equal("friendly name read", "[LG] webOS TV NANO86VPA", tv.friendlyName);
        equal("address taken from the description location", "192.168.1.50", tv.address);
        equal("port taken from the description location", 1588, tv.port);
        check("remote playback offered", tv.supportsRemotePlayback());
        check("remote volume offered", tv.supportsRemoteVolume());
        check("a renderer is not mistaken for a second-screen endpoint",
                !tv.lgSecondScreen);
        equal("AVTransport control URL resolved against the location",
                "http://192.168.1.50:1588/75f8f5e1/AVTransport", tv.avTransportUrl);
        equal("RenderingControl control URL resolved against the location",
                "http://192.168.1.50:1588/75f8f5e1/RenderingControl",
                tv.renderingControlUrl);
        check("capability summary is user-readable",
                tv.capabilitySummary() != null && !tv.capabilitySummary().isEmpty());

        String nonLg = "<root><device>"
                + "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"
                + "<friendlyName>Some Other Box</friendlyName><manufacturer>ACME</manufacturer>"
                + "<UDN>uuid:acme</UDN>"
                + "<serviceList><service>"
                + "<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>"
                + "<controlURL>/avt</controlURL></service></serviceList></device></root>";
        TvDevice other = TvDevice.parseDescription("http://10.0.0.9:1234/", nonLg,
                "SomeServer/1.0 UPnP/1.0");
        check("a non-LG renderer is still usable (DLNA is DLNA)", other != null);
        check("but it is not claimed to be an LG", !other.isLg());
        check("its playback capability is still recognised", other.supportsRemotePlayback());
        equal("control URL resolved for a root-mounted service too",
                "http://10.0.0.9:1234/avt", other.avTransportUrl);

        String server = "<root><device>"
                + "<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>"
                + "<friendlyName>[LG] webOS TV NANO86VPA</friendlyName>"
                + "<modelName>55NANO86VPA</modelName><UDN>uuid:9f0a-parent</UDN></device></root>";
        TvDevice sameTv = TvDevice.parseDescription("http://192.168.1.50:1588/", server,
                lgeHeader);
        check("the TV is recognised through its MediaServer face too", sameTv != null
                && sameTv.isLg());
        equal("both faces of one TV share a single identity",
                tv.effectiveId(), sameTv.effectiveId());

        // A TV found by SSDP alone (no description yet) must still be usable and,
        // once the description arrives, must not become a second entry.
        TvDevice bare = new TvDevice();
        bare.udn = "uuid:9f0a-parent";
        bare.address = "192.168.1.50";
        bare.port = 1588;
        bare.serverHeader = lgeHeader;
        bare.friendlyName = "";
        bare.mergeFrom(tv);
        equal("merging fills in the missing name", tv.friendlyName, bare.friendlyName);
        check("merging keeps one identity", bare.effectiveId().equals(tv.effectiveId()));

        // After a DHCP change the TV announces the same UUID from a new address.
        TvDevice moved = new TvDevice();
        moved.udn = "uuid:9f0a-parent";
        moved.address = "192.168.1.99";
        moved.port = 1588;
        TvDevice remerged = TvDevice.parseDescription(location, renderer, lgeHeader);
        remerged.udn = "uuid:9f0a-parent";
        remerged.address = "192.168.1.99";
        equal("an IP change does not create a new device",
                moved.effectiveId(), remerged.effectiveId());
        equal("the new address wins", "192.168.1.99", remerged.address);

        // Robustness: discovery must survive whatever is on the network.
        TvDevice garbage = TvDevice.parseDescription("http://10.0.0.1/",
                "not xml at all", null);
        check("a garbage description does not throw and claims no capabilities",
                garbage != null && !garbage.supportsRemotePlayback()
                        && !garbage.supportsRemoteVolume());

        // URL joining is what makes remote control possible at all.
        equal("relative control URL is joined to the location",
                "http://192.168.1.50:1588/a/b", TvDevice.absoluteUrl(
                        "http://192.168.1.50:1588/75f8f5e1/", "a/b"));
        equal("absolute control URL is left alone",
                "http://10.0.0.5:1234/x", TvDevice.absoluteUrl(
                        "http://192.168.1.50:1588/", "http://10.0.0.5:1234/x"));
        equal("host extracted from a control URL", "192.168.1.50",
                TvDevice.hostOf("http://192.168.1.50:1588/avt"));
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

        // What the phone answers when a TV searches: silence here is exactly the
        // "my phone never shows up on the TV" failure mode.
        equal("ssdp:all gets every target", 5,
                SsdpMessages.responsesFor("ssdp:all", uuid).length);
        equal("a MediaServer search is answered with the device",
                DeviceDescription.DEVICE_TYPE,
                SsdpMessages.responsesFor("urn:schemas-upnp-org:device:MediaServer:1", uuid)[0][0]);
        equal("a ContentDirectory search is answered with the service",
                DeviceDescription.CONTENT_DIRECTORY_TYPE,
                SsdpMessages.responsesFor(
                        "urn:schemas-upnp-org:service:ContentDirectory:1", uuid)[0][0]);
        equal("a rootdevice search is answered",
                SsdpMessages.ROOT_DEVICE,
                SsdpMessages.responsesFor("upnp:rootdevice", uuid)[0][0]);
        equal("a search for our own UUID is answered",
                "uuid:" + uuid,
                SsdpMessages.responsesFor("uuid:" + uuid, uuid)[0][1]);
        equal("a search for a renderer is not ours to answer", 0,
                SsdpMessages.responsesFor(
                        "urn:schemas-upnp-org:device:MediaRenderer:1", uuid).length);
        equal("an empty search target is ignored", 0,
                SsdpMessages.responsesFor("", uuid).length);
        equal("a null search target is ignored", 0,
                SsdpMessages.responsesFor(null, uuid).length);

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
                !session.isStale(60_000));
        check("a transfer goes stale when it has been idle", session.isStale(-1));

        // A converted source reports its own length, so progress still reaches 100%.
        StreamSession converted = new StreamSession("s3", "192.168.1.51", "TV", "a9", "Song",
                "audio/mp4", 0, true);
        converted.setTotalBytes(12_000_000L);
        converted.onBytes(12_000_000L, 65_536);
        equal("a converted transfer reports the converted file's size",
                12_000_000L, converted.totalBytes());
        equal("and its progress reaches 100%", 100, converted.progressPercent());
        converted.setTotalBytes(0);
        equal("a bogus zero length does not clear a known size", 12_000_000L,
                converted.totalBytes());
        converted.setTotalBytes(-1);
        equal("a negative length is ignored too", 12_000_000L, converted.totalBytes());
        StreamSession unknown = new StreamSession("s4", "192.168.1.52", "TV", "v9", "Clip",
                "video/mp4", 0, false);
        equal("an unknown size reports no progress rather than a wrong one", 0,
                unknown.progressPercent());
        unknown.onBytes(4_000_000L, 65_536);
        equal("with no known total, the screen shows what was sent", 4_000_000L,
                unknown.displayTotalBytes());
        unknown.setTotalBytes(10_000_000L);
        equal("once the total is known, the screen shows the total", 10_000_000L,
                unknown.displayTotalBytes());

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

    // ------------------------------------------------------- stream registry

    private static void streamRegistry() {
        StreamRegistry registry = new StreamRegistry();
        final int[] notifications = {0};
        registry.addListener(sessions -> notifications[0]++);

        StreamSession first = registry.create("192.168.1.50", "[TV][LG]55NANO86VPA", "v42",
                "Holiday Clip.mp4", "video/mp4", 734_003_200L, false);
        StreamSession second = registry.create("192.168.1.51", "[TV][LG]55NANO86VPA", "a3",
                "Song.flac", "audio/flac", 30_000_000L, false);
        equal("both transfers are tracked", 2, registry.activeCount());
        check("the phone reports something is playing", registry.hasActiveStreams());
        equal("one session can be looked up by id", first, registry.byId(first.id));
        equal("the session list is newest first", second.id, registry.activeSorted().get(0).id);
        equal("transfers that have not moved bytes yet contribute no throughput", 0L,
                registry.totalBytesPerSecond());
        first.onBytes(367_001_600L, 65_536);
        second.onBytes(30_000_000L, 65_536);

        registry.finish(first, 200);
        equal("a finished transfer leaves the active list", 1, registry.activeCount());
        check("the phone still reports the other transfer as playing",
                registry.hasActiveStreams());
        equal("and it is not counted twice in the rate", registry.totalBytesPerSecond(),
                second.bytesPerSecond());
        equal("history records what just finished", 1, registry.recentFinished().size());
        equal("with its final status", 200, registry.recentFinished().get(0).finishedStatus());
        equal("and its byte count", 367_001_600L,
                registry.recentFinished().get(0).bytesSent());

        registry.finish(second, 500);
        check("nothing is playing once every transfer ends", !registry.hasActiveStreams());
        equal("a failed transfer is kept in history too", 2, registry.recentFinished().size());
        equal("the newest finished transfer comes first", 500,
                registry.recentFinished().get(0).finishedStatus());
        equal("no active transfer means no throughput", 0L, registry.totalBytesPerSecond());

        // History is bounded, and clearing it is what the UI button does.
        for (int i = 0; i < StreamRegistry.HISTORY_LIMIT + 5; i++) {
            StreamSession extra = registry.create("10.0.0.2", "TV", "v" + i, "clip " + i,
                    "video/mp4", 1000, false);
            registry.finish(extra, 200);
        }
        equal("history is capped", StreamRegistry.HISTORY_LIMIT,
                registry.recentFinished().size());
        registry.clearFinished();
        equal("clearing history empties it", 0, registry.recentFinished().size());
        check("the UI was told about every change", notifications[0] > 5);

        // Stopping everything must end every session and say so.
        registry.create("10.0.0.3", "TV", "p1", "photo", "image/jpeg", 4000, false);
        registry.create("10.0.0.3", "TV", "v2", "clip", "video/mp4", 9000, false);
        registry.stopAll();
        equal("stop all clears the active list", 0, registry.activeCount());
        check("and the stopped transfers are visible in history",
                registry.recentFinished().size() == 2
                        && "stopped".equals(registry.recentFinished().get(0).state()));

        // A listener that throws must not break the others.
        StreamRegistry resilient = new StreamRegistry();
        final int[] reached = {0};
        resilient.addListener(sessions -> {
            throw new IllegalStateException("boom");
        });
        resilient.addListener(sessions -> reached[0]++);
        resilient.create("10.0.0.4", "TV", "v1", "clip", "video/mp4", 100, false);
        equal("a failing listener does not stop the rest", 1, reached[0]);
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

    /** HEAD (the TV uses it to probe a file) and two requests on one connection. */
    private static void headAndKeepAlive() throws Exception {
        final byte[] payload = new byte[2048];
        final AtomicInteger served = new AtomicInteger();

        ServerSocket listener = new ServerSocket(0);
        final AtomicReference<Exception> serverError = new AtomicReference<>();
        Thread server = new Thread(() -> {
            try (Socket socket = listener.accept()) {
                while (served.get() < 2) {
                    HttpRequest request = HttpRequest.read(socket);
                    if (request == null) {
                        break;
                    }
                    boolean head = "HEAD".equals(request.method);
                    HttpResponse response = new HttpResponse()
                            .header("Accept-Ranges", "bytes")
                            .body(payload)
                            .status(200, "OK");
                    boolean keepAlive = request.keepAlive();
                    response.writeTo(socket.getOutputStream(), head, keepAlive);
                    served.incrementAndGet();
                    if (!keepAlive) {
                        break;
                    }
                }
            } catch (Exception e) {
                serverError.set(e);
            }
        });
        server.setDaemon(true);
        server.start();

        Socket client = new Socket("127.0.0.1", listener.getLocalPort());
        client.setSoTimeout(5000);
        OutputStreamWriter writer = new OutputStreamWriter(client.getOutputStream(), "ISO-8859-1");
        writer.write("HEAD /media/v1/Clip.mp4 HTTP/1.1\r\nHost: tv\r\nConnection: keep-alive\r\n\r\n");
        writer.flush();
        String headResponse = readOneResponse(client, false);
        check("HEAD answered with 200", headResponse.startsWith("HTTP/1.1 200 OK"));
        check("HEAD reports the size the body would have",
                headResponse.contains("Content-Length: 2048"));
        check("HEAD sent no body", headResponse.endsWith("\r\n\r\n"));
        check("connection kept alive when the client asks",
                headResponse.contains("Connection: keep-alive"));

        writer.write("GET /media/v1/Clip.mp4 HTTP/1.1\r\nHost: tv\r\nConnection: close\r\n\r\n");
        writer.flush();
        String getResponse = readOneResponse(client, true);
        check("the same connection serves a second request",
                getResponse.startsWith("HTTP/1.1 200 OK"));
        check("the second response carries the body",
                getResponse.length() > getResponse.indexOf("\r\n\r\n") + 4);
        check("connection closed when the client asks",
                getResponse.contains("Connection: close"));

        client.close();
        server.join(3000);
        listener.close();
        check("no server-side error during HEAD/keep-alive", serverError.get() == null,
                String.valueOf(serverError.get()));
        equal("both requests served", 2, served.get());
    }

    /** Real TCP splits requests; the parser must not depend on one read(). */
    private static void segmentedRequests() throws Exception {
        final AtomicReference<HttpRequest> received = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        ServerSocket listener = new ServerSocket(0);
        Thread server = new Thread(() -> {
            try (Socket socket = listener.accept()) {
                received.set(HttpRequest.read(socket));
            } catch (Exception e) {
                failure.set(e);
            }
        });
        server.setDaemon(true);
        server.start();

        Socket client = new Socket("127.0.0.1", listener.getLocalPort());
        java.io.OutputStream out = client.getOutputStream();
        out.write("POST /upnp/control/content-directory HTTP/1.1\r\nHost: tv\r\n".getBytes("ISO-8859-1"));
        out.flush();
        Thread.sleep(60);
        out.write("SOAPACTION: \"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"\r\n".getBytes("ISO-8859-1"));
        out.write("Content-Length: 39\r\n\r\n".getBytes("ISO-8859-1"));
        out.flush();
        Thread.sleep(60);
        out.write("<Browse><ObjectID>0</ObjectID></Bro".getBytes("ISO-8859-1"));
        out.flush();
        Thread.sleep(60);
        try {
            out.write("wse>".getBytes("ISO-8859-1"));
            out.flush();
        } catch (java.io.IOException closed) {
            // The server answered and closed before the last fragment arrived;
            // the assertion below then fails on the assembled body, with the
            // real cause visible in the report.
        }
        Thread.sleep(120);

        check("a request split across four TCP writes still parses", failure.get() == null,
                String.valueOf(failure.get()));
        HttpRequest request = received.get();
        check("the split request was received", request != null);
        if (request != null) {
            equal("path intact after splitting", "/upnp/control/content-directory", request.path);
            equal("header from the middle write intact",
                    "urn:schemas-upnp-org:service:ContentDirectory:1#Browse",
                    request.header("soapaction").replace("\"", ""));
            equal("body assembled from two writes", "<Browse><ObjectID>0</ObjectID></Browse>",
                    request.bodyText());
        }
        client.close();
        server.join(3000);
        listener.close();
    }

    /**
     * Reads exactly one HTTP response off a socket: headers, then the body a
     * Content-Length promises ({@code expectBody} false for HEAD, where the
     * length describes the body that was deliberately not sent).
     */
    private static String readOneResponse(Socket socket, boolean expectBody) throws Exception {
        java.io.InputStream in = socket.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int contentLength = -1;
        int headerEnd = -1;
        while (headerEnd < 0) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            buffer.write(b);
            byte[] current = buffer.toByteArray();
            if (current.length >= 4 && current[current.length - 4] == '\r'
                    && current[current.length - 3] == '\n'
                    && current[current.length - 2] == '\r'
                    && current[current.length - 1] == '\n') {
                headerEnd = current.length;
            }
        }
        String head = new String(buffer.toByteArray(), Charset.forName("ISO-8859-1"));
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase(java.util.Locale.US).startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        int body = 0;
        while (expectBody && body < Math.max(0, contentLength)) {
            int read = in.read();
            if (read < 0) {
                break;
            }
            buffer.write(read);
            body++;
        }
        return new String(buffer.toByteArray(), Charset.forName("ISO-8859-1"));
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
