package ie.claudius.cardash.diag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Android-free rules behind "launch any exported activity": which
 * manifest entries are launchable from another app, which of them are
 * risky enough to warrant a confirmation, and how the list is filtered.
 * No framework imports, so test.sh can run it on a plain JVM.
 */
public final class ActivityTargets {

    /** One activity another app may start. */
    public static final class Target {
        public final String pkg;
        public final String name;       // fully-qualified class name
        public final boolean launcher;  // has a MAIN/LAUNCHER filter (has an icon)
        public final String permission; // null, or what the caller must hold
        public final String risk;       // null, or why launching deserves a warning

        public Target(String pkg, String name, boolean launcher, String permission) {
            this.pkg = pkg;
            this.name = name;
            this.launcher = launcher;
            this.permission = permission;
            this.risk = risk(pkg, name);
        }

        /** "pkg/Class", the form `am start -n` takes. */
        public String flat() {
            return pkg + "/" + name;
        }

        /** The class name without its package prefix, for a big label. */
        public String shortName() {
            if (name.startsWith(pkg + ".")) return name.substring(pkg.length() + 1);
            int dot = name.lastIndexOf('.');
            return dot < 0 ? name : name.substring(dot + 1);
        }
    }

    private ActivityTargets() {}

    /**
     * Can another app start this activity? The manifest's explicit
     * {@code exported} wins; without it the platform default applies
     * (exported only when the activity declares an intent filter).
     * Anything we can't parse is treated as not exported: never offer a
     * Launch button we are not sure about.
     */
    public static boolean isExported(String exportedAttr, boolean hasIntentFilter) {
        if (exportedAttr == null || "default".equals(exportedAttr)) return hasIntentFilter;
        switch (exportedAttr.toLowerCase(Locale.US)) {
            case "true":
            case "0xffffffff":
            case "-1":
            case "1":
                return true;
            case "false":
            case "0x0":
            case "0":
                return false;
            default:
                return false;
        }
    }

    /** True when the filters make this a home-screen launchable (an icon exists). */
    public static boolean isLauncher(List<String> actions, List<String> categories) {
        return actions.contains("android.intent.action.MAIN")
                && categories.contains("android.intent.category.LAUNCHER");
    }

    private static final String[] RISKY_WORDS = { "factory", "engineer", "modem", "radio", "imei" };

    /**
     * Why launching this deserves a confirmation, or null if nothing
     * looks dangerous. The package com.mediatek.engineermode is risky
     * wholesale (radio, modem and band pages, NVRAM, RF test modes);
     * elsewhere the component name decides: "factory", "engineer",
     * "modem", "radio", "imei" anywhere in it, and "rf" only as its own
     * word (so RfTest matches but PerformanceActivity doesn't).
     */
    public static String risk(String pkg, String name) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.US);
        if (p.equals("com.mediatek.engineermode") || p.startsWith("com.mediatek.engineermode.")) {
            return "MediaTek EngineerMode: radio, modem and band pages can change or "
                    + "break mobile connectivity and calibration.";
        }
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        for (String w : RISKY_WORDS) {
            if (n.contains(w)) {
                return "The name contains \"" + w + "\": factory, engineering, radio or "
                        + "modem pages can change settings that are hard to undo.";
            }
        }
        for (String word : words(name)) {
            if (word.equals("rf")) {
                return "The name contains \"RF\": RF test pages can change radio settings "
                        + "that are hard to undo.";
            }
        }
        return null;
    }

    /** Split a class name into lower-case words at dots, underscores, digits and camel-case humps. */
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean sep = !Character.isLetter(c);
            boolean hump = Character.isUpperCase(c) && cur.length() > 0
                    && (Character.isLowerCase(s.charAt(i - 1))
                    || (i + 1 < s.length() && Character.isLowerCase(s.charAt(i + 1))));
            if (sep || hump) {
                if (cur.length() > 0) out.add(cur.toString().toLowerCase(Locale.US));
                cur.setLength(0);
            }
            if (!sep) cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString().toLowerCase(Locale.US));
        return out;
    }

    /**
     * Keep targets whose "pkg/Class" contains every whitespace-separated
     * term of the query (case-insensitive); {@code hiddenOnly} drops the
     * ones that already have a launcher icon.
     */
    public static List<Target> filter(List<Target> all, String query, boolean hiddenOnly) {
        String[] terms = query == null ? new String[0]
                : query.toLowerCase(Locale.US).trim().split("\\s+");
        List<Target> out = new ArrayList<>();
        for (Target t : all) {
            if (hiddenOnly && t.launcher) continue;
            String hay = t.flat().toLowerCase(Locale.US);
            boolean ok = true;
            for (String term : terms) {
                if (!term.isEmpty() && !hay.contains(term)) {
                    ok = false;
                    break;
                }
            }
            if (ok) out.add(t);
        }
        return out;
    }
}
