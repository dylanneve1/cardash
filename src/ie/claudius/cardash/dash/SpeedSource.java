package ie.claudius.cardash.dash;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Speed and position.
 *
 * Asking only GPS_PROVIDER was wrong on this head unit. Its raw GPS
 * provider has never produced a fix — `last location=null` after
 * thirteen days of four vendor apps requesting it — yet Google Maps
 * works fine, because Play Services registers a **fused** provider that
 * blends network, wifi and sensors, and that one reports
 * `supports=[bearing, speed, altitude]` with a live velocity.
 *
 * So: subscribe to every provider the device actually has, prefer the
 * freshest fix, and derive speed from successive positions when a
 * provider gives position without velocity. Raw GPS is then just one
 * input among several rather than a hard requirement.
 */
public final class SpeedSource {

    private static final String TAG = "CarDash/GPS";

    /** Best first. "fused" predates the public constant (API 31). */
    private static final String[] WANTED = {
            "fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
    };

    /** Below this a derived speed is noise from fix jitter, not motion. */
    private static final float MIN_DERIVED_KPH = 3f;
    /** A fix older than this tells us nothing about current speed. */
    private static final long STALE_MS = 10_000L;

    public interface Listener {
        void onSpeed(float kph, boolean hasFix);

        void onPosition(double lat, double lon);
    }

    private final Context ctx;
    private LocationManager lm;
    private Listener listener;
    private boolean reportedPosition;

    private Location previous;
    private final List<LocationListener> attached = new ArrayList<>();

    public SpeedSource(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public boolean hasPermission() {
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void start(Listener l) {
        listener = l;
        if (!hasPermission()) {
            Log.i(TAG, "no location permission");
            l.onSpeed(0f, false);
            return;
        }
        lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            l.onSpeed(0f, false);
            return;
        }

        List<String> available = lm.getAllProviders();
        boolean any = false;
        for (String provider : WANTED) {
            if (available == null || !available.contains(provider)) continue;
            try {
                LocationListener ll = newListener();
                lm.requestLocationUpdates(provider, 1000, 0, ll);
                attached.add(ll);
                any = true;
                Log.i(TAG, "subscribed to " + provider);
            } catch (Exception e) {
                Log.w(TAG, provider + " unavailable: " + e);
            }
            seedPosition(provider);
        }
        if (!any) l.onSpeed(0f, false);
    }

    private void seedPosition(String provider) {
        if (reportedPosition || listener == null) return;
        try {
            Location last = lm.getLastKnownLocation(provider);
            if (last != null) {
                reportedPosition = true;
                listener.onPosition(last.getLatitude(), last.getLongitude());
            }
        } catch (Exception ignored) {
        }
    }

    private LocationListener newListener() {
        return new LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
                accept(l);
            }

            @Override
            public void onStatusChanged(String p, int s, Bundle e) {
            }

            @Override
            public void onProviderEnabled(String p) {
            }

            @Override
            public void onProviderDisabled(String p) {
            }
        };
    }

    private void accept(Location l) {
        if (listener == null || l == null) return;

        if (!reportedPosition) {
            reportedPosition = true;
            listener.onPosition(l.getLatitude(), l.getLongitude());
        }

        float kph;
        boolean known;
        if (l.hasSpeed()) {
            kph = l.getSpeed() * 3.6f;
            known = true;
        } else {
            kph = derive(l);
            known = kph >= 0f;
            if (!known) kph = 0f;
        }
        previous = l;
        listener.onSpeed(kph, known);
    }

    /** Speed between two fixes, or -1 when it can't be trusted. */
    private float derive(Location now) {
        if (previous == null) return -1f;
        long dt = now.getTime() - previous.getTime();
        if (dt <= 0 || dt > STALE_MS) return -1f;
        float metres = previous.distanceTo(now);
        float kph = (metres / (dt / 1000f)) * 3.6f;
        // A stationary car still jitters by a few metres a second.
        if (kph < MIN_DERIVED_KPH) return 0f;
        return kph;
    }

    public void stop() {
        try {
            for (LocationListener ll : attached) lm.removeUpdates(ll);
        } catch (Exception ignored) {
        }
        attached.clear();
        previous = null;
        listener = null;
    }
}
