package com.lgmediabridge;

import android.app.Application;
import android.content.Context;

import com.lgmediabridge.catalog.MediaCatalog;
import com.lgmediabridge.compat.MediaCompat;
import com.lgmediabridge.control.TvStore;
import com.lgmediabridge.dlna.ContentDirectory;
import com.lgmediabridge.net.LocalNetwork;
import com.lgmediabridge.server.ServerStatus;
import com.lgmediabridge.core.LogBus;
import com.lgmediabridge.security.SecureStore;
import com.lgmediabridge.settings.Settings;
import com.lgmediabridge.stream.StreamRegistry;
import com.lgmediabridge.transcode.AudioTranscoder;
import com.lgmediabridge.transcode.PhotoTranscoder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import com.lgmediabridge.core.Formats;

/**
 * Application object: the single owner of every long-lived component.
 *
 * The media server runs inside a foreground service, but the catalog, device
 * store and transcoders live here so that UI screens and the service share one
 * instance each (no duplicate indexes, no duplicated caches). Every accessor is
 * lazily created on first use and cheap to call from the main thread; anything
 * expensive is dispatched to the background pools.
 */
public final class App extends Application {

    private static App instance;

    private Settings settings;
    private SecureStore secureStore;
    private MediaCatalog catalog;
    private TvStore tvStore;
    private StreamRegistry streams;
    private PhotoTranscoder photos;
    private AudioTranscoder audio;
    private ExecutorService controlPool;
    private ScheduledExecutorService scheduler;
    private MediaCompat compat;
    private ContentDirectory contentDirectory;
    private volatile ServerStatus serverStatus = ServerStatus.stopped();

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        LogBus.get().i("App", "MediaBridge starting (" + android.os.Build.MODEL
                + ", Android " + android.os.Build.VERSION.RELEASE + ")");
        // Watch the default network from the moment the process starts: the
        // sharing service and the TV screens both rely on this.
        try {
            LocalNetwork.register(this);
        } catch (Exception e) {
            LogBus.get().w("App", "network monitoring unavailable: " + e.getMessage());
        }
        // The UPnP tree must publish a new SystemUpdateID whenever the library
        // changes, otherwise a TV that caches the browse result never refreshes.
        catalog().addListener(new MediaCatalog.Listener() {
            @Override public void onCatalogChanged(MediaCatalog changed) {
                ContentDirectory directory = contentDirectory;
                if (directory != null) {
                    // Tell subscribed TVs to re-read the browse tree.
                    directory.onLibraryChanged();
                }
            }
        });
    }

    public static App get() {
        return instance;
    }

    /** Application context; safe to use from any thread and never leaks an Activity. */
    public static Context ctx() {
        return instance.getApplicationContext();
    }

    public Settings settings() {
        if (settings == null) {
            settings = new Settings(this);
        }
        return settings;
    }

    public SecureStore secureStore() {
        if (secureStore == null) {
            secureStore = new SecureStore(this);
        }
        return secureStore;
    }

    public MediaCatalog catalog() {
        if (catalog == null) {
            catalog = new MediaCatalog(this);
        }
        return catalog;
    }

    public TvStore tvStore() {
        if (tvStore == null) {
            tvStore = new TvStore(this);
        }
        return tvStore;
    }

    /** Codec/container verdicts for every media item; shared by UI and server. */
    public MediaCompat compat() {
        if (compat == null) {
            compat = new MediaCompat();
        }
        return compat;
    }

    /**
     * The UPnP content tree. Built here (not in the service) so browse results
     * survive a service restart and the UI can preview exactly what the TV sees.
     */
    public ContentDirectory contentDirectory() {
        if (contentDirectory == null) {
            contentDirectory = new ContentDirectory(catalog(), compat(), settings());
        }
        return contentDirectory;
    }

    public ServerStatus serverStatus() {
        return serverStatus;
    }

    public void setServerStatus(ServerStatus status) {
        this.serverStatus = status == null ? ServerStatus.stopped() : status;
    }

    public StreamRegistry streams() {
        if (streams == null) {
            streams = new StreamRegistry();
        }
        return streams;
    }

    public PhotoTranscoder photoTranscoder() {
        if (photos == null) {
            photos = new PhotoTranscoder(this);
        }
        return photos;
    }

    public AudioTranscoder audioTranscoder() {
        if (audio == null) {
            audio = new AudioTranscoder(this);
        }
        return audio;
    }

    /** Control-plane work: discovery, SSAP calls, thumbnails, transcoding. */
    public ExecutorService controlPool() {
        if (controlPool == null) {
            controlPool = Formats.newPool("mb-control", 4);
        }
        return controlPool;
    }

    /** Periodic work: network watchdog, renderer polling, metric sampling. */
    public ScheduledExecutorService scheduler() {
        if (scheduler == null) {
            scheduler = Formats.newScheduler("mb-scheduler", 3);
        }
        return scheduler;
    }

    public void runOnControlPool(Runnable task) {
        controlPool().execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                LogBus.get().e("App", "background task failed", e);
            }
        });
    }
}
