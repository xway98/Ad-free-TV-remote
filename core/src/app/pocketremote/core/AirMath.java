package app.pocketremote.core;

/**
 * Turns the phone's gyroscope into pointer movement that doesn't depend on how the phone is held.
 *
 * The gyroscope reports rotation about the phone's own axes (x right, y up the screen, z out of the screen). What the
 * pointer wants is rotation about the world: turning left or right (about the vertical) moves it sideways, and
 * pointing up or down (about the horizontal axis across the phone's heading) moves it up or down. Gravity tells us
 * which way is up; the heading is the way the phone's top edge or its back is facing, flattened onto the horizontal.
 * Held flat, upright, or tilted like a remote, the same wrist movement gives the same pointer movement.
 */
public final class AirMath {
    private AirMath() {}

    /**
     * @param up   direction of "up" in phone axes (the gravity sensor's reading, or the accelerometer's at rest)
     * @param gyro rotation rate in phone axes, radians per second
     * @param out  receives {turn left, point up}: both positive for the movements their names say
     */
    public static void project(float[] up, float[] gyro, float[] out) {
        float ux = up[0], uy = up[1], uz = up[2];
        float n = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (n < 1e-3f) {                      // no gravity reading yet: assume the phone is flat
            out[0] = gyro[2];
            out[1] = gyro[0];
            return;
        }
        ux /= n;
        uy /= n;
        uz /= n;

        // heading: the top edge (y) and the back of the phone (-z), each flattened onto the horizontal plane
        float fx = -uy * ux + uz * ux;
        float fy = (1 - uy * uy) + uz * uy;
        float fz = -uy * uz + (uz * uz - 1);
        float fn = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fn < 0.1f) {
            fx = 0;
            fy = 1;
            fz = 0;
        } else {
            fx /= fn;
            fy /= fn;
            fz /= fn;
        }
        // right = heading x up
        float rx = fy * uz - fz * uy, ry = fz * ux - fx * uz, rz = fx * uy - fy * ux;

        out[0] = gyro[0] * ux + gyro[1] * uy + gyro[2] * uz;
        out[1] = gyro[0] * rx + gyro[1] * ry + gyro[2] * rz;
    }
}
