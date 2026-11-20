package net.kroia.ainpc.sensor;

import net.minecraft.world.phys.Vec3;

/** The viewer's eye position and view basis; angles to a point are relative to where the head looks. */
public record Head(Vec3 eye, Vec3 fwd, Vec3 up, Vec3 right) {

    public static Head of(PlayerState p) {
        Vec3 fwd = Vec3.directionFromRotation(p.xRot(), p.yRot());
        Vec3 up = Vec3.directionFromRotation(p.xRot() - 90, p.yRot());
        return new Head(p.eyePos(), fwd, up, fwd.cross(up));
    }

    /** Degrees, positive = right, range -180..180 (behind is +-180). */
    public float yawTo(Vec3 p) {
        Vec3 rel = p.subtract(eye);
        return (float) Math.toDegrees(Math.atan2(rel.dot(right), rel.dot(fwd)));
    }

    /** Degrees, positive = up, range -90..90. */
    public float pitchTo(Vec3 p) {
        Vec3 rel = p.subtract(eye);
        double x = rel.dot(right), z = rel.dot(fwd);
        return (float) Math.toDegrees(Math.atan2(rel.dot(up), Math.sqrt(x * x + z * z)));
    }
}
