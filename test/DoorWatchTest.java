import ie.claudius.cardash.vehicle.DoorWatch;
import ie.claudius.cardash.vehicle.VehicleState;
import ie.claudius.cardash.vehicle.VehicleState.Tri;

public class DoorWatchTest {
    static int fails = 0;
    static void check(boolean ok, String what) {
        if (!ok) { fails++; System.out.println("FAIL: " + what); }
    }

    /** A car with every part reported: the named ones open, the rest shut. */
    static VehicleState car(int open) {
        VehicleState s = new VehicleState();
        s.doorFrontLeft = tri(open, DoorWatch.FRONT_LEFT);
        s.doorFrontRight = tri(open, DoorWatch.FRONT_RIGHT);
        s.doorRearLeft = tri(open, DoorWatch.REAR_LEFT);
        s.doorRearRight = tri(open, DoorWatch.REAR_RIGHT);
        s.boot = tri(open, DoorWatch.BOOT);
        s.bonnet = tri(open, DoorWatch.BONNET);
        return s;
    }

    static Tri tri(int open, int bit) { return (open & bit) != 0 ? Tri.YES : Tri.NO; }

    public static void main(String[] a) {
        DoorWatch w = new DoorWatch();
        w.update(new VehicleState(), false, 0);
        check(!w.visible(0) && w.open() == 0, "nothing known, nothing shown");
        w.update(car(0), false, 10);
        check(!w.visible(10), "all shut, nothing shown");
        check(DoorWatch.mask(null, Tri.YES) == 0, "no state");

        w.update(car(DoorWatch.BOOT), false, 100);
        check(w.visible(100) && w.open() == DoorWatch.BOOT && w.hidesAt() == -1, "boot opens: shown");
        check(w.visible(100_000), "stays while open");
        w.update(car(0), false, 200_000);
        check(w.visible(200_000) && w.hidesAt() == 203_000, "lingers after closing");
        w.update(car(0), false, 202_000);
        check(w.visible(202_999) && w.hidesAt() == 203_000, "later reports don't restart the linger");
        check(!w.visible(203_000), "gone after 3 s");

        // Reopened during the linger: stays, and the clock starts over.
        w.update(car(DoorWatch.FRONT_LEFT), false, 300_000);
        w.update(car(0), false, 301_000);
        w.update(car(DoorWatch.FRONT_LEFT), false, 302_000);
        check(w.visible(310_000), "reopened: no hiding");
        w.update(car(0), false, 311_000);
        check(w.visible(313_999) && !w.visible(314_000), "new linger");

        // Dismissal.
        w.update(car(DoorWatch.FRONT_LEFT), false, 400_000);
        w.dismiss();
        check(!w.visible(400_001), "tapped away");
        w.update(car(DoorWatch.FRONT_LEFT), false, 400_200);
        check(!w.visible(400_200), "stays away while the same door is open");
        w.update(car(DoorWatch.FRONT_LEFT | DoorWatch.BOOT), false, 400_400);
        check(w.visible(400_400), "another part opening brings it back");
        w.dismiss();
        w.update(car(DoorWatch.FRONT_LEFT), false, 400_600);
        w.update(car(DoorWatch.FRONT_LEFT | DoorWatch.BOOT), false, 400_800);
        check(w.visible(400_800), "shut and opened again brings it back");
        w.dismiss();
        // Silence is not shut: a source dropping out and back must not undo the dismissal.
        w.suspend();
        w.update(new VehicleState(), false, 401_000);
        check(!w.visible(401_000), "suspended");
        w.update(car(DoorWatch.FRONT_LEFT | DoorWatch.BOOT), false, 401_200);
        check(!w.visible(401_200), "dismissal survives a gap in the data");
        w.reset();
        w.update(car(DoorWatch.BOOT), false, 401_400);
        check(w.visible(401_400), "another source starts clean");

        // Suspend hides at once; dismissing during the linger is fine.
        w.suspend();
        check(!w.visible(401_500) && w.hidesAt() == -1, "nothing while suspended");
        w.update(car(DoorWatch.BOOT), false, 500_000);
        w.update(car(0), false, 500_100);
        w.dismiss();
        check(!w.visible(500_200), "dismiss during linger");

        // A three-door car has no rear doors.
        DoorWatch three = new DoorWatch();
        three.update(car(DoorWatch.REAR_LEFT | DoorWatch.REAR_RIGHT), true, 0);
        check(!three.visible(0), "rear doors ignored on a 3-door");
        three.update(car(DoorWatch.REAR_LEFT | DoorWatch.BONNET), true, 1);
        check(three.visible(1) && three.open() == DoorWatch.BONNET, "bonnet still counts");

        // The pop-up leaves if the open part simply stops being reported.
        DoorWatch quiet = new DoorWatch();
        quiet.update(car(DoorWatch.BOOT), false, 0);
        quiet.update(new VehicleState(), false, 1000);
        check(quiet.visible(3999) && !quiet.visible(4000), "unknown lingers then leaves");

        // From the simulator, as the app would see it.
        ie.claudius.cardash.vehicle.SimVehicle sim = new ie.claudius.cardash.vehicle.SimVehicle();
        sim.apply(ie.claudius.cardash.vehicle.SimVehicle.pairs("door_rr=1"), 0);
        DoorWatch s = new DoorWatch();
        s.update(sim.state(0), false, 0);
        check(s.visible(0) && s.open() == DoorWatch.REAR_RIGHT, "simulated door");

        if (fails == 0) System.out.println("DoorWatchTest: ok");
        else { System.out.println("DoorWatchTest: " + fails + " failure(s)"); System.exit(1); }
    }
}
