import ie.claudius.cardash.car.CarProfile;
import ie.claudius.cardash.car.Vin;
import ie.claudius.cardash.vehicle.ObdReply;
import ie.claudius.cardash.vehicle.VehicleMerge;
import ie.claudius.cardash.vehicle.VehicleState;

/** VIN decoding, the car profile, the VIN reply and merging the sources. */
public class CarProfileTest {

    private static int failures = 0;

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    public static void main(String[] args) {
        Vin v = Vin.decode("WVWZZZ6RZAY123456");
        check(v != null && "Volkswagen".equals(v.make) && "Polo".equals(v.model) && "6R".equals(v.code)
                && v.year == 2010, "Polo 6R 2010");
        check("Volkswagen Polo (6R), 2010".equals(v.describe()), v.describe());
        check(Vin.decode(" wvwzzz6rzdy000001 ").year == 2013, "lower case, 2013");
        check(Vin.decode("WVWZZZ9NZ5Y000001").year == 2005, "digit year");
        check("Golf".equals(Vin.decode("WVWZZZ1KZ8W000001").model), "Golf");
        v = Vin.decode("TMBZZZ5JZB3000001");
        check("Škoda".equals(v.make) && "Fabia".equals(v.model), "Fabia");
        v = Vin.decode("WF0XXXGCDX1234567");
        check("Ford".equals(v.make) && v.model == null && v.year == 0, "maker only");
        v = Vin.decode("XYZZZZ6RZAY123456");
        check(v != null && v.make == null && v.model == null, "unknown maker");
        v = Vin.decode("WVWZZZQQZAY123456".replace('Q', 'X'));
        check("Volkswagen".equals(v.make) && v.model == null, "unknown VW code");
        for (String bad : new String[] { null, "", "WVWZZZ6RZAY12345", "WVWZZZ6RZAY1234567", "WVWZZZ6RZAY12345O" }) {
            check(Vin.decode(bad) == null, "rejects " + bad);
        }
        check("WVWZZZ6RZAY******".equals(Vin.masked("WVWZZZ6RZAY123456")), "masked");
        check(Vin.makes().contains("Volkswagen") && Vin.modelsOf("Volkswagen").contains("Polo")
                && Vin.modelsOf("Nobody").isEmpty(), "pick lists");

        CarProfile p = CarProfile.of("", "", "auto", "WVWZZZ6RZAY123456");
        check("Polo".equals(p.model) && CarProfile.FIVE_DOOR.equals(p.body) && !p.threeDoor(), "auto from VIN");
        p = CarProfile.of(null, null, CarProfile.THREE_DOOR, "WVWZZZ6RZAY123456");
        check(p.threeDoor() && "Volkswagen Polo, 3-door".equals(p.describe()), p.describe());
        p = CarProfile.of("SEAT", "", null, "WVWZZZ6RZAY123456");
        check("SEAT".equals(p.make) && p.model == null, "an overridden make drops the VIN's model");
        p = CarProfile.of("", "Golf", "estate", null);
        check("Golf".equals(p.model) && CarProfile.ESTATE.equals(p.body), "no VIN");
        check(CarProfile.of(null, null, "nonsense", null).describe().equals("Car not identified, 5-door"), "empty");

        // VIN replies: CAN multi-frame, and the older five-message form with padding.
        String can = "014\r0:490201575657\r1:5A5A5A36525A41\r2:59313233343536\r\r";
        check("WVWZZZ6RZAY123456".equals(ObdReply.vin(can)), "CAN VIN " + ObdReply.vin(can));
        String old = "49020100000057\r49020256575A5A\r4902035A36525A\r49020441593132\r49020533343536\r";
        check("WVWZZZ6RZAY123456".equals(ObdReply.vin(old)), "K-line VIN " + ObdReply.vin(old));
        check(ObdReply.vin("NO DATA\r") == null && ObdReply.vin("49020157565A\r") == null, "no VIN");

        // Merge: OBD first for engine values and fuel, CAN for what OBD lacks.
        VehicleState obd = new VehicleState(), can2 = new VehicleState();
        obd.rpm = 900;
        obd.fuelUnsupported = true;
        can2.rpm = 700;
        can2.fuelPercent = 60;
        can2.doorFrontLeft = VehicleState.Tri.YES;
        VehicleState m = VehicleMerge.merge(obd, can2, null);
        check(m.rpm == 900 && m.fuelPercent == 60 && !m.fuelUnsupported
                && m.doorFrontLeft == VehicleState.Tri.YES, "fuel from CAN when OBD lacks it");
        can2.fuelPercent = VehicleState.UNKNOWN_INT;
        m = VehicleMerge.merge(obd, can2);
        check(m.fuelPercent == VehicleState.UNKNOWN_INT && m.fuelUnsupported, "unavailable, not zero");
        can2.fuelPercent = 255;
        check(VehicleMerge.merge(can2).fuelPercent == VehicleState.UNKNOWN_INT, "out of range dropped");
        obd.fuelUnsupported = false;
        obd.fuelPercent = 40;
        can2.fuelPercent = 60;
        check(VehicleMerge.merge(obd, can2).fuelPercent == 40, "OBD fuel preferred");
        obd.rpm = VehicleState.UNKNOWN_INT;
        check(VehicleMerge.merge(obd, can2).rpm == 700, "falls back when a source goes quiet");
        VehicleState copy = m.copy();
        check(copy.fuelUnsupported && copy.doorFrontLeft == m.doorFrontLeft, "copy keeps fields");

        if (failures > 0) {
            System.out.println(failures + " failures");
            System.exit(1);
        }
        System.out.println("CarProfileTest: all passed");
    }
}
