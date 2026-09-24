package com.lgmediabridge.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.lgmediabridge.App;
import com.lgmediabridge.R;
import com.lgmediabridge.catalog.MediaItem;
import com.lgmediabridge.catalog.Thumbnails;
import com.lgmediabridge.compat.CompatResult;
import com.lgmediabridge.compat.MediaCompat;
import com.lgmediabridge.core.Formats;
import com.lgmediabridge.dlna.ContentDirectory;
import com.lgmediabridge.dlna.DidlLite;
import com.lgmediabridge.dlna.Dlna;
import com.lgmediabridge.server.ServerStatus;

import java.util.Map;

/**
 * Details for one media object, including the verdict of the compatibility
 * engine.
 *
 * This screen exists because the honest answer to "will my TV play this?" is
 * per file, not per app: the sheet shows the container, the codec, the bitrate
 * and either "plays directly", "will be converted" or the precise reason the
 * file is outside the documented webOS list.
 */
public final class MediaDetailSheet {

    private MediaDetailSheet() {
    }

    public static void show(final MainActivity activity, final MediaItem item) {
        final Dialog dialog = new Dialog(activity, Ui.sheetTheme());
        View content = LayoutInflater.from(activity)
                .inflate(R.layout.sheet_media_details, null, false);
        dialog.setContentView(content);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setWindowAnimations(R.style.Animation_MediaBridge_Sheet);
            window.setGravity(android.view.Gravity.BOTTOM);
        }

        final ImageView thumb = content.findViewById(R.id.sheet_thumb);
        TextView title = content.findViewById(R.id.sheet_title);
        TextView subtitle = content.findViewById(R.id.sheet_subtitle);
        TextView verdictTitle = content.findViewById(R.id.sheet_verdict_title);
        TextView verdictBody = content.findViewById(R.id.sheet_verdict_body);
        LinearLayout details = content.findViewById(R.id.sheet_details);
        Button play = content.findViewById(R.id.sheet_play);
        Button open = content.findViewById(R.id.sheet_open);
        Button url = content.findViewById(R.id.sheet_url);

        title.setText(item.title);
        subtitle.setText(item.displayName);

        final int target = Ui.dp(activity, 64);
        App.get().runOnControlPool(() -> {
            final Bitmap bitmap = Thumbnails.load(activity, item, target);
            if (bitmap != null) {
                thumb.post(() -> thumb.setImageBitmap(bitmap));
            }
        });

        App.get().runOnControlPool(() -> {
            final CompatResult result = App.get().compat().analyze(activity, item);
            final String mime = MediaCompat.effectiveMime(item, result);
            final String protocolInfo = Dlna.protocolInfo(mime, Dlna.seekable(result),
                    result.verdict != CompatResult.Verdict.DIRECT,
                    Dlna.profileFor(mime, item.kind, false,
                            result.verdict != CompatResult.Verdict.DIRECT));
            content.post(() -> {
                verdictTitle.setText(verdictHeadline(activity, result));
                Ui.chipStyle(verdictTitle, result.verdict == CompatResult.Verdict.DIRECT ? 1
                        : result.verdict == CompatResult.Verdict.UNSUPPORTED ? 3 : 2);
                verdictBody.setText(result.reasonText());

                details.removeAllViews();
                addDetail(activity, details, R.string.detail_format, mime);
                if (item.width > 0 && item.height > 0) {
                    addDetail(activity, details, R.string.detail_resolution,
                            item.width + " × " + item.height);
                }
                if (item.durationMs > 0) {
                    addDetail(activity, details, R.string.detail_duration,
                            Formats.duration(item.durationMs));
                }
                addDetail(activity, details, R.string.detail_size, Formats.bytes(item.sizeBytes));
                for (Map.Entry<String, String> entry : result.details.entrySet()) {
                    addDetail(activity, details, 0, entry.getKey() + ": " + entry.getValue());
                }
                String folder = item.folderPath == null || item.folderPath.isEmpty()
                        ? item.bucketName : item.folderPath;
                if (folder != null) {
                    addDetail(activity, details, R.string.detail_folder, folder);
                }
                addDetail(activity, details, R.string.detail_protocol, protocolInfo);
                if (item.artist != null && !item.artist.isEmpty()) {
                    addDetail(activity, details, R.string.detail_artist, item.artist);
                }
                if (item.album != null && !item.album.isEmpty()) {
                    addDetail(activity, details, R.string.detail_album, item.album);
                }
                addDetail(activity, details, R.string.detail_added,
                        Formats.timeAgo(activity, item.dateAddedSec * 1000L));
            });
        });

        play.setOnClickListener(view -> {
            dialog.dismiss();
            activity.playOnTv(item);
        });
        open.setOnClickListener(view -> HomeView.openOnPhone(activity, item));
        url.setOnClickListener(view -> {
            ServerStatus status = App.get().serverStatus();
            if (!status.isRunning()) {
                Ui.toast(activity, activity.getString(R.string.error_server_off));
                return;
            }
            String mime = MediaCompat.effectiveMime(item, App.get().compat().quick(item));
            String link = ContentDirectory.mediaUrl(status.baseUrl, item, mime);
            Ui.copyToClipboard(activity, item.title, link);
        });

        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    private static String verdictHeadline(Context context, CompatResult result) {
        switch (result.verdict) {
            case DIRECT: return context.getString(R.string.verdict_direct);
            case PHOTO_CONVERT: return context.getString(R.string.verdict_photo_convert);
            case AUDIO_CONVERT: return context.getString(R.string.verdict_audio_convert);
            default: return context.getString(R.string.verdict_unsupported);
        }
    }

    private static void addDetail(Context context, LinearLayout parent, int labelRes, String value) {
        View row = LayoutInflater.from(context).inflate(R.layout.item_setting_action, parent, false);
        TextView title = row.findViewById(R.id.setting_title);
        if (labelRes != 0) {
            title.setText(labelRes);
        } else {
            title.setText("");
        }
        TextView summary = row.findViewById(R.id.setting_summary);
        summary.setVisibility(View.GONE);
        TextView valueView = row.findViewById(R.id.setting_value);
        valueView.setText(value == null ? "—" : value);
        row.findViewById(R.id.setting_value);
        row.setClickable(false);
        row.setOnClickListener(null);
        parent.addView(row);
    }

    /** DIDL metadata pushed to the TV together with a media URL. */
    public static String metadataFor(MediaItem item, String baseUrl, String mime) {
        CompatResult result = App.get().compat().quick(item);
        boolean seekable = Dlna.seekable(result);
        boolean converted = result.verdict != CompatResult.Verdict.DIRECT;
        String profile = Dlna.profileFor(mime, item.kind, false, converted);
        String protocolInfo = Dlna.protocolInfo(mime, seekable, converted, profile);
        String url = ContentDirectory.mediaUrl(baseUrl, item, mime);
        String thumbnail = item.kind == MediaItem.Kind.PHOTO ? null
                : baseUrl + "/thumb/" + item.objectId() + ".jpg";
        return DidlLite.open() + DidlLite.item(item.objectId(), "0", item, url, thumbnail, mime,
                protocolInfo, profile, item.sizeBytes,
                item.kind == MediaItem.Kind.PHOTO ? 0 : item.durationMs) + DidlLite.close();
    }
}
