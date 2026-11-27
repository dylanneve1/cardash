package ie.claudius.cardash.car;

/**
 * The car CarDash draws and describes: what the VIN says, unless the
 * user says otherwise. Android-free; see CarProfileTest.
 */
public final class CarProfile {

    public static final String AUTO = "auto", THREE_DOOR = "3door", FIVE_DOOR = "5door",
            ESTATE = "estate", SALOON = "saloon";
    public static final String[] BODIES = { AUTO, THREE_DOOR, FIVE_DOOR, ESTATE, SALOON };

    /** Null when neither the VIN nor the user says. */
    public final String make, model;
    /** One of the body constants, never AUTO. */
    public final String body;

    private CarProfile(String make, String model, String body) {
        this.make = make; this.model = model; this.body = body;
    }

    /**
     * @param make,model user overrides; null, empty or "auto" take the VIN's
     * @param body       user override; the VIN doesn't carry the door count, so auto means 5-door
     */
    public static CarProfile of(String make, String model, String body, String vin) {
        Vin v = Vin.decode(vin);
        String m = blank(make) ? (v == null ? null : v.make) : make;
        String md;
        if (!blank(model)) md = model;
        // The VIN's model only goes with the VIN's make.
        else md = v == null || m == null || !m.equals(v.make) ? null : v.model;
        String b = FIVE_DOOR;
        for (String s : BODIES) if (!AUTO.equals(s) && s.equals(body)) b = s;
        return new CarProfile(m, md, b);
    }

    private static boolean blank(String s) {
        return s == null || s.isEmpty() || AUTO.equals(s);
    }

    public boolean threeDoor() {
        return THREE_DOOR.equals(body);
    }

    public static String bodyLabel(String body) {
        if (THREE_DOOR.equals(body)) return "3-door";
        if (FIVE_DOOR.equals(body)) return "5-door";
        if (ESTATE.equals(body)) return "Estate";
        if (SALOON.equals(body)) return "Saloon";
        return "Auto";
    }

    /** "Volkswagen Polo, 5-door". */
    public String describe() {
        String name = (make == null ? "" : make) + (model == null ? "" : " " + model);
        name = name.trim();
        return (name.isEmpty() ? "Car not identified" : name) + ", " + bodyLabel(body);
    }
}
