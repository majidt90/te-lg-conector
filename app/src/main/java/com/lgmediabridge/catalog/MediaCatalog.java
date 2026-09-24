package com.lgmediabridge.catalog;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import com.lgmediabridge.App;
import com.lgmediabridge.core.LogBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The browsable library: an immutable snapshot index of everything MediaStore
 * exposes, rebuilt in the background.
 *
 * Design decisions that keep the app responsive with 50 000 photos:
 *   * building the index never happens on the main thread;
 *   * readers always see a complete snapshot (old one stays valid until the new
 *     one is ready), so DLNA browse requests never block or see half a library;
 *   * MediaStore changes are observed and coalesced, so a new photo does not
 *     trigger dozens of rebuilds;
 *   * folder views are derived lazily and cached.
 */
public final class MediaCatalog {

    public enum Status { IDLE, INDEXING, READY, NO_PERMISSION, ERROR }

    public interface Listener {
        void onCatalogChanged(MediaCatalog catalog);
    }

    public static final class Folder {
        public final String path;
        public final String name;
        public final int count;
        public final MediaItem.Kind kind;

        Folder(String path, String name, int count, MediaItem.Kind kind) {
            this.path = path;
            this.name = name;
            this.count = count;
            this.kind = kind;
        }
    }

    private static final String TAG = "Catalog";
    private static final long STALE_AFTER_MS = 60_000;
    private static final long COALESCE_MS = 1_500;

    private final Context context;
    private final MediaStoreRepository repository;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean indexing = new AtomicBoolean();

    private volatile List<MediaItem> videos = Collections.emptyList();
    private volatile List<MediaItem> photos = Collections.emptyList();
    private volatile List<MediaItem> audio = Collections.emptyList();
    private volatile Map<String, MediaItem> byObjectId = Collections.emptyMap();
    private volatile Status status = Status.IDLE;
    private volatile String error;
    private volatile long lastIndexedAt;

    private volatile Map<String, Map<String, List<MediaItem>>> groupedCache = Collections.emptyMap();
    private volatile Map<String, List<Folder>> folderListCache = Collections.emptyMap();
    private ContentObserver observer;
    private Handler mainHandler;
    private Runnable pendingRefresh;

    public MediaCatalog(Context context) {
        this.context = context.getApplicationContext();
        this.repository = new MediaStoreRepository(this.context);
    }

    // ------------------------------------------------------------- indexing

    public void start() {
        registerObserver();
        if (lastIndexedAt == 0 || isStale()) {
            refresh(true);
        }
    }

    public void refresh(boolean force) {
        if (indexing.get()) {
            return;
        }
        if (!force && !isStale()) {
            return;
        }
        if (!indexing.compareAndSet(false, true)) {
            return;
        }
        status = Status.INDEXING;
        notifyListeners();
        App.get().runOnControlPool(this::indexNow);
    }

    private void indexNow() {
        long startedAt = System.currentTimeMillis();
        try {
            List<MediaItem> newVideos = repository.loadVideos();
            List<MediaItem> newPhotos = repository.loadPhotos();
            List<MediaItem> newAudio = repository.loadAudio();
            if (newVideos.isEmpty() && newPhotos.isEmpty() && newAudio.isEmpty()
                    && !hasMediaStoreRows()) {
                status = Status.NO_PERMISSION;
                error = "Media permission not granted (or the device has no media)";
            } else {
                Map<String, MediaItem> index = new HashMap<>(
                        (newVideos.size() + newPhotos.size() + newAudio.size()) * 2);
                for (MediaItem item : newVideos) {
                    index.put(item.objectId(), item);
                }
                for (MediaItem item : newPhotos) {
                    index.put(item.objectId(), item);
                }
                for (MediaItem item : newAudio) {
                    index.put(item.objectId(), item);
                }
                videos = newVideos;
                photos = newPhotos;
                audio = newAudio;
                byObjectId = index;
                groupedCache = Collections.emptyMap();
                folderListCache = Collections.emptyMap();
                status = Status.READY;
                error = null;
                lastIndexedAt = System.currentTimeMillis();
                LogBus.get().i(TAG, "indexed " + newVideos.size() + " videos, "
                        + newPhotos.size() + " photos, " + newAudio.size() + " audio in "
                        + (System.currentTimeMillis() - startedAt) + " ms");
            }
        } catch (RuntimeException e) {
            status = Status.ERROR;
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            LogBus.get().e(TAG, "indexing failed", e);
        } finally {
            indexing.set(false);
            notifyListeners();
        }
    }

    private boolean hasMediaStoreRows() {
        try (android.database.Cursor cursor = context.getContentResolver().query(
                MediaStore.Files.getContentUri("external"),
                new String[]{MediaStore.Files.FileColumns._ID}, null, null, null)) {
            return cursor != null && cursor.getCount() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isStale() {
        return System.currentTimeMillis() - lastIndexedAt > STALE_AFTER_MS;
    }

    private void registerObserver() {
        if (observer != null) {
            return;
        }
        mainHandler = new Handler(Looper.getMainLooper());
        observer = new ContentObserver(mainHandler) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                scheduleCoalescedRefresh();
            }
        };
        android.content.ContentResolver resolver = context.getContentResolver();
        try {
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer);
            resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer);
        } catch (RuntimeException e) {
            LogBus.get().w(TAG, "could not observe MediaStore: " + e.getMessage());
        }
    }

    private void scheduleCoalescedRefresh() {
        if (mainHandler == null) {
            return;
        }
        if (pendingRefresh != null) {
            mainHandler.removeCallbacks(pendingRefresh);
        }
        pendingRefresh = () -> {
            pendingRefresh = null;
            refresh(true);
        };
        mainHandler.postDelayed(pendingRefresh, COALESCE_MS);
    }

    // -------------------------------------------------------------- access

    public List<MediaItem> videos() {
        return videos;
    }

    public List<MediaItem> photos() {
        return photos;
    }

    public List<MediaItem> audio() {
        return audio;
    }

    public List<MediaItem> of(MediaItem.Kind kind) {
        switch (kind) {
            case PHOTO: return photos;
            case AUDIO: return audio;
            default: return videos;
        }
    }

    public List<MediaItem> all() {
        List<MediaItem> all = new ArrayList<>(videos.size() + photos.size() + audio.size());
        all.addAll(videos);
        all.addAll(photos);
        all.addAll(audio);
        return all;
    }

    public MediaItem byObjectId(String objectId) {
        if (objectId == null) {
            return null;
        }
        return byObjectId.get(objectId);
    }

    public int totalCount() {
        return videos.size() + photos.size() + audio.size();
    }

    public Status status() {
        return status;
    }

    public String error() {
        return error;
    }

    public boolean isIndexing() {
        return indexing.get();
    }

    public long lastIndexedAt() {
        return lastIndexedAt;
    }

    /** Folder listing for one media kind, computed once per index generation. */
    public List<Folder> folders(MediaItem.Kind kind) {
        Map<String, List<Folder>> folderLists = folderListCache;
        String key = kind.name();
        List<Folder> existing = folderLists.get(key);
        if (existing != null) {
            return existing;
        }
        Map<String, List<MediaItem>> grouped = groupedByKind(kind);
        List<Folder> folders = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<MediaItem>> entry : grouped.entrySet()) {
            folders.add(new Folder(entry.getKey(), displayNameOf(entry.getKey()),
                    entry.getValue().size(), kind));
        }
        Collections.sort(folders, (a, b) -> a.name.compareToIgnoreCase(b.name));
        Map<String, List<Folder>> updated = new LinkedHashMap<>(folderLists);
        updated.put(key, folders);
        folderListCache = updated;
        return folders;
    }

    /** Folder grouping for one media kind, cached per index generation. */
    private Map<String, List<MediaItem>> groupedByKind(MediaItem.Kind kind) {
        Map<String, Map<String, List<MediaItem>>> cache = groupedCache;
        Map<String, List<MediaItem>> existing = cache.get(kind.name());
        if (existing != null) {
            return existing;
        }
        Map<String, List<MediaItem>> grouped = groupByFolder(of(kind));
        Map<String, Map<String, List<MediaItem>>> updated = new LinkedHashMap<>(cache);
        updated.put(kind.name(), grouped);
        groupedCache = updated;
        return grouped;
    }

    public List<MediaItem> folderItems(MediaItem.Kind kind, String path, String albumName) {
        if (albumName != null) {
            List<MediaItem> result = new ArrayList<>();
            for (MediaItem item : of(kind)) {
                if (albumName.equals(item.album)) {
                    result.add(item);
                }
            }
            return result;
        }
        List<MediaItem> items = groupedByKind(kind).get(path);
        return items == null ? Collections.emptyList() : items;
    }

    public List<String> albums() {
        List<String> albums = new ArrayList<>();
        for (MediaItem item : audio) {
            if (item.album != null && !item.album.isEmpty() && !albums.contains(item.album)) {
                albums.add(item.album);
            }
        }
        Collections.sort(albums, String::compareToIgnoreCase);
        return albums;
    }

    public List<String> artists() {
        List<String> artists = new ArrayList<>();
        for (MediaItem item : audio) {
            if (item.artist != null && !item.artist.isEmpty() && !artists.contains(item.artist)) {
                artists.add(item.artist);
            }
        }
        Collections.sort(artists, String::compareToIgnoreCase);
        return artists;
    }

    public List<MediaItem> byAlbum(String album) {
        List<MediaItem> result = new ArrayList<>();
        for (MediaItem item : audio) {
            if (album.equals(item.album)) {
                result.add(item);
            }
        }
        Collections.sort(result, (a, b) -> Integer.compare(a.trackNumber, b.trackNumber));
        return result;
    }

    public List<MediaItem> byArtist(String artist) {
        List<MediaItem> result = new ArrayList<>();
        for (MediaItem item : audio) {
            if (artist.equals(item.artist)) {
                result.add(item);
            }
        }
        return result;
    }

    /** Case-insensitive search across titles, artists and albums. */
    public List<MediaItem> search(MediaItem.Kind kind, String query) {
        List<MediaItem> result = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            return result;
        }
        String needle = query.trim().toLowerCase(java.util.Locale.US);
        for (MediaItem item : of(kind)) {
            if (item.title.toLowerCase(java.util.Locale.US).contains(needle)
                    || (item.artist != null && item.artist.toLowerCase(java.util.Locale.US).contains(needle))
                    || (item.album != null && item.album.toLowerCase(java.util.Locale.US).contains(needle))
                    || item.displayName.toLowerCase(java.util.Locale.US).contains(needle)) {
                result.add(item);
                if (result.size() >= 500) {
                    break;
                }
            }
        }
        return result;
    }

    private Map<String, List<MediaItem>> groupByFolder(List<MediaItem> source) {
        Map<String, List<MediaItem>> grouped = new LinkedHashMap<>();
        for (MediaItem item : source) {
            String folder = item.folder();
            List<MediaItem> bucket = grouped.get(folder);
            if (bucket == null) {
                bucket = new ArrayList<>();
                grouped.put(folder, bucket);
            }
            bucket.add(item);
        }
        return grouped;
    }

    private static String displayNameOf(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "Root";
        }
        String trimmed = path.replaceAll("/+$", "");
        int slash = trimmed.lastIndexOf('/');
        String name = slash < 0 ? trimmed : trimmed.substring(slash + 1);
        return name.isEmpty() ? trimmed : name;
    }

    // ----------------------------------------------------------- listeners

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Listener listener : listeners) {
            try {
                listener.onCatalogChanged(this);
            } catch (RuntimeException e) {
                LogBus.get().e(TAG, "listener failed", e);
            }
        }
    }

    public String statsLine() {
        return videos.size() + " videos · " + photos.size() + " photos · " + audio.size() + " audio";
    }
}
