package net.kroia.ainpc.sensor;


/** Interoception: health, pain, hunger, armor, breath and how the body moves. One instance per body. */
public final class BodySensor {
    private float prevHealth = Float.NaN;

    public BodyFrame sense(PlayerState p) {
        float delta = Float.isNaN(prevHealth) ? 0 : p.health() - prevHealth;
        prevHealth = p.health();

        // PlayerState carries the damage while hurtTime > 0: pain is felt for half a second after a hit.
        Head head = p.damageFrom() == null ? null : Head.of(p);
        return new BodyFrame(
                p.health(), p.maxHealth(), p.absorption(), delta, p.damageType(),
                head == null ? Float.NaN : head.yawTo(p.damageFrom()),
                head == null ? Float.NaN : head.pitchTo(p.damageFrom()),
                p.food(), p.saturation(), p.armor(), p.armorToughness(), p.air(), p.maxAir(),
                p.onGround(), p.inWater(), p.inLava(), p.onFire(), p.frozen(), p.fallDistance(), p.velocity(), p.effects());
    }
}
