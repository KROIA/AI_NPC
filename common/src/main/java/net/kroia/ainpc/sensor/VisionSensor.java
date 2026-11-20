package net.kroia.ainpc.sensor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Ray grid from the viewer's eyes (equal angles per cell; "planar" = the pinhole camera of older models). Reads only a block source and recorded state, so it
 * runs the same live (client level, debug overlay, NPC on the server) and in replay (rebuilt world).
 *
 * The fields are the calibration knobs: tune them with the debug overlay until the viewer
 * reliably sees what it needs to.
 */
public final class VisionSensor {
    /** Wider than a player (90x60 planar before). The server's setting: /ainpc vision; the client records with this. */
    public static final VisionSensor DEFAULT = new VisionSensor(48, 36, 180, 150, 48, 16, true, 0, true);
    /** Server setting for new training sets and playback (/ainpc vision, config/ainpc.json). */
    public static volatile VisionSensor current = DEFAULT;

    public final int cols, rows;
    public final double hFovDeg, vFovDeg, range;
    public final int maxEntities;
    /** true: equal angles per ray (any width up to 360); false: pinhole camera (below 180, models before angular) */
    public final boolean angular;
    /** Angular only, 0..0.9: rays packed toward the view center (0 = evenly spaced). See warp(). */
    public final double foveation;
    /** Angular only: projection "even", neighbouring rays about equally far apart up/down and sideways, also
     * far up and down (see evenDir); false = yaw then pitch (rays bunch up toward straight up and down). */
    public final boolean even;

    public VisionSensor(int cols, int rows, double hFovDeg, double vFovDeg, double range, int maxEntities, boolean angular) {
        this(cols, rows, hFovDeg, vFovDeg, range, maxEntities, angular, 0, false);
    }

    public VisionSensor(int cols, int rows, double hFovDeg, double vFovDeg, double range, int maxEntities, boolean angular,
                        double foveation, boolean even) {
        this.foveation = angular ? foveation : 0;
        this.even = angular && even;
        this.cols = cols;
        this.rows = rows;
        this.hFovDeg = hFovDeg;
        this.vFovDeg = vFovDeg;
        this.range = range;
        this.maxEntities = maxEntities;
        this.angular = angular;
    }

    /** From a header's "vision" object (dataset, model config); no "projection" = planar (written before angular). */
    public static VisionSensor of(com.google.gson.JsonObject o) {
        return new VisionSensor(o.get("cols").getAsInt(), o.get("rows").getAsInt(), o.get("hFov").getAsDouble(),
                o.get("vFov").getAsDouble(), o.get("range").getAsDouble(), o.get("maxEntities").getAsInt(),
                o.has("projection") && !"planar".equals(o.get("projection").getAsString()),
                o.has("foveation") ? o.get("foveation").getAsDouble() : 0,
                o.has("projection") && "even".equals(o.get("projection").getAsString()));
    }

    /** The "vision" object of headers; of() reads it back. */
    public void write(com.google.gson.stream.JsonWriter w) throws java.io.IOException {
        w.beginObject().name("cols").value(cols).name("rows").value(rows).name("hFov").value(hFovDeg).name("vFov").value(vFovDeg)
                .name("range").value(range).name("maxEntities").value(maxEntities)
                .name("projection").value(projection());
        if (foveation > 0) w.name("foveation").value(foveation); // absent = 0: datasets from before stay equal
        w.endObject();
    }

    public boolean sameAs(VisionSensor o) {
        return cols == o.cols && rows == o.rows && hFovDeg == o.hFovDeg && vFovDeg == o.vFovDeg && range == o.range
                && maxEntities == o.maxEntities && angular == o.angular && foveation == o.foveation && even == o.even;
    }

    public String projection() {
        return even ? "even" : angular ? "angular" : "planar";
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "%.0fx%.0f degrees, %dx%d rays, range %.0f, %s%s", hFovDeg, vFovDeg, cols, rows, range,
                projection(), foveation > 0 ? String.format(java.util.Locale.ROOT, ", foveation %.2f", foveation) : "");
    }

    /** Without light information: every ray reports full brightness. */
    public VisionFrame sense(BlockGetter world, PlayerState viewer, List<EntityState> entities) {
        return sense(world, LightSource.FULL, viewer, entities);
    }

    /** entities: everything around the viewer except the viewer itself; objects without memory. */
    public VisionFrame sense(BlockGetter world, LightSource light, PlayerState viewer, List<EntityState> entities) {
        return sense(world, light, viewer, entities, new ObjectSensor.Memory());
    }

    /** objects: what this viewer recognised before (ObjectSensor.Memory), updated. */
    public VisionFrame sense(BlockGetter world, LightSource light, PlayerState viewer, List<EntityState> entities,
                             ObjectSensor.Memory objects) {
        long t0 = System.nanoTime();
        Head head = Head.of(viewer);
        Vec3 eye = head.eye(), fwd = head.fwd(), up = head.up(), right = head.right();
        double halfH = Math.toRadians(hFovDeg / 2), halfV = Math.toRadians(vFovDeg / 2);
        double tanH = Math.tan(halfH), tanV = Math.tan(halfV);

        // ponytail: every ray tests every nearby entity (rays x entities AABB clips); add a per-entity
        // screen-space bound if mob farms make this show up in the HUD timing.
        AABB reach = new AABB(eye, eye).inflate(range);
        List<EntityState> nearby = new ArrayList<>();
        for (EntityState e : entities) if (!e.invisible() && e.bbox().intersects(reach)) nearby.add(e);
        boolean underwater = eyeInWater(world, eye);

        VisionFrame.Ray[] rays = new VisionFrame.Ray[cols * rows];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                Vec3 dir = even ? evenDir(fwd, up, right, halfH, halfV, col, cols, row, rows, foveation)
                        : angular ? rayDir(fwd, up, right, halfH, halfV, col, cols, row, rows, foveation)
                        : planarDir(fwd, up, right, tanH, tanV, col, cols, row, rows);
                rays[row * cols + col] = lit(castRay(world, eye, dir, range, underwater, nearby), eye, dir, light);
            }
        }
        VisionFrame.Ray cross = lit(castRay(world, eye, fwd, range, underwater, nearby), eye, fwd, light);

        List<VisionFrame.SeenEntity> seen = new ArrayList<>();
        for (EntityState e : nearby) {
            Vec3 center = e.bbox().getCenter();
            Vec3 rel = center.subtract(eye);
            double x = rel.dot(right), y = rel.dot(up), z = rel.dot(fwd);
            boolean inView;
            if (even) { // angle from the view center, split into its sideways and up parts (see evenDir)
                double side = Math.hypot(x, y), theta = Math.atan2(side, z);
                inView = side == 0 ? z > 0 : Math.abs(theta * x / side) <= halfH && Math.abs(theta * y / side) <= halfV;
            } else {
                inView = angular ? Math.abs(Math.atan2(x, z)) <= halfH && Math.abs(Math.atan2(y, Math.hypot(x, z))) <= halfV
                        : z > 0 && Math.abs(x / z) <= tanH && Math.abs(y / z) <= tanV;
            }
            if (!inView || rel.length() > range) continue;
            if (blocked(world, eye, center) && blocked(world, eye, e.eyePos())) continue;
            seen.add(new VisionFrame.SeenEntity(e, rel.length(), head.yawTo(center), head.pitchTo(center)));
        }
        seen.sort(Comparator.comparingDouble(VisionFrame.SeenEntity::distance));
        if (seen.size() > maxEntities) seen = new ArrayList<>(seen.subList(0, maxEntities));

        var hits = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (VisionFrame.Ray r : rays) if (r.blockPos() != null) hits.add(r.blockPos().asLong());
        return new VisionFrame(cols, rows, rays, cross, seen, ObjectSensor.sense(world, head, hits, objects), System.nanoTime() - t0);
    }

    /** Step back from the hit along the ray: the light of the air in front of the face, not the dark block inside. */
    private VisionFrame.Ray lit(VisionFrame.Ray r, Vec3 eye, Vec3 dir, LightSource light) {
        Vec3 at = (r.pos() != null ? r.pos() : eye.add(dir.scale(range))).subtract(dir.scale(0.01));
        return r.withLight(light.at(BlockPos.containing(at)));
    }

    /**
     * Direction through the center of grid cell (col, row); row 0 = top, col 0 = left. Equal angles per cell
     * (yaw then pitch, like a globe's longitude/latitude), so the view can be wider than a camera's (up to 360).
     * Pitch beyond 90 continues over the top (or under the feet) to behind, upside down, like bending backwards.
     */
    static Vec3 rayDir(Vec3 fwd, Vec3 up, Vec3 right, double halfH, double halfV, int col, int cols, int row, int rows) {
        return rayDir(fwd, up, right, halfH, halfV, col, cols, row, rows, 0);
    }

    /** foveation f: like a human eye, sharp in the center and coarse at the edges (same number of rays). */
    static Vec3 rayDir(Vec3 fwd, Vec3 up, Vec3 right, double halfH, double halfV, int col, int cols, int row, int rows, double f) {
        double yaw = warp((col + 0.5) / cols * 2 - 1, f) * halfH;
        double pitch = warp(1 - (row + 0.5) / rows * 2, f) * halfV;
        return fwd.scale(Math.cos(yaw)).add(right.scale(Math.sin(yaw))).scale(Math.cos(pitch)).add(up.scale(Math.sin(pitch)));
    }

    /**
     * Projection "even" (azimuthal equidistant, a fisheye): the cell's sideways angle a and up angle b give a
     * direction (a, b) degrees away from the view center, turned straight toward it by hypot(a, b). Along the
     * middle row and column exactly like yaw/pitch; elsewhere neighbours stay about equally far apart both ways
     * (sideways steps shrink by sin(t)/t at t from the center: 0.9 at 45, 0.64 at 90 degrees), where yaw then
     * pitch bunches them by cos(pitch) (0.16 at 81 degrees up).
     */
    static Vec3 evenDir(Vec3 fwd, Vec3 up, Vec3 right, double halfH, double halfV, int col, int cols, int row, int rows, double f) {
        double a = warp((col + 0.5) / cols * 2 - 1, f) * halfH;
        double b = warp(1 - (row + 0.5) / rows * 2, f) * halfV;
        double t = Math.hypot(a, b);
        if (t < 1e-12) return fwd;
        double s = Math.sin(t) / t;
        return fwd.scale(Math.cos(t)).add(right.scale(a * s)).add(up.scale(b * s));
    }

    /**
     * Grid position u (-1..1) -> share of the half view angle: u * ((1 - f) + f * u^2). Rays near the center
     * are about (1 - f) times the even spacing apart, the outer ones more (10 rays, f 0.6: 0.4x and 1.6x); the view's edges stay.
     */
    static double warp(double u, double f) {
        return u * ((1 - f) + f * u * u);
    }

    /** Pinhole camera: through the cell center on a flat image plane (the models before angular). */
    static Vec3 planarDir(Vec3 fwd, Vec3 up, Vec3 right, double tanH, double tanV, int col, int cols, int row, int rows) {
        double u = ((col + 0.5) / cols * 2 - 1) * tanH;
        double v = (1 - (row + 0.5) / rows * 2) * tanV;
        return fwd.add(right.scale(u)).add(up.scale(v)).normalize();
    }

    /**
     * Eye below the water surface (like the camera's fluid test). Not vanilla's Entity.updateFluidOnEyes: that
     * counts an eye up to 0.11 above the surface as in the water, and rays from there start in the air, so
     * nothing stopped them at the surface and they saw the land while the player counted as under water.
     */
    static boolean eyeInWater(BlockGetter world, Vec3 eye) {
        BlockPos pos = BlockPos.containing(eye);
        FluidState fluid = world.getFluidState(pos);
        return fluid.is(FluidTags.WATER) && pos.getY() + fluid.getHeight(world, pos) > eye.y;
    }

    // ponytail: OUTLINE stops at glass and leaves like any solid block; switch to per-block
    // transparency handling if the agent needs to see through them.
    private static VisionFrame.Ray castRay(BlockGetter world, Vec3 eye, Vec3 dir, double range,
                                           boolean underwater, List<EntityState> nearby) {
        Vec3 end = eye.add(dir.scale(range));
        BlockHitResult bhr = underwater ? clipUnderwater(world, eye, end)
                : world.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, CollisionContext.empty()));
        Vec3 blockHit = bhr.getType() == HitResult.Type.MISS ? end : bhr.getLocation();

        EntityState bestEntity = null;
        Vec3 bestPos = null;
        double best = eye.distanceToSqr(blockHit);
        for (EntityState e : nearby) {
            Optional<Vec3> p = e.bbox().clip(eye, blockHit);
            if (p.isPresent() && eye.distanceToSqr(p.get()) < best) {
                best = eye.distanceToSqr(p.get());
                bestEntity = e;
                bestPos = p.get();
            }
        }
        if (bestEntity != null)
            return new VisionFrame.Ray(dir, Math.sqrt(best), bestPos, null, null, null, bestEntity);
        if (bhr.getType() == HitResult.Type.MISS)
            return VisionFrame.Ray.miss(dir, range);
        return new VisionFrame.Ray(dir, Math.sqrt(best), blockHit, bhr.getBlockPos().immutable(),
                world.getBlockState(bhr.getBlockPos()), bhr.getDirection(), null);
    }

    /**
     * Like an OUTLINE clip, but water is transparent: blocks (including waterlogged ones and plants) and
     * other fluids such as lava are hit, and a ray leaving the water into a non-water block hits the water
     * surface (the top of the fluid at its real height), reported as the last water block.
     */
    static BlockHitResult clipUnderwater(BlockGetter world, Vec3 from, Vec3 to) {
        BlockPos[] lastWater = {null};
        BlockHitResult hit = BlockGetter.traverseBlocks(from, to, null, (ctx, pos) -> {
            BlockState state = world.getBlockState(pos);
            FluidState fluid = state.getFluidState();
            BlockHitResult blockHit = world.clipWithInteractionOverride(from, to, pos, state.getShape(world, pos), state);
            if (!fluid.isEmpty() && !fluid.is(FluidTags.WATER)) {
                BlockHitResult fluidHit = fluid.getShape(world, pos).clip(from, to, pos);
                if (fluidHit != null && (blockHit == null
                        || from.distanceToSqr(fluidHit.getLocation()) < from.distanceToSqr(blockHit.getLocation())))
                    blockHit = fluidHit;
            }
            if (blockHit != null) return blockHit;
            if (fluid.is(FluidTags.WATER)) {
                lastWater[0] = pos.immutable();
                return null;
            }
            if (lastWater[0] != null) {
                // Clip backwards against the last water block's fluid shape: where the ray left the water.
                BlockHitResult surface = world.getFluidState(lastWater[0]).getShape(world, lastWater[0]).clip(to, from, lastWater[0]);
                if (surface != null) return surface;
            }
            return null;
        }, ctx -> null);
        if (hit != null) return hit;
        Vec3 d = to.subtract(from);
        return BlockHitResult.miss(to, Direction.getNearest(d.x, d.y, d.z), BlockPos.containing(to));
    }

    private static boolean blocked(BlockGetter world, Vec3 from, Vec3 to) {
        return world.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()))
                .getType() != HitResult.Type.MISS;
    }
}
