package net.kroia.ainpc.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** How far the block being mined is broken (0..1), recorded as an NPC input. */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccess {
    @Accessor("destroyProgress")
    float ainpc$destroyProgress();
}
