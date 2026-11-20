package net.kroia.ainpc.replay;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.kroia.ainpc.sensor.EntityState;
import net.kroia.ainpc.sensor.PlayerState;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * The raw recording format (format 2): writer and reader side by side so they stay symmetric.
 * Positions, rotations and sizes are written at full precision so a replay reproduces the live
 * sensor values bit for bit (see VisionFrame.checksum).
 *
 * Lines of a recording (.jsonl.gz):
 *   {"type":"header", "kind":"recording", "format":2, "label", "mc", "seed", "dimension", "minY", "height", ...}
 *   {"type":"tick", "t", "gameTime", "player":{...}, "actions":{...}, "inventory":[...] (only when changed),
 *    "container":{"pos", "type", "items", "carried"} (open container screen, only when changed; null = closed),
 *    "entities":[...], "sounds":[...], "blocks":[[x,y,z,"state"],...], "chunks":[...], "check":int}
 *   {"type":"end", "ticks":n}
 * Replay applies a tick's "blocks" before its "chunks" (a chunk snapshot is always the newest state).
 */
public final class RecordingFormat {
    public static final int FORMAT = 4;
    /**
     * Recording labels = the goals a model-driven NPC can be given. Same list as GOALS in training/data.py.
     * Append only: a trained model knows a goal by its position in this list.
     */
    public static final List<String> LABELS = List.of("gather_wood", "mine_stone", "mine_ore", "hunt", "eat", "craft",
            "build", "farm", "explore", "swim", "fight", "flee", "free_play");
    public static final int SECTION_VOLUME = 16 * 16 * 16;

    private RecordingFormat() {
    }

    // --- block states ---------------------------------------------------------------------------

    private static final Map<BlockState, String> STATE_NAMES = new HashMap<>();
    private static final Map<String, BlockState> PARSED_STATES = new HashMap<>();

    /** e.g. minecraft:oak_log[axis=x]; cached, the set of block states is finite. */
    public static synchronized String stateName(BlockState s) {
        return STATE_NAMES.computeIfAbsent(s, BlockStateParser::serialize);
    }

    /** States that could not be parsed (block from a mod missing at replay); they replay as air. */
    public static final Set<String> UNKNOWN_STATES = ConcurrentHashMap.newKeySet();

    public static synchronized BlockState parseState(String name) {
        return PARSED_STATES.computeIfAbsent(name, n -> {
            try {
                return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), n, false).blockState();
            } catch (CommandSyntaxException e) {
                UNKNOWN_STATES.add(n);
                return Blocks.AIR.defaultBlockState();
            }
        });
    }

    // --- chunk sections ---------------------------------------------------------------------------

    /**
     * Writes "p" (palette of state names) and "r" (run-length pairs index,count) for one 16^3 section.
     * Cell order is vanilla's: index = (y * 16 + z) * 16 + x.
     */
    public static void writeSection(JsonWriter w, IntFunction<BlockState> stateAt) throws IOException {
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        int[] cells = new int[SECTION_VOLUME];
        for (int i = 0; i < SECTION_VOLUME; i++)
            cells[i] = index.computeIfAbsent(stateAt.apply(i), s -> {
                palette.add(s);
                return palette.size() - 1;
            });
        w.name("p").beginArray();
        for (BlockState s : palette) w.value(stateName(s));
        w.endArray();
        w.name("r").beginArray();
        for (int i = 0; i < SECTION_VOLUME; ) {
            int j = i;
            while (j < SECTION_VOLUME && cells[j] == cells[i]) j++;
            w.value(cells[i]).value(j - i);
            i = j;
        }
        w.endArray();
    }

    public static ReplayWorld.Section readSection(JsonObject o) {
        List<BlockState> palette = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("p")) palette.add(parseState(e.getAsString()));
        short[] cells = new short[SECTION_VOLUME];
        JsonArray r = o.getAsJsonArray("r");
        int at = 0;
        for (int k = 0; k < r.size(); k += 2) {
            short idx = r.get(k).getAsShort();
            int n = r.get(k + 1).getAsInt();
            for (int i = 0; i < n; i++) cells[at++] = idx;
        }
        if (at != SECTION_VOLUME) throw new IllegalArgumentException("section has " + at + " cells");
        return new ReplayWorld.Section(palette, cells);
    }

    // --- player -----------------------------------------------------------------------------------

    public static void writePlayer(JsonWriter w, PlayerState p) throws IOException {
        w.beginObject();
        vec(w.name("pos"), p.pos());
        w.name("rot").beginArray().value(p.yRot()).value(p.xRot()).endArray();
        w.name("eyeHeight").value(p.eyeHeight());
        w.name("pose").value(p.pose());
        w.name("size").beginArray().value(p.width()).value(p.height()).endArray();
        vec(w.name("vel"), p.velocity());
        w.name("onGround").value(p.onGround()).name("inWater").value(p.inWater()).name("inLava").value(p.inLava())
                .name("onFire").value(p.onFire()).name("frozen").value(p.frozen()).name("fall").value(p.fallDistance());
        w.name("health").value(p.health()).name("maxHealth").value(p.maxHealth()).name("absorption").value(p.absorption());
        w.name("food").value(p.food()).name("saturation").value(p.saturation());
        w.name("armor").value(p.armor()).name("toughness").value(p.armorToughness());
        w.name("air").value(p.air()).name("maxAir").value(p.maxAir());
        w.name("hurtTime").value(p.hurtTime()).name("damageType").value(p.damageType());
        vec(w.name("damageFrom"), p.damageFrom());
        w.name("slot").value(p.slot()).name("mainHand").value(p.mainHand()).name("offHand").value(p.offHand());
        w.name("xp").value(p.xpLevel());
        if (!p.effects().isEmpty()) {
            w.name("effects").beginArray();
            for (PlayerState.Effect e : p.effects()) w.beginArray().value(e.id()).value(e.amplifier()).value(e.duration()).endArray();
            w.endArray();
        }
        w.endObject();
    }

    public static PlayerState readPlayer(JsonObject o) {
        JsonArray rot = o.getAsJsonArray("rot"), size = o.getAsJsonArray("size");
        return new PlayerState(vec(o.get("pos")), rot.get(0).getAsFloat(), rot.get(1).getAsFloat(),
                o.get("eyeHeight").getAsFloat(), o.get("pose").getAsString(),
                size.get(0).getAsFloat(), size.get(1).getAsFloat(), vec(o.get("vel")),
                o.get("onGround").getAsBoolean(), o.get("inWater").getAsBoolean(), o.get("inLava").getAsBoolean(),
                o.get("onFire").getAsBoolean(), o.get("frozen").getAsBoolean(), o.get("fall").getAsFloat(),
                o.get("health").getAsFloat(), o.get("maxHealth").getAsFloat(), o.get("absorption").getAsFloat(),
                o.get("food").getAsInt(), o.get("saturation").getAsFloat(),
                o.get("armor").getAsInt(), o.get("toughness").getAsFloat(),
                o.get("air").getAsInt(), o.get("maxAir").getAsInt(),
                o.get("hurtTime").getAsInt(), str(o, "damageType"), vec(o.get("damageFrom")),
                o.get("slot").getAsInt(), str(o, "mainHand"), str(o, "offHand"), o.get("xp").getAsInt(), effects(o));
    }

    private static List<PlayerState.Effect> effects(JsonObject o) {
        List<PlayerState.Effect> out = new ArrayList<>();
        if (o.has("effects")) for (JsonElement e : o.getAsJsonArray("effects")) {
            JsonArray a = e.getAsJsonArray();
            out.add(new PlayerState.Effect(a.get(0).getAsString(), a.get(1).getAsInt(), a.get(2).getAsInt()));
        }
        return out;
    }

    // --- entities ---------------------------------------------------------------------------------

    /** Default-valued optional fields are left out to keep the per-tick entity list small. */
    public static void writeEntity(JsonWriter w, EntityState e) throws IOException {
        w.beginObject();
        w.name("id").value(e.id()).name("type").value(e.type());
        vec(w.name("pos"), e.pos());
        w.name("rot").beginArray().value(e.yRot()).value(e.xRot()).value(e.headYRot()).endArray();
        w.name("size").beginArray().value(e.width()).value(e.height()).value(e.eyeHeight()).endArray();
        if (!e.pose().equals("STANDING")) w.name("pose").value(e.pose());
        if (e.baby()) w.name("baby").value(true);
        if (e.onFire()) w.name("fire").value(true);
        if (e.invisible()) w.name("invis").value(true);
        if (e.health() != 0) w.name("hp").value(e.health());
        if (e.hurtTime() != 0) w.name("hurt").value(e.hurtTime());
        if (e.deathTime() != 0) w.name("death").value(e.deathTime());
        if (e.mainHand() != null) w.name("main").value(e.mainHand());
        if (e.offHand() != null) w.name("off").value(e.offHand());
        if (e.item() != null) w.name("item").value(e.item());
        if (e.count() != 1) w.name("count").value(e.count());
        if (e.swell() != 0) w.name("swell").value(e.swell());
        strings(w, "armor", e.armor());
        strings(w, "tags", e.tags());
        w.endObject();
    }

    public static EntityState readEntity(JsonObject o) {
        JsonArray rot = o.getAsJsonArray("rot"), size = o.getAsJsonArray("size");
        return new EntityState(o.get("id").getAsInt(), o.get("type").getAsString(), vec(o.get("pos")),
                rot.get(0).getAsFloat(), rot.get(1).getAsFloat(), rot.get(2).getAsFloat(),
                size.get(0).getAsFloat(), size.get(1).getAsFloat(), size.get(2).getAsFloat(),
                o.has("pose") ? o.get("pose").getAsString() : "STANDING",
                bool(o, "baby"), bool(o, "fire"), bool(o, "invis"),
                o.has("hp") ? o.get("hp").getAsFloat() : 0,
                o.has("hurt") ? o.get("hurt").getAsInt() : 0,
                o.has("death") ? o.get("death").getAsInt() : 0,
                str(o, "main"), str(o, "off"), str(o, "item"),
                o.has("count") ? o.get("count").getAsInt() : 1,
                o.has("swell") ? o.get("swell").getAsFloat() : 0,
                strings(o, "armor"), strings(o, "tags"));
    }

    // --- light: per section 4096 bytes (sky << 4 | block), index (y * 16 + z) * 16 + x, as RLE pairs ------

    public static void writeLight(JsonWriter w, byte[] light) throws IOException {
        w.name("r").beginArray();
        for (int i = 0; i < light.length; ) {
            int j = i;
            while (j < light.length && light[j] == light[i]) j++;
            w.value(light[i] & 255).value(j - i);
            i = j;
        }
        w.endArray();
    }

    public static byte[] readLight(JsonArray runs) {
        byte[] out = new byte[SECTION_VOLUME];
        for (int k = 0, i = 0; k < runs.size(); k += 2) {
            byte v = (byte) runs.get(k).getAsInt();
            for (int n = runs.get(k + 1).getAsInt(); n > 0; n--) out[i++] = v;
        }
        return out;
    }

    // --- items: [id, count] or [id, count, damage, maxDamage, ["enchantment:level", ...]], null = empty ------

    public static ItemStack item(String id) {
        return id == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)));
    }

    public static ItemStack item(JsonElement e, RegistryAccess registries) {
        if (e == null || !e.isJsonArray()) return ItemStack.EMPTY;
        JsonArray a = e.getAsJsonArray();
        ItemStack s = item(a.get(0).getAsString());
        s.setCount(a.get(1).getAsInt());
        if (a.size() > 2) {
            s.setDamageValue(a.get(2).getAsInt());
            var enchantments = registries.registryOrThrow(Registries.ENCHANTMENT);
            for (JsonElement en : a.get(4).getAsJsonArray()) {
                String v = en.getAsString();
                int colon = v.lastIndexOf(':');
                enchantments.getHolder(ResourceLocation.parse(v.substring(0, colon)))
                        .ifPresent(h -> s.enchant(h, Integer.parseInt(v.substring(colon + 1))));
            }
        }
        return s;
    }

    // --- inventory as the NPC sees it: what and how many, not where ------------------------------------

    /**
     * Inventory slots as recorded (main 0-35, armor 36-39 feet..head, offhand 40; [id, count, ...] or null) to
     * {"items":{id:count}, "armor":[feet, legs, chest, head], "offhand":id}. Slot positions do not matter to the NPC:
     * it picks what to hold by item ({@link net.kroia.ainpc.npc.Actions#hold}).
     */
    public static JsonObject inventorySummary(JsonArray slots) {
        JsonObject items = new JsonObject();
        java.util.TreeMap<String, Integer> counts = new java.util.TreeMap<>();
        for (int i = 0; i < Math.min(36, slots.size()); i++)
            if (slots.get(i).isJsonArray())
                counts.merge(slots.get(i).getAsJsonArray().get(0).getAsString(), slots.get(i).getAsJsonArray().get(1).getAsInt(), Integer::sum);
        counts.forEach(items::addProperty);
        JsonArray armor = new JsonArray();
        for (int i = 36; i < 40; i++) armor.add(i < slots.size() && slots.get(i).isJsonArray() ? slots.get(i).getAsJsonArray().get(0) : null);
        JsonObject o = new JsonObject();
        o.add("items", items);
        o.add("armor", armor);
        o.add("offhand", slots.size() > 40 && slots.get(40).isJsonArray() ? slots.get(40).getAsJsonArray().get(0) : null);
        return o;
    }

    /** Recorded slots of a live inventory, in the same layout as the recorder writes. */
    public static JsonArray slots(net.minecraft.world.entity.player.Inventory inv) {
        JsonArray a = new JsonArray();
        for (int i = 0; i < 41; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                a.add((JsonElement) null);
                continue;
            }
            JsonArray it = new JsonArray();
            it.add(EntityState.itemId(s));
            it.add(s.getCount());
            a.add(it);
        }
        return a;
    }

    /** The item in the selected hotbar slot, given the slots before the tick. */
    public static String held(JsonArray slots, int slot) {
        JsonElement e = slots == null || slot >= slots.size() ? null : slots.get(slot);
        return e != null && e.isJsonArray() ? e.getAsJsonArray().get(0).getAsString() : null;
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** Left out when empty; null entries allowed. */
    private static void strings(JsonWriter w, String key, List<String> list) throws IOException {
        if (list.isEmpty()) return;
        w.name(key).beginArray();
        for (String s : list) w.value(s);
        w.endArray();
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key)) for (JsonElement e : o.getAsJsonArray(key)) out.add(e.isJsonNull() ? null : e.getAsString());
        return out;
    }

    public static void vec(JsonWriter w, Vec3 v) throws IOException {
        if (v == null) w.nullValue();
        else w.beginArray().value(v.x).value(v.y).value(v.z).endArray();
    }

    public static Vec3 vec(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        JsonArray a = e.getAsJsonArray();
        return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).getAsBoolean();
    }
}
