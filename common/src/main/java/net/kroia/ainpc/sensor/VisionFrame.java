package net.kroia.ainpc.sensor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * One tick of what the viewer sees.
 * rays: cols*rows, row-major, row 0 = top, col 0 = left.
 * cross: one extra ray exactly along the view direction (the crosshair), whatever the grid; not in the checksum.
 * entities: entities whose center is in the FOV with line of sight, nearest first.
 * objects: trees etc. recognised by the game, also behind walls and outside the view (ObjectSensor); not in the checksum.
 */
public record VisionFrame(int cols, int rows, Ray[] rays, Ray cross, List<SeenEntity> entities,
                          List<ObjectSensor.Found> objects, long senseNanos) {

    /**
     * dir: world-space unit direction of the ray. distance: to the hit, or the sensor range on a miss.
     * pos == null: nothing within range. entity != null: the ray hit that entity before any block,
     * otherwise block/blockPos/face describe the block hit (fluids included, state with properties).
     * light: brightness 0-15 just in front of the hit (the air lit in front of the face), at the range end on a miss.
     */
    public record Ray(Vec3 dir, double distance, Vec3 pos, BlockPos blockPos, BlockState block, Direction face,
                      EntityState entity, int light) {
        public Ray(Vec3 dir, double distance, Vec3 pos, BlockPos blockPos, BlockState block, Direction face, EntityState entity) {
            this(dir, distance, pos, blockPos, block, face, entity, 0);
        }

        public Ray withLight(int light) {
            return new Ray(dir, distance, pos, blockPos, block, face, entity, light);
        }

        public static Ray miss(Vec3 dir, double range) {
            return new Ray(dir, range, null, null, null, null, null);
        }
    }

    /** yaw/pitch offsets in degrees relative to the view direction; positive = right / up. */
    public record SeenEntity(EntityState entity, double distance, float yawOffset, float pitchOffset) {
    }

    /**
     * Fingerprint of what was seen. The recorder stores the live value per tick and replay compares it,
     * so any difference between recording and replay shows up instead of silently corrupting training data.
     * Light is left out: the client sometimes relights a tick late, which is not a replay error.
     */
    public int checksum() {
        int h = 1;
        for (Ray r : rays) {
            h = 31 * h + Float.floatToIntBits((float) r.distance);
            h = 31 * h + (r.block == null ? -1 : Block.getId(r.block));
            h = 31 * h + (r.entity == null ? -1 : r.entity.id());
        }
        for (SeenEntity e : entities) h = 31 * h + e.entity.id();
        return h;
    }
}
