package net.kroia.ainpc.replay;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Called by LevelChunkMixin for client-side chunks only. Lives in common code without client
 * references so the mixin is safe on a dedicated server; the client recorder installs the listener.
 */
public final class BlockChangeHook {
    public interface Listener {
        void onBlockChanged(LevelChunk chunk, BlockPos pos, BlockState state);

        /** The chunk's content was replaced wholesale (chunk (re)load packet). */
        void onChunkReplaced(LevelChunk chunk);
    }

    public static volatile Listener listener;

    private BlockChangeHook() {
    }
}
