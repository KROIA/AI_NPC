package net.kroia.ainpc.replay;

import net.kroia.ainpc.sensor.LightSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The blocks of a recording, rebuilt from its chunk snapshots and block-change log. Implements vanilla's
 * BlockGetter, so the sensors raycast it exactly like a live level. No block entities, no ticking.
 */
public final class ReplayWorld implements BlockGetter {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState VOID_AIR = Blocks.VOID_AIR.defaultBlockState();

    /** One 16^3 section: palette + palette index per cell, index = (y * 16 + z) * 16 + x. */
    public record Section(List<BlockState> palette, short[] cells) {
        BlockState get(int x, int y, int z) {
            return palette.get(cells[index(x, y, z)]);
        }

        void set(int x, int y, int z, BlockState state) {
            int p = palette.indexOf(state);
            if (p < 0) {
                palette.add(state);
                p = palette.size() - 1;
            }
            cells[index(x, y, z)] = (short) p;
        }

        static int index(int x, int y, int z) {
            return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        }
    }

    private final int minY, height;
    // ponytail: keeps every chunk the recording ever touched; drop far chunks if long journeys run out of memory.
    private final Map<Long, Section[]> chunks = new HashMap<>();

    public ReplayWorld(int minY, int height) {
        this.minY = minY;
        this.height = height;
    }

    /** Replaces a whole chunk; sections missing from the array (null) are air. */
    public void setChunk(int cx, int cz, Section[] sections) {
        chunks.put(ChunkPos.asLong(cx, cz), sections);
    }

    public Section[] newChunkSections() {
        return new Section[height >> 4];
    }

    // Recorded light per section (sky << 4 | block); sections without data count as open sky.
    private final Map<Long, byte[]> light = new HashMap<>();

    public void setLight(int cx, int section, int cz, byte[] data) {
        light.put(SectionPos.asLong(cx, section, cz), data);
    }

    /** Brightness 0-15 like vanilla's getRawBrightness; unrecorded places count as open sky. */
    public int brightness(BlockPos pos, int skyDarken) {
        if (pos.getY() < minY) return 0;
        int si = (pos.getY() - minY) >> 4;
        byte[] d = si >= (height >> 4) ? null : light.get(SectionPos.asLong(pos.getX() >> 4, si, pos.getZ() >> 4));
        if (d == null) return Math.max(0, 15 - skyDarken);
        return LightSource.brightness(d[Section.index(pos.getX(), pos.getY(), pos.getZ())] & 255, skyDarken);
    }

    public void setBlock(BlockPos pos, BlockState state) {
        Section[] secs = chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        int si = (pos.getY() - minY) >> 4;
        if (secs == null || si < 0 || si >= secs.length) return; // change outside the recorded chunks
        if (secs[si] == null) {
            secs[si] = new Section(new java.util.ArrayList<>(List.of(AIR)), new short[RecordingFormat.SECTION_VOLUME]);
        }
        secs[si].set(pos.getX(), pos.getY(), pos.getZ(), state);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int si = (pos.getY() - minY) >> 4;
        if (pos.getY() < minY || si >= (height >> 4)) return VOID_AIR;
        Section[] secs = chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (secs == null || secs[si] == null) return AIR;
        return secs[si].get(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinBuildHeight() {
        return minY;
    }
}
