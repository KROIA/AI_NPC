package net.kroia.ainpc.mixin;

import net.kroia.ainpc.replay.BlockChangeHook;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Feeds the recording's block-change log. Every client block change ends in LevelChunk.setBlockState
 * (server updates, multi-block updates, the player's own predicted breaks/places); whole-chunk loads
 * go through replaceWithPacketData.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void ainpc$onSetBlockState(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir) {
        BlockChangeHook.Listener l = BlockChangeHook.listener;
        LevelChunk self = (LevelChunk) (Object) this;
        // A null return value means nothing changed.
        if (l != null && cir.getReturnValue() != null && self.getLevel().isClientSide())
            l.onBlockChanged(self, pos.immutable(), state);
    }

    @Inject(method = "replaceWithPacketData", at = @At("TAIL"))
    private void ainpc$onReplaced(CallbackInfo ci) {
        BlockChangeHook.Listener l = BlockChangeHook.listener;
        LevelChunk self = (LevelChunk) (Object) this;
        if (l != null && self.getLevel().isClientSide()) l.onChunkReplaced(self);
    }
}
