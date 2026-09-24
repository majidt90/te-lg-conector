package com.lgmediabridge.ui;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.lgmediabridge.App;
import com.lgmediabridge.R;
import com.lgmediabridge.catalog.MediaCatalog;
import com.lgmediabridge.catalog.MediaItem;
import com.lgmediabridge.core.LogBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The library screen: exactly what the TV can browse, seen from the phone.
 *
 * The list is fed by the same {@link MediaCatalog} the DLNA server uses, so a
 * file that is missing here is missing on the TV too - which makes this screen
 * the fastest way to debug "my video does not appear". Sorting, folder browsing
 * and search all run on the catalog's immutable snapshots off the main thread.
 */
public final class LibraryView implements MediaCatalog.Listener {

    private enum Sort { DATE_DESC, DATE_ASC, NAME, SIZE_DESC }

    private static final String TAG = "LibraryView";

    private final MainActivity activity;
    private final View root;
    private final ListView list;
    private final TextView summary;
    private final EditText search;
    private final View searchClear;
    private final View foldersButton;
    private final View sortButton;
    private final com.lgmediabridge.ui.StatePanel state;

    private final MediaListAdapter adapter;
    private final List<MediaItem> source = new ArrayList<>();

    private int kindIndex = 0;      // 0 videos, 1 photos, 2 music
    private Sort sort = Sort.DATE_DESC;
    private boolean folderMode;
    private String folderPath;
    private String query = "";

    public LibraryView(MainActivity activity) {
        this.activity = activity;
        this.root = LayoutInflater.from(activity).inflate(R.layout.view_library, null, false);
        this.list = root.findViewById(R.id.library_list);
        this.summary = root.findViewById(R.id.library_summary);
        this.search = root.findViewById(R.id.search);
        this.searchClear = root.findViewById(R.id.search_clear);
        this.foldersButton = root.findViewById(R.id.library_folders);
        this.sortButton = root.findViewById(R.id.library_sort);
        this.state = new StatePanel(root.findViewById(R.id.library_state));
        this.adapter = new MediaListAdapter(activity, new MediaListAdapter.Listener() {
            @Override public void onPlayOnTv(MediaItem item) {
                if (isFolder(item)) {
                    openFolder(item.folderPath);
                } else {
                    activity.playOnTv(item);
                }
            }

            @Override public void onOpenDetails(MediaItem item) {
                if (isFolder(item)) {
                    openFolder(item.folderPath);
                } else {
                    MediaDetailSheet.show(activity, item);
                }
            }
        });
        list.setAdapter(adapter);
        wire();
    }

    public View root() {
        return root;
    }

    private void wire() {
        root.findViewById(R.id.filter_videos).setOnClickListener(view -> selectKind(0));
        root.findViewById(R.id.filter_photos).setOnClickListener(view -> selectKind(1));
        root.findViewById(R.id.filter_music).setOnClickListener(view -> selectKind(2));
        foldersButton.setOnClickListener(view -> toggleFolders());
        sortButton.setOnClickListener(view -> cycleSort());

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override public void afterTextChanged(Editable editable) {
                query = editable.toString().trim();
                searchClear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                reload();
            }
        });
        search.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                reload();
                return true;
            }
            return false;
        });
        searchClear.setOnClickListener(view -> {
            search.setText("");
            hideKeyboard();
        });
    }

    public void start() {
        App.get().catalog().addListener(this);
        App.get().catalog().start();
        selectKind(kindIndex);
    }

    public void stop() {
        App.get().catalog().removeListener(this);
    }

    public void select(int kindIndex) {
        selectKind(kindIndex);
    }

    /** Called by the catalog (background thread) whenever the index changes. */
    @Override public void onCatalogChanged(MediaCatalog catalog) {
        root.post(this::reload);
    }

    private void selectKind(int index) {
        this.kindIndex = index;
        this.folderMode = false;
        this.folderPath = null;
        highlight(kindIndex);
        reload();
    }

    private void highlight(int index) {
        int selected = R.drawable.bg_segment_selected;
        int normal = R.drawable.bg_segment;
        root.findViewById(R.id.filter_videos).setBackgroundResource(index == 0 ? selected : normal);
        root.findViewById(R.id.filter_photos).setBackgroundResource(index == 1 ? selected : normal);
        root.findViewById(R.id.filter_music).setBackgroundResource(index == 2 ? selected : normal);
    }

    private void toggleFolders() {
        folderMode = !folderMode;
        folderPath = null;
        Ui.toast(activity, activity.getString(R.string.library_folders));
        reload();
    }

    private void cycleSort() {
        switch (sort) {
            case DATE_DESC: sort = Sort.DATE_ASC; break;
            case DATE_ASC: sort = Sort.NAME; break;
            case NAME: sort = Sort.SIZE_DESC; break;
            default: sort = Sort.DATE_DESC; break;
        }
        Ui.toast(activity, sortLabel());
        reload();
    }

    private String sortLabel() {
        switch (sort) {
            case DATE_ASC: return "oldest first";
            case NAME: return "name";
            case SIZE_DESC: return "largest first";
            default: return "newest first";
        }
    }

    private MediaItem.Kind kind() {
        switch (kindIndex) {
            case 1: return MediaItem.Kind.PHOTO;
            case 2: return MediaItem.Kind.AUDIO;
            default: return MediaItem.Kind.VIDEO;
        }
    }

    private void reload() {
        MediaCatalog catalog = App.get().catalog();
        MediaItem.Kind kind = kind();
        List<MediaItem> items = new ArrayList<>();

        if (folderMode && folderPath == null) {
            showFolders(catalog, kind);
            return;
        }
        if (!query.isEmpty()) {
            items.addAll(catalog.search(kind, query));
        } else if (folderMode && folderPath != null) {
            items.addAll(catalog.folderItems(kind, folderPath, null));
        } else {
            items.addAll(catalog.of(kind));
        }
        if (App.get().settings().hideUnsupported()) {
            List<MediaItem> filtered = new ArrayList<>(items.size());
            for (MediaItem item : items) {
                if (App.get().compat().quick(item).verdict.playable()) {
                    filtered.add(item);
                }
            }
            items = filtered;
        }
        sort(items);
        source.clear();
        source.addAll(items);
        adapter.setItems(items);
        updateSummary(catalog);
    }

    private void showFolders(MediaCatalog catalog, MediaItem.Kind kind) {
        List<MediaCatalog.Folder> folders = catalog.folders(kind);
        List<MediaItem> rows = new ArrayList<>();
        adapter.setItems(rows);
        for (MediaCatalog.Folder folder : folders) {
            rows.add(folderRow(folder));
        }
        adapter.setItems(rows);
        summary.setText(activity.getString(R.string.library_folders) + " · " + folders.size());
        if (folders.isEmpty()) {
            state.empty(R.drawable.ic_folder, activity.getString(R.string.library_empty_generic),
                    activity.getString(R.string.library_permission_body), null, null);
        } else {
            state.hide();
        }
    }

    /**
     * Folder rows are represented as a lightweight media item so the same list
     * adapter and row layout can render them consistently.
     */
    private MediaItem folderRow(MediaCatalog.Folder folder) {
        return new MediaItem.Builder()
                .kind(kind())
                .storeId(-Math.abs(folder.path.hashCode()))
                .displayName(folder.name)
                .title(folder.name)
                .mimeType("inode/directory")
                .sizeBytes(folder.count)
                .folderPath(folder.path)
                .build();
    }

    /** Folder rows carry a negative id; real media ids are always positive. */
    private static boolean isFolder(MediaItem item) {
        return item.storeId < 0;
    }

    private void openFolder(String path) {
        this.folderPath = path;
        this.folderMode = true;
        this.query = "";
        search.setText("");
        reload();
    }

    private void sort(List<MediaItem> items) {
        switch (sort) {
            case DATE_DESC:
                Collections.sort(items, (a, b) -> Long.compare(timestamp(b), timestamp(a)));
                break;
            case DATE_ASC:
                Collections.sort(items, (a, b) -> Long.compare(timestamp(a), timestamp(b)));
                break;
            case NAME:
                Collections.sort(items, (a, b) -> a.title.compareToIgnoreCase(b.title));
                break;
            default:
                Collections.sort(items, (a, b) -> Long.compare(b.sizeBytes, a.sizeBytes));
                break;
        }
    }

    private static long timestamp(MediaItem item) {
        return item.dateTakenMs > 0 ? item.dateTakenMs : item.dateAddedSec * 1000L;
    }

    private void updateSummary(MediaCatalog catalog) {
        MediaCatalog.Status status = catalog.status();
        int count = source.size();
        summary.setText(count == 1
                ? activity.getString(R.string.library_one_item)
                : activity.getString(R.string.library_item_count, count));

        switch (status) {
            case INDEXING:
                state.loading(activity.getString(R.string.library_indexing));
                return;
            case NO_PERMISSION:
                state.empty(R.drawable.ic_shield,
                        activity.getString(R.string.library_permission_title),
                        activity.getString(R.string.library_permission_body),
                        activity.getString(R.string.action_grant),
                        () -> Permissions.request(activity,
                                Permissions.mediaPermissions(App.get().settings()),
                                Permissions.REQUEST_MEDIA));
                return;
            case ERROR:
                state.error(activity.getString(R.string.state_error_title), catalog.error(),
                        () -> App.get().catalog().refresh(true));
                return;
            default:
                break;
        }
        if (count == 0) {
            state.empty(iconForKind(), emptyTitle(), emptyBody(), null, null);
        } else {
            state.hide();
        }
        if (list.getVisibility() != View.VISIBLE) {
            list.setVisibility(View.VISIBLE);
        }
    }

    private int iconForKind() {
        switch (kindIndex) {
            case 1: return R.drawable.ic_photo;
            case 2: return R.drawable.ic_music;
            default: return R.drawable.ic_video;
        }
    }

    private String emptyTitle() {
        switch (kindIndex) {
            case 1: return activity.getString(R.string.library_empty_photos);
            case 2: return activity.getString(R.string.library_empty_music);
            default: return activity.getString(R.string.library_empty_videos);
        }
    }

    private String emptyBody() {
        return activity.getString(R.string.library_empty_generic);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager manager = (InputMethodManager)
                    activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.hideSoftInputFromWindow(search.getWindowToken(), 0);
            }
        } catch (Exception e) {
            LogBus.get().d(TAG, "keyboard: " + e.getMessage());
        }
    }

    /** Number of items the TV will currently see for the active filter. */
    public int visibleCount() {
        return adapter.getCount();
    }
}
