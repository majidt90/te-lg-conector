package com.lgmediabridge.transcode;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/**
 * The disk cache both converters share: where converted files live, how a
 * half-written one is kept out of the way, and how the cache is kept from
 * growing without limit.
 *
 * Deliberately free of Android types, so the rules that decide whether a user's
 * storage grows forever are exercised by tools/verify.py on a plain JVM.
 *
 * Two invariants matter, and both are the kind that fail silently in production:
 * <ul>
 *   <li>a file in the cache is complete - a conversion is written to a unique
 *       {@code .part} name and renamed into place only when it is finished, so a
 *       kill in the middle, or two requests for the same photo at once, can never
 *       leave a truncated file that the television would then be served;</li>
 *   <li>the cache has a ceiling - trimming removes the least recently used
 *       conversions first, so converting an album does not fill the phone.</li>
 * </ul>
 */
public final class TranscodeCache {

    /** Budget applied to each cache (photos, converted audio). */
    public static final long DEFAULT_BUDGET_BYTES = 96L * 1024 * 1024;

    private final File directory;

    public TranscodeCache(File parent, String name) {
        this.directory = new File(parent, name);
    }

    public File directory() {
        return directory;
    }

    /** The finished file for a key, cached or not. */
    public File target(String key) {
        return new File(directory, key);
    }

    /** True when the file exists and has content (a zero-byte file is a failure). */
    public static boolean isUsable(File file) {
        return file != null && file.exists() && file.length() > 0;
    }

    public boolean has(String key) {
        return isUsable(target(key));
    }

    /**
     * A private name to write a conversion into: unique per call, so two
     * concurrent conversions of the same item cannot interleave into one file.
     */
    public File newPart(String key) {
        return new File(directory, key + "." + Long.toHexString(System.nanoTime())
                + "." + Thread.currentThread().getId() + ".part");
    }

    /** Makes sure the directory exists; throws when it cannot be created. */
    public void prepare() throws IOException {
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
            throw new IOException("cannot create cache directory: " + directory);
        }
    }

    /**
     * Moves a finished conversion into place.
     *
     * The rename is atomic, so the published name only ever refers to a complete
     * file; if another request produced the same conversion first, replacing it is
     * harmless (both are equivalent) and the scratch file is removed either way.
     *
     * @throws IOException when neither this call nor anyone else produced a file
     */
    public File publish(File part, String key) throws IOException {
        File destination = target(key);
        if (part.renameTo(destination)) {
            return destination;
        }
        part.delete();
        if (isUsable(destination)) {
            return destination;
        }
        throw new IOException("could not store converted file");
    }

    public long size() {
        return sizeOf(directory);
    }

    public void clear() {
        deleteContents(directory);
    }

    /** Deletes the oldest conversions until the cache fits inside {@code maxBytes}. */
    public void trim(long maxBytes) {
        if (sizeOf(directory) <= maxBytes) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long size = sizeOf(directory);
        for (File file : files) {
            if (size <= maxBytes) {
                break;
            }
            if (isPart(file)) {
                // A conversion in progress: worth more than a few megabytes of
                // budget, and it will be accounted for when it is published.
                continue;
            }
            size -= file.length();
            file.delete();
        }
    }

    private static boolean isPart(File file) {
        return file.getName().endsWith(".part");
    }

    /**
     * Removes leftovers from conversions that were interrupted, and reports how
     * many it removed.
     *
     * Only files untouched for longer than {@code olderThanMillis} are candidates:
     * a conversion in progress also writes to a .part file, and deleting that
     * would abort a request the television is waiting on. A conversion has its own
     * wall-clock limit, so anything older than that is certainly dead.
     */
    public int clearStaleParts(long olderThanMillis) {
        long cutoff = System.currentTimeMillis() - olderThanMillis;
        File[] files = directory.listFiles();
        if (files == null) {
            return 0;
        }
        int removed = 0;
        for (File file : files) {
            if (file.isFile() && isPart(file)
                    && file.lastModified() < cutoff && file.delete()) {
                removed++;
            }
        }
        return removed;
    }

    static long sizeOf(File directory) {
        File[] files = directory.listFiles();
        if (files == null) {
            return 0;
        }
        long total = 0;
        for (File file : files) {
            total += file.isDirectory() ? sizeOf(file) : file.length();
        }
        return total;
    }

    static void deleteContents(File directory) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                deleteContents(file);
            }
            file.delete();
        }
    }
}
