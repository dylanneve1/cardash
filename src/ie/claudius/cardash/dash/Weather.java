package ie.claudius.cardash.dash;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Current conditions from Open-Meteo.
 *
 * No API key, no account, no SDK — one HTTPS GET and org.json, which is
 * already in the platform. Refreshes every 20 minutes, which is far more
 * often than the weather changes and gentle on a tethered connection.
 *
 * Failure is silent by design: the head unit is offline most of the
 * time, and a launcher that shouts about a failed fetch on every boot
 * would be worse than one that simply shows nothing.
 */
public final class Weather {

    private static final String TAG = "CarDash/Weather";
    private static final long REFRESH_MS = 20 * 60 * 1000L;

    public interface Listener {
        void onWeather(int tempC, String description, int code);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private double lat, lon;
    private boolean haveLocation;
    private Thread worker;
    private volatile boolean running;

    public void start(Listener l) {
        listener = l;
    }

    public void setLocation(double latitude, double longitude) {
        lat = latitude;
        lon = longitude;
        boolean first = !haveLocation;
        haveLocation = true;
        if (first) kick();
    }

    public void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        worker = null;
        listener = null;
    }

    private void kick() {
        if (running) return;
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    fetch();
                    try {
                        Thread.sleep(REFRESH_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "weather");
        worker.setDaemon(true);
        worker.start();
    }

    private void fetch() {
        HttpURLConnection c = null;
        try {
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast"
                            + "?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,weather_code",
                    lat, lon);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = c.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);

            JSONObject current = new JSONObject(out.toString("UTF-8"))
                    .getJSONObject("current");
            final int temp = (int) Math.round(current.getDouble("temperature_2m"));
            final int code = current.getInt("weather_code");
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) listener.onWeather(temp, describe(code), code);
                }
            });
        } catch (Exception e) {
            Log.i(TAG, "fetch failed (offline?): " + e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** WMO weather codes, collapsed to what fits on a tile. */
    public static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 85 && code <= 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "—";
    }
}
