import ie.claudius.cardash.diag.ActivityTargets;
import ie.claudius.cardash.diag.ActivityTargets.Target;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Plain-JVM checks for the exported-activity launcher (issue #5): what
 * counts as launchable, what gets a warning, how the list is filtered.
 */
public class ActivityTargetsTest {

    private static int failures = 0;

    static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static Target t(String pkg, String name, boolean launcher) {
        return new Target(pkg, name, launcher, null);
    }

    public static void main(String[] a) {
        // exported: explicit wins, otherwise an intent filter makes it exported
        check("explicit true", ActivityTargets.isExported("true", false));
        check("explicit false beats filter", !ActivityTargets.isExported("false", true));
        check("default + filter", ActivityTargets.isExported(null, true));
        check("default, no filter", !ActivityTargets.isExported(null, false));
        check("'default' + filter", ActivityTargets.isExported("default", true));
        check("binary-xml true", ActivityTargets.isExported("0xffffffff", false));
        check("binary-xml false", !ActivityTargets.isExported("0x0", true));
        check("garbage is not exported", !ActivityTargets.isExported("@7f010001", true));

        // launcher detection
        check("launcher",
                ActivityTargets.isLauncher(Arrays.asList("android.intent.action.MAIN"),
                        Arrays.asList("android.intent.category.LAUNCHER")));
        check("MAIN without LAUNCHER is hidden",
                !ActivityTargets.isLauncher(Arrays.asList("android.intent.action.MAIN"),
                        Arrays.asList("android.intent.category.DEFAULT")));

        // risk
        check("EngineerMode package is risky",
                ActivityTargets.risk("com.mediatek.engineermode", "com.mediatek.engineermode.Main") != null);
        check("EngineerMode any page",
                t("com.mediatek.engineermode", "com.mediatek.engineermode.bandmode.BandMode", false).risk != null);
        for (String n : new String[] { "com.x.FactoryTest", "com.x.EngineerMenu", "com.x.ModemSettings",
                "com.x.RadioInfo", "com.x.IMEIWriter", "com.x.RfTest", "com.x.RF", "com.x.rf_cal",
                "com.x.Test_RF_Page", "com.x.ui.RFTool" }) {
            check("risky: " + n, ActivityTargets.risk("com.x", n) != null);
        }
        for (String n : new String[] { "com.x.PerformanceActivity", "com.x.SettingsActivity",
                "com.x.Info", "com.x.SurfaceTest", "com.x.Reference" }) {
            check("not risky: " + n, ActivityTargets.risk("com.x", n) == null);
        }
        check("null-safe", ActivityTargets.risk(null, null) == null);

        // names
        check("shortName strips package", t("com.a", "com.a.ui.Main", false).shortName().equals("ui.Main"));
        check("shortName foreign class", t("com.a", "com.b.Main", false).shortName().equals("Main"));
        check("flat", t("com.a", "com.a.Main", false).flat().equals("com.a/com.a.Main"));

        // filter
        List<Target> all = new ArrayList<>();
        all.add(t("com.mediatek.engineermode", "com.mediatek.engineermode.EngineerMode", true));
        all.add(t("com.mediatek.engineermode", "com.mediatek.engineermode.BandMode", false));
        all.add(t("com.vendor.car", "com.vendor.car.FactorySettings", false));
        all.add(t("com.vendor.car", "com.vendor.car.Main", true));
        check("empty query keeps all", ActivityTargets.filter(all, "", false).size() == 4);
        check("null query keeps all", ActivityTargets.filter(all, null, false).size() == 4);
        check("hidden only", ActivityTargets.filter(all, "", true).size() == 2);
        check("case-insensitive", ActivityTargets.filter(all, "BANDMODE", false).size() == 1);
        check("all terms must match", ActivityTargets.filter(all, "vendor factory", false).size() == 1);
        check("terms in any order", ActivityTargets.filter(all, "factory vendor", false).size() == 1);
        check("no match", ActivityTargets.filter(all, "zzz", false).isEmpty());
        check("hidden + query", ActivityTargets.filter(all, "mediatek", true).size() == 1);
        check("extra spaces", ActivityTargets.filter(all, "  mediatek   band ", false).size() == 1);

        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("ActivityTargetsTest: ok");
    }
}
