package net.kroia.ainpc.replay;

import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import dev.architectury.event.events.common.LifecycleEvent;
import net.kroia.ainpc.AiNpc;
import net.kroia.ainpc.sensor.VisionSensor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * /ainpc vision [reset | <setting> <value>]: what new training sets (/ainpc generateTrainingset) and playback see with,
 * kept in &lt;server dir&gt;/config/ainpc_vision.json. A model-driven NPC sees like its model's training data instead.
 */
public final class VisionSetting {
    private VisionSetting() {
    }

    /** Server -> client: the vision setting as JSON, so F6 and recordings on the client use it too. */
    public record Sync(String json) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("ainpc", "vision"));
        public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.FriendlyByteBuf, Sync> CODEC =
                net.minecraft.network.codec.StreamCodec.of((buf, s) -> buf.writeUtf(s.json), buf -> new Sync(buf.readUtf()));

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    public static void register() {
        LifecycleEvent.SERVER_STARTING.register(VisionSetting::load);
        dev.architectury.event.events.common.PlayerEvent.PLAYER_JOIN.register(VisionSetting::send);
    }

    private static void send(net.minecraft.server.level.ServerPlayer p) {
        if (!dev.architectury.networking.NetworkManager.canPlayerReceive(p, Sync.TYPE)) return; // client without the mod
        StringWriter s = new StringWriter();
        try {
            VisionSensor.current.write(new JsonWriter(s));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        dev.architectury.networking.NetworkManager.sendToPlayer(p, new Sync(s.toString()));
    }

    private static Path file(MinecraftServer server) {
        return server.getServerDirectory().resolve("config").resolve("ainpc_vision.json");
    }

    private static void load(MinecraftServer server) {
        Path f = file(server);
        VisionSensor.current = VisionSensor.DEFAULT;
        if (!Files.exists(f)) return;
        try {
            VisionSensor.current = VisionSensor.of(JsonParser.parseString(Files.readString(f)).getAsJsonObject());
            AiNpc.LOGGER.info("Vision setting from {}: {}", f, VisionSensor.current);
        } catch (Exception e) {
            AiNpc.LOGGER.error("Reading {} failed, using the default vision {}", f, VisionSensor.DEFAULT, e);
        }
    }

    /** Settings of /ainpc vision &lt;setting&gt; &lt;value&gt;, with their limits. */
    public static final java.util.Map<String, double[]> SETTINGS = new java.util.LinkedHashMap<>();

    static {
        SETTINGS.put("horizontalAngle", new double[]{10, 360}); // degrees seen left to right (player: about 90)
        // degrees seen bottom to top (player: about 60); above 180 the rays go on over the head to behind,
        // so with horizontalAngle 360 every direction is seen twice
        SETTINGS.put("verticalAngle", new double[]{10, 360});
        SETTINGS.put("cols", new double[]{1, 256});     // rays per row (grid width)
        SETTINGS.put("rows", new double[]{1, 256});     // rays per column (grid height)
        SETTINGS.put("range", new double[]{4, 128});    // blocks a ray reaches
        SETTINGS.put("entities", new double[]{0, 16});  // nearest seen entities reported (training reads at most 16)
        // rays packed toward the view center like a human eye: 0 = even, 0.6 = center rays about 0.4x, outer rays about 1.6x apart
        SETTINGS.put("foveation", new double[]{0, 0.9});
    }

    public static int show(CommandSourceStack src) {
        VisionSensor v = VisionSensor.current;
        src.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "Vision for new training sets: horizontalAngle %.0f, verticalAngle %.0f degrees, cols %d, rows %d (%d rays, %.1fx%.1f degrees each%s), "
                        + "range %.0f, entities %d, foveation %.2f. Change: /ainpc vision <setting> <value>, back to default: /ainpc vision reset",
                v.hFovDeg, v.vFovDeg, v.cols, v.rows, v.cols * v.rows, v.hFovDeg / v.cols, v.vFovDeg / v.rows,
                v.foveation > 0 ? String.format(java.util.Locale.ROOT, " on average, %.1fx%.1f in the center",
                        v.hFovDeg / v.cols * (1 - v.foveation), v.vFovDeg / v.rows * (1 - v.foveation)) : "",
                v.range, v.maxEntities, v.foveation)), false);
        return 1;
    }

    public static int reset(CommandSourceStack src) {
        return save(src, VisionSensor.DEFAULT);
    }

    /** horizontalAngle/verticalAngle keep the degrees per ray (cols/rows follow), so the view gets wider, not blurrier. */
    public static int set(CommandSourceStack src, String setting, double value) {
        VisionSensor o = VisionSensor.current;
        int cols = o.cols, rows = o.rows, entities = o.maxEntities;
        double h = o.hFovDeg, vv = o.vFovDeg, range = o.range, fovea = o.foveation;
        switch (setting) {
            case "horizontalAngle" -> {
                cols = Math.max(1, (int) Math.round(value / (o.hFovDeg / o.cols)));
                h = value;
            }
            case "verticalAngle" -> {
                rows = Math.max(1, (int) Math.round(value / (o.vFovDeg / o.rows)));
                vv = value;
            }
            case "cols" -> cols = (int) value;
            case "rows" -> rows = (int) value;
            case "range" -> range = value;
            case "entities" -> entities = (int) value;
            case "foveation" -> fovea = value;
            default -> {
                src.sendFailure(Component.literal("Unknown setting " + setting + ", one of " + String.join(", ", SETTINGS.keySet())));
                return 0;
            }
        }
        return save(src, new VisionSensor(cols, rows, h, vv, range, entities, true, fovea, true));
    }

    private static int save(CommandSourceStack src, VisionSensor v) {
        VisionSensor old = VisionSensor.current;
        try {
            StringWriter s = new StringWriter();
            v.write(new JsonWriter(s));
            Files.createDirectories(file(src.getServer()).getParent());
            Files.writeString(file(src.getServer()), s.toString());
        } catch (IOException e) {
            src.sendFailure(Component.literal("Saving the vision setting failed: " + e.getMessage()));
            return 0;
        }
        VisionSensor.current = v;
        src.getServer().getPlayerList().getPlayers().forEach(VisionSetting::send);
        show(src);
        src.sendSuccess(() -> Component.literal("Rays: " + Math.round(100.0 * v.cols * v.rows / (old.cols * old.rows))
                + "% of before (recording and training cost grow with it). Next: /ainpc generateTrainingset, then a New run "
                + "in training. A model-driven NPC always sees like its own model was trained."), true);
        return 1;
    }
}
