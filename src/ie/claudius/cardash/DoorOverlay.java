package ie.claudius.cardash;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import ie.claudius.cardash.vehicle.DoorWatch;
import ie.claudius.cardash.vehicle.VehicleHub;
import ie.claudius.cardash.vehicle.VehicleState;

/**
 * The pop-up over CarDash's own screens when a door, the boot or the
 * bonnet opens: a translucent card with the car and what is open. It sits
 * in the activity's own window (nothing is drawn over other apps), goes
 * away by itself once everything is shut, and a tap anywhere dismisses
 * it. {@link DoorWatch} decides when; this draws it.
 *
 * An activity calls {@link #resume} and {@link #pause}. While resumed it
 * listens to the vehicle hub, so it works from any source, the simulator
 * included, whether or not a widget on screen shows the doors.
 */
final class DoorOverlay implements VehicleState.Listener {

    /** Shared by the screens, so a dismissal holds when you move between them. */
    private static final DoorWatch WATCH = new DoorWatch();
    private static String watchedSource;

    private static final int[] NAMES = { R.string.door_front_left, R.string.door_front_right,
            R.string.door_rear_left, R.string.door_rear_right, R.string.door_boot, R.string.door_bonnet };

    private final Activity act;
    private final VehicleHub hub = VehicleHub.shared();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable render = new Runnable() {
        @Override
        public void run() {
            render();
        }
    };

    private boolean resumed;
    /** The source key listened to, or null while detached. */
    private String attached;
    private VehicleState last;

    private View view;
    private int viewSeed;
    private TextView title, parts;
    private CarView car;

    DoorOverlay(Activity act) {
        this.act = act;
    }

    void resume() {
        resumed = true;
        sync();
    }

    void pause() {
        resumed = false;
        sync();
    }

    /** Attach to or detach from the vehicle as the screen and the settings now say. */
    void sync() {
        boolean want = resumed && Prefs.doorOverlay(act) && Prefs.showVehicle(act);
        String key = Prefs.sourceMode(act) + '|' + Prefs.obdAddress(act);
        if (attached != null && (!want || !attached.equals(key))) {
            attached = null;
            hub.stop(this);
            main.removeCallbacks(render);
            WATCH.suspend();
            last = null;
        }
        if (want && attached == null) {
            // What another source said, or was dismissed about it, no longer holds.
            if (watchedSource != null && !watchedSource.equals(key)) WATCH.reset();
            watchedSource = key;
            attached = key;
            hub.start(act, this, Prefs.sourceMode(act));
        }
        // New colours need a new card.
        if (view != null && viewSeed != Prefs.themeSeed(act)) remove();
        render();
    }

    @Override
    public void onVehicleState(VehicleState state) {
        if (attached == null) return;
        last = state;
        WATCH.update(state, Prefs.carProfile(act).threeDoor(), SystemClock.uptimeMillis());
        render();
    }

    private void render() {
        main.removeCallbacks(render);
        long now = SystemClock.uptimeMillis();
        if (attached == null || !Prefs.doorOverlay(act) || !WATCH.visible(now)) {
            remove();
            return;
        }
        if (view == null) build();
        fill();
        long hides = WATCH.hidesAt();
        if (hides >= 0) main.postDelayed(render, Math.max(1L, hides - now));
    }

    private void remove() {
        if (view == null) return;
        ((ViewGroup) view.getParent()).removeView(view);
        view = null;
        car = null;
    }

    private void build() {
        float d = act.getResources().getDisplayMetrics().density;
        viewSeed = Prefs.themeSeed(act);
        M3 m3 = M3.fromSeed(viewSeed);

        FrameLayout scrim = new FrameLayout(act);
        scrim.setBackgroundColor(0x80000000);
        scrim.setClickable(true);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WATCH.dismiss();
                render();
            }
        });

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(Shapes.round(d, M3.withAlpha(m3.surfaceContainerHigh(), 0xF0), 36));
        int p = Math.round(20 * d);
        card.setPadding(p, p, p, p);

        title = text(card, 28, m3.onSurface(), true);
        parts = text(card, 18, m3.onSurfaceVariant(), false);
        car = new CarView(act, m3.surfaceContainerHigh());
        car.setCaption(false);
        card.addView(car, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        text(card, 15, m3.onSurfaceVariant(), false).setText(R.string.door_overlay_dismiss);

        android.util.DisplayMetrics dm = act.getResources().getDisplayMetrics();
        scrim.addView(card, new FrameLayout.LayoutParams(
                Math.min(Math.round(520 * d), dm.widthPixels - Math.round(48 * d)),
                Math.min(Math.round(600 * d), dm.heightPixels - Math.round(64 * d)), Gravity.CENTER));
        ((ViewGroup) act.findViewById(android.R.id.content)).addView(scrim, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        view = scrim;
        Motion.reveal(card);
    }

    private TextView text(LinearLayout into, int sp, int color, boolean heading) {
        TextView t = new TextView(act);
        t.setTypeface(heading ? Fonts.display(act) : Fonts.body(act));
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER);
        into.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private void fill() {
        int open = WATCH.open();
        StringBuilder names = new StringBuilder();
        int n = 0;
        for (int i = 0; i < DoorWatch.PARTS; i++) {
            if ((open & (1 << i)) == 0) continue;
            if (n++ > 0) names.append(" · ");
            names.append(act.getString(NAMES[i]));
        }
        String head = n == 0
                ? act.getString(last != null && last.anyDoorKnown() ? R.string.door_all_closed : R.string.door_no_data)
                : n == 1 ? act.getString(R.string.door_one_open, names)
                : act.getString(R.string.door_many_open, n);
        Motion.setText(title, head);
        Motion.setText(parts, n > 1 ? names : "");
        parts.setVisibility(n > 1 ? View.VISIBLE : View.GONE);
        view.setContentDescription(head);
        car.setThreeDoor(Prefs.carProfile(act).threeDoor());
        car.setState(last);
    }
}
