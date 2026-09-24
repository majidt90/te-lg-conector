package com.lgmediabridge.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.lgmediabridge.App;
import com.lgmediabridge.R;
import com.lgmediabridge.catalog.MediaItem;
import com.lgmediabridge.catalog.Thumbnails;
import com.lgmediabridge.compat.CompatResult;
import com.lgmediabridge.core.Formats;
import com.lgmediabridge.settings.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Media rows for the library screen.
 *
 * Thumbnails are decoded on the shared control pool and kept in a bitmap cache;
 * a recycled row therefore never shows the previous item's picture, and the list
 * keeps scrolling smoothly while a 10 000 item library is bound. Compatibility is
 * shown as a small badge so the user knows before pressing play whether the TV
 * will accept the file as-is.
 */
public final class MediaListAdapter extends BaseAdapter {

    public interface Listener {
        void onPlayOnTv(MediaItem item);

        void onOpenDetails(MediaItem item);
    }

    private final Context context;
    private final Listener listener;
    private final List<MediaItem> items = new ArrayList<>();
    private final LruCache<String, Bitmap> thumbnails;

    public MediaListAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        int cacheKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 12);
        this.thumbnails = new LruCache<String, Bitmap>(Math.max(4096, cacheKb)) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    public void setItems(List<MediaItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    public List<MediaItem> items() {
        return items;
    }

    @Override public int getCount() {
        return items.size();
    }

    @Override public Object getItem(int position) {
        return items.get(position);
    }

    @Override public long getItemId(int position) {
        return items.get(position).storeId;
    }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        if (row == null) {
            row = LayoutInflater.from(context).inflate(R.layout.item_media, parent, false);
        }
        final MediaItem item = items.get(position);
        ImageView thumb = row.findViewById(R.id.media_thumb);
        ImageView kind = row.findViewById(R.id.media_kind);
        TextView title = row.findViewById(R.id.media_title);
        TextView meta = row.findViewById(R.id.media_meta);
        TextView badge = row.findViewById(R.id.media_badge);
        ImageButton action = row.findViewById(R.id.media_action);

        title.setText(item.title);
        meta.setText(metaFor(item));
        kind.setImageResource(item.kind == MediaItem.Kind.PHOTO ? R.drawable.ic_photo
                : item.kind == MediaItem.Kind.AUDIO ? R.drawable.ic_music : R.drawable.ic_video);

        CompatResult verdict = verdict(item);
        if (verdict == null || verdict.verdict == CompatResult.Verdict.DIRECT) {
            badge.setVisibility(View.GONE);
        } else {
            badge.setVisibility(View.VISIBLE);
            badge.setText(badgeText(verdict.verdict));
            Ui.chipStyle(badge, verdict.verdict == CompatResult.Verdict.UNSUPPORTED ? 3 : 2);
        }

        final String key = item.objectId();
        Bitmap cached = thumbnails.get(key);
        if (cached != null) {
            thumb.setImageBitmap(cached);
        } else {
            thumb.setImageDrawable(null);
            bindThumbnail(thumb, item, key);
        }

        row.setOnClickListener(view -> listener.onOpenDetails(item));
        action.setOnClickListener(view -> listener.onPlayOnTv(item));
        Ui.animateIn(row, position > 12 ? 12 : position);
        return row;
    }

    private void bindThumbnail(final ImageView view, final MediaItem item, final String key) {
        final int target = item.kind == MediaItem.Kind.PHOTO
                ? Ui.dp(context, 72) : Ui.dp(context, 48);
        view.setTag(key);
        App.get().runOnControlPool(() -> {
            final Bitmap bitmap = Thumbnails.load(context, item, target);
            if (bitmap == null) {
                return;
            }
            thumbnails.put(key, bitmap);
            view.post(() -> {
                if (key.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                    view.startAnimation(android.view.animation.AnimationUtils.loadAnimation(
                            context, R.anim.fade_in_slight));
                }
            });
        });
    }

    private CompatResult verdict(MediaItem item) {
        try {
            return item.kind == MediaItem.Kind.AUDIO
                    ? App.get().compat().analyze(context, item)
                    : App.get().compat().quick(item);
        } catch (Exception e) {
            return null;
        }
    }

    private String badgeText(CompatResult.Verdict verdict) {
        switch (verdict) {
            case PHOTO_CONVERT: return context.getString(R.string.badge_photo_converted);
            case AUDIO_CONVERT: return context.getString(R.string.badge_audio_converted);
            case UNSUPPORTED: return context.getString(R.string.badge_unsupported);
            default: return context.getString(R.string.badge_direct);
        }
    }

    private String metaFor(MediaItem item) {
        StringBuilder sb = new StringBuilder();
        if (item.kind != MediaItem.Kind.PHOTO && item.durationMs > 0) {
            sb.append(Formats.duration(item.durationMs));
        }
        if (item.sizeBytes > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(Formats.bytes(item.sizeBytes));
        }
        if (item.kind == MediaItem.Kind.PHOTO && item.width > 0 && item.height > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(item.width).append("×").append(item.height);
        }
        if (item.kind == MediaItem.Kind.AUDIO && item.artist != null && !item.artist.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(item.artist);
        }
        if (item.kind != MediaItem.Kind.AUDIO && item.sizeBytes > 0 && item.durationMs > 0) {
            long bitrate = item.sizeBytes * 8000L / Math.max(1, item.durationMs);
            if (bitrate > 0) {
                sb.append(" · ").append(Formats.bitrate(bitrate));
            }
        }
        Settings settings = App.get().settings();
        if (settings.folderView() && item.folderPath != null && !item.folderPath.isEmpty()) {
            sb.append(" · ").append(item.folderPath);
        }
        return sb.toString();
    }
}
