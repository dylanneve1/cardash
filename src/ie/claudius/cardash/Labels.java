package ie.claudius.cardash;

import java.util.Locale;

/**
 * Android-free tile-label rules: some vendor apps have an empty label
 * or one equal to their package name. Fall back to the activity's
 * label, then to a cleaned-up last package segment.
 */
public final class Labels {

    private Labels() {}

    private static final String[] GENERIC = { "app", "apps", "main", "local", "android", "release", "service", "ui" };

    /** First usable label of appLabel, activityLabel; else {@link #fromPackage}. */
    public static String pick(String pkg, CharSequence appLabel, CharSequence activityLabel) {
        String a = usable(pkg, appLabel);
        if (a != null) return a;
        String b = usable(pkg, activityLabel);
        if (b != null) return b;
        return fromPackage(pkg);
    }

    private static String usable(String pkg, CharSequence label) {
        if (label == null) return null;
        String t = label.toString().trim();
        if (t.isEmpty() || t.equals(pkg)) return null;
        return t;
    }

    /** "com.hcn.canupgrade" becomes "Canupgrade"; generic tail segments are skipped. */
    public static String fromPackage(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return "";
        String[] segs = pkg.split("\\.");
        String best = null;
        for (int i = segs.length - 1; i >= 0; i--) {
            String s = segs[i].replace('_', ' ').trim();
            if (s.isEmpty()) continue;
            if (best == null) best = s;
            boolean generic = false;
            for (String g : GENERIC) if (g.equalsIgnoreCase(s)) generic = true;
            if (!generic && i > 0) { best = s; break; }
        }
        if (best == null) return pkg;
        return best.substring(0, 1).toUpperCase(Locale.US) + best.substring(1);
    }
}
