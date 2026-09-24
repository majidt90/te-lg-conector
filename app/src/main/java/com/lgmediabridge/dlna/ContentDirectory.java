package com.lgmediabridge.dlna;

import com.lgmediabridge.catalog.MediaCatalog;
import com.lgmediabridge.catalog.MediaItem;
import com.lgmediabridge.compat.CompatResult;
import com.lgmediabridge.compat.MediaCompat;
import com.lgmediabridge.core.LogBus;
import com.lgmediabridge.net.HttpRequest;
import com.lgmediabridge.settings.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The ContentDirectory service: turns the phone library into an UPnP browse tree.
 *
 * Structure offered to the TV:
 * <pre>
 *   0
 *   ├── Videos            (flat, newest first)
 *   ├── Photos            (flat, newest first)
 *   ├── Music             (flat, by artist then title)
 *   ├── Recently added    (mixed, newest first)
 *   ├── Folders           (real device folders, per media type)
 *   ├── Albums            (music)
 *   └── Artists           (music)
 * </pre>
 *
 * Pagination is stable: a container's ordered child list is built once per
 * catalog generation and cached, so a TV that pages through 5 000 photos always
 * sees a consistent sequence even if MediaStore changes in between.
 */
public final class ContentDirectory {

    private static final String TAG = "ContentDirectory";
    private static final String ROOT_ID = "0";
    private static final int MAX_CHILDREN_PER_RESPONSE = 2000;
    private static final int CONTAINER_CACHE_SIZE = 24;

    public static final String ID_VIDEOS = "videos";
    public static final String ID_PHOTOS = "photos";
    public static final String ID_MUSIC = "music";
    public static final String ID_RECENT = "recent";
    public static final String ID_FOLDERS = "folders";
    public static final String ID_ALBUMS = "albums";
    public static final String ID_ARTISTS = "artists";
    private static final String PREFIX_FOLDER = "folder:";
    private static final String PREFIX_ALBUM = "album:";
    private static final String PREFIX_ARTIST = "artist:";

    public static final class BrowseResult {
        public final String didl;
        public final int numberReturned;
        public final int totalMatches;
        public final int updateId;

        BrowseResult(String didl, int numberReturned, int totalMatches, int updateId) {
            this.didl = didl;
            this.numberReturned = numberReturned;
            this.totalMatches = totalMatches;
            this.updateId = updateId;
        }
    }

    /** One entry in a browse tree: either a container or a media object. */
    public static final class Entry {
        public final String id;
        public final String parentId;
        public final String title;
        public final boolean container;
        public final int childCount;
        public final MediaItem item;

        Entry(String id, String parentId, String title, boolean container, int childCount,
              MediaItem item) {
            this.id = id;
            this.parentId = parentId;
            this.title = title;
            this.container = container;
            this.childCount = childCount;
            this.item = item;
        }
    }

    private final MediaCatalog catalog;
    private final MediaCompat compat;
    private final Settings settings;
    private final AtomicInteger updateId = new AtomicInteger(1);
    private long cachedGeneration = -1;
    private final Map<String, List<Entry>> containerCache =
            new LinkedHashMap<String, List<Entry>>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, List<Entry>> eldest) {
                    return size() > CONTAINER_CACHE_SIZE;
                }
            };

    public ContentDirectory(MediaCatalog catalog, MediaCompat compat, Settings settings) {
        this.catalog = catalog;
        this.compat = compat;
        this.settings = settings;
    }

    public int systemUpdateId() {
        return updateId.get();
    }

    /** Called by the server when the library index is rebuilt. */
    public void onLibraryChanged() {
        updateId.incrementAndGet();
        synchronized (containerCache) {
            containerCache.clear();
        }
    }

    public boolean isKnownObject(String objectId) {
        if (objectId == null) {
            return false;
        }
        if (ROOT_ID.equals(objectId) || ID_VIDEOS.equals(objectId) || ID_PHOTOS.equals(objectId)
                || ID_MUSIC.equals(objectId) || ID_RECENT.equals(objectId)
                || ID_FOLDERS.equals(objectId) || ID_ALBUMS.equals(objectId)
                || ID_ARTISTS.equals(objectId)) {
            return true;
        }
        if (objectId.startsWith(PREFIX_FOLDER) || objectId.startsWith(PREFIX_ALBUM)
                || objectId.startsWith(PREFIX_ARTIST)) {
            return true;
        }
        return catalog.byObjectId(objectId) != null;
    }

    public List<Entry> children(String containerId) {
        long generation = catalog.lastIndexedAt();
        synchronized (containerCache) {
            if (generation != cachedGeneration) {
                containerCache.clear();
                cachedGeneration = generation;
            }
            List<Entry> cached = containerCache.get(containerId);
            if (cached != null) {
                return cached;
            }
        }
        List<Entry> built = buildChildren(containerId);
        synchronized (containerCache) {
            containerCache.put(containerId, built);
        }
        return built;
    }

    private List<Entry> buildChildren(String containerId) {
        if (ROOT_ID.equals(containerId)) {
            return buildRoot();
        }
        if (ID_VIDEOS.equals(containerId)) {
            return mediaEntries(filter(catalog.videos(), MediaItem.Kind.VIDEO), ID_VIDEOS);
        }
        if (ID_PHOTOS.equals(containerId)) {
            return mediaEntries(filter(catalog.photos(), MediaItem.Kind.PHOTO), ID_PHOTOS);
        }
        if (ID_MUSIC.equals(containerId)) {
            return mediaEntries(filter(catalog.audio(), MediaItem.Kind.AUDIO), ID_MUSIC);
        }
        if (ID_RECENT.equals(containerId)) {
            return mediaEntries(recent(), ID_RECENT);
        }
        if (ID_FOLDERS.equals(containerId)) {
            List<Entry> entries = new ArrayList<>();
            for (MediaItem.Kind kind : activeKinds()) {
                for (MediaCatalog.Folder folder : catalog.folders(kind)) {
                    String id = PREFIX_FOLDER + kind.name() + ":" + folder.path;
                    entries.add(new Entry(id, ID_FOLDERS, folder.name + "  (" + kindLabel(kind) + ")",
                            true, folder.count, null));
                }
            }
            Collections.sort(entries, (a, b) -> a.title.compareToIgnoreCase(b.title));
            return entries;
        }
        if (ID_ALBUMS.equals(containerId)) {
            List<Entry> entries = new ArrayList<>();
            for (String album : catalog.albums()) {
                List<MediaItem> items = catalog.byAlbum(album);
                entries.add(new Entry(PREFIX_ALBUM + album, ID_ALBUMS, album, true,
                        items.size(), null));
            }
            return entries;
        }
        if (ID_ARTISTS.equals(containerId)) {
            List<Entry> entries = new ArrayList<>();
            for (String artist : catalog.artists()) {
                List<MediaItem> items = catalog.byArtist(artist);
                entries.add(new Entry(PREFIX_ARTIST + artist, ID_ARTISTS, artist, true,
                        items.size(), null));
            }
            return entries;
        }
        if (containerId.startsWith(PREFIX_FOLDER)) {
            String rest = containerId.substring(PREFIX_FOLDER.length());
            int colon = rest.indexOf(':');
            if (colon < 0) {
                return Collections.emptyList();
            }
            MediaItem.Kind kind = MediaItem.Kind.valueOf(rest.substring(0, colon));
            String path = rest.substring(colon + 1);
            return mediaEntries(filter(catalog.folderItems(kind, path, null), kind), containerId);
        }
        if (containerId.startsWith(PREFIX_ALBUM)) {
            String album = containerId.substring(PREFIX_ALBUM.length());
            return mediaEntries(filter(catalog.byAlbum(album), MediaItem.Kind.AUDIO), containerId);
        }
        if (containerId.startsWith(PREFIX_ARTIST)) {
            String artist = containerId.substring(PREFIX_ARTIST.length());
            return mediaEntries(filter(catalog.byArtist(artist), MediaItem.Kind.AUDIO), containerId);
        }
        LogBus.get().d(TAG, "browse requested for unknown container " + containerId);
        return Collections.emptyList();
    }

    private List<Entry> buildRoot() {
        List<Entry> entries = new ArrayList<>();
        Settings.Scope scope = settings.scope();
        if (scope != Settings.Scope.AUDIO) {
            entries.add(new Entry(ID_VIDEOS, ROOT_ID, "Videos", true, filter(catalog.videos(),
                    MediaItem.Kind.VIDEO).size(), null));
            entries.add(new Entry(ID_PHOTOS, ROOT_ID, "Photos", true, filter(catalog.photos(),
                    MediaItem.Kind.PHOTO).size(), null));
        }
        if (scope != Settings.Scope.VIDEO_PHOTO) {
            entries.add(new Entry(ID_MUSIC, ROOT_ID, "Music", true, filter(catalog.audio(),
                    MediaItem.Kind.AUDIO).size(), null));
            if (!catalog.albums().isEmpty()) {
                entries.add(new Entry(ID_ALBUMS, ROOT_ID, "Albums", true,
                        catalog.albums().size(), null));
            }
            if (!catalog.artists().isEmpty()) {
                entries.add(new Entry(ID_ARTISTS, ROOT_ID, "Artists", true,
                        catalog.artists().size(), null));
            }
        }
        entries.add(new Entry(ID_RECENT, ROOT_ID, "Recently added", true, recent().size(), null));
        if (settings.folderView()) {
            entries.add(new Entry(ID_FOLDERS, ROOT_ID, "Folders", true, -1, null));
        }
        return entries;
    }

    private List<MediaItem.Kind> activeKinds() {
        List<MediaItem.Kind> kinds = new ArrayList<>(3);
        Settings.Scope scope = settings.scope();
        if (scope != Settings.Scope.AUDIO) {
            kinds.add(MediaItem.Kind.VIDEO);
            kinds.add(MediaItem.Kind.PHOTO);
        }
        if (scope != Settings.Scope.VIDEO_PHOTO) {
            kinds.add(MediaItem.Kind.AUDIO);
        }
        return kinds;
    }

    private List<MediaItem> recent() {
        List<MediaItem> merged = new ArrayList<>();
        for (MediaItem.Kind kind : activeKinds()) {
            merged.addAll(filter(catalog.of(kind), kind));
        }
        Collections.sort(merged, (a, b) -> Long.compare(timestamp(b), timestamp(a)));
        return merged.size() > 200 ? new ArrayList<>(merged.subList(0, 200)) : merged;
    }

    private static long timestamp(MediaItem item) {
        return item.dateTakenMs > 0 ? item.dateTakenMs : item.dateAddedSec * 1000L;
    }

    private List<MediaItem> filter(List<MediaItem> source, MediaItem.Kind kind) {
        boolean hide = settings.hideUnsupported();
        List<MediaItem> result = new ArrayList<>(source.size());
        for (MediaItem item : source) {
            if (hide && !compat.quick(item).verdict.playable()) {
                continue;
            }
            result.add(item);
        }
        return result;
    }

    private List<Entry> mediaEntries(List<MediaItem> items, String parentId) {
        List<Entry> entries = new ArrayList<>(items.size());
        for (MediaItem item : items) {
            entries.add(new Entry(item.objectId(), parentId, item.title, false, 0, item));
        }
        return entries;
    }

    private static String kindLabel(MediaItem.Kind kind) {
        switch (kind) {
            case PHOTO: return "Photos";
            case AUDIO: return "Music";
            default: return "Videos";
        }
    }

    // --------------------------------------------------------------- browse

    /** BrowseDirectChildren. */
    public BrowseResult browseChildren(String objectId, String sortCriteria,
                                       int startingIndex, int requestedCount, String baseUrl) {
        List<Entry> all = new ArrayList<>(children(objectId));
        applySort(all, sortCriteria);
        int total = all.size();
        int from = Math.max(0, Math.min(startingIndex, total));
        int count = requestedCount <= 0 ? total - from : requestedCount;
        count = Math.min(count, MAX_CHILDREN_PER_RESPONSE);
        int to = Math.min(total, from + count);

        StringBuilder didl = new StringBuilder(DidlLite.open());
        int returned = 0;
        String parent = objectId == null ? ROOT_ID : objectId;
        for (int i = from; i < to; i++) {
            Entry entry = all.get(i);
            if (entry.container) {
                didl.append(DidlLite.container(entry.id, parent, entry.title,
                        "object.container.storageFolder", entry.childCount, false));
            } else {
                didl.append(itemDidl(entry, parent, baseUrl));
            }
            returned++;
        }
        didl.append(DidlLite.close());
        return new BrowseResult(didl.toString(), returned, total, updateId.get());
    }

    /** BrowseMetadata for a single object. */
    public BrowseResult browseMetadata(String objectId, String baseUrl) {
        Entry entry = findEntry(objectId);
        if (entry == null) {
            return null;
        }
        String didl;
        if (entry.container) {
            int count = children(objectId).size();
            didl = DidlLite.open()
                    + DidlLite.container(entry.id, entry.parentId, entry.title,
                    "object.container.storageFolder", count, false)
                    + DidlLite.close();
        } else {
            didl = DidlLite.open() + itemDidl(entry, entry.parentId, baseUrl) + DidlLite.close();
        }
        return new BrowseResult(didl, 1, 1, updateId.get());
    }

    /** Search: the subset of SearchCriteria grammar TVs actually send. */
    public BrowseResult search(String containerId, String criteria, int startingIndex,
                               int requestedCount, String baseUrl) {
        String kindFilter = null;
        String needle = null;
        if (criteria != null) {
            String lower = criteria.toLowerCase(Locale.US);
            if (lower.contains("videoitem") || lower.contains("video/mp4") || lower.contains("video")) {
                kindFilter = "video";
            } else if (lower.contains("audioitem") || lower.contains("audio")) {
                kindFilter = "audio";
            } else if (lower.contains("imageitem") || lower.contains("image")) {
                kindFilter = "photo";
            }
            int quote = criteria.indexOf('"');
            if (quote >= 0) {
                int end = criteria.indexOf('"', quote + 1);
                if (end > quote) {
                    needle = criteria.substring(quote + 1, end);
                }
            }
        }
        List<Entry> matches = new ArrayList<>();
        List<MediaItem.Kind> kinds = new ArrayList<>();
        if ("video".equals(kindFilter)) {
            kinds.add(MediaItem.Kind.VIDEO);
        } else if ("audio".equals(kindFilter)) {
            kinds.add(MediaItem.Kind.AUDIO);
        } else if ("photo".equals(kindFilter)) {
            kinds.add(MediaItem.Kind.PHOTO);
        } else {
            kinds.addAll(activeKinds());
        }
        for (MediaItem.Kind kind : kinds) {
            List<MediaItem> found = needle == null ? filter(catalog.of(kind), kind)
                    : filter(catalog.search(kind, needle), kind);
            for (MediaItem item : found) {
                matches.add(new Entry(item.objectId(), containerId == null ? ROOT_ID : containerId,
                        item.title, false, 0, item));
            }
        }
        int total = matches.size();
        int from = Math.max(0, Math.min(startingIndex, total));
        int count = requestedCount <= 0 ? total - from : requestedCount;
        int to = Math.min(total, from + Math.min(count, MAX_CHILDREN_PER_RESPONSE));
        StringBuilder didl = new StringBuilder(DidlLite.open());
        for (int i = from; i < to; i++) {
            didl.append(itemDidl(matches.get(i), containerId == null ? ROOT_ID : containerId, baseUrl));
        }
        didl.append(DidlLite.close());
        return new BrowseResult(didl.toString(), to - from, total, updateId.get());
    }

    public Entry findEntry(String objectId) {
        if (objectId == null) {
            return null;
        }
        if (ROOT_ID.equals(objectId)) {
            return new Entry(ROOT_ID, "-1", "MediaBridge", true, children(ROOT_ID).size(), null);
        }
        MediaItem item = catalog.byObjectId(objectId);
        if (item != null) {
            return new Entry(item.objectId(), parentOf(item), item.title, false, 0, item);
        }
        for (String container : new String[]{ID_VIDEOS, ID_PHOTOS, ID_MUSIC, ID_RECENT,
                ID_FOLDERS, ID_ALBUMS, ID_ARTISTS}) {
            for (Entry entry : children(container)) {
                if (entry.id.equals(objectId)) {
                    return entry;
                }
            }
        }
        if (objectId.startsWith(PREFIX_FOLDER)) {
            return new Entry(objectId, ID_FOLDERS, objectId, true, children(objectId).size(), null);
        }
        if (objectId.startsWith(PREFIX_ALBUM)) {
            return new Entry(objectId, ID_ALBUMS, objectId.substring(PREFIX_ALBUM.length()),
                    true, children(objectId).size(), null);
        }
        if (objectId.startsWith(PREFIX_ARTIST)) {
            return new Entry(objectId, ID_ARTISTS, objectId.substring(PREFIX_ARTIST.length()),
                    true, children(objectId).size(), null);
        }
        return null;
    }

    private static String parentOf(MediaItem item) {
        return item.kind == MediaItem.Kind.PHOTO ? ID_PHOTOS
                : item.kind == MediaItem.Kind.AUDIO ? ID_MUSIC : ID_VIDEOS;
    }

    private String itemDidl(Entry entry, String parentId, String baseUrl) {
        MediaItem item = entry.item;
        CompatResult result = compat.quick(item);
        String mime = MediaCompat.effectiveMime(item, result);
        boolean converted = result.verdict == CompatResult.Verdict.PHOTO_CONVERT
                || result.verdict == CompatResult.Verdict.AUDIO_CONVERT;
        String profile = Dlna.profileFor(mime, item.kind, false, converted);
        String protocolInfo = Dlna.protocolInfo(mime, Dlna.seekable(result), converted, profile);
        String url = mediaUrl(baseUrl, item, mime);
        String thumbnailUrl = item.kind == MediaItem.Kind.PHOTO ? null
                : com.lgmediabridge.server.MediaPath.thumbnailUrl(baseUrl, item);
        long size = Dlna.advertisedSize(item, result);
        long duration = item.kind == MediaItem.Kind.PHOTO ? 0 : item.durationMs;
        return DidlLite.item(entry.id, parentId, item, url, thumbnailUrl, mime, protocolInfo,
                profile, size, duration);
    }

    /** The URL scheme lives in MediaPath, which is exercised by tools/verify.py. */
    public static String mediaUrl(String baseUrl, MediaItem item, String mime) {
        return com.lgmediabridge.server.MediaPath.mediaUrl(baseUrl, item, mime);
    }


    private static String safeName(String title) {
        if (title == null || title.trim().isEmpty()) {
            return "media";
        }
        String name = title.replaceAll("[\\r\\n\\t]", " ").trim();
        name = name.replaceAll("[/\\\\]", "-");
        return name.length() > 80 ? name.substring(0, 80) : name;
    }

    private static void applySort(List<Entry> entries, String sortCriteria) {
        if (sortCriteria == null || sortCriteria.trim().isEmpty()) {
            return;
        }
        String criteria = sortCriteria.trim();
        boolean descending = criteria.startsWith("-");
        boolean byTitle = criteria.toLowerCase(Locale.US).contains("title");
        boolean byDate = criteria.toLowerCase(Locale.US).contains("date");
        if (byTitle) {
            Collections.sort(entries, (a, b) -> {
                int result = a.title.compareToIgnoreCase(b.title);
                return descending ? -result : result;
            });
        } else if (byDate) {
            Collections.sort(entries, (a, b) -> {
                long left = a.item == null ? 0 : timestamp(a.item);
                long right = b.item == null ? 0 : timestamp(b.item);
                int result = Long.compare(left, right);
                return descending ? -result : result;
            });
        }
    }

    /** Source protocolInfo list reported by ConnectionManager. */
    public String sourceProtocolInfo() {
        StringBuilder sb = new StringBuilder();
        appendProtocol(sb, Dlna.protocolInfo("video/mp4", true, false, null));
        appendProtocol(sb, Dlna.protocolInfo("video/x-matroska", true, false, null));
        appendProtocol(sb, Dlna.protocolInfo("video/mpeg", true, false, null));
        appendProtocol(sb, Dlna.protocolInfo("video/mp2t", true, false, null));
        appendProtocol(sb, Dlna.protocolInfo("video/x-msvideo", true, false, null));
        appendProtocol(sb, Dlna.protocolInfo("video/x-ms-wmv", true, false, null));
        appendProtocol(sb, Dlna.protocolInfo("video/quicktime", true, false, null));
        appendProtocol(sb, Dlna.protocolInfo("audio/mpeg", true, false, "MP3"));
        appendProtocol(sb, Dlna.protocolInfo("audio/mp4", true, false, "AAC_ISO_320"));
        appendProtocol(sb, Dlna.protocolInfo("audio/flac", true, false, null));
        appendProtocol(sb, Dlna.protocolInfo("audio/x-ms-wma", true, false, null));
        appendProtocol(sb, Dlna.protocolInfo("audio/ac3", true, false, "AC3"));
        appendProtocol(sb, Dlna.protocolInfo("image/jpeg", true, false, "JPEG_LRG"));
        appendProtocol(sb, Dlna.protocolInfo("image/png", true, false, "PNG_LRG"));
        appendProtocol(sb, Dlna.protocolInfo("image/gif", true, false, "GIF_LRG"));
        appendProtocol(sb, "http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN");
        return sb.toString();
    }

    private static void appendProtocol(StringBuilder sb, String value) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append(value);
    }
}
