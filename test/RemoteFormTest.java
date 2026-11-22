import ie.claudius.cardash.remote.RemoteForm;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
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
