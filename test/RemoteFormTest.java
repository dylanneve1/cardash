import ie.claudius.cardash.remote.RemoteForm;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain-JVM checks for the phone settings page (issue #1) — run with test.sh.
 *
 * What matters: nothing changes without the code, wrong codes lock it,
 * only whitelisted values are ever written, and a hostile page in the
 * phone's browser (rebinding, cross-site post) gets nowhere.
 */
public class RemoteFormTest {

    private static int failures = 0;

    static final class MapStore implements RemoteForm.Store {
        final Map<String, String> m = new HashMap<>();
        int applies;

        public String get(String key, String def) {
            String v = m.get(key);
            return v == null ? def : v;
        }

        public void apply(Map<String, String> changes) {
            applies++;
            m.putAll(changes);
        }

        Map<String, String> sim;
        Map<String, String> simChanges;

        public Map<String, String> sim() {
            return sim;
        }

        public void simApply(Map<String, String> changes) {
            simChanges = changes;
        }

        public List<String[]> apps() {
            List<String[]> a = new ArrayList<>();
            a.add(new String[] { "com.example.maps", "Maps" });
            a.add(new String[] { "com.example.music", "Music & <Radio>" });
            return a;
        }
    }

    static RemoteForm.Request req(String raw) throws Exception {
        return RemoteForm.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)));
    }

    static String post(String path, String cookie, String body) {
        return "POST " + path + " HTTP/1.1\r\nHost: 192.168.43.1:8466\r\n"
                + (cookie == null ? "" : "Cookie: cd=" + cookie + "\r\n")
                + "Content-Type: application/x-www-form-urlencoded\r\n"
                + "Content-Length: " + body.length() + "\r\n\r\n" + body;
    }

    static String get(String path, String cookie) {
        return "GET " + path + " HTTP/1.1\r\nHost: 192.168.43.1:8466\r\n"
                + (cookie == null ? "" : "Cookie: theme=x; cd=" + cookie + "\r\n") + "\r\n";
    }

    static String cookieOf(String response) {
        int i = response.indexOf("Set-Cookie: cd=");
        if (i < 0) return null;
        int j = response.indexOf(';', i);
        return response.substring(i + "Set-Cookie: cd=".length(), j);
    }

    public static void main(String[] args) throws Exception {
        MapStore store = new MapStore();
        RemoteForm.Session s = new RemoteForm.Session(new SecureRandom());
        String code = s.code();
        check("code is 6 digits", code.matches("\\d{6}"));

        // Unpaired: login page, and a settings POST changes nothing.
        String r = s.handle(req(get("/", null)), store);
        check("unpaired gets login", r.startsWith("HTTP/1.0 200") && r.contains("action=/pair"));
        r = s.handle(req(post("/", null, "speed_unit=mph")), store);
        check("unpaired post ignored", store.applies == 0 && store.m.isEmpty());

        // Rebinding: a hostname in Host is refused outright.
        r = s.handle(req("GET / HTTP/1.1\r\nHost: evil.example:8466\r\n\r\n"), store);
        check("named host refused", r.startsWith("HTTP/1.0 400"));
        r = s.handle(req("GET / HTTP/1.1\r\n\r\n"), store);
        check("missing host refused", r.startsWith("HTTP/1.0 400"));

        // Wrong code redirects back with a message, no cookie.
        String wrong = code.equals("000000") ? "111111" : "000000";
        r = s.handle(req(post("/pair", null, "code=" + wrong)), store);
        check("wrong code no cookie", cookieOf(r) == null && r.contains("Location: /?wrong=1"));

        // Right code pairs.
        r = s.handle(req(post("/pair", null, "code=" + code)), store);
        String cookie = cookieOf(r);
        check("right code sets cookie", cookie != null && cookie.matches("[0-9a-f]{32}"));
        check("cookie strict", r.contains("SameSite=Strict") && r.contains("HttpOnly"));
        check("bogus cookie unpaired",
                s.handle(req(get("/", "deadbeef")), store).contains("action=/pair"));

        // Paired page reflects the store and escapes nothing dangerous in.
        r = s.handle(req(get("/", cookie)), store);
        check("paired gets settings", r.contains("CarDash settings") && r.contains("name=speed_unit"));
        check("defaults checked", r.contains("id=speed_unit_kmh value=\"kmh\" checked")
                && r.contains("id=dash_speedo value=speedo checked")
                && !r.contains("id=dash_trip value=trip checked"));
        check("no scripts", !r.toLowerCase().contains("<script"));

        // A valid change is written; junk keys and values are not.
        r = s.handle(req(post("/", cookie,
                "dash_present=1&dash=speedo&dash=trip&speed_unit=mph&gauge=rainbow"
                        + "&canbox_protocols=%3Cx%3E&remote=off&clock=24")), store);
        check("redirect after save", r.contains("Location: /?saved=1"));
        check("mph written", "mph".equals(store.m.get("speed_unit")));
        check("clock written", "24".equals(store.m.get("clock")));
        check("dash normalised", "speedo,trip,".equals(store.m.get("dash")));
        check("bad value dropped", !store.m.containsKey("gauge"));
        check("unlisted keys dropped",
                !store.m.containsKey("canbox_protocols") && !store.m.containsKey("remote"));
        check("widget change clears preset", "".equals(store.m.get("preset")));
        check("one apply per save", store.applies == 1);

        // Saving the same values again is a no-op (no needless rebuild).
        s.handle(req(post("/", cookie, "dash_present=1&dash=trip&dash=speedo&speed_unit=mph")), store);
        check("unchanged no apply", store.applies == 1);

        // Unit-only change does not touch the preset highlight.
        store.m.put("preset", "driver");
        s.handle(req(post("/", cookie, "temp_unit=f")), store);
        check("units keep preset", "driver".equals(store.m.get("preset")));

        // All dash widgets off: only the marker arrives.
        s.handle(req(post("/", cookie, "dash_present=1")), store);
        check("dash all off", "".equals(store.m.get("dash")));

        // Tiles: only as many dropdowns as the dash shows, labels escaped.
        store.m.put("dash", "speedo,");
        store.m.put("layout", "hero");
        store.m.put("tile_0", "com.example.maps");
        store.m.put("tile_2", "com.gone.app");
        r = s.handle(req(get("/", cookie)), store);
        check("tiles shown", r.contains("name=tile_6 ") && !r.contains("name=tile_7 "));
        check("tile selected", r.contains("value=\"com.example.maps\" selected>Maps"));
        check("tile label escaped", r.contains("Music &amp; &lt;Radio&gt;"));
        check("uninstalled kept", r.contains("value=\"com.gone.app\" selected>com.gone.app (not installed)"));
        check("empty selected", r.contains("<select name=tile_1 id=t1><option value=\"\" selected>Empty"));
        check("visible counts", RemoteForm.visibleTiles(true, true) == 7 && RemoteForm.visibleTiles(true, false) == 8
                && RemoteForm.visibleTiles(false, true) == 10 && RemoteForm.visibleTiles(false, false) == 12);

        int before = store.applies;
        store.m.put("preset", "driver");
        s.handle(req(post("/", cookie, "tile_0=com.example.maps&tile_1=com.example.music"
                + "&tile_2=com.gone.app&tile_3=com.evil%3Bx&tile_4=&tile_12=com.example.maps"
                + "&tile_-1=com.example.maps")), store);
        check("tile written", "com.example.music".equals(store.m.get("tile_1")));
        check("unknown package dropped", !store.m.containsKey("tile_3"));
        check("empty unchanged no write", !store.m.containsKey("tile_4"));
        check("uninstalled untouched", "com.gone.app".equals(store.m.get("tile_2")));
        check("out-of-range slots dropped",
                !store.m.containsKey("tile_12") && !store.m.containsKey("tile_-1"));
        check("tile change keeps preset", "driver".equals(store.m.get("preset")) && store.applies == before + 1);
        s.handle(req(post("/", cookie, "tile_0=")), store);
        check("tile cleared", "".equals(store.m.get("tile_0")));
        s.handle(req(post("/", cookie, "tile_0=&tile_1=com.example.music")), store);
        check("tiles unchanged no apply", store.applies == before + 2);

        // Simulator: on the page only while it is on, and only for a paired browser.
        r = s.handle(req(get("/", cookie)), store);
        check("no simulator section when off", !r.contains("action=/sim"));
        s.handle(req(post("/sim", cookie, "action=cycle")), store);
        check("sim post ignored when off", store.simChanges == null);
        store.sim = new HashMap<>();
        store.sim.put("rpm", "3000");
        store.sim.put("door_fl", "1");
        r = s.handle(req(get("/", cookie)), store);
        check("sliders shown", r.contains("action=/sim") && r.contains("name=rpm id=s_rpm")
                && r.contains("value=\"3000\"") && r.contains("id=s_door_fl value=1 checked"));
        s.handle(req(post("/sim", null, "action=cycle")), store);
        check("unpaired sim post ignored", store.simChanges == null);
        r = s.handle(req(post("/sim", cookie, "action=apply&rpm=4000&coolant=115&door_rr=1&evil=1")), store);
        check("sim redirect", r.contains("Location: /?sim=1"));
        check("sim applied", "4000".equals(store.simChanges.get("rpm")) && "1".equals(store.simChanges.get("door_rr"))
                && "0".equals(store.simChanges.get("door_fl")) && !store.simChanges.containsKey("evil"));
        s.handle(req(post("/sim", cookie, "action=cycle&rpm=1")), store);
        check("cycle button", store.simChanges.size() == 1 && "1".equals(store.simChanges.get("cycle")));
        store.sim = null;

        // Lockout: five wrong codes and even the right one stops working.
        RemoteForm.Session t = new RemoteForm.Session(new SecureRandom());
        String w = t.code().equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) t.handle(req(post("/pair", null, "code=" + w)), store);
        r = t.handle(req(post("/pair", null, "code=" + t.code())), store);
        check("locked", t.locked() && r.startsWith("HTTP/1.0 403") && cookieOf(r) == null);

        // Parser limits.
        StringBuilder big = new StringBuilder("GET / HTTP/1.1\r\nHost: 1.2.3.4\r\nX: ");
        for (int i = 0; i < 20000; i++) big.append('a');
        big.append("\r\n\r\n");
        check("oversize header rejected", req(big.toString()) == null);
        check("oversize body rejected",
                req("POST / HTTP/1.1\r\nHost: 1.2.3.4\r\nContent-Length: 999999\r\n\r\n") == null);
        check("truncated body rejected",
                req("POST / HTTP/1.1\r\nHost: 1.2.3.4\r\nContent-Length: 10\r\n\r\nabc") == null);
        check("garbage rejected", req("\r\n\r\n") == null);
        RemoteForm.Request lf = req("GET /?saved=1 HTTP/1.0\nHost: 10.0.0.2\n\n");
        check("bare LF ok", lf != null && lf.path.equals("/") && lf.query.containsKey("saved"));
        RemoteForm.Request pct = req(post("/pair", null, "code=%31%322+3"));
        check("percent decode", pct != null && "122 3".equals(pct.form.get("code")));
        check("404", s.handle(req(get("/favicon.ico", cookie)), store).startsWith("HTTP/1.0 404"));

        if (failures > 0) {
            System.out.println("RemoteFormTest: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("RemoteFormTest: all passed");
    }

    static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name);
        }
    }
}
