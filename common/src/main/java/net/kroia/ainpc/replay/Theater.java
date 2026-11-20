package net.kroia.ainpc.replay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.networking.NetworkManager;
import net.kroia.ainpc.AiNpc;
import net.kroia.ainpc.npc.Actions;
import net.kroia.ainpc.npc.NpcBody;
import net.kroia.ainpc.sensor.BodySensor;
import net.kroia.ainpc.sensor.EntityState;
import net.kroia.ainpc.sensor.HearingSensor;
import net.kroia.ainpc.sensor.PlayerState;
import net.kroia.ainpc.sensor.SensorGizmos;
import net.kroia.ainpc.sensor.VisionFrame;
import net.kroia.ainpc.sensor.VisionSensor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Rotations;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Plays a recording back in a real server world at game speed, to watch it as a spectator:
 * the recorded chunks and block changes are written into the world (it OVERWRITES those chunks, use a
 * throwaway world), mobs and items become frozen puppets moved every tick, the recording player is an armor
 * stand, recorded sounds are played. The replayed NPC's sensors run like in {@link Replayer} and their
 * gizmos are sent to every spectator.
 *
 * NPC mode (setting npc on): additionally an NPC body ({@link NpcBody}) starts where the recording starts and only
 * gets the recorded actions (keys, clicks, look). The world then reacts to the NPC instead of replaying the recorded
 * block changes and dropped items, and the drift between NPC and recording shows whether the actions are complete.
 */
public final class Theater {
    private static final String PUPPET_TAG = "ainpc_puppet";
    /** No neighbour updates, so recorded blocks stay as recorded (no sand falling, no shape fixing). */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static Theater current;
    // Settings outlive a single loaded recording.
    private static boolean loop;
    private static double speed = 1;
    private static boolean npcMode;
    /** Show the recorded player (armor stand) next to the NPC. */
    private static boolean reference = true;

    private final CommandSourceStack src;
    private final ServerLevel level;
    private final Path file;
    private final String name;
    private BufferedReader in;
    private ReplayWorld world;
    private HearingSensor hearing;
    private net.kroia.ainpc.sensor.ObjectSensor.Memory objects;
    private BodySensor body;
    private final Map<Integer, Entity> puppets = new HashMap<>();
    private ArmorStand self;
    private boolean paused = true, finished, viewerPlaced;
    // NPC mode
    private NpcBody npc;
    private Boolean mobSpawningBefore;
    private Integer randomTickBefore;
    /** Recorded item entity id -> the real item standing for it in the NPC's world. */
    private final Map<Integer, Entity> realItems = new HashMap<>();
    /** Real items without a recorded counterpart, and the tick they were first seen. */
    private final Map<Entity, Integer> unmatchedSince = new HashMap<>();
    private static final int UNMATCHED_GRACE = 5;
    private JsonArray recordedInventory;
    private final Set<BlockPos> filledContainers = new HashSet<>();
    private double driftSum, driftMax;
    private int driftTicks, driftMaxAt, driftFirstAt;
    /** Per tick NPC vs recording, written to ainpc_npc_trace.csv in the game folder when the run ends. */
    private final StringBuilder trace = new StringBuilder();
    private double budget;
    private int t;

    private Theater(CommandSourceStack src, Path file, String name) throws IOException {
        this.src = src;
        this.level = src.getLevel();
        this.file = file;
        this.name = name;
        open();
    }

    /** (Re)starts reading at the beginning with fresh sensors; the next tick rebuilds the recorded chunks. */
    private void open() throws IOException {
        if (in != null) in.close();
        in = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8));
        String first = in.readLine();
        JsonObject header = first == null ? null : JsonParser.parseString(first).getAsJsonObject();
        if (header == null || !"recording".equals(RecordingFormat.str(header, "kind"))
                || header.get("format").getAsInt() != RecordingFormat.FORMAT) {
            in.close();
            throw new IOException(name + " is not a format " + RecordingFormat.FORMAT + " recording");
        }
        world = new ReplayWorld(header.get("minY").getAsInt(), header.get("height").getAsInt());
        hearing = new HearingSensor();
        objects = new net.kroia.ainpc.sensor.ObjectSensor.Memory();
        body = new BodySensor();
        budget = 0;
        finished = false;
        removeNpc();
    }

    private void removeNpc() {
        if (npc == null) return;
        if (mobSpawningBefore != null && !loop)
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(mobSpawningBefore, level.getServer());
        if (randomTickBefore != null && !loop)
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING).set(randomTickBefore, level.getServer());
        realItems.clear();
        unmatchedSince.clear();
        if (driftTicks > 0) report();
        npc.remove();
        npc = null;
        filledContainers.clear();
        driftSum = driftMax = 0;
        driftTicks = driftMaxAt = 0;
        driftFirstAt = -1;
    }

    /** How far the NPC drifted from the recorded player, and whether it ended with the same items. */
    private void report() {
        Map<String, Integer> diff = new java.util.TreeMap<>();
        if (recordedInventory != null)
            for (JsonElement e : recordedInventory)
                if (e.isJsonArray()) diff.merge(e.getAsJsonArray().get(0).getAsString(), e.getAsJsonArray().get(1).getAsInt(), Integer::sum);
        var inv = npc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++)
            if (!inv.getItem(i).isEmpty()) diff.merge(EntityState.itemId(inv.getItem(i)), -inv.getItem(i).getCount(), Integer::sum);
        diff.values().removeIf(v -> v == 0);
        String msg = String.format(java.util.Locale.ROOT,
                "NPC vs recording over %d ticks: drift mean %.2f m, max %.2f m at tick %d, first over 0.5 m at %s%s. Inventory %s",
                driftTicks, driftSum / driftTicks, driftMax, driftMaxAt, driftFirstAt < 0 ? "never" : "tick " + driftFirstAt,
                npc.player.isDeadOrDying() ? ", NPC DIED" : "",
                diff.isEmpty() ? "matches" : "differs (recorded minus NPC): " + diff);
        AiNpc.LOGGER.info(msg);
        src.sendSuccess(() -> Component.literal(msg), true);
        try {
            Files.writeString(level.getServer().getServerDirectory().resolve("ainpc_npc_trace.csv"),
                    "t,d,rx,ry,rz,nx,ny,nz,rvx,rvy,rvz,nvx,nvy,nvz,rGround,nGround,rSprint,nSprint,rCrouch,nCrouch,rMining,nMining,keys,attacks,uses,blocks,rHand,nHand,rArmor,nArmor,rOffhand,nOffhand,rItems,nItems\n" + trace);
        } catch (IOException e) {
            AiNpc.LOGGER.warn("Could not write the NPC trace", e);
        }
        trace.setLength(0);
    }

    public static void register() {
        TickEvent.SERVER_POST.register(server -> {
            if (current != null) current.tick();
        });
    }

    /** Loads a recording paused at its first tick: chunks built, puppets placed, watcher(s) teleported there. */
    static int load(CommandSourceStack src, Path file, String name) {
        if (current != null) current.stop("unloaded");
        try {
            current = new Theater(src, file, name);
            removePuppets(src.getLevel());
            if (!current.step(true)) throw new IOException(name + " has no ticks");
        } catch (Exception e) {
            AiNpc.LOGGER.error("Loading {} failed", name, e);
            src.sendFailure(Component.literal("Loading " + name + " failed: " + e.getMessage()));
            if (current != null) current.stop("failed");
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Loaded " + name + " (overwrites the recorded chunks in this world). "
                + "/ainpc playback start [speed]"), true);
        return 1;
    }

    static int startCommand(CommandSourceStack src, Double newSpeed) {
        if (current == null) return nothingLoaded(src);
        if (newSpeed != null) speed = newSpeed;
        if (current.finished) {
            try {
                current.open();
            } catch (IOException e) {
                current.stop("failed: " + e.getMessage());
                return 0;
            }
        }
        current.paused = false;
        src.sendSuccess(() -> Component.literal("Playing " + current.name + " at " + speed + "x" + (loop ? ", looping" : "")), true);
        return 1;
    }

    static int stopCommand(CommandSourceStack src) {
        if (current == null) return nothingLoaded(src);
        current.stop("unloaded");
        return 1;
    }

    static int pauseCommand(CommandSourceStack src) {
        if (current == null) return nothingLoaded(src);
        current.paused = true;
        src.sendSuccess(() -> Component.literal("Paused at tick " + current.t + ", /ainpc playback start to continue"), false);
        return 1;
    }

    static int speedCommand(CommandSourceStack src, double newSpeed) {
        speed = newSpeed;
        src.sendSuccess(() -> Component.literal("Playback speed " + newSpeed + "x"), false);
        return 1;
    }

    static int loopCommand(CommandSourceStack src, boolean on) {
        loop = on;
        src.sendSuccess(() -> Component.literal("Loop " + (on ? "ON" : "OFF")), false);
        return 1;
    }

    static int npcCommand(CommandSourceStack src, boolean on) {
        npcMode = on;
        src.sendSuccess(() -> Component.literal("NPC " + (on ? "ON: an NPC acts out the recorded actions" : "OFF")
                + ", applies from the next /ainpc playback load"), false);
        return 1;
    }

    static int referenceCommand(CommandSourceStack src, boolean on) {
        reference = on;
        src.sendSuccess(() -> Component.literal("Reference armor stand in NPC mode " + (on ? "ON" : "OFF")), false);
        return 1;
    }

    private static int nothingLoaded(CommandSourceStack src) {
        src.sendFailure(Component.literal("No playback loaded, use /ainpc playback load <recording>"));
        return 0;
    }

    private void tick() {
        if (paused) return;
        budget += speed;
        try {
            for (; budget >= 1; budget--) {
                if (step(budget < 2)) continue; // only the last tick of a fast-forward step is shown
                if (loop) {
                    open(); // next step rebuilds the first tick's chunks, undoing the recorded changes
                } else {
                    paused = true;
                    finished = true;
                    src.sendSuccess(() -> Component.literal("Playback of " + name + " finished at tick " + t
                            + ", /ainpc playback start to play again"), true);
                    return;
                }
            }
        } catch (Exception e) {
            AiNpc.LOGGER.error("Playback of {} failed", name, e);
            stop("failed: " + e);
        }
    }

    /** Plays the next tick of the recording; false at its end. */
    private boolean step(boolean show) throws IOException {
        for (String line; (line = in.readLine()) != null; ) {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            String type = o.get("type").getAsString();
            if (type.equals("end")) return false;
            if (type.equals("tick")) {
                play(o, show);
                return true;
            }
        }
        return false; // cut off without an end line
    }

    private void play(JsonObject o, boolean show) {
        t = o.get("t").getAsInt();
        Replayer.applyWorldChanges(world, o);
        // With an NPC the world reacts to it, not to the recorded player.
        if (!npcMode) for (JsonElement e : o.getAsJsonArray("blocks")) {
            JsonArray a = e.getAsJsonArray();
            BlockPos pos = new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
            level.setBlock(pos, world.getBlockState(pos), FLAGS);
        }
        for (JsonElement e : o.getAsJsonArray("chunks"))
            copyChunk(e.getAsJsonObject().get("x").getAsInt(), e.getAsJsonObject().get("z").getAsInt());
        if (o.has("container") && o.get("container").isJsonObject()) fillContainer(o.getAsJsonObject("container"));
        JsonArray inventoryBefore = recordedInventory;
        if (o.has("inventory")) recordedInventory = o.getAsJsonArray("inventory");

        long gameTime = o.get("gameTime").getAsLong();
        for (JsonElement e : o.getAsJsonArray("sounds")) {
            JsonObject s = e.getAsJsonObject();
            ResourceLocation id = ResourceLocation.parse(s.get("id").getAsString());
            var cat = Replayer.soundSource(s.get("cat").getAsString());
            var pos = RecordingFormat.vec(s.get("pos"));
            hearing.onSound(id, cat, pos, s.get("vol").getAsFloat(), s.get("range").getAsFloat(), s.get("gt").getAsLong());
            if (show) level.playSound(null, pos.x, pos.y, pos.z, SoundEvent.createVariableRangeEvent(id), cat,
                    s.get("vol").getAsFloat(), 1f);
        }

        PlayerState player = RecordingFormat.readPlayer(o.getAsJsonObject("player"));
        List<EntityState> entities = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("entities")) entities.add(RecordingFormat.readEntity(e.getAsJsonObject()));
        movePuppets(player, entities, o.has("inventory") ? o.getAsJsonArray("inventory") : null);

        JsonObject wo = o.getAsJsonObject("world");
        int darken = wo.get("skyDarken").getAsInt();
        if (show) { // the world's time and weather follow the recording
            level.setDayTime(wo.get("dayTime").getAsLong());
            boolean rain = wo.get("rain").getAsFloat() > 0.2f, thunder = wo.get("thunder").getAsFloat() > 0.2f;
            if (rain != level.isRaining() || thunder != level.isThundering())
                level.setWeatherParameters(rain ? 0 : 6000, rain ? 6000 : 0, rain, thunder);
        }
        if (npcMode) actNpc(o, player, inventoryBefore, entities);
        // the senses shown: the NPC's own (in the real world) when it plays, else the recorded player's
        PlayerState seer = npc != null ? PlayerState.of(npc.player) : player;
        net.minecraft.world.level.BlockGetter seen = npc != null ? level : world;
        int dark = net.kroia.ainpc.sensor.LightSource.skyDarken(level);
        VisionFrame vision = npc != null
                ? VisionSensor.current.sense(level, p -> level.getRawBrightness(p, dark), seer,
                EntityState.collect(level, npc.player, VisionSensor.current.range + 16), objects)
                : VisionSensor.current.sense(world, p -> world.brightness(p, darken), player, entities, objects);
        var heard = hearing.sense(seer, gameTime);
        var felt = body.sense(seer);
        List<ServerPlayer> viewers = level.getServer().getPlayerList().getPlayers().stream()
                .filter(ServerPlayer::isSpectator).filter(p -> NetworkManager.canPlayerReceive(p, SensorGizmos.TYPE)).toList();
        if (show && !viewers.isEmpty()) {
            SensorGizmos g = SensorGizmos.of(seen, seer, vision, heard, felt);
            if (npc != null) g.text().add(new SensorGizmos.Text(String.format(java.util.Locale.ROOT,
                    "NPC drift %.2f m (max %.2f)", npc.player.position().distanceTo(player.pos()), driftMax), 0x80FF80));
            NetworkManager.sendToPlayers(viewers, g);
        }

        // The player who started playback watches from behind the recorded player; everyone gets a teleport link.
        if (!viewerPlaced) {
            viewerPlaced = true;
            var back = player.eyePos().add(net.minecraft.world.phys.Vec3.directionFromRotation(0, player.yRot()).scale(-4));
            String tp = String.format(java.util.Locale.ROOT, "/execute in %s run tp @s %.1f %.1f %.1f %.0f 15",
                    level.dimension().location(), back.x, back.y + 1, back.z, player.yRot());
            src.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                    "Recording is at %.0f %.0f %.0f in %s. ", player.pos().x, player.pos().y, player.pos().z,
                    level.dimension().location())).append(Component.literal("[click to teleport]").withStyle(s -> s
                    .withColor(net.minecraft.ChatFormatting.AQUA)
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, tp)))), true);
            // Started by a player: that player; started from the console: everyone already spectating.
            List<ServerPlayer> watchers = src.getPlayer() != null ? List.of(src.getPlayer()) : viewers;
            for (ServerPlayer p : watchers) {
                p.setGameMode(GameType.SPECTATOR);
                p.teleportTo(level, back.x, back.y + 1, back.z, player.yRot(), 15);
            }
        }
    }

    /** First tick: the NPC appears in the recorded state; then it gets only the recorded actions. */
    private void actNpc(JsonObject o, PlayerState recorded, JsonArray inventoryBefore, List<EntityState> entities) {
        if (npc == null) {
            // Only the NPC acts: no real mobs (night playback spawns them), no leftovers of a previous run.
            var spawning = level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING);
            if (mobSpawningBefore == null) mobSpawningBefore = spawning.get();
            spawning.set(false, level.getServer());
            List<Entity> stray = new ArrayList<>();
            for (Entity e : level.getAllEntities())
                if (!e.getTags().contains(PUPPET_TAG) && !(e instanceof net.minecraft.world.entity.player.Player)
                        && (e instanceof Mob || e instanceof ItemEntity || e instanceof net.minecraft.world.entity.ExperienceOrb)
                        && e.position().closerThan(recorded.pos(), 128)) stray.add(e);
            stray.forEach(Entity::discard);
            // No random block ticks: leaves decay and drop saplings only as recorded (see matchItems).
            var ticking = level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING);
            if (randomTickBefore == null) randomTickBefore = ticking.get();
            ticking.set(0, level.getServer());
            matchItems(entities);
            npc = NpcBody.spawn(level, recorded, recordedInventory);
            driftFirstAt = -1;
            return;
        }
        // Spawner blocks ignore the mob spawning rule; their mobs were recorded and are puppets already.
        level.getEntitiesOfClass(Mob.class, npc.player.getBoundingBox().inflate(128), m -> !m.getTags().contains(PUPPET_TAG))
                .forEach(Entity::discard);
        Actions a = Actions.of(o, inventoryBefore);
        npc.act(a);
        matchItems(entities); // after acting: the NPC's own drops of this tick exist now
        double d = npc.player.position().distanceTo(recorded.pos());
        var n = npc.player;
        JsonObject did = o.getAsJsonObject("actions").getAsJsonObject("did");
        StringBuilder keys = new StringBuilder();
        o.getAsJsonObject("actions").getAsJsonObject("keys").entrySet().forEach(k -> {
            if (k.getValue().getAsBoolean()) keys.append(k.getKey()).append(' ');
        });
        trace.append(String.format(java.util.Locale.ROOT, "%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%b,%b,%b,%b,%b,%b,%b,%b,%s,%d,%d,%d,%s,%s,%s,%s,%s,%s,%s,%s%n",
                t, d, recorded.pos().x, recorded.pos().y, recorded.pos().z, n.getX(), n.getY(), n.getZ(),
                recorded.velocity().x, recorded.velocity().y, recorded.velocity().z,
                n.getDeltaMovement().x, n.getDeltaMovement().y, n.getDeltaMovement().z,
                recorded.onGround(), n.onGround(), did.get("sprinting").getAsBoolean(), n.isSprinting(),
                did.get("crouching").getAsBoolean(), n.isCrouching(), did.get("mining").getAsBoolean(), npc.destroying(),
                keys.toString().trim(), a.attacks(), a.uses(), o.getAsJsonArray("blocks").size(),
                short_(recorded.mainHand()), short_(EntityState.itemId(n.getMainHandItem())),
                traceInv(RecordingFormat.inventorySummary(recordedInventory), "armor"),
                traceInv(RecordingFormat.inventorySummary(RecordingFormat.slots(n.getInventory())), "armor"),
                traceInv(RecordingFormat.inventorySummary(recordedInventory), "offhand"),
                traceInv(RecordingFormat.inventorySummary(RecordingFormat.slots(n.getInventory())), "offhand"),
                traceInv(RecordingFormat.inventorySummary(recordedInventory), "items"),
                traceInv(RecordingFormat.inventorySummary(RecordingFormat.slots(n.getInventory())), "items")));
        driftTicks++;
        driftSum += d;
        if (d > driftMax) {
            driftMax = d;
            driftMaxAt = t;
        }
        if (d > 0.5 && driftFirstAt < 0) driftFirstAt = t;
    }

    /**
     * Every recorded item gets a real item in the NPC's world, so the NPC can pick up what the player picked up.
     * A real item of the same kind within 2 blocks that stands for no other recorded item is taken (the NPC's own
     * drop, e.g. from chopping the same log); otherwise one is placed at the recorded position. While the recording
     * has it, the real item is kept at the recorded position, so it lands where it landed for the player.
     */
    private void matchItems(List<EntityState> entities) {
        for (EntityState e : entities) {
            if (!e.type().equals("minecraft:item") || e.item() == null) continue;
            Entity real = realItems.get(e.id());
            if (real != null) { // follows the recorded flight and landing spot while the recording has it
                if (!real.isRemoved()) {
                    real.setPos(e.pos());
                    ((ItemEntity) real).getItem().setCount(e.count()); // the NPC picks up as many as the player did
                    real.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                }
                continue;
            }
            ItemStack want = stack(e.item());
            want.setCount(e.count());
            Entity match = level.getEntitiesOfClass(ItemEntity.class, AABB.ofSize(e.pos(), 4, 4, 4),
                            i -> i.getItem().is(want.getItem()) && !i.getTags().contains(PUPPET_TAG) && !realItems.containsValue(i))
                    .stream().min(java.util.Comparator.comparingDouble(i -> i.distanceToSqr(e.pos()))).orElse(null);
            if (match == null) {
                ItemEntity placed = new ItemEntity(level, e.pos().x, e.pos().y, e.pos().z, want, 0, 0, 0);
                if (npc != null) placed.setDefaultPickUpDelay(); // a fresh drop, not one lying there from the start
                level.addFreshEntity(placed);
                match = placed;
            }
            ((ItemEntity) match).setUnlimitedLifetime(); // no merging into a neighbour, it stays this recorded item
            realItems.put(e.id(), match);
        }
        // The other way round: an item the recording never got (random leaf loot of the NPC's world) is removed.
        // Grace ticks, because the player's client saw new drops a tick or two after the server spawned them.
        if (npc == null) return;
        var claimed = new HashSet<>(realItems.values());
        for (ItemEntity i : level.getEntitiesOfClass(ItemEntity.class, npc.player.getBoundingBox().inflate(128), i -> !claimed.contains(i))) {
            if (t - unmatchedSince.computeIfAbsent(i, x -> t) > UNMATCHED_GRACE) i.discard();
        }
        unmatchedSince.keySet().removeIf(i -> i.isRemoved() || claimed.contains(i));
    }

    /** CSV-safe: "oak_log*12 stick*3", armor "-|-|-|iron_helmet". */
    private static String traceInv(JsonObject summary, String part) {
        JsonElement e = summary.get(part);
        if (e.isJsonNull()) return "-";
        if (e.isJsonArray()) {
            List<String> s = new ArrayList<>();
            for (JsonElement x : e.getAsJsonArray()) s.add(x.isJsonNull() ? "-" : short_(x.getAsString()));
            return String.join("|", s);
        }
        List<String> s = new ArrayList<>();
        e.getAsJsonObject().entrySet().forEach(x -> s.add(short_(x.getKey()) + "*" + x.getValue().getAsInt()));
        return String.join(" ", s);
    }

    private static String short_(String id) {
        return id == null ? "-" : id.replace("minecraft:", "");
    }

    // ponytail: block by block with setBlock (lighting included), a few seconds for 25 mountain chunks;
    // write sections directly and resend the chunks if starting playback is too slow.
    private void copyChunk(int cx, int cz) {
        LevelChunk chunk = level.getChunk(cx, cz);
        int minY = Math.max(world.getMinBuildHeight(), level.getMinBuildHeight());
        int maxY = Math.min(world.getMaxBuildHeight(), level.getMaxBuildHeight());
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = minY; y < maxY; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    p.set(cx * 16 + x, y, cz * 16 + z);
                    BlockState s = world.getBlockState(p);
                    if (chunk.getBlockState(p) != s) level.setBlock(p, s, FLAGS);
                }
    }

    // ponytail: fills only the block entity at pos, so a double chest shows just one half's 27 slots.
    private void fillContainer(JsonObject c) {
        if (!c.get("pos").isJsonArray()) return;
        JsonArray a = c.getAsJsonArray("pos");
        BlockPos pos = new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
        // NPC mode: only the contents found when first opened, afterwards the NPC's clicks change them.
        if (npcMode && !filledContainers.add(pos)) return;
        if (!(level.getBlockEntity(pos) instanceof Container box)) return;
        JsonArray items = c.getAsJsonArray("items");
        int n = Math.min(box.getContainerSize(), items.size() - 36); // the last 36 menu slots are the player's
        for (int i = 0; i < n; i++) box.setItem(i, stack(items.get(i)));
        box.setChanged();
    }

    private void movePuppets(PlayerState player, List<EntityState> entities, JsonArray inventory) {
        if (npcMode && !reference) { // the NPC alone stands in for the recorded player; its drift is on the HUD
            movePuppets(entities);
            return;
        }
        if (self == null || self.isRemoved()) {
            self = new ArmorStand(EntityType.ARMOR_STAND, level);
            self.setShowArms(true);
            self.setCustomName(Component.literal("recording"));
            self.setCustomNameVisible(true);
            self.addTag(NpcBody.IGNORE_TAG);
            spawn(self, player.pos());
        }
        place(self, player.pos(), player.yRot(), 0, player.yRot());
        self.setHeadPose(new Rotations(player.xRot(), 0, 0));
        self.setItemSlot(EquipmentSlot.MAINHAND, stack(player.mainHand()));
        self.setItemSlot(EquipmentSlot.OFFHAND, stack(player.offHand()));
        if (inventory != null) { // armor slots 36-39: feet, legs, chest, head
            EquipmentSlot[] armor = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
            for (int i = 0; i < 4; i++) self.setItemSlot(armor[i], stack(inventory.get(36 + i)));
        }
        movePuppets(entities);
    }

    private void movePuppets(List<EntityState> entities) {

        Set<Integer> alive = new HashSet<>();
        for (EntityState e : entities) {
            alive.add(e.id());
            Entity p = puppets.get(e.id());
            if (p == null || p.isRemoved()) {
                p = createPuppet(e);
                if (p == null) continue;
                spawn(p, e.pos());
                puppets.put(e.id(), p);
            }
            place(p, e.pos(), e.yRot(), e.xRot(), e.headYRot());
            try {
                p.setPose(Pose.valueOf(e.pose()));
            } catch (IllegalArgumentException ignored) {
            }
            if (p instanceof LivingEntity le) {
                le.setItemSlot(EquipmentSlot.MAINHAND, stack(e.mainHand()));
                le.setItemSlot(EquipmentSlot.OFFHAND, stack(e.offHand()));
                EquipmentSlot[] slots = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
                for (int i = 0; i < slots.length; i++)
                    le.setItemSlot(slots[i], stack(e.armor().isEmpty() ? null : e.armor().get(i)));
            }
            if (p instanceof Mob m) m.setBaby(e.baby());
        }
        puppets.entrySet().removeIf(en -> {
            if (alive.contains(en.getKey())) return false;
            en.getValue().discard();
            return true;
        });
    }

    /** Living things and dropped items only: projectiles, TNT or lightning would act on their own. */
    private Entity createPuppet(EntityState e) {
        if (e.type().equals("minecraft:item")) {
            if (npcMode) return null; // the NPC's own drops are real items
            ItemEntity item = new ItemEntity(level, e.pos().x, e.pos().y, e.pos().z, stack(e.item()));
            item.setNeverPickUp();
            item.setUnlimitedLifetime();
            return item;
        }
        EntityType<?> type = EntityType.byString(e.type()).orElse(null);
        if (type == null || type == EntityType.PLAYER) {
            ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, level);
            stand.setShowArms(true);
            stand.setCustomName(Component.literal(e.type()));
            return stand;
        }
        Entity p = type.create(level);
        if (!(p instanceof LivingEntity)) {
            if (p != null) p.discard();
            return null;
        }
        if (p instanceof Mob m) {
            m.setNoAi(true);
            m.setPersistenceRequired();
        }
        return p;
    }

    private void spawn(Entity p, net.minecraft.world.phys.Vec3 pos) {
        p.setPos(pos);
        p.setNoGravity(true);
        p.setInvulnerable(true);
        p.setSilent(true); // the recording's sounds are played instead
        p.noPhysics = true;
        p.addTag(PUPPET_TAG);
        level.addFreshEntity(p);
    }

    private static void place(Entity p, net.minecraft.world.phys.Vec3 pos, float yRot, float xRot, float headYRot) {
        p.moveTo(pos.x, pos.y, pos.z, yRot, xRot);
        p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        p.setYHeadRot(headYRot);
        if (p instanceof LivingEntity le) le.setYBodyRot(yRot);
    }

    private static ItemStack stack(String id) {
        return RecordingFormat.item(id);
    }

    private ItemStack stack(JsonElement e) {
        return RecordingFormat.item(e, level.registryAccess());
    }

    private void stop(String why) {
        try {
            in.close();
        } catch (IOException ignored) {
        }
        removeNpc();
        removePuppets(level);
        puppets.clear();
        self = null;
        if (current == this) current = null;
        src.sendSuccess(() -> Component.literal("Playback of " + name + " " + why + " at tick " + t), true);
    }

    /** Also removes puppets left over from a crash or restart (they are saved with the world). */
    private static void removePuppets(ServerLevel level) {
        List<Entity> old = new ArrayList<>();
        for (Entity e : level.getAllEntities()) if (e.getTags().contains(PUPPET_TAG)) old.add(e);
        old.forEach(Entity::discard);
    }
}
