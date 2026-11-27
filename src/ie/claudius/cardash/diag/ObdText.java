package ie.claudius.cardash.diag;

import java.util.Locale;

import ie.claudius.cardash.vehicle.ObdReply;
import ie.claudius.cardash.vehicle.ObdSource;

/** The OBD link lines shared by the Diagnostics screen and the adb report. */
final class ObdText {

    private ObdText() {}

    static String link(ObdSource.Diag d) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "requests/s: %.1f%n", d.requestsPerSecond));
        sb.append("protocol: ");
        if (d.protocol < 0) sb.append("not found yet");
        else sb.append(d.protocol).append(ObdReply.isCan(d.protocol)
                ? (d.multi ? " (CAN, several PIDs per request)" : " (CAN, one PID per request)")
                : " (one PID per request)");
        sb.append("\nsupported PIDs: ");
        if (d.supported == null) sb.append("not read");
        else for (int p : d.supported) sb.append(ObdReply.hex2(p)).append(' ');
        return sb.append('\n').toString();
    }
}
