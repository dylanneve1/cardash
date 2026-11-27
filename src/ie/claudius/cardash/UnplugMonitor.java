package ie.claudius.cardash;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import ie.claudius.cardash.vehicle.UnplugReminder;
import ie.claudius.cardash.vehicle.VehicleHub;
import ie.claudius.cardash.vehicle.VehicleState;

/**
 * Runs {@link UnplugReminder} for as long as the app's process does.
 * While a dongle is chosen it stays attached to the vehicle hub, so the
 * engine stopping is noticed even with another app in front, and raises
 * a notification; the home screen shows the same as a banner.
 */
public final class UnplugMonitor {

    private static final int NOTE_ID = 7;
    private static final String CHANNEL = "unplug";
    private static final long TICK_MS = 10_000L;

    private static UnplugMonitor shared;

    public static synchronized UnplugMonitor shared() {
        if (shared == null) shared = new UnplugMonitor();
        return shared;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private UnplugReminder reminder = new UnplugReminder();
    private Context app;
    private boolean attached;
    private Runnable onChange;
    private VehicleState last = new VehicleState();

    private final VehicleState.Listener listener = new VehicleState.Listener() {
        @Override
        public void onVehicleState(VehicleState state) {
            last = state;
            check();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!attached) return;
            check();
            main.postDelayed(this, TICK_MS);
        }
    };

    private UnplugMonitor() {}

    /** Start or stop watching to match the settings. Call on the main thread. */
    void sync(Context ctx) {
        app = ctx.getApplicationContext();
        boolean want = Prefs.unplugReminder(app) && Prefs.showVehicle(app)
                && (Prefs.simulating(app) || Prefs.obdAddress(app) != null
                        && !VehicleHub.MODE_CAN.equals(Prefs.vehicleMode(app)));
        if (want) {
            attached = true;
            // Also restarts the sources if the mode or the dongle changed.
            VehicleHub.shared().start(app, listener, Prefs.sourceMode(app));
            main.removeCallbacks(tick);
            main.postDelayed(tick, TICK_MS);
        } else {
            hold();
            reminder = new UnplugReminder();
            cancel();
            changed();
        }
    }

    /**
     * Let go of the sources for a while (the Bluetooth screen: every dongle
     * connect attempt would cancel its device search). {@link #sync} resumes.
     */
    public void hold() {
        if (!attached) return;
        attached = false;
        main.removeCallbacks(tick);
        VehicleHub.shared().stop(listener);
    }

    void setListener(Runnable r) {
        onChange = r;
    }

    boolean showing() {
        return reminder.showing();
    }

    void dismiss() {
        reminder.dismiss();
        cancel();
        changed();
    }

    private void check() {
        boolean was = reminder.showing();
        // elapsedRealtime keeps counting through sleep, which is how a gap is recognised.
        if (reminder.update(SystemClock.elapsedRealtime(), last.obdLinked, last.obdReplyMillis, last.rpm)) {
            post();
        }
        if (was != reminder.showing()) {
            if (!reminder.showing()) cancel();
            changed();
        }
    }

    private void changed() {
        if (onChange != null) onChange.run();
    }

    private NotificationManager manager() {
        return (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @SuppressWarnings("deprecation")
    private void post() {
        NotificationManager nm = manager();
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    app.getString(R.string.unplug_channel), NotificationManager.IMPORTANCE_HIGH));
            b = new Notification.Builder(app, CHANNEL);
        } else {
            b = new Notification.Builder(app).setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_SOUND);
        }
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        String text = app.getString(R.string.unplug_text);
        b.setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(app.getString(R.string.unplug_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(PendingIntent.getActivity(app, 0,
                        new Intent(app, HomeActivity.class), immutable))
                .setAutoCancel(true);
        nm.notify(NOTE_ID, b.build());
    }

    private void cancel() {
        NotificationManager nm = app == null ? null : manager();
        if (nm != null) nm.cancel(NOTE_ID);
    }
}
