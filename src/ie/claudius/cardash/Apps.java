package ie.claudius.cardash;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Launchable apps, plus the favourites the user has pinned to tiles. */
public final class Apps {

    public static final class Entry {
        public final String pkg;
        public final String label;
        public final Drawable icon;

        Entry(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private static final String PREFS = "cardash";
    private static final String KEY_TILE = "tile_";
    public static final int TILES = 7;

    /**
     * Sensible first-run tiles for a Jancar-based head unit, in priority
     * order. Anything not installed is skipped, so the same build still
     * does something reasonable on another unit.
     */
    private static final String[] PREFERRED = {
            "com.jancar.aauto",
            "com.jancar.carplay",
            "com.jancar.music",
            "com.jancar.bluetooth",
            "com.google.android.apps.maps",
            "com.jancar.gallery",
            "com.jancar.audiosettings",
            "com.jancar.settings",
            "com.android.settings",
    };

    private Apps() {}

    public static List<Entry> launchable(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> found = pm.queryIntentActivities(main, 0);
        List<Entry> out = new ArrayList<>(found.size());
        String self = ctx.getPackageName();

        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (self.equals(pkg)) continue; // don't offer ourselves
            CharSequence label = ri.loadLabel(pm);
            out.add(new Entry(pkg,
                    label == null ? pkg : label.toString(),
                    ri.loadIcon(pm)));
        }

        final Collator collator = Collator.getInstance();
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return collator.compare(a.label, b.label);
            }
        });
        return out;
    }

    public static Entry byPackage(Context ctx, String pkg) {
        if (pkg == null) return null;
        PackageManager pm = ctx.getPackageManager();
        Intent launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return null; // uninstalled since we saved it
        try {
            CharSequence label = pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0));
            return new Entry(pkg, label.toString(), pm.getApplicationIcon(pkg));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    public static boolean launch(Context ctx, String pkg) {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- favourites -----------------------------------------------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String tile(Context ctx, int index) {
        return prefs(ctx).getString(KEY_TILE + index, null);
    }

    public static void setTile(Context ctx, int index, String pkg) {
        prefs(ctx).edit().putString(KEY_TILE + index, pkg).apply();
    }

    /** First launch: pre-fill tiles with whatever of PREFERRED exists. */
    public static void seedIfEmpty(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.getBoolean("seeded", false)) return;

        PackageManager pm = ctx.getPackageManager();
        List<String> chosen = new ArrayList<>(TILES);

        for (String pkg : PREFERRED) {
            if (chosen.size() >= TILES) break;
            if (pm.getLaunchIntentForPackage(pkg) != null && !chosen.contains(pkg)) {
                chosen.add(pkg);
            }
        }
        // Still short? Top up from whatever else is installed.
        for (Entry entry : launchable(ctx)) {
            if (chosen.size() >= TILES) break;
            if (!chosen.contains(entry.pkg)) chosen.add(entry.pkg);
        }

        SharedPreferences.Editor e = p.edit();
        for (int i = 0; i < chosen.size(); i++) {
            e.putString(KEY_TILE + i, chosen.get(i));
        }
        e.putBoolean("seeded", true).apply();
    }
}
