package net.kroia.ainpc.npc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import dev.architectury.event.events.common.TickEvent;
import net.kroia.ainpc.AiNpc;
import net.kroia.ainpc.replay.RecordingFormat;
import net.kroia.ainpc.replay.Replayer;
import net.kroia.ainpc.sensor.BodySensor;
import net.kroia.ainpc.sensor.EntityState;
import net.kroia.ainpc.sensor.HearingSensor;
import net.kroia.ainpc.sensor.LightSource;
import net.kroia.ainpc.sensor.PlayerState;
import net.kroia.ainpc.sensor.VisionSensor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * An NPC driven by a trained model (training/serve.py) over a local socket. Each server tick: observe with the
 * same sensors and in the same format as a dataset tick, send it, get the actions back, act them out. So the
 * model sees the state after its previous actions and decides the next ones, as in training (obs t-1 -> act t).
 * Hearing: the sounds the server sends to the NPC as it would to a player standing there.
 * ponytail: the NPC's own sounds (steps, its hits, its block breaks) are missing; a real client plays those
 * itself, so the server never sends them. Synthesize them here if the model turns out to rely on them.
 * From the console it spawns at the world spawn with an empty inventory (for automated tests).
 * ponytail: one NPC at a time, sharing NpcBody's profile with the playback NPC (don't run both at once).
 */
public final class Brain {
    public static final int DEFAULT_PORT = 5571;
    private static final int TIMEOUT_MS = 3000;
    /** Loading a model on reload may take longer than a step. */
    private static final int RELOAD_TIMEOUT_MS = 30000;
    /**
     * Time budget of the NPC per second (20 ticks). Vanilla only drops its lag at the "Can't keep up" warning,
     * which comes rarely when every tick is slow, so steps of 1-2 s add up until the watchdog kills the server
     * although no single wait hits TIMEOUT_MS. Above this budget the NPC stops instead.
     */
    private static final long BUDGET_NANOS_PER_SECOND = 500_000_000L;
    private static Brain current;
    /** What the NPC should do, sent with every observation; kept across NPCs. ponytail: set by hand, a manager picking it comes later */
    private static String goal = RecordingFormat.LABELS.get(0);

    private final CommandSourceStack src;
    private final NpcBody body;
    private ServerLevel level; // follows the NPC, e.g. after /tp into another dimension
    private final Socket socket;
    private final BufferedReader in;
    private final Writer out;
    private final HearingSensor hearing = new HearingSensor();
    private final net.kroia.ainpc.sensor.ObjectSensor.Memory objects = new net.kroia.ainpc.sensor.ObjectSensor.Memory();
    private final BodySensor bodySensor = new BodySensor();
    private int t;
    private long budgetUsed;
    private int budgetTicks;
    /** Set by /ainpc npc reload [latest|best]: the next tick asks the model server to load that model of the newest run. */
    private String reload;
    private final Vec3 start;
    /** The vision the model was trained with (asked from the model server), not the server's /ainpc vision setting. */
    private VisionSensor vision;

    private Brain(CommandSourceStack src, NpcBody body, Socket socket) throws IOException {
        this.src = src;
        this.body = body;
        this.level = body.player.serverLevel();
        this.socket = socket;
        this.start = body.player.position();
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        body.onPacket(this::hear);
        try {
            out.write("{\"type\":\"hello\"}\n");
            out.flush();
            vision = modelVision(in.readLine());
        } catch (IOException | RuntimeException e) { // no NPC body left standing without a brain
            body.remove();
            throw new IOException("model server handshake failed: " + e.getMessage(), e);
        }
    }

    /** "vision" of a hello/reload reply; a model server without it (older serve.py) gets the server's setting. */
    private static VisionSensor modelVision(String reply) throws IOException {
        if (reply == null) throw new IOException("model server closed the connection");
        JsonObject o = JsonParser.parseString(reply).getAsJsonObject();
        return o.has("vision") ? VisionSensor.of(o.getAsJsonObject("vision")) : VisionSensor.current;
    }

    public static void register() {
        TickEvent.SERVER_POST.register(server -> {
            if (current != null) current.timedTick();
        });
    }

    /** Spawns the NPC where the command's player stands, with a copy of their inventory, and connects. */
    public static int start(CommandSourceStack src, int port) {
        ServerPlayer p = src.getPlayer();
        if (p == null) { // from the console: at the world spawn, empty-handed
            ServerLevel level = src.getLevel();
            p = new ServerPlayer(src.getServer(), level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "spawn"),
                    net.minecraft.server.level.ClientInformation.createDefault());
            var at = level.getSharedSpawnPos();
            p.moveTo(at.getX() + 0.5, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()),
                    at.getZ() + 0.5, 0, 0);
        }
        if (current != null) current.stop("replaced");
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), TIMEOUT_MS);
            s.setSoTimeout(TIMEOUT_MS);
            s.setTcpNoDelay(true);
            current = new Brain(src, NpcBody.spawn(p.serverLevel(), PlayerState.of(p), RecordingFormat.slots(p.getInventory())), s);
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            src.sendFailure(Component.literal("No model server on port " + port + " (python training/serve.py): " + e.getMessage()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("NPC spawned, driven by the model on port " + port + ", goal " + goal
                + ", vision " + current.vision), true);
        return 1;
    }

    public static int stopCommand(CommandSourceStack src) {
        if (current == null) {
            src.sendFailure(Component.literal("No model-driven NPC running"));
            return 0;
        }
        current.stop("stopped");
        return 1;
    }

    /** /ainpc npc goal [goal]: what the NPC should do (a recording label); without one, says the current goal. */
    public static int goalCommand(CommandSourceStack src, String g) {
        if (g == null) {
            src.sendSuccess(() -> Component.literal("NPC goal: " + goal + " (one of " + String.join(", ", RecordingFormat.LABELS) + ")"), false);
            return 1;
        }
        if (!RecordingFormat.LABELS.contains(g)) {
            src.sendFailure(Component.literal("Unknown goal " + g + ", one of " + String.join(", ", RecordingFormat.LABELS)));
            return 0;
        }
        goal = g;
        src.sendSuccess(() -> Component.literal("NPC goal: " + g), true);
        return 1;
    }

    public static int reloadCommand(CommandSourceStack src, String which) {
        if (current == null) {
            src.sendFailure(Component.literal("No model-driven NPC running"));
            return 0;
        }
        current.reload = which;
        return 1;
    }

    private void timedTick() {
        long start = System.nanoTime();
        tick();
        budgetUsed += System.nanoTime() - start;
        if (++budgetTicks < 20) return;
        long used = budgetUsed;
        budgetUsed = 0;
        budgetTicks = 0;
        if (used > BUDGET_NANOS_PER_SECOND && current == this && t > 40) // first second: model warm-up
            stop(String.format(java.util.Locale.ROOT, "too slow: %.0f ms per tick (model server busy? GPU shared with "
                    + "training, or too many rays), stopped before the server falls behind", used / 20e6));
    }

    private void tick() {
        if (body.player.isRemoved() || body.player.isDeadOrDying()) {
            stop("the NPC died");
            return;
        }
        level = body.player.serverLevel();
        JsonObject r;
        try {
            if (reload != null) { // between two ticks, so the model never sees half a step
                out.write("{\"type\":\"reload\",\"which\":\"" + reload + "\"}\n");
                reload = null;
                out.flush();
                socket.setSoTimeout(RELOAD_TIMEOUT_MS);
                String reply = in.readLine();
                socket.setSoTimeout(TIMEOUT_MS);
                if (reply == null) throw new IOException("model server closed the connection");
                JsonObject o = JsonParser.parseString(reply).getAsJsonObject();
                vision = modelVision(reply);
                String m = "Model NPC now driven by " + o.get("model").getAsString()
                        + (o.get("epoch").isJsonNull() ? "" : ", epoch " + o.get("epoch").getAsInt())
                        + (o.get("val_loss").isJsonNull() ? "" : String.format(java.util.Locale.ROOT, ", val loss %.3f", o.get("val_loss").getAsDouble()))
                        + ", vision " + vision;
                AiNpc.LOGGER.info(m);
                src.sendSuccess(() -> Component.literal(m), true);
            }
            out.write(observation());
            out.write('\n');
            out.flush();
            String reply = in.readLine();
            if (reply == null) throw new IOException("model server closed the connection");
            r = JsonParser.parseString(reply).getAsJsonObject();
        } catch (Exception e) {
            AiNpc.LOGGER.error("Model NPC failed at tick {}", t, e);
            stop("model server error: " + e.getMessage());
            return;
        }
        body.act(actions(r));
        t++;
    }

    private static Actions actions(JsonObject r) {
        JsonObject k = r.getAsJsonObject("keys");
        return new Actions(key(k, "forward"), key(k, "back"), key(k, "left"), key(k, "right"), key(k, "jump"),
                key(k, "sneak"), key(k, "sprint"), key(k, "attack"), key(k, "use"), key(k, "drop"), key(k, "swapHands"),
                r.get("attacks").getAsInt(), r.get("uses").getAsInt(), r.get("yaw").getAsFloat(), r.get("pitch").getAsFloat(),
                r.get("hold").isJsonNull() ? null : r.get("hold").getAsString(), null, List.of());
    }

    private static boolean key(JsonObject keys, String name) {
        return keys.has(name) && keys.get(name).getAsBoolean();
    }

    /** A dataset tick line without "actions" (see Replayer). */
    private String observation() throws IOException {
        ServerPlayer p = body.player;
        PlayerState ps = PlayerState.of(p);
        List<EntityState> entities = EntityState.collect(level, p, vision.range + 16);
        int darken = LightSource.skyDarken(level);
        var vf = vision.sense(level, pos -> level.getRawBrightness(pos, darken), ps, entities, objects);
        var hf = hearing.sense(ps, level.getGameTime());
        var bf = bodySensor.sense(ps);
        // the F6 overlay shows what the NPC senses (the client draws it instead of the player's own)
        var viewers = level.getServer().getPlayerList().getPlayers().stream().filter(v -> v != p)
                .filter(v -> dev.architectury.networking.NetworkManager.canPlayerReceive(v, net.kroia.ainpc.sensor.SensorGizmos.TYPE)).toList();
        if (!viewers.isEmpty())
            dev.architectury.networking.NetworkManager.sendToPlayers(viewers, net.kroia.ainpc.sensor.SensorGizmos.of(level, ps, vf, hf, bf));
        StringWriter s = new StringWriter();
        JsonWriter w = new JsonWriter(s);
        w.beginObject().name("type").value("tick").name("goal").value(goal).name("t").value(t).name("gameTime").value(level.getGameTime());
        w.name("world").beginObject().name("dayTime").value(level.getDayTime()).name("skyDarken").value(darken)
                .name("rain").value(level.getRainLevel(1f)).name("thunder").value(level.getThunderLevel(1f)).endObject();
        w.name("rot").beginArray().value(ps.yRot()).value(ps.xRot()).endArray();
        w.name("hand").value(ps.mainHand());
        w.name("breaking").value(body.breaking()); // a dataset has it in actions.did.breaking
        w.name("inventory").jsonValue(RecordingFormat.inventorySummary(RecordingFormat.slots(p.getInventory())).toString());
        Replayer.writeVision(w.name("vision"), vf);
        Replayer.writeHearing(w.name("sounds"), hf);
        Replayer.writeBody(w.name("body"), bf);
        w.endObject();
        w.close();
        return s.toString();
    }

    private void hear(Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket b) {
            for (var p : b.subPackets()) hear(p);
        } else if (packet instanceof ClientboundSoundPacket s) {
            heard(s.getSound().value(), s.getSource(), new Vec3(s.getX(), s.getY(), s.getZ()), s.getVolume());
        } else if (packet instanceof ClientboundSoundEntityPacket s) {
            Entity e = level.getEntity(s.getId());
            if (e != null) heard(s.getSound().value(), s.getSource(), e.position(), s.getVolume());
        } else if (packet instanceof ClientboundLevelEventPacket e && e.getType() == LevelEvent.PARTICLES_DESTROY_BLOCK) {
            var sound = Block.stateById(e.getData()).getSoundType(); // the client plays the break sound for this event
            heard(sound.getBreakSound(), SoundSource.BLOCKS, Vec3.atCenterOf(e.getPos()), (sound.getVolume() + 1) / 2);
        }
    }

    private void heard(SoundEvent sound, SoundSource source, Vec3 pos, float volume) {
        hearing.onSound(sound.getLocation(), source, pos, volume, sound.getRange(volume), level.getGameTime());
    }

    private void stop(String why) {
        current = null;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        String msg = String.format(java.util.Locale.ROOT, "Model NPC ended after %d ticks at %.1f %.1f %.1f (%.1f m from its start): %s",
                t, body.player.getX(), body.player.getY(), body.player.getZ(), body.player.position().distanceTo(start), why);
        body.remove();
        AiNpc.LOGGER.info(msg);
        src.sendSuccess(() -> Component.literal(msg), true);
    }
}
