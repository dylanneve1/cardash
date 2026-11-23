import ie.claudius.cardash.Labels;
import ie.claudius.cardash.diag.ActivityTargets;
import ie.claudius.cardash.diag.ExportText;

/** Plain-JVM checks: export masking, vehicle-key filter, tile labels, firmware-updater risk rules. */
public class ExportTextTest {

    private static int failures = 0;

    static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static void eq(String what, String want, String got) {
        check(what + " (want " + want + ", got " + got + ")", want == null ? got == null : want.equals(got));
    }

    public static void main(String[] a) {
        // MAC masking
        eq("mac", "BC:93:07:xx:xx:xx", ExportText.maskMac("bc:93:07:12:34:56"));
        eq("mac dash", "BC-93-07-xx-xx-xx", ExportText.maskMac("BC-93-07-AA-BB-CC"));
        eq("not a mac", "hello", ExportText.maskMac("hello"));
        eq("null", null, ExportText.maskMac(null));
        eq("text with macs", "OBDII  BC:93:07:xx:xx:xx and 00:1A:7D:xx:xx:xx.",
                ExportText.maskText("OBDII  BC:93:07:12:34:56 and 00:1a:7d:da:71:13."));
        eq("mac in brackets", "[BC:93:07:xx:xx:xx]", ExportText.maskText("[BC:93:07:12:34:56]"));
        eq("time is not a mac", "12:34:56 up", ExportText.maskText("12:34:56 up"));

        // serial masking
        eq("serial", "AB******(10 chars)", ExportText.maskSerial("ABCDEF1234"));
        eq("short serial", "****", ExportText.maskSerial("1234"));
        eq("unknown stays", "unknown", ExportText.maskSerial("unknown"));
        eq("getprop serial", "[ro.serialno]: [AB******(10 chars)]",
                ExportText.maskText("[ro.serialno]: [ABCDEF1234]"));
        eq("kv serial", "serial=AB******(8 chars)", ExportText.maskText("serial=ABCDEFGH"));
        eq("idempotent", "serial=AB******(8 chars)", ExportText.maskText("serial=AB******(8 chars)"));
        eq("other props untouched", "[ro.product.model]: [tb8163p3_bsp]",
                ExportText.maskText("[ro.product.model]: [tb8163p3_bsp]"));
        eq("serial ports header untouched", "SERIAL PORTS", ExportText.maskText("SERIAL PORTS"));

        // vehicle keys
        for (String k : new String[] { "ro.car.mode", "persist.vendor.canbox.protocol", "canbox_protocol",
                "ro.hcn.version", "gear_state", "SpeedLimit", "mcu_version", "door.open", "persist.sys.brake" }) {
            check("vehicle key: " + k, ExportText.looksVehicleKey(k));
        }
        for (String k : new String[] { "ro.carrier", "wifi_scan_always_enabled", "ro.build.id", "screen_off_timeout",
                "ro.product.cancel", "persist.sys.speedy", null }) {
            check("not vehicle key: " + k, !ExportText.looksVehicleKey(k));
        }

        // tile labels
        eq("good label", "Music", Labels.pick("com.x.music", "Music", "Other"));
        eq("empty label -> activity", "Upgrade Tool", Labels.pick("com.hcn.canupgrade", "", "Upgrade Tool"));
        eq("pkg label -> activity", "Camera", Labels.pick("com.a.cam", "com.a.cam", "Camera"));
        eq("both bad -> segment", "Canupgrade", Labels.pick("com.hcn.canupgrade", "com.hcn.canupgrade", null));
        eq("null labels", "Canupgrade", Labels.pick("com.hcn.canupgrade", null, "  "));
        eq("generic tail skipped", "Hcamera", Labels.fromPackage("com.auto.hcamera.local"));
        eq("single segment", "Foo", Labels.fromPackage("foo"));
        eq("empty", "", Labels.fromPackage(""));

        // firmware-updater risk rules
        check("canupgrade activity", ActivityTargets.risk("com.hcn.canupgrade",
                "com.hcn.canupgrade.AutoCanUpgradeActivity") != null);
        check("canupgrade relative", ActivityTargets.risk("com.hcn.canupgrade", ".AutoCanUpgradeActivity") != null);
        check("mcuupgrade", ActivityTargets.risk("com.hcn.mcuupgrade", "com.hcn.mcuupgrade.UpgradeActivity") != null);
        check("autoupdate pkg", ActivityTargets.risk("com.hcn.autoupdate", "com.hcn.autoupdate.Main") != null);
        for (String n : new String[] { "com.x.McuSettings", "com.x.FlashTool", "com.x.OtaActivity",
                "com.x.SystemUpdate", "com.x.ota.Main", "com.x.flash_fw" }) {
            check("risky: " + n, ActivityTargets.risk("com.x", n) != null);
        }
        for (String n : new String[] { "com.x.FlashlightActivity", "com.x.PhotoActivity", "com.x.BootActivity",
                "com.x.Rotate", "com.x.Remote", "com.x.Potato", "com.x.SettingsActivity" }) {
            check("not risky: " + n, ActivityTargets.risk("com.x", n) == null);
        }
        check("riskyPackage canupgrade", ActivityTargets.riskyPackage("com.hcn.canupgrade"));
        check("riskyPackage mcu segment? no", !ActivityTargets.riskyPackage("com.jancar.music"));
        check("riskyPackage settings no", !ActivityTargets.riskyPackage("com.android.settings"));
        check("riskyPackage null", !ActivityTargets.riskyPackage(null));

        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("ExportTextTest: ok");
    }
}
