import ie.claudius.cardash.car.CarGeometry;
import ie.claudius.cardash.car.Compass;

/** The car drawing keeps the approved design's proportions, and parts open the right way. */
public class CarGeometryTest {

    private static int failures = 0;

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.05f;
    }

    private static void inBounds(float[] p, String what) {
        float[] b = CarGeometry.BOUNDS;
        for (int i = 0; i < p.length; i += 2) {
            check(p[i] >= b[0] && p[i] <= b[2] && p[i + 1] >= b[1] && p[i + 1] <= b[3],
                    what + " leaves the bounds at " + p[i] + "," + p[i + 1]);
        }
    }

    public static void main(String[] args) {
        check(CarGeometry.HALF == 72 && CarGeometry.CX == 180, "half width 72 on a 360 canvas");
        float[] f = CarGeometry.frontDoor(false), r = CarGeometry.rearDoor(), three = CarGeometry.frontDoor(true);
        check(near(f[0], 233) && near(f[1], 290), "front door span");
        check(near(r[0], 292) && near(r[1], 346), "rear door span");
        check(near(three[0], 233) && near(three[1], 321), "3-door front span");

        float[] shut = CarGeometry.door(CarGeometry.LEFT, f, 0f);
        check(near(shut[0], 108) && near(shut[2], 108) && near(shut[3], 290), "shut door lies along the body");
        float[] left = CarGeometry.door(CarGeometry.LEFT, f, 1f);
        float[] right = CarGeometry.door(CarGeometry.RIGHT, f, 1f);
        check(left[2] < 108 && right[2] > 252, "doors swing outwards");
        check(near(left[0], 108) && near(left[1], 233), "around the front hinge");
        check(near(left[2], 108 - 57 * (float) Math.sin(Math.toRadians(38))), "38 degrees");
        check(near(180 - left[2], right[2] - 180) && near(left[3], right[3]), "mirror images");

        check(near(CarGeometry.tailgate(0f)[5], CarGeometry.BOT - 14), "shut tailgate ends at the shut line");
        check(CarGeometry.tailgate(1f)[5] > CarGeometry.BOT, "open tailgate reaches past the rear");

        float[] panel = CarGeometry.bonnetPanel();
        for (int i = 1; i < panel.length; i += 2) check(panel[i] <= CarGeometry.BONNET_Y, "bonnet panel in the nose");
        check(panel.length > 20 && panel[2] < panel[panel.length - 4], "bonnet panel runs left to right");

        inBounds(CarGeometry.body(), "body");
        inBounds(CarGeometry.tailgate(1f), "tailgate");
        for (int side : new int[] { CarGeometry.LEFT, CarGeometry.RIGHT }) {
            inBounds(CarGeometry.mirror(side), "mirror");
            inBounds(CarGeometry.door(side, three, 1f), "long door");
            inBounds(CarGeometry.door(side, r, 1f), "rear door");
        }

        check("N".equals(Compass.point(0)) && "NE".equals(Compass.point(47)) && "N".equals(Compass.point(350))
                && "W".equals(Compass.point(-90)) && "S".equals(Compass.point(540)), "compass points");

        if (failures > 0) {
            System.out.println(failures + " failures");
            System.exit(1);
        }
        System.out.println("CarGeometryTest: all passed");
    }
}
