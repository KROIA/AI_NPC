package net.kroia.ainpc.sensor;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/** Brightness 0-15 at a position, as vanilla computes it: max(sky light - sky darkening, block light). */
@FunctionalInterface
public interface LightSource {
    int at(BlockPos pos);

    LightSource FULL = p -> 15;

    /** Vanilla's sky darkening (0 = noon, 11 = darkest night/storm), Level.updateSkyBrightness. */
    static int skyDarken(Level level) {
        double rain = 1.0 - level.getRainLevel(1f) * 5f / 16.0;
        double thunder = 1.0 - level.getThunderLevel(1f) * 5f / 16.0;
        double sun = 0.5 + 2.0 * Mth.clamp(Mth.cos(level.getTimeOfDay(1f) * (float) (Math.PI * 2)), -0.25, 0.25);
        return (int) ((1.0 - sun * rain * thunder) * 11.0);
    }

    /** Packed light (sky << 4 | block) to brightness. */
    static int brightness(int packed, int skyDarken) {
        return Math.max((packed >> 4 & 15) - skyDarken, packed & 15);
    }
}
