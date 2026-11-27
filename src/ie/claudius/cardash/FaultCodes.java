package ie.claudius.cardash;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import java.util.List;

import ie.claudius.cardash.vehicle.Dtc;
import ie.claudius.cardash.vehicle.DtcReader;
import ie.claudius.cardash.vehicle.VehicleHub;

/** "Read fault codes": ask the car for its stored codes and show them. Nothing is cleared. */
public final class FaultCodes {

    private FaultCodes() {}

    public static void show(final Context ctx, final M3 m3) {
        Toast.makeText(ctx, R.string.codes_reading, Toast.LENGTH_SHORT).show();
        VehicleHub.shared().readCodes(new DtcReader.Listener() {
            @Override
            public void onCodes(List<String> codes, String error) {
                if (ctx instanceof Activity
                        && (((Activity) ctx).isFinishing() || ((Activity) ctx).isDestroyed())) return;
                Pop pop = new Pop(ctx, m3, ctx.getString(R.string.codes_title));
                if (codes == null) {
                    pop.note(ctx.getString(R.string.codes_failed, error));
                } else if (codes.isEmpty()) {
                    pop.note(ctx.getString(R.string.codes_none));
                } else {
                    for (String c : codes) pop.note(c + "  ·  " + Dtc.describe(c));
                    pop.note(ctx.getString(R.string.codes_footer));
                }
                pop.button(ctx.getString(android.R.string.ok), null, true).show();
            }
        });
    }
}
