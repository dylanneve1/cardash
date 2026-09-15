package ie.claudius.cardash.vehicle;

/**
 * ELM327 reply parsing, kept free of Android imports so it can be
 * tested on a plain JVM. Clone adapters are unreliable enough that
 * this is the part most worth having a test for.
 */
public final class ObdParse {

    private ObdParse() {}

    /**
     * Pull the data bytes out of an ELM327 reply. A response to 010C
     * looks like "41 0C 1A F8". Anything that doesn't match that shape
     * (NO DATA, SEARCHING..., ?, STOPPED) returns null.
     */
    public static int[] payload(String reply, String pid, int expected) {
        if (reply == null) return null;
        String clean = reply.replaceAll("[\\r\\n>]", " ")
                .replaceAll("\\s+", " ").trim().toUpperCase();
        if (clean.isEmpty() || clean.contains("NO DATA")
                || clean.contains("UNABLE") || clean.contains("STOPPED")
                || clean.contains("ERROR") || clean.contains("?")) {
            return null;
        }
        // Mode 01 responses come back as mode+0x40.
        String want = String.format("4%s %s",
                pid.substring(1, 2), pid.substring(2, 4));
        int at = clean.indexOf(want);
        if (at < 0) {
            // Some adapters strip the spaces entirely.
            String tight = clean.replace(" ", "");
            String wantTight = want.replace(" ", "");
            int t = tight.indexOf(wantTight);
            if (t < 0) return null;
            String rest = tight.substring(t + wantTight.length());
            if (rest.length() < expected * 2) return null;
            int[] outBytes = new int[expected];
            for (int i = 0; i < expected; i++) {
                outBytes[i] = Integer.parseInt(rest.substring(i * 2, i * 2 + 2), 16);
            }
            return outBytes;
        }
        String[] parts = clean.substring(at).split(" ");
        if (parts.length < 2 + expected) return null;
        int[] outBytes = new int[expected];
        for (int i = 0; i < expected; i++) {
            outBytes[i] = Integer.parseInt(parts[2 + i], 16);
        }
        return outBytes;
    }

}
