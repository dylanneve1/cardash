package ie.claudius.cardash.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone settings page (issue #1): everything except the socket.
 *
 * Android-free on purpose so test.sh can run the whole request flow on
 * the JVM: request parsing, pairing, the value whitelist, and the HTML.
 * {@link RemoteServer} only accepts connections and hands each request
 * to {@link Session#handle}.
 *
 * Security model, for a page that lives on a phone hotspot shared with
 * passengers: a 6-digit code shown only on the head unit pairs a browser
 * (cookie, SameSite=Strict so other sites can't post to it); five wrong
 * codes lock the server until it is switched off and on (new code); the
 * Host header must be a bare IP so a DNS-rebinding page can't reach it;
 * and only values from the fixed sets below can ever be written.
 */
public final class RemoteForm {

    private RemoteForm() {}

    /** Max request (headers + body). The form is a few hundred bytes. */
    static final int MAX_REQUEST = 16 * 1024;
    static final int MAX_FAILURES = 5;
    static final int MAX_SESSIONS = 4;

    // ---- the settings the page may change ----------------------------------

    /** One segmented setting. Keys and values mirror Prefs / SettingsActivity. */
    public static final class Option {
        final String key, def, title;
        final String[] values, labels;
        /** A widget option: hand-tuning it clears the preset, as on the unit. */
        final boolean widget;

        Option(String key, String def, String title, boolean widget, String... pairs) {
            this.key = key;
            this.def = def;
            this.title = title;
            this.widget = widget;
            values = new String[pairs.length / 2];
            labels = new String[pairs.length / 2];
            for (int i = 0; i < values.length; i++) {
                values[i] = pairs[2 * i];
                labels[i] = pairs[2 * i + 1];
            }
        }

        boolean allows(String v) {
            for (String s : values) if (s.equals(v)) return true;
            return false;
        }
    }

    // Literal keys rather than Prefs.* so this compiles without android.jar.
    public static final Option[] OPTIONS = {
            new Option("scrim", "balanced", "Background", false,
                    "glass", "Glass", "balanced", "Balanced", "solid", "Solid"),
            new Option("layout", "hero", "Layout", true, "hero", "Hero", "grid", "Grid"),
            new Option("motion", "expressive", "Motion", true,
                    "expressive", "Expressive", "calm", "Calm"),
            new Option("gauge", "arc", "Speedometer", true, "arc", "Arc", "digits", "Digits"),
            new Option("scale", "auto", "Gauge scale", true,
                    "city", "City", "auto", "Auto", "fast", "Fast"),
            new Option("date", "full", "Date", true,
                    "full", "Full", "short", "Short", "hide", "Hide"),
            new Option("media", "full", "Now playing", true,
                    "full", "Full", "compact", "Compact", "hide", "Hide"),
            new Option("vehicle", "auto", "Vehicle data", true,
                    "auto", "Auto", "obd", "OBD", "can", "CAN", "off", "Off"),
            new Option("speed_unit", "kmh", "Speed", false, "kmh", "km/h", "mph", "mph"),
            new Option("temp_unit", "c", "Temperature", false, "c", "°C", "f", "°F"),
            new Option("clock", "system", "Clock", false,
                    "system", "System", "12", "12 h", "24", "24 h"),
            new Option("hint", "show", "Tips", false, "show", "Show", "hide", "Hide"),
    };

    public static final String DASH = "dash";
    static final String DASH_DEFAULT = "speedo,weather";
    static final String[] DASH_ALL = { "speedo", "trip", "weather" };
    static final String[] DASH_LABELS = { "Speed", "Trip", "Weather" };
    public static final String PRESET = "preset";

    /** Where the page reads and writes settings: Prefs on the unit, a map in tests. */
    public interface Store {
        String get(String key, String def);

        /** Write all of these at once, then tell the dash. */
        void apply(Map<String, String> changes);
    }

    /**
     * The validated subset of a submitted form that differs from what is
     * stored. Anything not in the whitelist is dropped silently.
     */
    static Map<String, String> changes(Store store, Map<String, String> form) {
        Map<String, String> out = new LinkedHashMap<>();
        boolean widget = false;
        for (Option o : OPTIONS) {
            String v = form.get(o.key);
            if (v == null || !o.allows(v) || v.equals(store.get(o.key, o.def))) continue;
            out.put(o.key, v);
            widget |= o.widget;
        }
        // Checkboxes send nothing when all are off, so the form carries a
        // marker saying the dash group was on the page at all.
        if (form.containsKey("dash_present")) {
            String raw = "," + (form.get(DASH) == null ? "" : form.get(DASH)) + ",";
            StringBuilder sb = new StringBuilder();
            for (String w : DASH_ALL) if (raw.contains("," + w + ",")) sb.append(w).append(',');
            String now = normaliseDash(store.get(DASH, DASH_DEFAULT));
            if (!sb.toString().equals(now)) {
                out.put(DASH, sb.toString());
                widget = true;
            }
        }
        if (widget) out.put(PRESET, "");
        return out;
    }

    static String normaliseDash(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String w : DASH_ALL) {
            if (("," + raw + ",").contains("," + w + ",")) sb.append(w).append(',');
        }
        return sb.toString();
    }

    // ---- HTTP ------------------------------------------------------------------

    public static final class Request {
        public String method = "", path = "/", host = "";
        public final Map<String, String> query = new LinkedHashMap<>();
        public final Map<String, String> cookies = new LinkedHashMap<>();
        public final Map<String, String> form = new LinkedHashMap<>();
    }

    /** Reads one HTTP/1.x request; null if it is malformed or too big. */
    public static Request read(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int last4 = 0; // the last four bytes read, newest in the low byte
        while ((last4 & 0xFFFF) != 0x0A0A && last4 != 0x0D0A0D0A) {
            int b = in.read();
            if (b < 0) return null;
            head.write(b);
            if (head.size() > MAX_REQUEST) return null;
            last4 = (last4 << 8) | b;
        }
        String[] lines = new String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r?\n");
        if (lines.length == 0) return null;
        String[] first = lines[0].split(" ");
        if (first.length < 2) return null;
        Request r = new Request();
        r.method = first[0];
        String target = first[1];
        int q = target.indexOf('?');
        r.path = q < 0 ? target : target.substring(0, q);
        if (q >= 0) decode(target.substring(q + 1), r.query);

        int length = 0;
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c < 0) continue;
            String name = lines[i].substring(0, c).trim().toLowerCase(java.util.Locale.ROOT);
            String value = lines[i].substring(c + 1).trim();
            if (name.equals("host")) r.host = value;
            else if (name.equals("cookie")) {
                for (String part : value.split(";")) {
                    int e = part.indexOf('=');
                    if (e > 0) r.cookies.put(part.substring(0, e).trim(), part.substring(e + 1).trim());
                }
            } else if (name.equals("content-length")) {
                try {
                    length = Integer.parseInt(value);
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
        }
        if (length < 0 || head.size() + length > MAX_REQUEST) return null;
        byte[] body = new byte[length];
        int got = 0;
        while (got < length) {
            int n = in.read(body, got, length - got);
            if (n < 0) return null;
            got += n;
        }
        decode(new String(body, StandardCharsets.ISO_8859_1), r.form);
        return r;
    }

    /** application/x-www-form-urlencoded; repeated names are joined with ','. */
    static void decode(String s, Map<String, String> into) {
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            int e = pair.indexOf('=');
            String k = unescape(e < 0 ? pair : pair.substring(0, e));
            String v = unescape(e < 0 ? "" : pair.substring(e + 1));
            String prev = into.get(k);
            into.put(k, prev == null ? v : prev + "," + v);
        }
    }

    private static String unescape(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return "";
        }
    }

    static String response(int status, String reason, String extraHeaders, String html) {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        return "HTTP/1.0 " + status + " " + reason + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Frame-Options: DENY\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Connection: close\r\n"
                + extraHeaders + "\r\n" + html;
    }

    static String redirect(String to, String extraHeaders) {
        return response(303, "See Other", "Location: " + to + "\r\n" + extraHeaders, "");
    }

    /** True for "192.168.43.1" or "192.168.43.1:8466", false for any name. */
    static boolean ipHost(String host) {
        return host != null && host.matches("\\d{1,3}(\\.\\d{1,3}){3}(:\\d{1,5})?");
    }

    // ---- pairing + routing -------------------------------------------------------

    /** One server lifetime: one code, its paired browsers, its failure count. */
    public static final class Session {
        private final SecureRandom random;
        private final String code;
        private final List<String> tokens = new ArrayList<>();
        private int failures;

        public Session(SecureRandom random) {
            this.random = random;
            code = String.format(java.util.Locale.ROOT, "%06d", random.nextInt(1000000));
        }

        public String code() {
            return code;
        }

        public synchronized boolean locked() {
            return failures >= MAX_FAILURES;
        }

        public synchronized String handle(Request r, Store store) {
            if (!ipHost(r.host)) return response(400, "Bad Request", "", "");
            if (locked()) {
                return response(403, "Forbidden", "", page("Locked",
                        "<p class=err>Too many wrong codes. On the head unit, turn "
                                + "<b>Phone settings</b> off and on again for a new code.</p>"));
            }
            boolean paired = paired(r.cookies.get("cd"));
            if (r.path.equals("/pair") && r.method.equals("POST")) {
                if (equalsConstantTime(code, r.form.get("code"))) {
                    String t = token();
                    tokens.add(t);
                    while (tokens.size() > MAX_SESSIONS) tokens.remove(0);
                    return redirect("/", "Set-Cookie: cd=" + t
                            + "; Path=/; HttpOnly; SameSite=Strict\r\n");
                }
                failures++;
                return redirect("/?wrong=1", "");
            }
            if (!r.path.equals("/")) return response(404, "Not Found", "", page("Not found", ""));
            if (!paired) return response(200, "OK", "", loginPage(r.query.containsKey("wrong")));
            if (r.method.equals("POST")) {
                Map<String, String> ch = changes(store, r.form);
                if (!ch.isEmpty()) store.apply(ch);
                return redirect("/?saved=1", "");
            }
            return response(200, "OK", "", settingsPage(store, r.query.containsKey("saved")));
        }

        private boolean paired(String t) {
            if (t == null) return false;
            for (String s : tokens) if (equalsConstantTime(s, t)) return true;
            return false;
        }

        private String token() {
            byte[] b = new byte[16];
            random.nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format(java.util.Locale.ROOT, "%02x", x));
            return sb.toString();
        }
    }

    static boolean equalsConstantTime(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.trim().getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < x.length; i++) diff |= x[i] ^ (i < y.length ? y[i] : 0);
        return diff == 0;
    }

    // ---- pages ---------------------------------------------------------------------

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static final String CSS =
            "body{margin:0;background:#101318;color:#e2e2e9;font:17px/1.4 system-ui,sans-serif}"
            + "main{max-width:560px;margin:0 auto;padding:20px}"
            + "h1{font-weight:500;font-size:26px;margin:4px 0 16px}"
            + "section{background:#1d2024;border-radius:24px;padding:4px 18px;margin:0 0 16px}"
            + "fieldset{border:0;margin:0;padding:14px 0;border-top:1px solid #2e3136}"
            + "fieldset:first-child{border-top:0}"
            + "legend{padding:0 0 8px;font-size:18px}"
            + ".seg{display:flex;flex-wrap:wrap;gap:8px}"
            + ".seg input{position:absolute;opacity:0}"
            + ".seg label{min-height:48px;padding:0 18px;display:flex;align-items:center;"
            + "border-radius:24px;background:#2e3136;cursor:pointer}"
            + ".seg input:checked+label{background:#aac7ff;color:#0a305f}"
            + ".seg input:focus-visible+label{outline:2px solid #aac7ff;outline-offset:2px}"
            + "button{width:100%;min-height:56px;border:0;border-radius:28px;"
            + "background:#aac7ff;color:#0a305f;font:inherit;font-weight:600}"
            + "input.code{width:100%;box-sizing:border-box;min-height:56px;font-size:28px;"
            + "letter-spacing:8px;text-align:center;border-radius:16px;border:0;"
            + "background:#2e3136;color:#e2e2e9;margin:0 0 16px}"
            + "p{color:#c4c6d0}.ok{color:#9dd49a}.err{color:#ffb4ab}";

    static String page(String title, String body) {
        return "<!doctype html><html lang=en><head><meta charset=utf-8>"
                + "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<meta name=color-scheme content=dark>"
                + "<title>CarDash · " + esc(title) + "</title><style>" + CSS + "</style></head>"
                + "<body><main><h1>" + esc(title) + "</h1>" + body + "</main></body></html>";
    }

    static String loginPage(boolean wrong) {
        return page("Pair with CarDash",
                "<p>Enter the 6-digit code shown under <b>Phone settings</b> in CarDash's "
                        + "settings on the head unit.</p>"
                        + (wrong ? "<p class=err>That code didn't match.</p>" : "")
                        + "<form method=post action=/pair><input class=code name=code "
                        + "inputmode=numeric autocomplete=one-time-code maxlength=6 "
                        + "pattern=\"[0-9]{6}\" required autofocus>"
                        + "<button>Pair</button></form>");
    }

    static String settingsPage(Store store, boolean saved) {
        StringBuilder sb = new StringBuilder();
        if (saved) sb.append("<p class=ok>Saved. The dash updates right away.</p>");
        sb.append("<form method=post action=/><section>");
        // Dash widgets first: they are what people want to change most.
        sb.append("<fieldset><legend>Dash</legend><div class=seg>")
                .append("<input type=hidden name=dash_present value=1>");
        String dash = normaliseDash(store.get(DASH, DASH_DEFAULT));
        for (int i = 0; i < DASH_ALL.length; i++) {
            String id = "dash_" + DASH_ALL[i];
            sb.append("<input type=checkbox name=dash id=").append(id)
                    .append(" value=").append(DASH_ALL[i])
                    .append(dash.contains(DASH_ALL[i] + ",") ? " checked" : "")
                    .append("><label for=").append(id).append('>')
                    .append(esc(DASH_LABELS[i])).append("</label>");
        }
        sb.append("</div></fieldset>");
        for (Option o : OPTIONS) {
            String cur = store.get(o.key, o.def);
            sb.append("<fieldset><legend>").append(esc(o.title)).append("</legend><div class=seg>");
            for (int i = 0; i < o.values.length; i++) {
                String id = o.key + "_" + o.values[i];
                sb.append("<input type=radio name=").append(o.key).append(" id=").append(id)
                        .append(" value=\"").append(esc(o.values[i])).append('"')
                        .append(o.values[i].equals(cur) ? " checked" : "")
                        .append("><label for=").append(id).append('>')
                        .append(esc(o.labels[i])).append("</label>");
            }
            sb.append("</div></fieldset>");
        }
        sb.append("</section><button>Save</button></form>");
        return page("CarDash settings", sb.toString());
    }
}
