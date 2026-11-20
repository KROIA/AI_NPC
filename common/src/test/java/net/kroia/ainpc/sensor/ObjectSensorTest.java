package net.kroia.ainpc.sensor;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.kroia.ainpc.replay.ReplayWorld;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectSensorTest {
    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static final BlockState LOG = Blocks.OAK_LOG.defaultBlockState();
    static final BlockState LEAVES = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false);
    static final BlockState PLACED_LEAVES = LEAVES.setValue(LeavesBlock.PERSISTENT, true);

    static ReplayWorld world() {
        ReplayWorld w = new ReplayWorld(-64, 384);
        for (int cx = -3; cx <= 3; cx++)
            for (int cz = -3; cz <= 3; cz++) w.setChunk(cx, cz, w.newChunkSections());
        return w;
    }

    /** trunk of height logs at x, z on y 64.., a leaf crown around its top */
    static void tree(ReplayWorld w, int x, int z, int logs, BlockState leaves) {
        for (int y = 64; y < 64 + logs; y++) w.setBlock(new BlockPos(x, y, z), LOG);
        int top = 64 + logs - 1;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = top - 1; y <= top + 1; y++)
                    if ((dx != 0 || dz != 0 || y > top)) w.setBlock(new BlockPos(x + dx, y, z + dz), leaves);
    }

    // standing at 0, 64, 0 looking south (+Z)
    static final Head HEAD = new Head(new Vec3(0.5, 65.62, 0.5), new Vec3(0, 0, 1), new Vec3(0, 1, 0), new Vec3(-1, 0, 0));

    @Test
    void treesAreFoundAndLogPilesAndHousesAreNot() {
        ReplayWorld w = world();
        tree(w, 0, 10, 5, LEAVES);         // a tree straight ahead
        tree(w, 20, 0, 4, LEAVES);         // one to the left (west is right when facing south)
        tree(w, -10, -10, 5, PLACED_LEAVES); // logs with placed leaves: a build, not a tree
        for (int x = 5; x < 8; x++) w.setBlock(new BlockPos(x, 64, -5), LOG); // a log pile
        LongOpenHashSet hits = new LongOpenHashSet();
        hits.add(new BlockPos(0, 66, 10).asLong()); // a ray hits the first tree's trunk

        List<ObjectSensor.Found> found = ObjectSensor.sense(w, HEAD, hits);
        assertEquals(2, found.size(), found.toString());
        ObjectSensor.Found a = found.get(0), b = found.get(1);
        assertEquals("tree", a.kind());
        assertEquals("minecraft:oak_log", a.block());
        assertEquals(new BlockPos(0, 64, 10), a.anchor());
        assertEquals(5, a.parts());
        assertEquals(5, a.height());
        assertTrue(a.seen());
        assertEquals(0, a.yawOffset(), 0.5);
        assertTrue(a.pitchOffset() < 0); // its foot is below the eye
        assertEquals(9.5, a.nearest(), 1e-9); // eye 0.5, 65.62, 0.5 -> the trunk block at eye height, z 10
        assertFalse(a.inReach());
        tree(w, 0, 4, 5, LEAVES); // one right in front
        ObjectSensor.Found near = ObjectSensor.sense(w, HEAD, hits).get(0);
        assertEquals(new BlockPos(0, 64, 4), near.anchor());
        assertTrue(near.inReach(), near.toString());
        assertEquals(new BlockPos(20, 64, 0), b.anchor());
        assertFalse(b.seen());
        assertEquals(-90, b.yawOffset(), 1); // +X is left when facing south
    }

    @Test
    void aTreeStaysUntilItsLastLogIsGone() {
        ReplayWorld w = world();
        tree(w, 0, 6, 5, LEAVES);
        ObjectSensor.Memory memory = new ObjectSensor.Memory();
        LongOpenHashSet none = new LongOpenHashSet();
        assertEquals(1, ObjectSensor.sense(w, HEAD, none, memory).size());
        // the leaves decay, the player chops all logs but one
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = 64; y <= 70; y++) {
                    BlockPos p = new BlockPos(dx, y, 6 + dz);
                    if (!p.equals(new BlockPos(0, 66, 6))) w.setBlock(p, Blocks.AIR.defaultBlockState());
                }
        assertEquals(0, ObjectSensor.sense(w, HEAD, none).size(), "without memory: one log is no tree");
        List<ObjectSensor.Found> left = ObjectSensor.sense(w, HEAD, none, memory);
        assertEquals(1, left.size());
        assertEquals(new BlockPos(0, 66, 6), left.get(0).anchor());
        assertEquals(1, left.get(0).parts());
        assertEquals(1, ObjectSensor.sense(w, HEAD, none, memory).size(), "and stays");
        w.setBlock(new BlockPos(0, 66, 6), Blocks.AIR.defaultBlockState());
        assertEquals(0, ObjectSensor.sense(w, HEAD, none, memory).size());
        // a log placed there later is a new log, not the old tree
        w.setBlock(new BlockPos(0, 66, 6), LOG);
        assertEquals(0, ObjectSensor.sense(w, HEAD, none, memory).size());
    }

    @Test
    void scanIsFastEnoughForEveryTick() {
        ReplayWorld w = world();
        for (int x = -40; x < 40; x++)
            for (int z = -40; z < 40; z++)
                for (int y = 48; y < 64; y++) w.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
        for (int i = 0; i < 20; i++) tree(w, -20 + i * 2 % 40, -20 + i * 7 % 40, 5, LEAVES);
        for (int i = 0; i < 5; i++) ObjectSensor.sense(w, HEAD, new LongOpenHashSet()); // warm up
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) ObjectSensor.sense(w, HEAD, new LongOpenHashSet());
        double ms = (System.nanoTime() - t0) / 20 / 1e6;
        System.out.printf("ObjectSensor: %.2f ms per scan%n", ms);
        assertTrue(ms < 15, ms + " ms");
    }
}
