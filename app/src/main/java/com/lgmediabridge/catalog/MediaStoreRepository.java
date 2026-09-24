package com.lgmediabridge.catalog;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.lgmediabridge.core.LogBus;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the device library through MediaStore only.
 *
 * This is the scoped-storage-safe path: no {@code READ_EXTERNAL_STORAGE}
 * workaround, no direct file paths (the {@code DATA} column is only consulted on
 * API < 29 where it is still the canonical folder source), and no access to
 * anything the user has not granted. Everything the TV can browse came through
 * MediaStore's own permission model.
 */
public final class MediaStoreRepository {

    private static final String TAG = "MediaStore";

    private final Context context;

    public MediaStoreRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public List<MediaItem> loadVideos() {
        List<MediaItem> items = new ArrayList<>();
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = join(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                relativePathColumn(),
                MediaStore.Video.Media.ARTIST,
                MediaStore.Video.Media.ALBUM,
                MediaStore.Video.Media.RESOLUTION);
        String sort = MediaStore.Video.Media.DATE_ADDED + " DESC";
        try (Cursor cursor = query(collection, projection, sort)) {
            if (cursor == null) {
                return items;
            }
            while (cursor.moveToNext()) {
                MediaItem.Builder builder = new MediaItem.Builder()
                        .kind(MediaItem.Kind.VIDEO)
                        .storeId(longAt(cursor, MediaStore.Video.Media._ID))
                        .displayName(stringAt(cursor, MediaStore.Video.Media.DISPLAY_NAME))
                        .title(stringAt(cursor, MediaStore.Video.Media.TITLE))
                        .mimeType(stringAt(cursor, MediaStore.Video.Media.MIME_TYPE))
                        .sizeBytes(longAt(cursor, MediaStore.Video.Media.SIZE))
                        .durationMs(longAt(cursor, MediaStore.Video.Media.DURATION))
                        .size(intAt(cursor, MediaStore.Video.Media.WIDTH), intAt(cursor, MediaStore.Video.Media.HEIGHT))
                        .dateAddedSec(longAt(cursor, MediaStore.Video.Media.DATE_ADDED))
                        .dateTakenMs(longAt(cursor, MediaStore.Video.Media.DATE_TAKEN))
                        .bucketName(stringAt(cursor, MediaStore.Video.Media.BUCKET_DISPLAY_NAME))
                        .folderPath(folderFrom(cursor))
                        .artist(stringAt(cursor, MediaStore.Video.Media.ARTIST))
                        .album(stringAt(cursor, MediaStore.Video.Media.ALBUM));
                builder.uri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        .buildUpon().appendPath(String.valueOf(longAt(cursor, MediaStore.Video.Media._ID)))
                        .build().toString());
                items.add(builder.build());
            }
        } catch (SecurityException e) {
            LogBus.get().w(TAG, "video access denied: " + e.getMessage());
        }
        return items;
    }

    public List<MediaItem> loadPhotos() {
        List<MediaItem> items = new ArrayList<>();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = join(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.TITLE,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                relativePathColumn());
        String sort = MediaStore.Images.Media.DATE_TAKEN + " DESC, "
                + MediaStore.Images.Media.DATE_ADDED + " DESC";
        try (Cursor cursor = query(collection, projection, sort)) {
            if (cursor == null) {
                return items;
            }
            while (cursor.moveToNext()) {
                long id = longAt(cursor, MediaStore.Images.Media._ID);
                MediaItem.Builder builder = new MediaItem.Builder()
                        .kind(MediaItem.Kind.PHOTO)
                        .storeId(id)
                        .displayName(stringAt(cursor, MediaStore.Images.Media.DISPLAY_NAME))
                        .title(stringAt(cursor, MediaStore.Images.Media.TITLE))
                        .mimeType(stringAt(cursor, MediaStore.Images.Media.MIME_TYPE))
                        .sizeBytes(longAt(cursor, MediaStore.Images.Media.SIZE))
                        .size(intAt(cursor, MediaStore.Images.Media.WIDTH),
                                intAt(cursor, MediaStore.Images.Media.HEIGHT))
                        .dateAddedSec(longAt(cursor, MediaStore.Images.Media.DATE_ADDED))
                        .dateTakenMs(longAt(cursor, MediaStore.Images.Media.DATE_TAKEN))
                        .bucketName(stringAt(cursor, MediaStore.Images.Media.BUCKET_DISPLAY_NAME))
                        .folderPath(folderFrom(cursor));
                builder.uri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        .buildUpon().appendPath(String.valueOf(id)).build().toString());
                items.add(builder.build());
            }
        } catch (SecurityException e) {
            LogBus.get().w(TAG, "photo access denied: " + e.getMessage());
        }
        return items;
    }

    public List<MediaItem> loadAudio() {
        List<MediaItem> items = new ArrayList<>();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = join(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.TRACK,
                relativePathColumn());
        String selection = MediaStore.Audio.Media.IS_MUSIC + "!=0 OR "
                + MediaStore.Audio.Media.DURATION + ">30000";
        try (Cursor cursor = query(collection, projection, selection,
                MediaStore.Audio.Media.ARTIST + " ASC, " + MediaStore.Audio.Media.TITLE + " ASC")) {
            if (cursor == null) {
                return items;
            }
            while (cursor.moveToNext()) {
                long id = longAt(cursor, MediaStore.Audio.Media._ID);
                MediaItem.Builder builder = new MediaItem.Builder()
                        .kind(MediaItem.Kind.AUDIO)
                        .storeId(id)
                        .displayName(stringAt(cursor, MediaStore.Audio.Media.DISPLAY_NAME))
                        .title(stringAt(cursor, MediaStore.Audio.Media.TITLE))
                        .mimeType(stringAt(cursor, MediaStore.Audio.Media.MIME_TYPE))
                        .sizeBytes(longAt(cursor, MediaStore.Audio.Media.SIZE))
                        .durationMs(longAt(cursor, MediaStore.Audio.Media.DURATION))
                        .dateAddedSec(longAt(cursor, MediaStore.Audio.Media.DATE_ADDED))
                        .artist(stringAt(cursor, MediaStore.Audio.Media.ARTIST))
                        .album(stringAt(cursor, MediaStore.Audio.Media.ALBUM))
                        .albumId(longAt(cursor, MediaStore.Audio.Media.ALBUM_ID))
                        .trackNumber(intAt(cursor, MediaStore.Audio.Media.TRACK))
                        .folderPath(folderFrom(cursor));
                builder.uri(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        .buildUpon().appendPath(String.valueOf(id)).build().toString());
                items.add(builder.build());
            }
        } catch (SecurityException e) {
            LogBus.get().w(TAG, "audio access denied: " + e.getMessage());
        }
        return items;
    }

    /** True when at least one media type is readable right now. */
    public boolean hasAnyAccess() {
        return !loadPhotos().isEmpty() || !loadVideos().isEmpty() || !loadAudio().isEmpty();
    }

    private Cursor query(Uri uri, String[] projection, String sort) {
        return query(uri, projection, null, sort);
    }

    private Cursor query(Uri uri, String[] projection, String selection, String sort) {
        ContentResolver resolver = context.getContentResolver();
        try {
            return resolver.query(uri, projection, selection, null, sort);
        } catch (IllegalArgumentException | SecurityException e) {
            LogBus.get().w(TAG, "query failed for " + uri + ": " + e.getMessage());
            return null;
        }
    }

    private static String relativePathColumn() {
        return Build.VERSION.SDK_INT >= 29
                ? MediaStore.MediaColumns.RELATIVE_PATH
                : MediaStore.MediaColumns.DATA;
    }

    /** Normalises the folder of a row: real folder on API < 29, RELATIVE_PATH otherwise. */
    private static String folderFrom(Cursor cursor) {
        String value = stringAt(cursor, relativePathColumn());
        if (value == null || value.isEmpty()) {
            return "/";
        }
        if (Build.VERSION.SDK_INT >= 29) {
            value = value.replaceAll("/+$", "");
            return value.startsWith("/") ? value : "/" + value;
        }
        int slash = value.lastIndexOf('/');
        return slash <= 0 ? "/" : value.substring(0, slash);
    }

    private static String[] join(String... columns) {
        List<String> unique = new ArrayList<>(columns.length);
        for (String column : columns) {
            if (!unique.contains(column)) {
                unique.add(column);
            }
        }
        return unique.toArray(new String[0]);
    }

    private static String stringAt(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0) {
            return null;
        }
        try {
            return cursor.getString(index);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long longAt(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0) {
            return 0L;
        }
        try {
            return cursor.getLong(index);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static int intAt(Cursor cursor, String column) {
        return (int) longAt(cursor, column);
    }
}
