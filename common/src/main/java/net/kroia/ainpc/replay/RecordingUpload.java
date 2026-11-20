package net.kroia.ainpc.replay;

import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.networking.NetworkManager;
import net.kroia.ainpc.AiNpc;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Client -> server: a finished recording, sent in pieces (a serverbound custom payload holds at most 32 KiB).
 * The server stores it as &lt;server dir&gt;/ainpc_recordings/&lt;label&gt;/&lt;player&gt;-&lt;timestamp&gt;.jsonl.gz, so
 * /ainpc generateTrainingset and training see every player's recordings. name = "&lt;label&gt;/&lt;timestamp&gt;" as on the client.
 */
public record RecordingUpload(String name, byte op, byte[] data) implements CustomPacketPayload {
    public static final byte START = 0, DATA = 1, END = 2, DELETE = 3;
    public static final int CHUNK = 30000;
    public static final Type<RecordingUpload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("ainpc", "recording_upload"));
    public static final StreamCodec<FriendlyByteBuf, RecordingUpload> CODEC = StreamCodec.of(
            (buf, u) -> buf.writeUtf(u.name, 64).writeByte(u.op).writeByteArray(u.data),
            buf -> new RecordingUpload(buf.readUtf(64), buf.readByte(), buf.readByteArray(CHUNK)));
    private static final long MAX_BYTES = 1L << 30; // ponytail: per-file cap only, no per-player quota
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{8}-\\d{6}");

    private record Open(String name, Path part, OutputStream out, long[] size) {
    }

    private static final Map<UUID, Open> open = new HashMap<>();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        // Registers the payload type on both sides; the handler only ever runs on the server.
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, TYPE, CODEC, (u, ctx) ->
                ctx.queue(() -> handle((ServerPlayer) ctx.getPlayer(), u)));
        PlayerEvent.PLAYER_QUIT.register(p -> abort(p.getUUID())); // a half upload is useless
    }

    private static void handle(ServerPlayer player, RecordingUpload u) {
        Path recordings = player.server.getServerDirectory().resolve("ainpc_recordings");
        String[] parts = u.name.split("/");
        // Validated, so a client cannot write outside ainpc_recordings.
        if (parts.length != 2 || !RecordingFormat.LABELS.contains(parts[0]) || !TIMESTAMP.matcher(parts[1]).matches()) {
            player.sendSystemMessage(Component.literal("Upload rejected: bad name " + u.name));
            return;
        }
        String rel = parts[0] + "/" + player.getGameProfile().getName() + "-" + parts[1] + ".jsonl.gz";
        Path file = recordings.resolve(rel);
        UUID id = player.getUUID();
        try {
            switch (u.op) {
                case START -> {
                    abort(id);
                    Files.createDirectories(file.getParent());
                    Path part = file.resolveSibling(file.getFileName() + ".part");
                    open.put(id, new Open(u.name, part, Files.newOutputStream(part), new long[1]));
                }
                case DATA -> {
                    Open o = open.get(id);
                    if (o == null || !o.name.equals(u.name)) return; // aborted earlier
                    if ((o.size[0] += u.data.length) > MAX_BYTES) {
                        abort(id);
                        player.sendSystemMessage(Component.literal("Upload rejected: " + rel + " is larger than 1 GiB"));
                        return;
                    }
                    o.out.write(u.data);
                }
                case END -> {
                    Open o = open.remove(id);
                    if (o == null || !o.name.equals(u.name)) return;
                    o.out.close();
                    Files.move(o.part, file, StandardCopyOption.REPLACE_EXISTING);
                    AiNpc.LOGGER.info("Received recording {} ({} KiB)", rel, o.size[0] / 1024);
                    player.sendSystemMessage(Component.literal("Server saved " + rel + String.format(" (%.1f MB)", o.size[0] / 1e6)));
                }
                case DELETE -> { // /ainpcrec deletelast: same as there, moved to ainpc_recordings_deleted
                    Open o = open.get(id);
                    if (o != null && o.name.equals(u.name)) abort(id);
                    Path game = player.server.getServerDirectory();
                    boolean any = false;
                    for (String from : new String[]{"ainpc_recordings", "ainpc_datasets"}) {
                        Path f = game.resolve(from).resolve(rel);
                        if (!Files.exists(f)) continue;
                        Path to = game.resolve("ainpc_recordings_deleted").resolve(from).resolve(rel);
                        Files.createDirectories(to.getParent());
                        Files.move(f, to, StandardCopyOption.REPLACE_EXISTING);
                        any = true;
                    }
                    if (any) player.sendSystemMessage(Component.literal("Server deleted " + rel));
                }
                default -> {
                }
            }
        } catch (IOException e) {
            AiNpc.LOGGER.error("Recording upload {} from {} failed", rel, player.getGameProfile().getName(), e);
            abort(id);
            player.sendSystemMessage(Component.literal("Upload of " + rel + " failed on the server: " + e.getMessage()));
        }
    }

    private static void abort(UUID id) {
        Open o = open.remove(id);
        if (o == null) return;
        try {
            o.out.close();
            Files.deleteIfExists(o.part);
        } catch (IOException e) {
            AiNpc.LOGGER.warn("Cleaning up {} failed", o.part, e);
        }
    }
}
