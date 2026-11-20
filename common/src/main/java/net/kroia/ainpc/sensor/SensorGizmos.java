package net.kroia.ainpc.sensor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * What the sensor debug overlay draws, as plain shapes and HUD text. Built from the sensor frames either on
 * the client (live, F6) or on the server (replay playback), where it is sent to spectators as a packet.
 *
 * Rays: a small box per hit, colored by block map color (water blue, lava orange, entity red).
 * Seen entities: their bounding box in yellow.
 * Sounds: a line from just in front of the eyes to the source and a box sized by intensity, colored by
 * category, fading with age.
 */
public record SensorGizmos(Vec3 origin, List<Box> boxes, List<Line> lines, List<Text> text) implements CustomPacketPayload {
    public static final Type<SensorGizmos> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("ainpc", "gizmos"));
    public static final StreamCodec<FriendlyByteBuf, SensorGizmos> CODEC = StreamCodec.of((buf, g) -> g.write(buf), SensorGizmos::read);

    public record Box(AABB box, int argb) {
    }

    public record Line(Vec3 a, Vec3 b, int argb) {
    }

    public record Text(String text, int rgb) {
    }

    @Override
    public Type<SensorGizmos> type() {
        return TYPE;
    }

    public static SensorGizmos of(BlockGetter world, PlayerState player, VisionFrame v, HearingFrame h, BodyFrame b) {
        List<Box> boxes = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        List<Text> text = new ArrayList<>();

        int hits = 0;
        for (VisionFrame.Ray r : v.rays()) {
            if (r.pos() == null) continue;
            hits++;
            int rgb = r.entity() != null ? 0xFF0000 : blockColor(world, r);
            double s = 0.04 + r.distance() * 0.008; // grow with distance so far hits stay visible
            boxes.add(new Box(AABB.ofSize(r.pos(), s, s, s), 0xFF000000 | dim(rgb, r.light())));
        }
        for (VisionFrame.SeenEntity e : v.entities()) boxes.add(new Box(e.entity().bbox(), 0xFFFFFF00));
        // recognised objects: around all their parts in the rule's color, darker behind walls; their anchor block
        for (ObjectSensor.Found o : v.objects()) {
            int rgb = ObjectSensor.color(o.kind());
            boxes.add(new Box(o.box(), 0xFF000000 | (o.seen() ? rgb : dim(rgb, 4))));
            boxes.add(new Box(new AABB(o.anchor()).inflate(0.05), o.inReach() ? 0xFFFFFFFF : 0xFF000000 | rgb)); // white: in reach
        }

        // Lines start just in front of the eyes, so in first person they point away from the crosshair.
        Head head = Head.of(player);
        Vec3 from = head.eye().add(head.fwd()).subtract(head.up().scale(0.3));
        for (HearingFrame.Heard s : h.sounds()) {
            float a = 1f - (float) s.ageTicks() / HearingSensor.MEMORY_TICKS;
            int argb = (Math.round(a * 255) << 24) | soundColor(s.category());
            lines.add(new Line(from, s.pos(), argb));
            double size = 0.2 + s.intensity();
            boxes.add(new Box(AABB.ofSize(s.pos(), size, size, size), argb));
        }

        line(text, 0xFFFFFF, "Vision: %d/%d rays hit, %d entities seen, %.2f ms",
                hits, v.rays().length, v.entities().size(), v.senseNanos() / 1e6);
        for (ObjectSensor.Rule r : ObjectSensor.RULES) { // per kind: how many, the nearest
            var of = v.objects().stream().filter(o -> o.kind().equals(r.kind())).toList();
            if (of.isEmpty()) continue;
            var o = of.get(0);
            line(text, r.color(), "%s: %d, nearest %.1fm yaw %.0f pitch %.0f, %d blocks high, %d parts, %s%s, %s", r.kind(), of.size(),
                    o.distance(), o.yawOffset(), o.pitchOffset(), o.height(), o.parts(), o.block().replace("minecraft:", ""),
                    o.seen() ? ", seen" : "", o.inReach() ? "IN REACH" : String.format(java.util.Locale.ROOT, "%.1fm to reach", o.nearest() - ObjectSensor.BLOCK_REACH));
        }
        VisionFrame.Ray c = v.cross(); // what a click would hit: in reach or too far
        if (c.pos() != null) {
            boolean ent = c.entity() != null, near = c.distance() <= (ent ? ObjectSensor.ENTITY_REACH : ObjectSensor.BLOCK_REACH);
            line(text, near ? 0x40FF40 : 0xFF8040, "Crosshair: %s %.1fm, %s", ent ? c.entity().type().replace("minecraft:", "")
                    : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(c.block().getBlock()).getPath(), c.distance(), near ? "IN REACH" : "too far");
        }
        line(text, 0xFFFFFF, "Body: hp %.1f/%.0f (+%.0f abs) food %d sat %.1f armor %d tough %.0f air %d/%d",
                b.health(), b.maxHealth(), b.absorption(), b.food(), b.saturation(), b.armor(), b.armorToughness(),
                b.air(), b.maxAir());
        line(text, 0xFFFFFF, "      %s%s%s%s%s fall %.1f vel %.2f %.2f %.2f",
                b.onGround() ? "ground " : "air ", b.inWater() ? "water " : "", b.inLava() ? "lava " : "",
                b.onFire() ? "fire " : "", b.frozen() ? "frozen " : "", b.fallDistance(),
                b.velocity().x, b.velocity().y, b.velocity().z);
        if (!b.effects().isEmpty())
            line(text, 0xC080FF, "Effects: %s", String.join(", ", b.effects().stream()
                    .map(e -> e.id().replace("minecraft:", "") + " " + (e.amplifier() + 1) + " (" + e.duration() / 20 + "s)").toList()));
        if (b.damageType() != null || b.healthDelta() != 0)
            line(text, 0xFF5050, "Pain: %+.1f hp, %s, from yaw %.0f pitch %.0f",
                    b.healthDelta(), b.damageType(), b.damageYaw(), b.damagePitch());
        line(text, 0xFFFFFF, "Hearing: %d sounds", h.sounds().size());
        for (int i = 0; i < Math.min(8, h.sounds().size()); i++) {
            HearingFrame.Heard s = h.sounds().get(i);
            line(text, soundColor(s.category()), "  %.2f  yaw %4.0f pitch %3.0f  %4.1fm  %s",
                    s.intensity(), s.yawOffset(), s.pitchOffset(), s.distance(), s.id().getPath());
        }
        return new SensorGizmos(head.eye(), boxes, lines, text);
    }

    /** Darker hit boxes for darker spots: brightness 0 keeps a fifth of the color. */
    private static int dim(int rgb, int light) {
        float f = 0.2f + 0.8f * light / 15f;
        return Math.round((rgb >> 16 & 255) * f) << 16 | Math.round((rgb >> 8 & 255) * f) << 8 | Math.round((rgb & 255) * f);
    }

    private static void line(List<Text> text, int rgb, String fmt, Object... args) {
        text.add(new Text(String.format(fmt, args), rgb));
    }

    public static int soundColor(SoundSource category) {
        return switch (category) {
            case HOSTILE -> 0xFF4040;
            case PLAYERS -> 0x40FF40;
            case NEUTRAL -> 0xFFFF40;
            case BLOCKS -> 0xB0B0B0;
            default -> 0x40FFFF;
        };
    }

    private static int blockColor(BlockGetter world, VisionFrame.Ray r) {
        BlockState state = r.block();
        if (state.getFluidState().is(FluidTags.LAVA)) return 0xFF8000;
        if (state.getFluidState().is(FluidTags.WATER)) return 0x3050FF;
        int c = state.getMapColor(world, r.blockPos()).col;
        return c == 0 ? 0xFFFFFF : c;
    }

    // --- network: positions as floats relative to the origin (the eye), ~17 KB per frame ------------

    private void write(FriendlyByteBuf buf) {
        buf.writeDouble(origin.x).writeDouble(origin.y).writeDouble(origin.z);
        buf.writeVarInt(boxes.size());
        for (Box b : boxes) {
            rel(buf, b.box.minX, b.box.minY, b.box.minZ);
            rel(buf, b.box.maxX, b.box.maxY, b.box.maxZ);
            buf.writeInt(b.argb);
        }
        buf.writeVarInt(lines.size());
        for (Line l : lines) {
            rel(buf, l.a.x, l.a.y, l.a.z);
            rel(buf, l.b.x, l.b.y, l.b.z);
            buf.writeInt(l.argb);
        }
        buf.writeVarInt(text.size());
        for (Text t : text) {
            buf.writeUtf(t.text);
            buf.writeInt(t.rgb);
        }
    }

    private void rel(FriendlyByteBuf buf, double x, double y, double z) {
        buf.writeFloat((float) (x - origin.x)).writeFloat((float) (y - origin.y)).writeFloat((float) (z - origin.z));
    }

    private static SensorGizmos read(FriendlyByteBuf buf) {
        Vec3 o = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        List<Box> boxes = new ArrayList<>();
        for (int i = buf.readVarInt(); i > 0; i--)
            boxes.add(new Box(new AABB(rel(buf, o), rel(buf, o)), buf.readInt()));
        List<Line> lines = new ArrayList<>();
        for (int i = buf.readVarInt(); i > 0; i--) lines.add(new Line(rel(buf, o), rel(buf, o), buf.readInt()));
        List<Text> text = new ArrayList<>();
        for (int i = buf.readVarInt(); i > 0; i--) text.add(new Text(buf.readUtf(), buf.readInt()));
        return new SensorGizmos(o, boxes, lines, text);
    }

    private static Vec3 rel(FriendlyByteBuf buf, Vec3 o) {
        return o.add(buf.readFloat(), buf.readFloat(), buf.readFloat());
    }
}
