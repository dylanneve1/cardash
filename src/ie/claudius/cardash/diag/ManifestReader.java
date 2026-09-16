package ie.claudius.cardash.diag;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads another installed package's AndroidManifest.xml, including the
 * intent filters PackageManager will not hand out.
 *
 * {@code PackageManager.GET_RECEIVERS} returns component names but not
 * what they filter on. The APK itself, however, is readable by any app
 * on the device, and {@code createPackageContext().getAssets()} opens
 * its compiled manifest as an ordinary XmlResourceParser. That is
 * public API, needs no permission and no root, and it is how this
 * launcher learns the broadcast actions a vendor's car service uses
 * without anyone fitting a canbox first.
 */
public final class ManifestReader {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    public static final class Component {
        public String kind;                 // activity | receiver | service | provider
        public String name;
        public String exported;             // "true" | "false" | "default"
        public String permission;
        public String authorities;
        public final List<String> actions = new ArrayList<>();
        public final List<String> categories = new ArrayList<>();
    }

    private ManifestReader() {}

    public static List<Component> read(Context ctx, String pkg) throws Exception {
        Context other = ctx.createPackageContext(pkg, 0);
        Resources res = other.getResources();
        XmlResourceParser p = other.getAssets().openXmlResourceParser("AndroidManifest.xml");
        List<Component> out = new ArrayList<>();
        Component current = null;
        try {
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = p.getName();
                    if ("activity".equals(tag) || "receiver".equals(tag)
                            || "service".equals(tag) || "provider".equals(tag)
                            || "activity-alias".equals(tag)) {
                        current = new Component();
                        current.kind = tag;
                        current.name = qualify(pkg, attr(p, res, "name"));
                        String exp = attr(p, res, "exported");
                        current.exported = exp == null ? "default" : exp;
                        current.permission = attr(p, res, "permission");
                        current.authorities = attr(p, res, "authorities");
                        out.add(current);
                    } else if (current != null && "action".equals(tag)) {
                        current.actions.add(attr(p, res, "name"));
                    } else if (current != null && "category".equals(tag)) {
                        current.categories.add(attr(p, res, "name"));
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String tag = p.getName();
                    if (current != null && tag.equals(current.kind)) current = null;
                }
            }
        } finally {
            p.close();
        }
        return out;
    }

    /** An attribute, following a resource reference if that's what it is. */
    private static String attr(XmlResourceParser p, Resources res, String name) {
        int id = p.getAttributeResourceValue(ANDROID, name, 0);
        if (id != 0) {
            try {
                return res.getString(id);
            } catch (Resources.NotFoundException e) {
                return "@" + Integer.toHexString(id);
            }
        }
        return p.getAttributeValue(ANDROID, name);
    }

    private static String qualify(String pkg, String name) {
        if (name == null) return null;
        if (name.startsWith(".")) return pkg + name;
        if (!name.contains(".")) return pkg + "." + name;
        return name;
    }
}
