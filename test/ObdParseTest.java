import ie.claudius.cardash.vehicle.ObdParse;

import java.util.Arrays;

/**
 * Plain-JVM checks for the ELM327 reply parser — run with test.sh.
 *
 * These are the cases that actually bite: clone adapters that strip
 * spaces, echo the request back, answer NO DATA for an unsupported PID,
 * or emit SEARCHING... before the real frame. A parser that gets any of
 * these wrong shows the driver a confident wrong number, which is worse
 * than showing nothing.
 */
public class ObdParseTest {

    private static int failures = 0;

    public static void main(String[] args) {
        // Normal, spaced reply: RPM 010C -> 0x1AF8 / 4 = 1726
        eq("spaced", ObdParse.payload("41 0C 1A F8", "010C", 2),
                new int[] { 0x1A, 0xF8 });

        // Some clones strip every space.
        eq("tight", ObdParse.payload("410C1AF8", "010C", 2),
                new int[] { 0x1A, 0xF8 });

        // Multi-line with CR and the prompt character.
        eq("crlf", ObdParse.payload("41 0C 1A F8\r\r>", "010C", 2),
                new int[] { 0x1A, 0xF8 });

        // Unsupported PID: Toyota may well do this for fuel level.
        isNull("no data", ObdParse.payload("NO DATA", "012F", 1));
        isNull("searching", ObdParse.payload("SEARCHING...", "010D", 1));
        isNull("question", ObdParse.payload("?", "010C", 2));
        isNull("empty", ObdParse.payload("", "010C", 2));
        isNull("null", ObdParse.payload(null, "010C", 2));

        // Right shape but truncated mid-frame — must not index past the end.
        isNull("truncated", ObdParse.payload("41 0C 1A", "010C", 2));

        // A reply for a different PID must not be accepted.
        isNull("wrong pid", ObdParse.payload("41 0D 32", "010C", 2));

        // Single-byte PIDs: speed 010D -> 0x32 = 50 km/h
        eq("speed", ObdParse.payload("41 0D 32", "010D", 1), new int[] { 0x32 });

        // Echo left on: the request is repeated before the answer.
        eq("echo", ObdParse.payload("010C\r41 0C 0B B8", "010C", 2),
                new int[] { 0x0B, 0xB8 });

        if (failures == 0) {
            System.out.println("ObdParseTest: all passed");
        } else {
            System.out.println("ObdParseTest: " + failures + " FAILED");
            System.exit(1);
        }
    }

    private static void eq(String name, int[] got, int[] want) {
        if (!Arrays.equals(got, want)) {
            System.out.println("FAIL " + name + ": got " + Arrays.toString(got)
                    + " want " + Arrays.toString(want));
            failures++;
        }
    }

    private static void isNull(String name, int[] got) {
        if (got != null) {
            System.out.println("FAIL " + name + ": expected null, got "
                    + Arrays.toString(got));
            failures++;
        }
    }
}
