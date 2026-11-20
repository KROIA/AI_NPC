package net.kroia.ainpc.sensor;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Collects positioned sounds as they start and turns them into what the listener hears.
 * A sound stays audible for {@link #MEMORY_TICKS} after it started, so short sounds are not
 * lost between two sensing ticks; ageTicks tells how fresh it is.
 *
 * Feed it from the client's sound listener (live), the recording's sound events (replay) or, later,
 * a server sound hook (NPCs).
 */
public final class HearingSensor {
    public static final int MEMORY_TICKS = 20;
    public static final int MAX_SOUNDS = 16;

    /** range: distance at which the sound fades to silence (vanilla linear attenuation). */
    private record Event(ResourceLocation id, SoundSource category, Vec3 pos, float volume, float range, long tick) {
    }

    private final List<Event> events = new ArrayList<>();

    public synchronized void onSound(ResourceLocation id, SoundSource category, Vec3 pos, float volume, float range, long tick) {
        events.add(new Event(id, category, pos, volume, range, tick));
    }

    /** now: the world's game time of this tick (events are timestamped with it too). */
    public synchronized HearingFrame sense(PlayerState listener, long now) {
        Head head = Head.of(listener);
        List<HearingFrame.Heard> heard = new ArrayList<>();
        for (Iterator<Event> it = events.iterator(); it.hasNext(); ) {
            Event e = it.next();
            long age = now - e.tick;
            if (age > MEMORY_TICKS || age < 0) { // age < 0: the world's clock jumped (dimension change)
                it.remove();
                continue;
            }
            double dist = head.eye().distanceTo(e.pos);
            float intensity = intensity(e.volume, e.range, dist);
            if (intensity <= 0) continue;
            heard.add(new HearingFrame.Heard(e.id, e.category, e.pos, intensity, (float) dist,
                    head.yawTo(e.pos), head.pitchTo(e.pos), (int) age));
        }
        heard.sort(Comparator.comparingDouble(h -> -h.intensity()));
        if (heard.size() > MAX_SOUNDS) heard = new ArrayList<>(heard.subList(0, MAX_SOUNDS));
        return new HearingFrame(heard);
    }

    /** Vanilla's linear falloff: full volume at the source, silent at range. */
    static float intensity(float volume, float range, double dist) {
        return (float) (Math.min(volume, 1f) * Math.max(0, 1 - dist / range));
    }
}
