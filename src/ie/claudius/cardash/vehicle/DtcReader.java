package ie.claudius.cardash.vehicle;

import java.util.List;

/** A source that can read the car's stored fault codes when asked. */
public interface DtcReader {

    /** Answered once, on the main thread. */
    interface Listener {
        /** @param codes the stored codes (empty for none), or null with {@code error} saying why not */
        void onCodes(List<String> codes, String error);
    }

    void readCodes(Listener listener);
}
