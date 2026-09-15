package ie.claudius.cardash.media;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import java.util.List;

/**
 * What's playing, and control over it.
 *
 * Two routes, because head units are inconsistent:
 *
 *   1. MediaSessionManager. Gives title, artist and album art, and
 *      transport that goes to the RIGHT player rather than whichever
 *      one the system last felt like. Needs notification-listener
 *      access, which on a rooted unit is one shell command.
 *   2. AudioManager.dispatchMediaKeyEvent. No permission, but it is
 *      fire-and-forget: it goes to the system's idea of the active
 *      session, which is nothing at all when none is active. That is
 *      why a play button can look dead.
 *
 * Route 1 when we have it, route 2 as the fallback, always.
 */
public final class NowPlaying {

    private static final String TAG = "CarDash/Media";

    public interface Listener {
        void onNowPlaying(String title, String artist, Bitmap art, boolean playing);
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager manager;
    private MediaController controller;
    private Listener listener;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publish();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            publish();
        }

        @Override
        public void onSessionDestroyed() {
            controller = null;
            refresh();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> list) {
                    bind(list);
                }
            };

    public NowPlaying(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** True once the user (or root) has granted notification access. */
    public boolean hasAccess() {
        String enabled = Settings.Secure.getString(
                ctx.getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(ctx.getPackageName());
    }

    public void start(Listener l) {
        listener = l;
        if (!hasAccess()) {
            Log.i(TAG, "no notification access; media keys only");
            return;
        }
        try {
            manager = (MediaSessionManager)
                    ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(ctx, NotificationListener.class);
            manager.addOnActiveSessionsChangedListener(sessions, me);
            bind(manager.getActiveSessions(me));
        } catch (Exception e) {
            // A vendor ROM with a gutted media stack shouldn't take the
            // launcher down with it.
            Log.w(TAG, "media session unavailable: " + e);
            manager = null;
        }
    }

    public void stop() {
        if (controller != null) controller.unregisterCallback(callback);
        controller = null;
        if (manager != null) {
            try {
                manager.removeOnActiveSessionsChangedListener(sessions);
            } catch (Exception ignored) {
            }
        }
        listener = null;
    }

    public void refresh() {
        if (manager == null) return;
        try {
            bind(manager.getActiveSessions(
                    new ComponentName(ctx, NotificationListener.class)));
        } catch (Exception ignored) {
        }
    }

    /** Prefer a session that is actually playing; else the top one. */
    private void bind(List<MediaController> list) {
        MediaController pick = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    pick = c;
                    break;
                }
                if (pick == null) pick = c;
            }
        }
        if (controller != null) controller.unregisterCallback(callback);
        controller = pick;
        if (controller != null) controller.registerCallback(callback, main);
        publish();
    }

    private void publish() {
        if (listener == null) return;
        String title = null, artist = null;
        Bitmap art = null;
        boolean playing = false;

        if (controller != null) {
            MediaMetadata m = controller.getMetadata();
            if (m != null) {
                title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
                artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (artist == null) {
                    artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                }
                art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                // Video players (YouTube) publish a thumbnail here
                // rather than as album art.
                if (art == null) {
                    art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                }
            }
            PlaybackState s = controller.getPlaybackState();
            playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        }
        final String t = title, a = artist;
        final Bitmap b = art;
        final boolean p = playing;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onNowPlaying(t, a, b, p);
            }
        });
    }

    // ---- transport ------------------------------------------------------

    public void playPause() {
        if (controller != null) {
            PlaybackState s = controller.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                controller.getTransportControls().pause();
            } else {
                controller.getTransportControls().play();
            }
            return;
        }
        key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    public void next() {
        if (controller != null) {
            controller.getTransportControls().skipToNext();
            return;
        }
        key(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    public void previous() {
        if (controller != null) {
            controller.getTransportControls().skipToPrevious();
            return;
        }
        key(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    private void key(int keyCode) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }
}
