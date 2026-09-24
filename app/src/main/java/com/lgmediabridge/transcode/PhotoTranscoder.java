package com.lgmediabridge.transcode;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import com.lgmediabridge.catalog.MediaItem;
import com.lgmediabridge.core.LogBus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * On-the-fly photo conversion.
 *
 * Why this exists: webOS 6.0 displays JPEG, GIF, PNG, BMP and WebP. Modern
 * phones store HEIC/HEIF by default, and a 50 MP JPEG can exceed what the TV's
 * photo path handles comfortably - so those images are decoded on the phone and
 * re-encoded as JPEG at a sensible size. Everything else is streamed untouched.
 *
 * Results are cached on disk (keyed by the source file's size so an edited photo
 * is re-converted), which makes flipping through an album on the TV instant.
 */
public final class PhotoTranscoder {

    private static final String TAG = "PhotoTranscoder";
    public static final int TV_MAX_DIMENSION = 2560;
    private static final int JPEG_QUALITY = 88;

    private final Context context;
    private final TranscodeCache cache;

    public PhotoTranscoder(Context context) {
        this.context = context.getApplicationContext();
        this.cache = new TranscodeCache(this.context.getCacheDir(), "photos");
    }

    public static final class Result {
        public final File file;
        public final String mime = "image/jpeg";
        public final long length;
        public final boolean converted;

        Result(File file, boolean converted) {
            this.file = file;
            this.length = file.length();
            this.converted = converted;
        }
    }

    /** Returns a cached or freshly produced JPEG for {@code item}. */
    public Result ensure(MediaItem item, int maxDimension, boolean allowConversion) throws IOException {
        String key = cacheKey(item, maxDimension);
        File target = cache.target(key);
        // A zero-byte file is a failure, not a cache hit: it would be served as
        // an empty response and the TV would show a broken image.
        if (TranscodeCache.isUsable(target)) {
            return new Result(target, true);
        }
        if (!allowConversion) {
            throw new IOException("conversion disabled");
        }
        cache.prepare();
        long startedAt = System.currentTimeMillis();
        // A unique name per attempt: two requests for the same photo cannot
        // interleave into one file and hand the TV a corrupt JPEG.
        File temp = cache.newPart(key);
        try {
            Bitmap bitmap = decode(item, maxDimension);
            if (bitmap == null) {
                throw new IOException("this phone cannot decode the image format ("
                        + item.mimeType + ")");
            }
            Bitmap rotated = applyOrientation(item, bitmap);
            try (FileOutputStream out = new FileOutputStream(temp)) {
                if (!rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    throw new IOException("JPEG encoding failed");
                }
            } finally {
                if (rotated != bitmap) {
                    rotated.recycle();
                }
                bitmap.recycle();
            }
            File stored = cache.publish(temp, key);
            LogBus.get().i(TAG, "converted " + item.displayName + " → " + stored.getName()
                    + " (" + stored.length() + " bytes, "
                    + (System.currentTimeMillis() - startedAt) + " ms)");
            return new Result(stored, true);
        } catch (IOException e) {
            temp.delete();
            throw e;
        } catch (RuntimeException e) {
            temp.delete();
            throw e;
        }
    }

    public File cacheFile(MediaItem item, int maxDimension) {
        return cache.target(cacheKey(item, maxDimension));
    }

    private static String cacheKey(MediaItem item, int maxDimension) {
        return item.objectId() + "_" + item.sizeBytes + "_" + maxDimension + ".jpg";
    }

    private Bitmap decode(MediaItem item, int maxDimension) {
        Uri uri = Uri.parse(item.uri);
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                if (stream == null) {
                    return null;
                }
                BitmapFactory.decodeStream(stream, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / (sample * 2) >= maxDimension) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                if (stream == null) {
                    return null;
                }
                Bitmap decoded = BitmapFactory.decodeStream(stream, null, options);
                if (decoded == null) {
                    return null;
                }
                return scaleDown(decoded, maxDimension);
            }
        } catch (OutOfMemoryError e) {
            // Huge panoramas: retry once with a hard 1/8 sample.
            LogBus.get().w(TAG, "out of memory decoding " + item.displayName + ", retrying subsampled");
            return decodeSubsampled(uri, 8);
        } catch (Exception e) {
            LogBus.get().d(TAG, "decode failed for " + item.displayName + ": " + e.getMessage());
            return null;
        }
    }

    private Bitmap decodeSubsampled(Uri uri, int sample) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                return stream == null ? null : BitmapFactory.decodeStream(stream, null, options);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap scaleDown(Bitmap source, int maxDimension) {
        int largest = Math.max(source.getWidth(), source.getHeight());
        if (largest <= maxDimension) {
            return source;
        }
        float scale = maxDimension / (float) largest;
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, true);
        if (scaled != source) {
            source.recycle();
        }
        return scaled;
    }

    /** Bakes the EXIF orientation into the pixels (TVs ignore EXIF rotation). */
    private Bitmap applyOrientation(MediaItem item, Bitmap bitmap) {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream stream = context.getContentResolver().openInputStream(Uri.parse(item.uri))) {
            if (stream != null) {
                ExifInterface exif = new ExifInterface(stream);
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Exception e) {
            return bitmap;
        }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.postScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.postRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.postRotate(270);
                matrix.postScale(-1, 1);
                break;
            default: return bitmap;
        }
        try {
            Bitmap transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
                    bitmap.getHeight(), matrix, true);
            if (transformed != bitmap) {
                bitmap.recycle();
            }
            return transformed;
        } catch (Exception e) {
            return bitmap;
        }
    }

    public long cacheSize() {
        return cache.size();
    }

    public void clearCache() {
        cache.clear();
    }

    /** Removes old conversions so the cache cannot grow without bound. */
    public void trimCache(long maxBytes) {
        cache.trim(maxBytes);
    }

    /** Removes half-written conversions left behind by an interrupted run. */
    public int clearStaleParts(long olderThanMillis) {
        return cache.clearStaleParts(olderThanMillis);
    }
}
