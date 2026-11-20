package net.kroia.ainpc.sensor;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Objects around the viewer recognised by the game itself (trees, later ore veins, crop fields, ...) instead
 * of from the rays. Sees through walls: every block within RADIUS sideways, BELOW/ABOVE the eye is checked
 * against the RULES; a rule grows an object from a block that can start one. Nearest first, at most
 * MAX_PER_KIND per kind.
 * Memory: an object once recognised stays one while any of its blocks is left (a tree whose leaves decayed,
 * the one log missed when chopping it). Each viewer keeps its own (see Memory).
 *
 * New kind: add a rule to RULES (most are a Cluster: connected blocks of a kind, maybe touching others), add
 * its name to training/data.py OBJECT_KINDS (append, the order is the model's input), regenerate the datasets.
 */
public final class ObjectSensor {
    public static final int RADIUS = 24, BELOW = 8, ABOVE = 12, MAX_PER_KIND = 8;

    /**
     * kind: the rule's name; block: its anchor block's id (e.g. oak_log); anchor: where to go / start (a
     * tree's lowest log); box: around all its parts; parts: how many blocks; seen: a vision ray hits one of
     * its parts; distance/yaw/pitch: from the eye to the anchor's center, offsets in degrees relative to the
     * view (positive = right / up) like a seen entity; nearest: from the eye to its nearest part's block
     * (how the game measures whether a block can be mined).
     */
    public record Found(String kind, String block, BlockPos anchor, AABB box, int parts, boolean seen,
                        double distance, float yawOffset, float pitchOffset, double nearest, LongOpenHashSet blocks) {
        public int height() {
            return (int) box.getYsize();
        }

        /** a part can be mined / used from here (survival reach) */
        public boolean inReach() {
            return nearest <= BLOCK_REACH;
        }
    }

    /** survival reach in blocks: blocks (eye to the block's box), entities (vanilla 1.21 defaults) */
    public static final double BLOCK_REACH = 4.5, ENTITY_REACH = 3;

    /** One kind of object. */
    public interface Rule {
        String kind();

        /** F6 color (RGB) */
        int color();

        /** can an object of this kind start at this block (cheap: called for every block around) */
        boolean starts(BlockState s);

        /**
         * the object containing start (its blocks added to done), or null if it is not one after all; known:
         * blocks of this kind's objects recognised before (an object containing one stays an object)
         */
        Found grow(BlockGetter world, BlockPos start, LongOpenHashSet done, Head head, LongOpenHashSet rayHits,
                   LongOpenHashSet known);
    }

    /**
     * The blocks of the objects recognised so far, per kind, for one viewer (the player with F6, an NPC, a
     * replay). A recording starts with an empty one, like its replay. Blocks within the scanned box that are no
     * longer part of an object are forgotten each tick; far ones are kept until the viewer comes back.
     */
    public static final class Memory {
        final Map<String, LongOpenHashSet> known = new HashMap<>();

        public void clear() {
            known.clear();
        }

        LongOpenHashSet of(String kind) {
            return known.computeIfAbsent(kind, k -> new LongOpenHashSet());
        }
    }

    /**
     * Blocks matching part, connected also diagonally (at most maxParts); an object if at least minTouching
     * blocks matching touching are next to them (0: always). Anchor: its lowest part.
     */
    public record Cluster(String kind, int color, Predicate<BlockState> part, Predicate<BlockState> touching,
                          int minTouching, int maxParts) implements Rule {
        @Override
        public boolean starts(BlockState s) {
            return part.test(s);
        }

        @Override
        public Found grow(BlockGetter world, BlockPos start, LongOpenHashSet done, Head head, LongOpenHashSet rayHits,
                          LongOpenHashSet known) {
            LongArrayList queue = new LongArrayList(); // order does not matter: used as a stack
            queue.add(start.asLong());
            done.add(start.asLong());
            LongOpenHashSet touched = new LongOpenHashSet(), blocks = new LongOpenHashSet();
            boolean wasOne = false;
            BlockPos anchor = start;
            int parts = 0, minX = start.getX(), minY = start.getY(), minZ = start.getZ(), maxX = minX, maxY = minY, maxZ = minZ;
            boolean seen = false;
            double nearest = Double.MAX_VALUE;
            BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
            while (!queue.isEmpty() && parts < maxParts) {
                BlockPos b = BlockPos.of(queue.removeLong(queue.size() - 1));
                parts++;
                blocks.add(b.asLong());
                wasOne |= known.contains(b.asLong());
                seen |= rayHits.contains(b.asLong());
                nearest = Math.min(nearest, new AABB(b).distanceToSqr(head.eye()));
                if (b.getY() < anchor.getY()) anchor = b;
                minX = Math.min(minX, b.getX()); minY = Math.min(minY, b.getY()); minZ = Math.min(minZ, b.getZ());
                maxX = Math.max(maxX, b.getX()); maxY = Math.max(maxY, b.getY()); maxZ = Math.max(maxZ, b.getZ());
                if (minTouching > 0)
                    for (Direction d : Direction.values())
                        if (touching.test(world.getBlockState(n.setWithOffset(b, d)))) touched.add(n.asLong());
                for (int x = -1; x <= 1; x++)
                    for (int y = -1; y <= 1; y++)
                        for (int z = -1; z <= 1; z++) {
                            long k = n.setWithOffset(b, x, y, z).asLong();
                            if (!done.contains(k) && part.test(world.getBlockState(n))) {
                                done.add(k);
                                queue.add(k);
                            }
                        }
            }
            if (touched.size() < minTouching && !wasOne) return null;
            Vec3 target = Vec3.atCenterOf(anchor);
            return new Found(kind, BuiltInRegistries.BLOCK.getKey(world.getBlockState(anchor).getBlock()).toString(), anchor,
                    new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1), parts, seen,
                    target.distanceTo(head.eye()), head.yawTo(target), head.pitchTo(target), Math.sqrt(nearest), blocks);
        }
    }

    public static final List<Rule> RULES = List.of(
            // a tree: logs touching at least 3 leaves that grew there (a log pile or a log house has none);
            // a forest's touching trees become one, its logs followed up to 1024
            new Cluster("tree", 0x40FF40, ObjectSensor::isLog, ObjectSensor::naturalLeaves, 3, 1024));

    private ObjectSensor() {
    }

    /** Without memory: only what is recognisable right now. */
    public static List<Found> sense(BlockGetter world, Head head, LongOpenHashSet rayHits) {
        return sense(world, head, rayHits, new Memory());
    }

    /** rayHits: BlockPos.asLong of the blocks the vision rays hit this tick; memory: updated. */
    public static List<Found> sense(BlockGetter world, Head head, LongOpenHashSet rayHits, Memory memory) {
        BlockPos eye = BlockPos.containing(head.eye());
        LongOpenHashSet done = new LongOpenHashSet();
        Map<String, List<Found>> byKind = new HashMap<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        // ponytail: reads every block of the 49x49x21 box each tick (~50k, part of the Vision ms in F6); scan
        // every few ticks or by chunk section palettes if it gets slow
        for (int dx = -RADIUS; dx <= RADIUS; dx++)
            for (int dz = -RADIUS; dz <= RADIUS; dz++)
                for (int dy = -BELOW; dy <= ABOVE; dy++) {
                    BlockState s = world.getBlockState(p.set(eye.getX() + dx, eye.getY() + dy, eye.getZ() + dz));
                    if (s.isAir() || done.contains(p.asLong())) continue;
                    for (Rule r : RULES) {
                        if (!r.starts(s)) continue;
                        Found f = r.grow(world, p.immutable(), done, head, rayHits, memory.of(r.kind()));
                        if (f != null) byKind.computeIfAbsent(f.kind(), k -> new ArrayList<>()).add(f);
                        break;
                    }
                }
        // memory: within the box exactly the objects found now (every block in it was looked at), beyond it as before
        for (var e : memory.known.entrySet()) {
            e.getValue().removeIf((long k) -> Math.abs(BlockPos.getX(k) - eye.getX()) <= RADIUS
                    && Math.abs(BlockPos.getZ(k) - eye.getZ()) <= RADIUS
                    && BlockPos.getY(k) - eye.getY() >= -BELOW && BlockPos.getY(k) - eye.getY() <= ABOVE);
        }
        for (var e : byKind.entrySet())
            for (Found f : e.getValue()) memory.of(e.getKey()).addAll(f.blocks());
        List<Found> all = new ArrayList<>();
        for (List<Found> l : byKind.values()) {
            l.sort(Comparator.comparingDouble(Found::distance));
            all.addAll(l.subList(0, Math.min(MAX_PER_KIND, l.size())));
        }
        all.sort(Comparator.comparingDouble(Found::distance));
        return all;
    }

    public static int color(String kind) {
        for (Rule r : RULES) if (r.kind().equals(kind)) return r.color();
        return 0xFFFFFF;
    }

    /** ponytail: by name (oak_log, cherry_log, modded *_log, ...), not the logs tag: tags are only bound once
     * a world loads, so tests could not see them */
    static boolean isLog(BlockState s) {
        return s.getBlock() instanceof RotatedPillarBlock && BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().endsWith("_log");
    }

    static boolean naturalLeaves(BlockState s) {
        return s.getBlock() instanceof LeavesBlock && !s.getValue(LeavesBlock.PERSISTENT);
    }
}
