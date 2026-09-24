package com.lgmediabridge.catalog;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.util.LruCache;
import android.util.Size;

import com.lgmediabridge.core.LogBus;

import java.io.ByteArrayOutputStream;

/**
 * Thumbnails for the TV (JPEG_TN) and for the phone UI.
 *
 * Uses the modern {@code ContentResolver.loadThumbnail} path on API 29+ and the
 * MediaStore helper below that, with a byte-bounded LRU cache so a photo grid
 * scroll never allocates more than a few megabytes.
 */
public final class Thumbnails {

    private static final String TAG = "Thumbnails";
    private static final int CACHE_BYTES = 24 * 1024 * 1024;

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private Thumbnails() {
    }

    /** Best-effort bitmap for a grid cell; returns null when nothing can be decoded. */
    public static Bitmap load(Context context, MediaItem item, int targetPx) {
        String key = item.objectId() + "@" + targetPx;
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Bitmap bitmap = decode(context, item, targetPx);
        if (bitmap != null) {
            CACHE.put(key, bitmap);
        }
        return bitmap;
    }

    private static Bitmap decode(Context context, MediaItem item, int targetPx) {
        Uri uri = Uri.parse(item.uri);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return context.getContentResolver().loadThumbnail(uri, new Size(targetPx, targetPx), null);
            }
        } catch (Exception e) {
            LogBus.get().d(TAG, "loadThumbnail failed for " + item.displayName + ": " + e.getMessage());
        }
        if (item.kind == MediaItem.Kind.AUDIO) {
            return embeddedArtwork(context, item, targetPx);
        }
        return decodeSampled(context, uri, targetPx);
    }

    private static Bitmap decodeSampled(Context context, Uri uri, int targetPx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream stream = context.getContentResolver().openInputStream(uri)) {
                if (stream == null) {
                    return null;
                }
                BitmapFactory.decodeStream(stream, null, bounds);
            }
            int sample = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / sample > targetPx * 2) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            try (java.io.InputStream stream = context.getContentResolver().openInputStream(uri)) {
                if (stream == null) {
                    return null;
                }
                return BitmapFactory.decodeStream(stream, null, options);
            }
        } catch (Exception e) {
            LogBus.get().d(TAG, "decode failed for " + uri + ": " + e.getMessage());
            return null;
        }
    }

    private static Bitmap embeddedArtwork(Context context, MediaItem item, int targetPx) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, Uri.parse(item.uri));
            byte[] picture = retriever.getEmbeddedPicture();
            if (picture == null) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(picture, 0, picture.length, bounds);
            int sample = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / sample > targetPx * 2) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(picture, 0, picture.length, options);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // nothing to do
            }
        }
    }

    /** JPEG bytes for the DLNA {@code upnp:albumArtURI} artwork resource. */
    public static byte[] jpeg(Context context, MediaItem item, int targetPx, int quality) {
        Bitmap bitmap = load(context, item, targetPx);
        if (bitmap == null) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return out.toByteArray();
    }

    public static void clearCache() {
        CACHE.evictAll();
    }
}
