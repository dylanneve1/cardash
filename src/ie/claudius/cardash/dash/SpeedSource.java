package ie.claudius.cardash.dash;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

/**
 * GPS speed and position.
 *
 * Doubles as the position source for the weather card, so there is one
 * location subscription rather than two.
 */
public final class SpeedSource {

    private static final String TAG = "CarDash/GPS";

    public interface Listener {
        void onSpeed(float kph, boolean hasFix);

        void onPosition(double lat, double lon);
    }

    private final Context ctx;
    private LocationManager lm;
    private Listener listener;
    private boolean reportedPosition;

    private final LocationListener location = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            if (listener == null) return;
            // hasSpeed() is false on a fix that has position but no
            // velocity yet — showing 0 then would be a lie, not a zero.
            listener.onSpeed(l.hasSpeed() ? l.getSpeed() * 3.6f : 0f,
                    l.hasSpeed());
            if (!reportedPosition) {
                reportedPosition = true;
                listener.onPosition(l.getLatitude(), l.getLongitude());
            }
        }

        @Override
        public void onStatusChanged(String p, int s, Bundle e) {
        }

        @Override
        public void onProviderEnabled(String p) {
        }

        @Override
        public void onProviderDisabled(String p) {
            if (listener != null) listener.onSpeed(0f, false);
        }
    };

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
        try {
            lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            // 1s / 0m: we want velocity updates, not movement-gated ones.
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0,
                    location);
            // Position for the weather card can come from anywhere —
            // a parked car under a roof may never get a GPS fix, but a
            // stale network or passive fix is still the right city.
            for (String provider : new String[] {
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER }) {
                Location last = null;
                try {
                    last = lm.getLastKnownLocation(provider);
                } catch (Exception ignored) {
                }
                if (last != null) {
                    l.onPosition(last.getLatitude(), last.getLongitude());
                    reportedPosition = true;
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "gps unavailable: " + e);
            l.onSpeed(0f, false);
        }
    }

    public void stop() {
        try {
            if (lm != null) lm.removeUpdates(location);
        } catch (Exception ignored) {
        }
        listener = null;
    }
}
