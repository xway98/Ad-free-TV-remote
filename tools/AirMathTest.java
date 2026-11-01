import app.pocketremote.core.AirMath;

/** Checks the gyro projection for the ways a phone is held. Prints PASS/FAIL per case. */
public class AirMathTest {
    static int failures;

    static void check(String name, float[] up, float[] gyro, float turnLeft, float pointUp) {
        float[] out = new float[2];
        AirMath.project(up, gyro, out);
        boolean ok = Math.abs(out[0] - turnLeft) < 0.02f && Math.abs(out[1] - pointUp) < 0.02f;
        if (!ok) failures++;
        System.out.printf("%s %s: turn=%.2f point=%.2f (want %.2f, %.2f)%n", ok ? "PASS" : "FAIL", name, out[0], out[1], turnLeft, pointUp);
    }

    public static void main(String[] a) {
        float s = (float) Math.sqrt(0.5);
        // flat, screen up: z is up
        check("flat, turn left", new float[]{0, 0, 9.8f}, new float[]{0, 0, 1}, 1, 0);
        check("flat, nose up", new float[]{0, 0, 9.8f}, new float[]{1, 0, 0}, 0, 1);
        // upright, screen facing you: y is up, the back of the phone points ahead
        check("upright, turn left", new float[]{0, 9.8f, 0}, new float[]{0, 1, 0}, 1, 0);
        check("upright, point up", new float[]{0, 9.8f, 0}, new float[]{1, 0, 0}, 0, 1);
        // tilted 45 degrees like a remote: top edge up-and-forward, screen facing up-and-back
        float[] tilt = {0, 9.8f * s, 9.8f * s};
        check("tilted, turn left", tilt, new float[]{0, s, s}, 1, 0);
        check("tilted, point up", tilt, new float[]{1, 0, 0}, 0, 1);
        // rolling the phone about its own long axis moves nothing when held upright
        check("upright, roll (no movement)", new float[]{0, 9.8f, 0}, new float[]{0, 0, 1}, 0, 0);
        // landscape-ish lean: gravity partly along x; turning about the vertical is still a turn
        float[] lean = {9.8f * s, 0, 9.8f * s};
        check("leaning, turn left", lean, new float[]{s, 0, s}, 1, 0);
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
