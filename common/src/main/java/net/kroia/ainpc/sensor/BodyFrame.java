package net.kroia.ainpc.sensor;

import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * healthDelta: change since the last tick (negative = hurt, also poison/starving).
 * damageType: vanilla damage message id (e.g. "mob", "fall", "lava") while the last hit is still felt, else null.
 * damageYaw/Pitch: direction of the damage source relative to the head, NaN if it has no position (fall, starving).
 * velocity: blocks per tick, world space.
 */
public record BodyFrame(float health, float maxHealth, float absorption, float healthDelta,
                        String damageType, float damageYaw, float damagePitch,
                        int food, float saturation, int armor, float armorToughness,
                        int air, int maxAir,
                        boolean onGround, boolean inWater, boolean inLava, boolean onFire, boolean frozen,
                        float fallDistance, Vec3 velocity, List<PlayerState.Effect> effects) {
}
