package com.lgmediabridge.catalog;

import java.util.Locale;

/**
 * One browsable media object: a row from MediaStore plus the metadata the DLNA
 * layer and the compatibility engine need.
 *
 * Instances are immutable snapshots - the catalog can be rebuilt in the
 * background while the TV browses an older index without ever seeing a
 * partially updated object.
 */
public final class MediaItem {

    public enum Kind {
        VIDEO, PHOTO, AUDIO;

        /** Object ID prefix; also keeps IDs stable and readable in logs. */
        public String prefix() {
            switch (this) {
                case VIDEO: return "v";
                case PHOTO: return "p";
                default: return "a";
            }
        }

        public static Kind of(String objectId) {
            if (objectId == null || objectId.isEmpty()) {
                return VIDEO;
            }
            switch (objectId.charAt(0)) {
                case 'p': return PHOTO;
                case 'a': return AUDIO;
                default: return VIDEO;
            }
        }
    }

    public final Kind kind;
    public final long storeId;
    public final String uri;
    public final String displayName;
    public final String title;
    public final String mimeType;
    public final long sizeBytes;
    public final long durationMs;
    public final int width;
    public final int height;
    public final long dateAddedSec;
    public final long dateTakenMs;
    public final String bucketName;
    public final String folderPath;
    public final String artist;
    public final String album;
    public final long albumId;
    public final int trackNumber;

    private MediaItem(Builder builder) {
        this.kind = builder.kind;
        this.storeId = builder.storeId;
        this.uri = builder.uri;
        this.displayName = builder.displayName;
        this.title = builder.title;
        this.mimeType = builder.mimeType;
        this.sizeBytes = builder.sizeBytes;
        this.durationMs = builder.durationMs;
        this.width = builder.width;
        this.height = builder.height;
        this.dateAddedSec = builder.dateAddedSec;
        this.dateTakenMs = builder.dateTakenMs;
        this.bucketName = builder.bucketName;
        this.folderPath = builder.folderPath;
        this.artist = builder.artist;
        this.album = builder.album;
        this.albumId = builder.albumId;
        this.trackNumber = builder.trackNumber;
    }

    /** Stable DLNA object id, e.g. {@code v1024}. */
    public String objectId() {
        return kind.prefix() + storeId;
    }

    public String extension() {
        int dot = displayName.lastIndexOf('.');
        if (dot < 0 || dot == displayName.length() - 1) {
            return "";
        }
        return displayName.substring(dot + 1).toLowerCase(Locale.US);
    }

    /** Folder used by the "Folders" view; never null. */
    public String folder() {
        return folderPath == null || folderPath.isEmpty() ? "/" : folderPath;
    }

    public String resolution() {
        if (width <= 0 || height <= 0) {
            return null;
        }
        return width + "×" + height;
    }

    public String artistAlbum() {
        StringBuilder sb = new StringBuilder();
        if (artist != null && !artist.isEmpty()) {
            sb.append(artist);
        }
        if (album != null && !album.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(album);
        }
        return sb.toString();
    }

    @Override public String toString() {
        return objectId() + " " + displayName;
    }

    public static final class Builder {
        private Kind kind = Kind.VIDEO;
        private long storeId;
        private String uri = "";
        private String displayName = "";
        private String title = "";
        private String mimeType = "application/octet-stream";
        private long sizeBytes;
        private long durationMs;
        private int width;
        private int height;
        private long dateAddedSec;
        private long dateTakenMs;
        private String bucketName;
        private String folderPath;
        private String artist;
        private String album;
        private long albumId;
        private int trackNumber;

        public Builder kind(Kind value) { this.kind = value; return this; }
        public Builder storeId(long value) { this.storeId = value; return this; }
        public Builder uri(String value) { this.uri = value == null ? "" : value; return this; }
        public Builder displayName(String value) { this.displayName = value == null ? "" : value; return this; }
        public Builder title(String value) { this.title = value == null ? "" : value; return this; }
        public Builder mimeType(String value) { this.mimeType = value == null ? "application/octet-stream" : value; return this; }
        public Builder sizeBytes(long value) { this.sizeBytes = value; return this; }
        public Builder durationMs(long value) { this.durationMs = value; return this; }
        public Builder size(int width, int height) { this.width = width; this.height = height; return this; }
        public Builder dateAddedSec(long value) { this.dateAddedSec = value; return this; }
        public Builder dateTakenMs(long value) { this.dateTakenMs = value; return this; }
        public Builder bucketName(String value) { this.bucketName = value; return this; }
        public Builder folderPath(String value) { this.folderPath = value; return this; }
        public Builder artist(String value) { this.artist = value; return this; }
        public Builder album(String value) { this.album = value; return this; }
        public Builder albumId(long value) { this.albumId = value; return this; }
        public Builder trackNumber(int value) { this.trackNumber = value; return this; }

        public MediaItem build() {
            if (title == null || title.trim().isEmpty()) {
                title = displayName;
            }
            return new MediaItem(this);
        }
    }
}
