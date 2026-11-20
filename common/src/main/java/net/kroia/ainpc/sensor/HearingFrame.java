package net.kroia.ainpc.sensor;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Sounds the listener hears this tick, loudest first. */
public record HearingFrame(List<Heard> sounds) {

    /**
     * id: sound event, e.g. minecraft:entity.creeper.primed. intensity: 0..1 at the listener's ears.
     * yaw/pitch: degrees relative to the head, positive = right / up; behind is +-180.
     * pos is kept for the debug overlay; a network should get only the relative values.
     */
    public record Heard(ResourceLocation id, SoundSource category, Vec3 pos, float intensity, float distance,
                        float yawOffset, float pitchOffset, int ageTicks) {
    }
}
