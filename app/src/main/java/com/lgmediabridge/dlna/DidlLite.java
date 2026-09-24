package com.lgmediabridge.dlna;

import com.lgmediabridge.catalog.MediaItem;
import com.lgmediabridge.compat.CompatResult;
import com.lgmediabridge.core.Xml;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Builds DIDL-Lite documents (the XML dialect UPnP AV uses for browse results).
 *
 * Only elements that players actually use are emitted, but the ones that matter
 * are all present and correct: object ids, parent ids, {@code upnp:class},
 * {@code res@protocolInfo} (the token LG and other renderers filter on), size and
 * duration (needed for a usable progress bar) and a JPEG thumbnail URI, which is
 * what the TV shows in its grid view.
 */
public final class DidlLite {

    private static final String NS = "urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/";
    private static final String DC = "http://purl.org/dc/elements/1.1/";
    private static final String UPNP = "urn:schemas-upnp-org:metadata-1-0/upnp/";
    private static final String DLNA_NS = "urn:schemas-dlna-org:metadata-1-0/";

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);

    private DidlLite() {
    }

    public static String open() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<DIDL-Lite xmlns=\"" + NS + "\" xmlns:dc=\"" + DC + "\""
                + " xmlns:upnp=\"" + UPNP + "\" xmlns:dlna=\"" + DLNA_NS + "\">";
    }

    public static String close() {
        return "</DIDL-Lite>";
    }

    public static String container(String id, String parentId, String title, String upnpClass,
                                   int childCount, boolean searchable) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<container id=\"").append(Xml.escape(id))
          .append("\" parentID=\"").append(Xml.escape(parentId))
          .append("\" restricted=\"1\" searchable=\"").append(searchable ? "1" : "0").append('"');
        if (childCount >= 0) {
            sb.append(" childCount=\"").append(childCount).append('"');
        }
        sb.append('>');
        sb.append("<dc:title>").append(Xml.escape(title)).append("</dc:title>");
        sb.append("<upnp:class>").append(upnpClass).append("</upnp:class>");
        sb.append("</container>");
        return sb.toString();
    }

    /**
     * A media item. {@code url} is the absolute resource URL the TV must fetch;
     * {@code thumbnailUrl} may be null for audio without artwork.
     */
    public static String item(String id, String parentId, MediaItem media, String url,
                              String thumbnailUrl, String mime, String protocolInfo,
                              String profileId, long sizeBytes, long durationMs) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<item id=\"").append(Xml.escape(id))
          .append("\" parentID=\"").append(Xml.escape(parentId))
          .append("\" restricted=\"1\">");
        sb.append("<dc:title>").append(Xml.escape(media.title)).append("</dc:title>");
        sb.append("<upnp:class>").append(DidlLite.upnpClassOf(media)).append("</upnp:class>");

        sb.append("<res");
        if (protocolInfo != null) {
            sb.append(" protocolInfo=\"").append(Xml.escape(protocolInfo)).append('"');
        }
        if (sizeBytes > 0) {
            sb.append(" size=\"").append(sizeBytes).append('"');
        }
        String duration = Dlna.duration(durationMs);
        if (duration != null) {
            sb.append(" duration=\"").append(duration).append('"');
        }
        if (media.kind != MediaItem.Kind.AUDIO && media.resolution() != null) {
            sb.append(" resolution=\"").append(Xml.escape(media.resolution())).append('"');
        }
        sb.append('>').append(Xml.escape(url)).append("</res>");

        if (thumbnailUrl != null) {
            sb.append("<upnp:albumArtURI dlna:profileID=\"JPEG_TN\">")
              .append(Xml.escape(thumbnailUrl)).append("</upnp:albumArtURI>");
        }

        long dateMillis = media.dateTakenMs > 0 ? media.dateTakenMs : media.dateAddedSec * 1000L;
        if (dateMillis > 0) {
            sb.append("<dc:date>").append(DATE_FORMAT.format(new Date(dateMillis))).append("</dc:date>");
        }
        if (media.artist != null && !media.artist.isEmpty()) {
            sb.append("<upnp:artist>").append(Xml.escape(media.artist)).append("</upnp:artist>");
        }
        if (media.album != null && !media.album.isEmpty()) {
            sb.append("<upnp:album>").append(Xml.escape(media.album)).append("</upnp:album>");
        }
        if (media.kind == MediaItem.Kind.AUDIO && media.trackNumber > 0) {
            sb.append("<upnp:originalTrackNumber>").append(media.trackNumber)
              .append("</upnp:originalTrackNumber>");
        }
        sb.append("</item>");
        return sb.toString();
    }

    private static String upnpClassOf(MediaItem media) {
        return Dlna.upnpClass(media.kind);
    }

    /** Convenience for a single-object metadata response (BrowseMetadata). */
    public static String single(String body) {
        return open() + body + close();
    }

    static String kindOf(MediaItem item, CompatResult result) {
        return item.kind.name();
    }
}
