package net.kroia.ainpc.sensor;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionSensorTest {
    // Facing south (+Z): up is +Y, right is west (-X).
    static final Vec3 FWD = Vec3.directionFromRotation(0, 0);
    static final Vec3 UP = Vec3.directionFromRotation(-90, 0);
    static final Vec3 RIGHT = FWD.cross(UP);

    @Test
    void basisMatchesMinecraftAxes() {
        assertEquals(0, FWD.distanceTo(new Vec3(0, 0, 1)), 1e-6);
        assertEquals(0, UP.distanceTo(new Vec3(0, 1, 0)), 1e-6);
        assertEquals(0, RIGHT.distanceTo(new Vec3(-1, 0, 0)), 1e-6);
    }

    @Test
    void gridSpansTheFieldOfView() {
        double halfH = Math.toRadians(90), halfV = Math.toRadians(75); // 180 x 150, wider than a camera can
        // single cell = straight ahead
        assertEquals(0, VisionSensor.rayDir(FWD, UP, RIGHT, halfH, halfV, 0, 1, 0, 1).distanceTo(FWD), 1e-9);
        // a huge grid's corner cell approaches the corner angles: top-left = 90 left, 75 up
        Vec3 topLeft = VisionSensor.rayDir(FWD, UP, RIGHT, halfH, halfV, 0, 10001, 0, 10001);
        assertTrue(topLeft.dot(RIGHT) < 0 && topLeft.dot(UP) > 0);
        assertEquals(1, topLeft.length(), 1e-9);
        assertEquals(75, Math.toDegrees(Math.asin(topLeft.dot(UP))), 0.02);
        assertEquals(90, Math.toDegrees(Math.atan2(-topLeft.dot(RIGHT), topLeft.dot(FWD))), 0.02);
        // equal angles: the middle column of a 3-wide grid is 60 degrees from the left one
        Vec3 l = VisionSensor.rayDir(FWD, UP, RIGHT, halfH, halfV, 0, 3, 0, 1), m = VisionSensor.rayDir(FWD, UP, RIGHT, halfH, halfV, 1, 3, 0, 1);
        assertEquals(60, Math.toDegrees(Math.acos(l.dot(m))), 1e-6);
    }

    @Test
    void fullCircleBothWays() {
        double half = Math.toRadians(180);
        // 360 x 360, 4 x 4 cells: centers at -135, -45, 45, 135 degrees in both directions
        Vec3 leftBack = VisionSensor.rayDir(FWD, UP, RIGHT, half, half, 0, 4, 1, 4); // yaw -135, pitch +45
        assertTrue(leftBack.dot(FWD) < 0 && leftBack.dot(RIGHT) < 0 && leftBack.dot(UP) > 0);
        Vec3 overHead = VisionSensor.rayDir(FWD, UP, RIGHT, half, half, 1, 4, 0, 4); // yaw -45, pitch +135
        assertTrue(overHead.dot(FWD) < 0 && overHead.dot(UP) > 0, "over the top to behind");
        assertEquals(1, overHead.length(), 1e-9);
    }

    @Test
    void foveationPacksRaysTowardTheCenter() {
        double half = Math.toRadians(90);
        // 10 columns over 180 degrees: evenly 18 apart; foveation 0.6: 0.4x in the center, wider at the edge
        double even = angleBetween(VisionSensor.rayDir(FWD, UP, RIGHT, half, half, 4, 10, 0, 1, 0), VisionSensor.rayDir(FWD, UP, RIGHT, half, half, 5, 10, 0, 1, 0));
        double center = angleBetween(VisionSensor.rayDir(FWD, UP, RIGHT, half, half, 4, 10, 0, 1, 0.6), VisionSensor.rayDir(FWD, UP, RIGHT, half, half, 5, 10, 0, 1, 0.6));
        double edge = angleBetween(VisionSensor.rayDir(FWD, UP, RIGHT, half, half, 0, 10, 0, 1, 0.6), VisionSensor.rayDir(FWD, UP, RIGHT, half, half, 1, 10, 0, 1, 0.6));
        assertEquals(18, even, 1e-6);
        assertTrue(center < 0.45 * even && edge > 1.4 * even, center + " " + edge); // 7.3 and 28 degrees
        assertEquals(1, VisionSensor.warp(1, 0.6), 1e-12); // the view's edge stays
        assertEquals(-0.5, VisionSensor.warp(-0.5, 0), 1e-12);
    }

    @Test
    void evenKeepsNeighboursApartFarUpAndDown() {
        double half = Math.toRadians(90);
        // 10x10 over 180x180: in the top row next to the middle, sideways neighbours vs. the rows' 18 degrees
        double yawPitch = angleBetween(VisionSensor.rayDir(FWD, UP, RIGHT, half, half, 4, 10, 0, 10, 0), VisionSensor.rayDir(FWD, UP, RIGHT, half, half, 5, 10, 0, 10, 0));
        double even = angleBetween(VisionSensor.evenDir(FWD, UP, RIGHT, half, half, 4, 10, 0, 10, 0), VisionSensor.evenDir(FWD, UP, RIGHT, half, half, 5, 10, 0, 10, 0));
        assertTrue(yawPitch < 3.5 && even > 12, yawPitch + " " + even); // 2.8 vs 12.6 degrees
        // middle row and column: exactly like yaw/pitch, 18 degrees apart
        assertEquals(18, angleBetween(VisionSensor.evenDir(FWD, UP, RIGHT, half, half, 0, 10, 4, 9, 0), VisionSensor.evenDir(FWD, UP, RIGHT, half, half, 1, 10, 4, 9, 0)), 1e-6);
        assertEquals(18, angleBetween(VisionSensor.evenDir(FWD, UP, RIGHT, half, half, 4, 9, 0, 10, 0), VisionSensor.evenDir(FWD, UP, RIGHT, half, half, 4, 9, 1, 10, 0)), 1e-6);
        // the top-left cell is up and left
        Vec3 tl = VisionSensor.evenDir(FWD, UP, RIGHT, half, half, 0, 10, 0, 10, 0);
        assertTrue(tl.dot(UP) > 0 && tl.dot(RIGHT) < 0);
        assertEquals(1, tl.length(), 1e-9);
    }

    static double angleBetween(Vec3 a, Vec3 b) {
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, a.dot(b)))));
    }

    @Test
    void headAnglesArePositiveRightAndUp() {
        Head head = new Head(Vec3.ZERO, FWD, UP, RIGHT);
        assertEquals(90, head.yawTo(new Vec3(-5, 0, 0)), 1e-4);   // west = right when facing south
        assertEquals(-90, head.yawTo(new Vec3(5, 0, 0)), 1e-4);
        assertEquals(180, Math.abs(head.yawTo(new Vec3(0, 0, -5))), 1e-4);
        assertEquals(45, head.pitchTo(new Vec3(0, 5, 5)), 1e-4);
    }

    @Test
    void soundFallsOffLinearly() {
        assertEquals(1f, HearingSensor.intensity(1f, 16f, 0), 1e-6);
        assertEquals(0.5f, HearingSensor.intensity(2f, 16f, 8), 1e-6); // volume capped at 1, range carries loudness
        assertEquals(0f, HearingSensor.intensity(1f, 16f, 20), 1e-6);
    }
}
