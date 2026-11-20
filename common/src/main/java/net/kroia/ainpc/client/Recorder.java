package net.kroia.ainpc.client;

import com.google.gson.stream.JsonWriter;
import net.kroia.ainpc.replay.BlockChangeHook;
import net.kroia.ainpc.replay.RecordingFormat;
import net.kroia.ainpc.sensor.EntityState;
import net.kroia.ainpc.sensor.PlayerState;
import net.kroia.ainpc.sensor.VisionSensor;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.kroia.ainpc.sensor.LightSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * Records raw state only (no sensor output): player, inputs, entities, sound events, chunk snapshots and
 * the block-change log. That is everything replay needs to rebuild what the player perceived, so sensors
 * can change later and the same recording yields new training data. Format: {@link RecordingFormat}.
 *
 * File: &lt;game dir&gt;/ainpc_recordings/&lt;label&gt;/&lt;timestamp&gt;.jsonl.gz
 */
final class Recorder implements BlockChangeHook.Listener {
    static final List<String> LABELS = net.kroia.ainpc.replay.RecordingFormat.LABELS;
    /**
     * Influence area: the 5x5 chunks around the player's chunk, moving with the player. Only chunks and
     * entities inside it are recorded; while recording, the live sensors see nothing outside the recorded
     * chunks either, so replay reproduces them exactly.
     */
    static final int CHUNK_RADIUS = 2;
    static final double ENTITY_RADIUS = (CHUNK_RADIUS + 1) * 16 * 1.5; // covers the area's corners

    private record BlockChange(BlockPos pos, BlockState state) {
    }

    private record Sound(ResourceLocation id, SoundSource category, Vec3 pos, float volume, float range, long gameTime) {
    }

    private Writer out;
    private Path file;
    private String label;
    /** The vision the "check" fingerprints are made with, fixed for the whole recording. */
    VisionSensor vision = VisionSensor.DEFAULT;
    private ClientLevel level;
    private int ticks;
    private long startMillis;
    private String lastInventory;
    private final Set<Long> snapshotted = new HashSet<>();
    private final List<BlockChange> pendingBlocks = new ArrayList<>();
    private final List<Sound> pendingSounds = new ArrayList<>();
    private final List<LevelChunk> pendingChunks = new ArrayList<>();
    private final List<String> pendingClicks = new ArrayList<>();
    private String lastContainer;
    private BlockPos containerPos;
    /** Clicks this tick: attack, use, pick block (from MinecraftMixin). */
    private final int[] clickCounts = new int[3];
    /** Light last written per section, and chunks whose light is due for a check at a tick. */
    private final Map<Long, byte[]> lastLight = new HashMap<>();
    private final Map<Long, Integer> lightDue = new HashMap<>();

    private record LightSection(int cx, int i, int cz, byte[] data) {
    }

    /** Live brightness, limited like the view: outside the recorded chunks it is open sky, as in replay. */
    LightSource light(int skyDarken) {
        return p -> snapshotted.contains(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4))
                ? level.getRawBrightness(p, skyDarken) : Math.max(0, 15 - skyDarken);
    }

    /** The level as recorded: chunks outside the snapshotted ones read as air, exactly like in replay. */
    final BlockGetter view = new BlockGetter() {
        private boolean recorded(BlockPos pos) {
            return snapshotted.contains(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return recorded(pos) ? level.getBlockState(pos) : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getHeight() {
            return level.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return level.getMinBuildHeight();
        }
    };

    static boolean inArea(ChunkPos center, Vec3 pos) {
        return Math.abs(((int) Math.floor(pos.x) >> 4) - center.x) <= CHUNK_RADIUS
                && Math.abs(((int) Math.floor(pos.z) >> 4) - center.z) <= CHUNK_RADIUS;
    }

    boolean active() {
        return out != null;
    }

    String label() {
        return label;
    }

    Path file() {
        return file;
    }

    long elapsedMillis() {
        return System.currentTimeMillis() - startMillis;
    }

    void start(Minecraft mc, String label) throws IOException {
        LocalPlayer p = mc.player;
        Path dir = mc.gameDirectory.toPath().resolve("ainpc_recordings").resolve(label);
        Files.createDirectories(dir);
        file = dir.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".jsonl.gz");
        out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8));
        this.label = label;
        level = mc.level;
        ticks = 0;
        lastInventory = null;
        startMillis = System.currentTimeMillis();
        snapshotted.clear();
        pendingBlocks.clear();
        pendingSounds.clear();
        pendingChunks.clear();
        pendingClicks.clear();
        lastContainer = null;
        containerPos = null;
        Arrays.fill(clickCounts, 0);
        lastLight.clear();
        lightDue.clear();

        VisionSensor v = vision = AiNpcClient.vision;
        writeLine(w -> {
            w.name("type").value("header").name("kind").value("recording").name("format").value(RecordingFormat.FORMAT);
            w.name("label").value(label);
            w.name("mc").value(SharedConstants.getCurrentVersion().getName());
            w.name("started").value(LocalDateTime.now().toString());
            // The seed is only known in singleplayer; replay does not need it (chunks are recorded).
            if (mc.getSingleplayerServer() != null) w.name("seed").value(mc.getSingleplayerServer().overworld().getSeed());
            else w.name("seed").nullValue();
            w.name("dimension").value(level.dimension().location().toString());
            w.name("minY").value(level.getMinBuildHeight()).name("height").value(level.getHeight());
            w.name("entityRadius").value(ENTITY_RADIUS).name("chunkRadius").value(CHUNK_RADIUS);
            // The vision settings the "check" fingerprints were made with: replay with other settings skips the check.
            w.name("checkVision");
            v.write(w);
        });
    }

    void onSound(ResourceLocation id, SoundSource category, Vec3 pos, float volume, float range, long gameTime) {
        if (active()) pendingSounds.add(new Sound(id, category, pos, volume, range, gameTime));
    }

    void onClick(int kind) {
        if (active()) clickCounts[kind]++;
    }

    void onContainerClick(int slot, int button, ClickType type) {
        if (active()) pendingClicks.add("[" + slot + "," + button + ",\"" + type.name() + "\"]");
    }

    /** Snapshots chunks entering the area. Call before sensing, so the live view already contains them. */
    void collectChunks(LocalPlayer p) {
        ChunkPos center = p.chunkPosition();
        for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
            for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
                int cx = center.x + dx, cz = center.z + dz;
                if (!snapshotted.contains(ChunkPos.asLong(cx, cz)) && level.hasChunk(cx, cz)) {
                    pendingChunks.add(level.getChunk(cx, cz));
                    snapshotted.add(ChunkPos.asLong(cx, cz));
                    forgetLight(cx, cz);
                    lightDue.put(ChunkPos.asLong(cx, cz), 0); // light now, with the snapshot
                }
            }
        }
    }

    @Override
    public void onBlockChanged(LevelChunk chunk, BlockPos pos, BlockState state) {
        // Chunks not yet snapshotted need no log: their snapshot will contain the change.
        if (active() && chunk.getLevel() == level && snapshotted.contains(chunk.getPos().toLong())) {
            pendingBlocks.add(new BlockChange(pos, state));
            // Light spreads up to 15 blocks: recheck the 3x3 chunks around once the client has relit them.
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    lightDue.merge(ChunkPos.asLong((pos.getX() >> 4) + dx, (pos.getZ() >> 4) + dz), ticks + 2, Math::max);
        }
    }

    @Override
    public void onChunkReplaced(LevelChunk chunk) {
        if (active() && chunk.getLevel() == level) {
            snapshotted.remove(chunk.getPos().toLong());
            forgetLight(chunk.getPos().x, chunk.getPos().z);
        }
    }

    /** check: VisionFrame.checksum() of the live vision for this exact state, verified by replay. */
    void tick(Minecraft mc, PlayerState player, List<EntityState> entities, int check) throws IOException {
        LocalPlayer p = mc.player;
        String inventory = inventoryJson(p);
        boolean inventoryChanged = !inventory.equals(lastInventory);
        lastInventory = inventory;

        String container = containerJson(mc);
        boolean containerChanged = !java.util.Objects.equals(container, lastContainer);
        lastContainer = container;
        List<LightSection> light = captureLight(ticks);
        int[] clicks = clickCounts.clone();

        int t = ticks++;
        writeLine(w -> {
            w.name("type").value("tick").name("t").value(t).name("gameTime").value(level.getGameTime());
            w.name("world").beginObject().name("dayTime").value(level.getDayTime())
                    .name("skyDarken").value(LightSource.skyDarken(level))
                    .name("rain").value(level.getRainLevel(1f)).name("thunder").value(level.getThunderLevel(1f)).endObject();
            RecordingFormat.writePlayer(w.name("player"), player);
            writeActions(w.name("actions"), mc, pendingClicks, clicks);
            if (inventoryChanged) w.name("inventory").jsonValue(inventory);
            if (containerChanged) w.name("container").jsonValue(container == null ? "null" : container);

            w.name("entities").beginArray();
            for (EntityState e : entities) RecordingFormat.writeEntity(w, e);
            w.endArray();

            w.name("sounds").beginArray();
            for (Sound s : pendingSounds) {
                w.beginObject().name("id").value(s.id.toString()).name("cat").value(s.category.getName());
                RecordingFormat.vec(w.name("pos"), s.pos);
                w.name("vol").value(s.volume).name("range").value(s.range).name("gt").value(s.gameTime).endObject();
            }
            w.endArray();

            w.name("blocks").beginArray();
            for (BlockChange b : pendingBlocks) {
                w.beginArray().value(b.pos.getX()).value(b.pos.getY()).value(b.pos.getZ())
                        .value(RecordingFormat.stateName(b.state)).endArray();
            }
            w.endArray();

            w.name("chunks").beginArray();
            for (LevelChunk c : pendingChunks) writeChunk(w, c);
            w.endArray();

            w.name("light").beginArray();
            for (LightSection l : light) {
                w.beginObject().name("x").value(l.cx).name("z").value(l.cz).name("i").value(l.i);
                RecordingFormat.writeLight(w, l.data);
                w.endObject();
            }
            w.endArray();

            w.name("check").value(check);
        });
        pendingBlocks.clear();
        pendingSounds.clear();
        pendingChunks.clear();
        pendingClicks.clear();
        Arrays.fill(clickCounts, 0);
    }

    private void forgetLight(int cx, int cz) {
        for (int i = 0; i < level.getSectionsCount(); i++) lastLight.remove(SectionPos.asLong(cx, i, cz));
    }

    /** Light of the chunks due at tick t; only sections whose light changed since last written. */
    private List<LightSection> captureLight(int t) {
        List<LightSection> out = new ArrayList<>();
        var it = lightDue.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue() > t) continue;
            it.remove();
            int cx = ChunkPos.getX(e.getKey()), cz = ChunkPos.getZ(e.getKey());
            if (!snapshotted.contains(e.getKey()) || !level.hasChunk(cx, cz)) continue;
            for (int i = 0; i < level.getSectionsCount(); i++) {
                byte[] d = sectionLight(cx, i, cz);
                if (Arrays.equals(d, lastLight.get(SectionPos.asLong(cx, i, cz)))) continue;
                lastLight.put(SectionPos.asLong(cx, i, cz), d);
                out.add(new LightSection(cx, i, cz, d));
            }
        }
        return out;
    }

    /** Sky and block light of one section, packed sky << 4 | block, index (y * 16 + z) * 16 + x. */
    private byte[] sectionLight(int cx, int i, int cz) {
        var sky = level.getLightEngine().getLayerListener(LightLayer.SKY);
        var blk = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        SectionPos sp = SectionPos.of(cx, level.getMinSection() + i, cz);
        DataLayer s = sky.getDataLayerData(sp), b = blk.getDataLayerData(sp);
        byte[] out = new byte[RecordingFormat.SECTION_VOLUME];
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int idx = 0; idx < out.length; idx++) {
            int x = idx & 15, z = (idx >> 4) & 15, y = idx >> 8;
            // No sky data stored for a section: ask the engine, which looks up from the sections above.
            int sl = s != null ? s.get(x, y, z) : sky.getLightValue(p.set(sp.minBlockX() + x, sp.minBlockY() + y, sp.minBlockZ() + z));
            int bl = b != null ? b.get(x, y, z) : 0;
            out[idx] = (byte) (sl << 4 | bl);
        }
        return out;
    }

    void stop() {
        if (out == null) return;
        try {
            writeLine(w -> w.name("type").value("end").name("ticks").value(ticks));
            out.close();
        } catch (IOException e) {
            AiNpcClient.LOGGER.error("Closing recording {} failed", file, e);
        }
        out = null;
        level = null;
        snapshotted.clear();
    }

    // --- serialization -------------------------------------------------------------------------

    private interface Body {
        void write(JsonWriter w) throws IOException;
    }

    // ponytail: JSON + gzip on the client thread; move to a writer thread if recording shows in frame times.
    private void writeLine(Body body) throws IOException {
        StringWriter s = new StringWriter();
        JsonWriter w = new JsonWriter(s);
        w.beginObject();
        body.write(w);
        w.endObject();
        w.close();
        out.write(s.toString());
        out.write('\n');
    }

    private static void writeChunk(JsonWriter w, LevelChunk c) throws IOException {
        w.beginObject().name("x").value(c.getPos().x).name("z").value(c.getPos().z).name("s").beginArray();
        LevelChunkSection[] sections = c.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s.hasOnlyAir()) continue;
            w.beginObject().name("i").value(i);
            RecordingFormat.writeSection(w, idx -> s.getBlockState(idx & 15, idx >> 8, (idx >> 4) & 15));
            w.endObject();
        }
        w.endArray().endObject();
    }

    /** What the player pressed, what the game made of it, the open screen and the crosshair target. */
    private static void writeActions(JsonWriter w, Minecraft mc, List<String> clicks, int[] counts) throws IOException {
        LocalPlayer p = mc.player;
        var o = mc.options;
        w.beginObject();
        w.name("keys").beginObject()
                .name("forward").value(o.keyUp.isDown()).name("back").value(o.keyDown.isDown())
                .name("left").value(o.keyLeft.isDown()).name("right").value(o.keyRight.isDown())
                .name("jump").value(o.keyJump.isDown()).name("sneak").value(o.keyShift.isDown())
                .name("sprint").value(o.keySprint.isDown()).name("attack").value(o.keyAttack.isDown())
                .name("use").value(o.keyUse.isDown()).name("drop").value(o.keyDrop.isDown())
                .name("swapHands").value(o.keySwapOffhand.isDown()).endObject();
        w.name("did").beginObject()
                .name("sprinting").value(p.isSprinting()).name("crouching").value(p.isCrouching())
                .name("swimming").value(p.isSwimming()).name("usingItem").value(p.isUsingItem())
                .name("mining").value(mc.gameMode != null && mc.gameMode.isDestroying())
                // block breaking progress 0..1 (0 when not mining): an NPC input, so it knows when to keep hitting
                .name("breaking").value(mc.gameMode == null || !mc.gameMode.isDestroying() ? 0
                        : ((net.kroia.ainpc.mixin.MultiPlayerGameModeAccess) mc.gameMode).ainpc$destroyProgress())
                .endObject();
        w.name("screen").value(screenName(mc.screen));
        // Container clicks this tick: [slot, button, click type]; slot -999 = outside the window (drop).
        w.name("clicks").jsonValue("[" + String.join(",", clicks) + "]");
        // Attack / use / pick-block clicks this tick, also the quick ones the key states above miss.
        w.name("attacks").value(counts[0]).name("uses").value(counts[1]).name("picks").value(counts[2]);
        HitResult hit = mc.hitResult;
        w.name("target");
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            w.beginObject().name("type").value("block")
                    .name("pos").beginArray().value(bhr.getBlockPos().getX()).value(bhr.getBlockPos().getY()).value(bhr.getBlockPos().getZ()).endArray()
                    .name("face").value(bhr.getDirection().getSerializedName()).endObject();
        } else if (hit instanceof EntityHitResult ehr) {
            w.beginObject().name("type").value("entity").name("id").value(ehr.getEntity().getId()).endObject();
        } else {
            w.nullValue();
        }
        w.endObject();
    }

    /**
     * The open container's slots (container first, then the player inventory part, in menu order) and the
     * item on the cursor. pos is the block looked at when the screen opened (null for e.g. the own inventory).
     * Contents of chests etc. are only known to the client while open, so this is what the player knew.
     */
    private String containerJson(Minecraft mc) throws IOException {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            containerPos = null;
            return null;
        }
        if (lastContainer == null) {
            containerPos = !(mc.screen instanceof InventoryScreen) && mc.hitResult instanceof BlockHitResult b
                    && b.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : null;
        }
        StringWriter s = new StringWriter();
        JsonWriter w = new JsonWriter(s);
        w.beginObject().name("pos");
        if (containerPos == null) w.nullValue();
        else w.beginArray().value(containerPos.getX()).value(containerPos.getY()).value(containerPos.getZ()).endArray();
        w.name("type").value(screenName(screen)).name("items").beginArray();
        for (Slot slot : screen.getMenu().slots) item(w, slot.getItem());
        w.endArray().name("carried");
        item(w, screen.getMenu().getCarried());
        w.endObject();
        w.close();
        return s.toString();
    }

    /** [id, count] or, for damageable / enchanted items, [id, count, damage, maxDamage, ["enchantment:level", ...]]. */
    private static void item(JsonWriter w, ItemStack st) throws IOException {
        if (st.isEmpty()) {
            w.nullValue();
            return;
        }
        w.beginArray().value(EntityState.itemId(st)).value(st.getCount());
        var enchantments = st.getEnchantments();
        if (st.isDamageableItem() || !enchantments.isEmpty()) {
            w.value(st.getDamageValue()).value(st.getMaxDamage()).beginArray();
            for (var e : enchantments.entrySet()) w.value(e.getKey().getRegisteredName() + ":" + e.getIntValue());
            w.endArray();
        }
        w.endArray();
    }

    /** Main inventory 0-35 (0-8 = hotbar), then armor feet..head, then offhand; empty slots are null. */
    private static String inventoryJson(LocalPlayer p) throws IOException {
        StringWriter s = new StringWriter();
        JsonWriter w = new JsonWriter(s);
        w.beginArray();
        var inv = p.getInventory();
        for (List<ItemStack> part : List.of(inv.items, inv.armor, inv.offhand)) {
            for (ItemStack st : part) item(w, st);
        }
        w.endArray();
        w.close();
        return s.toString();
    }

    private static String screenName(Screen screen) {
        if (screen == null) return null;
        if (screen instanceof InventoryScreen) return "inventory";
        if (screen instanceof ChatScreen) return "chat";
        if (screen instanceof PauseScreen) return "pause";
        if (screen instanceof AbstractContainerScreen<?> c) {
            try {
                return BuiltInRegistries.MENU.getKey(c.getMenu().getType()).toString();
            } catch (UnsupportedOperationException e) { // menus without a registered type
                return "container";
            }
        }
        return "other";
    }
}
