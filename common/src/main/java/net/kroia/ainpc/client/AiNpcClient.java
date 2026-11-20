package net.kroia.ainpc.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientLifecycleEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import net.kroia.ainpc.replay.BlockChangeHook;
import net.kroia.ainpc.replay.RecordingUpload;
import net.kroia.ainpc.sensor.BodySensor;
import net.kroia.ainpc.sensor.EntityState;
import net.kroia.ainpc.sensor.HearingSensor;
import net.kroia.ainpc.sensor.LightSource;
import net.kroia.ainpc.sensor.PlayerState;
import net.kroia.ainpc.sensor.SensorGizmos;
import net.kroia.ainpc.sensor.VisionFrame;
import net.kroia.ainpc.sensor.VisionSensor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/** Senses of the local player, i.e. what an NPC sitting in the player would perceive, plus recording them. */
public final class AiNpcClient {
    static final KeyMapping TOGGLE_SENSOR_DEBUG = new KeyMapping("key.ainpc.toggle_sensor_debug",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, "key.categories.ainpc");
    static final KeyMapping TOGGLE_RECORDING = new KeyMapping("key.ainpc.toggle_recording",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, "key.categories.ainpc");
    static final KeyMapping NEXT_LABEL = new KeyMapping("key.ainpc.next_label",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.ainpc");
    static final Logger LOGGER = LoggerFactory.getLogger("ainpc");
    /** Server gizmos older than this are stale (playback stopped or paused). */
    private static final long REMOTE_TIMEOUT_MS = 1000;

    static final HearingSensor hearing = new HearingSensor();
    /** trees etc. recognised so far (ObjectSensor); cleared when a recording starts, as its replay starts empty */
    static final net.kroia.ainpc.sensor.ObjectSensor.Memory objects = new net.kroia.ainpc.sensor.ObjectSensor.Memory();
    static final BodySensor body = new BodySensor();
    static final Recorder recorder = new Recorder();
    static int labelIndex;
    private static ClientLevel recordingLevel;

    static boolean sensorDebug;
    /** Gizmos of the local player's senses (F6); null while off. */
    static SensorGizmos liveGizmos;
    /** The server's vision setting (/ainpc vision); the default on servers without the mod. */
    static VisionSensor vision = VisionSensor.DEFAULT;
    /** Gizmos of a replayed NPC, sent by the server to spectators. */
    private static volatile SensorGizmos remote;
    private static volatile long remoteAt;

    public static void init() {
        KeyMappingRegistry.register(TOGGLE_SENSOR_DEBUG);
        KeyMappingRegistry.register(TOGGLE_RECORDING);
        KeyMappingRegistry.register(NEXT_LABEL);
        BlockChangeHook.listener = recorder;
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SensorGizmos.TYPE, SensorGizmos.CODEC, (g, ctx) -> {
            remote = g;
            remoteAt = System.currentTimeMillis();
        });
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, net.kroia.ainpc.replay.VisionSetting.Sync.TYPE,
                net.kroia.ainpc.replay.VisionSetting.Sync.CODEC,
                (s, ctx) -> vision = VisionSensor.of(com.google.gson.JsonParser.parseString(s.json()).getAsJsonObject()));
        dev.architectury.event.events.client.ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(p -> vision = VisionSensor.DEFAULT);

        ClientLifecycleEvent.CLIENT_STARTED.register(mc -> mc.getSoundManager().addListener((sound, events, range) -> {
            // Relative sounds (music, UI) and unattenuated ones carry no direction.
            if (sound.isRelative() || sound.getAttenuation() == SoundInstance.Attenuation.NONE || mc.level == null) return;
            Vec3 pos = new Vec3(sound.getX(), sound.getY(), sound.getZ());
            long gameTime = mc.level.getGameTime();
            hearing.onSound(sound.getLocation(), sound.getSource(), pos, sound.getVolume(), range, gameTime);
            recorder.onSound(sound.getLocation(), sound.getSource(), pos, sound.getVolume(), range, gameTime);
        }));

        ClientTickEvent.CLIENT_POST.register(AiNpcClient::tick);
        dev.architectury.event.events.client.ClientCommandRegistrationEvent.EVENT.register((dispatcher, ctx) -> dispatcher.register(
                dev.architectury.event.events.client.ClientCommandRegistrationEvent.literal("ainpcrec")
                        .then(dev.architectury.event.events.client.ClientCommandRegistrationEvent.literal("deletelast")
                                .executes(c -> deleteLastRecording(Minecraft.getInstance())))));

        ClientGuiEvent.RENDER_HUD.register((g, delta) -> {
            Font font = Minecraft.getInstance().font;
            int y = 4;
            SensorGizmos r = remoteGizmos();
            if (r == null && sensorDebug && liveGizmos != null) y = renderText(g, font, y, liveGizmos);
            if (r != null) {
                g.drawString(font, "NPC:", 4, y, 0xFFC040);
                renderText(g, font, y + 10, r);
            }
            renderRecordingBadge(g, font);
        });
    }

    /** An NPC's senses: shown to spectators (replay playback) and with F6 on (model-driven NPC), instead of the player's own. */
    static SensorGizmos remoteGizmos() {
        var p = Minecraft.getInstance().player;
        if (!sensorDebug && (p == null || !p.isSpectator())) return null;
        return System.currentTimeMillis() - remoteAt < REMOTE_TIMEOUT_MS ? remote : null;
    }

    private static void tick(Minecraft mc) {
        uploadTick(mc);
        while (TOGGLE_SENSOR_DEBUG.consumeClick()) sensorDebug = !sensorDebug;
        while (NEXT_LABEL.consumeClick())
            if (!recorder.active()) labelIndex = (labelIndex + 1) % Recorder.LABELS.size();
        while (TOGGLE_RECORDING.consumeClick()) {
            if (recorder.active()) stopRecording(mc);
            else if (mc.player != null) startRecording(mc);
        }
        // Leaving the world or changing dimension ends the recording: one file = one continuous world.
        if (recorder.active() && (mc.player == null || mc.level != recordingLevel)) stopRecording(mc);

        if (mc.player == null || mc.isPaused()) return; // paused: keep the last frames, record nothing
        if (!sensorDebug && !recorder.active()) {
            liveGizmos = null;
            return;
        }
        // Exactly the inputs a replay of this tick will get, so live and replayed senses match.
        PlayerState player = PlayerState.of(mc.player);
        List<EntityState> entities = EntityState.collect(mc.level, mc.player, Recorder.ENTITY_RADIUS);
        BlockGetter world = mc.level;
        if (recorder.active()) { // only what is recorded: the influence area around the player
            recorder.collectChunks(mc.player);
            var center = mc.player.chunkPosition();
            entities = entities.stream().filter(e -> Recorder.inArea(center, e.pos())).toList();
            world = recorder.view;
        }
        int darken = LightSource.skyDarken(mc.level);
        LightSource light = recorder.active() ? recorder.light(darken) : p -> mc.level.getRawBrightness(p, darken);
        // a recording keeps the vision it started with: its header names the one its checks were made with
        VisionSensor sensor = recorder.active() ? recorder.vision : vision;
        VisionFrame vision = sensor.sense(world, light, player, entities, objects);
        var heard = hearing.sense(player, mc.level.getGameTime());
        var felt = body.sense(player);
        liveGizmos = sensorDebug ? SensorGizmos.of(world, player, vision, heard, felt) : null;

        if (recorder.active()) {
            try {
                recorder.tick(mc, player, entities, vision.checksum());
            } catch (IOException e) {
                LOGGER.error("Recording failed", e);
                stopRecording(mc);
            }
        }
    }

    /** From MinecraftMixin: 0 = attack, 1 = use, 2 = pick block. */
    public static void onClick(int kind) {
        recorder.onClick(kind);
    }

    /** From MultiPlayerGameModeMixin. */
    public static void onContainerClick(int slot, int button, ClickType type) {
        recorder.onContainerClick(slot, button, type);
    }

    private static void startRecording(Minecraft mc) {
        try {
            recorder.start(mc, Recorder.LABELS.get(labelIndex));
            objects.clear();
            recordingLevel = mc.level;
            mc.player.displayClientMessage(Component.literal("Recording " + recorder.label()), true);
        } catch (IOException e) {
            LOGGER.error("Could not start recording", e);
            mc.player.displayClientMessage(Component.literal("Recording failed: " + e.getMessage()), true);
        }
    }

    private static void stopRecording(Minecraft mc) {
        recorder.stop();
        recordingLevel = null;
        LOGGER.info("Recording saved to {}", recorder.file());
        boolean up = uploadsToServer(mc);
        if (up) uploads.add(recorder.file());
        if (mc.player != null)
            mc.player.displayClientMessage(Component.literal("Saved " + mc.gameDirectory.toPath().relativize(recorder.file())
                    + (up ? ", uploading to the server" : "")), true);
    }

    // --- upload to a dedicated/remote server ------------------------------------------------------

    private static final java.util.ArrayDeque<java.nio.file.Path> uploads = new java.util.ArrayDeque<>();
    private static java.io.InputStream uploading;
    private static String uploadingName;
    private static final int CHUNKS_PER_TICK = 8; // 240 KB/tick = ~4.8 MB/s

    /** Singleplayer/LAN host: the recording already is in the server's folder. Server without this mod: stays local. */
    private static boolean uploadsToServer(Minecraft mc) {
        return mc.getConnection() != null && !mc.hasSingleplayerServer() && NetworkManager.canServerReceive(RecordingUpload.TYPE);
    }

    /** "gather_wood/20261117-130500" for .../gather_wood/20261117-130500.jsonl.gz */
    private static String uploadName(java.nio.file.Path file) {
        String f = file.getFileName().toString();
        return file.getParent().getFileName() + "/" + f.substring(0, f.length() - ".jsonl.gz".length());
    }

    private static void uploadTick(Minecraft mc) {
        if (mc.getConnection() == null) { // disconnected: unfinished uploads stay local only
            if (uploading != null || !uploads.isEmpty())
                LOGGER.warn("Disconnected, {} recording(s) not uploaded", uploads.size() + (uploading != null ? 1 : 0));
            closeUpload();
            uploads.clear();
            return;
        }
        try {
            for (int i = 0; i < CHUNKS_PER_TICK; i++) {
                if (uploading == null) {
                    java.nio.file.Path next = uploads.poll();
                    if (next == null) return;
                    uploading = java.nio.file.Files.newInputStream(next);
                    uploadingName = uploadName(next);
                    NetworkManager.sendToServer(new RecordingUpload(uploadingName, RecordingUpload.START, new byte[0]));
                }
                byte[] data = uploading.readNBytes(RecordingUpload.CHUNK);
                if (data.length > 0) NetworkManager.sendToServer(new RecordingUpload(uploadingName, RecordingUpload.DATA, data));
                if (data.length < RecordingUpload.CHUNK) {
                    NetworkManager.sendToServer(new RecordingUpload(uploadingName, RecordingUpload.END, new byte[0]));
                    closeUpload();
                }
            }
        } catch (IOException e) {
            LOGGER.error("Uploading {} failed", uploadingName, e);
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("Upload failed: " + e.getMessage()), false);
            closeUpload();
        }
    }

    private static void closeUpload() {
        if (uploading != null) try {
            uploading.close();
        } catch (IOException ignored) {
        }
        uploading = null;
        uploadingName = null;
    }

    /**
     * /ainpcrec deletelast: the running recording, otherwise the newest one, goes to ainpc_recordings_deleted/
     * (not gone for good), together with its dataset if /ainpc generateTrainingset already made one. Again: the one before.
     */
    private static int deleteLastRecording(Minecraft mc) {
        if (recorder.active()) stopRecording(mc);
        java.nio.file.Path game = mc.gameDirectory.toPath(), dir = game.resolve("ainpc_recordings");
        try (var files = java.nio.file.Files.isDirectory(dir) ? java.nio.file.Files.walk(dir) : java.util.stream.Stream.<java.nio.file.Path>empty()) {
            var newest = files.filter(f -> f.toString().endsWith(".jsonl.gz"))
                    .max(java.util.Comparator.comparingLong(f -> f.toFile().lastModified()));
            if (newest.isEmpty()) {
                mc.player.displayClientMessage(Component.literal("No recordings to delete"), false);
                return 0;
            }
            var rel = dir.relativize(newest.get());
            // Also on the server. First, so a running upload closes the file before it is moved (Windows locks it).
            if (uploadsToServer(mc)) {
                String up = uploadName(newest.get());
                uploads.remove(newest.get());
                if (up.equals(uploadingName)) closeUpload();
                NetworkManager.sendToServer(new RecordingUpload(up, RecordingUpload.DELETE, new byte[0]));
            }
            for (String from : new String[]{"ainpc_recordings", "ainpc_datasets"}) {
                var f = game.resolve(from).resolve(rel);
                if (!java.nio.file.Files.exists(f)) continue;
                var to = game.resolve("ainpc_recordings_deleted").resolve(from).resolve(rel);
                java.nio.file.Files.createDirectories(to.getParent());
                java.nio.file.Files.move(f, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            String name = rel.toString().replace('\\', '/');
            LOGGER.info("Deleted recording {} (moved to ainpc_recordings_deleted)", name);
            mc.player.displayClientMessage(Component.literal("Deleted recording " + name + " (moved to ainpc_recordings_deleted)"), false);
            return 1;
        } catch (IOException e) {
            LOGGER.error("Deleting the last recording failed", e);
            mc.player.displayClientMessage(Component.literal("Deleting failed: " + e.getMessage()), false);
            return 0;
        }
    }

    /** Top right: red REC with label and time while recording, otherwise the label the next recording gets. */
    private static void renderRecordingBadge(GuiGraphics g, Font font) {
        String text;
        int color;
        if (recorder.active()) {
            long s = recorder.elapsedMillis() / 1000;
            String dot = (s & 1) == 0 ? "●" : "○"; // blinking filled / hollow circle
            text = dot + " REC " + recorder.label() + String.format(" %d:%02d", s / 60, s % 60);
            color = 0xFF3030;
        } else {
            text = "○ " + Recorder.LABELS.get(labelIndex) + "  (F7 rec, F8 label)";
            color = 0xA0A0A0;
        }
        g.drawString(font, text, g.guiWidth() - font.width(text) - 4, 4, color);
    }

    private static int renderText(GuiGraphics g, Font font, int y, SensorGizmos gizmos) {
        for (SensorGizmos.Text t : gizmos.text()) {
            g.drawString(font, t.text(), 4, y, t.rgb());
            y += 10;
        }
        return y;
    }
}
