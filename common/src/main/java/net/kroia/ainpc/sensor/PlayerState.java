package net.kroia.ainpc.sensor;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/**
 * Everything recorded about the sensing player's own body in one tick. The sensors read only this,
 * so a replay reproduces them exactly.
 * damageType/damageFrom: the last hit while it is still felt (hurtTime > 0); damageFrom is null for
 * sources without a position (fall, starving).
 */
public record PlayerState(Vec3 pos, float yRot, float xRot, float eyeHeight, String pose, float width, float height,
                          Vec3 velocity, boolean onGround, boolean inWater, boolean inLava, boolean onFire,
                          boolean frozen, float fallDistance,
                          float health, float maxHealth, float absorption, int food, float saturation,
                          int armor, float armorToughness, int air, int maxAir,
                          int hurtTime, String damageType, Vec3 damageFrom,
                          int slot, String mainHand, String offHand, int xpLevel, List<Effect> effects) {
    /** Active status effect, e.g. minecraft:poison, amplifier 0 = level I, duration in ticks (-1 = infinite). */
    public record Effect(String id, int amplifier, int duration) {
    }


    public Vec3 eyePos() {
        return pos.add(0, eyeHeight, 0);
    }

    public static PlayerState of(Player p) {
        DamageSource src = p.hurtTime > 0 ? p.getLastDamageSource() : null;
        return new PlayerState(p.position(), p.getYRot(), p.getXRot(), p.getEyeHeight(), p.getPose().name(),
                p.getBbWidth(), p.getBbHeight(), p.getDeltaMovement(),
                p.onGround(), p.isInWater(), p.isInLava(), p.isOnFire(), p.isFullyFrozen(), p.fallDistance,
                p.getHealth(), p.getMaxHealth(), p.getAbsorptionAmount(),
                p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel(),
                p.getArmorValue(), (float) p.getAttributeValue(Attributes.ARMOR_TOUGHNESS),
                p.getAirSupply(), p.getMaxAirSupply(),
                p.hurtTime, src == null ? null : src.getMsgId(), src == null ? null : src.getSourcePosition(),
                p.getInventory().selected, EntityState.itemId(p.getMainHandItem()), EntityState.itemId(p.getOffhandItem()),
                p.experienceLevel,
                p.getActiveEffects().stream()
                        .map(e -> new Effect(e.getEffect().getRegisteredName(), e.getAmplifier(), e.getDuration()))
                        .sorted(Comparator.comparing(Effect::id)).toList());
    }
}
