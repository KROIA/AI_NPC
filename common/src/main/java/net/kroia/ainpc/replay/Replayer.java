package net.kroia.ainpc.replay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import net.kroia.ainpc.sensor.BodyFrame;
import net.kroia.ainpc.sensor.BodySensor;
import net.kroia.ainpc.sensor.EntityState;
import net.kroia.ainpc.sensor.HearingFrame;
import net.kroia.ainpc.sensor.HearingSensor;
import net.kroia.ainpc.sensor.PlayerState;
import net.kroia.ainpc.sensor.VisionFrame;
import net.kroia.ainpc.sensor.VisionSensor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Turns a raw recording into a training dataset by rebuilding the world tick by tick and running the
 * current sensors on it. No game ticking involved, so it runs as fast as the CPU allows. Change a sensor,
 * replay again, get new training data from the same recordings.
 *
 * Dataset lines (.jsonl.gz):
 *   {"type":"header", "kind":"dataset", "format":2, "label", "source", "vision":{config}, "hearing":{config}}
 *   {"type":"tick", "t", "gameTime", "world":{dayTime, skyDarken, rain, thunder}, "rot":[yaw,pitch], "hold" (item taken into the hand, action), "hand" (item in hand after the tick),
 *    "actions":{...}, "inventory":{items:{id:count}, armor:[feet..head], offhand} (when changed),
 *    "container" (when changed), "vision":{d, dx, dy, dz, block, face, entity, light, seen}, "sounds":[...], "body":{...}}
 *   {"type":"end", "ticks":n}
 */
public final class Replayer {
    public static final int DATASET_FORMAT = 2;

    /** checked/mismatches: ticks whose replayed vision was compared with the live fingerprint. */
    public record Result(int ticks, int checked, int mismatches, int firstMismatchTick, boolean complete) {
    }

    private Replayer() {
    }

    public static Result replay(Path recording, Path dataset) throws IOException {
        Files.createDirectories(dataset.getParent());
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(recording)), StandardCharsets.UTF_8));
             Writer out = new BufferedWriter(new OutputStreamWriter(
                     new GZIPOutputStream(Files.newOutputStream(dataset)), StandardCharsets.UTF_8))) {

            String first = in.readLine();
            JsonObject header = first == null ? null : JsonParser.parseString(first).getAsJsonObject();
            if (header == null || !"recording".equals(RecordingFormat.str(header, "kind"))
                    || header.get("format").getAsInt() != RecordingFormat.FORMAT)
                throw new IOException(recording + " is not a format " + RecordingFormat.FORMAT + " recording");

            ReplayWorld world = new ReplayWorld(header.get("minY").getAsInt(), header.get("height").getAsInt());
            VisionSensor vision = VisionSensor.current; // the server's setting (/ainpc vision)
            HearingSensor hearing = new HearingSensor();
            var objects = new net.kroia.ainpc.sensor.ObjectSensor.Memory(); // like the recording: empty at its start
            BodySensor body = new BodySensor();
            // Fingerprints are only comparable while the vision settings are the ones they were recorded with.
            JsonObject cv = header.getAsJsonObject("checkVision");
            boolean check = cv != null && VisionSensor.of(cv).sameAs(vision);

            writeLine(out, w -> {
                w.name("type").value("header").name("kind").value("dataset").name("format").value(DATASET_FORMAT);
                w.name("label").value(RecordingFormat.str(header, "label"));
                w.name("source").value(recording.getFileName().toString());
                w.name("vision");
                vision.write(w);
                w.name("hearing").beginObject()
                        .name("memoryTicks").value(HearingSensor.MEMORY_TICKS)
                        .name("maxSounds").value(HearingSensor.MAX_SOUNDS).endObject();
            });

            int ticks = 0, checked = 0, mismatches = 0, firstMismatch = -1;
            boolean complete = false;
            JsonArray inventoryNow = null;
            for (String line; (line = in.readLine()) != null; ) {
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                String type = o.get("type").getAsString();
                if (type.equals("end")) {
                    complete = true;
                    break;
                }
                if (!type.equals("tick")) continue;

                applyWorldChanges(world, o);
                long gameTime = o.get("gameTime").getAsLong();
                for (JsonElement e : o.getAsJsonArray("sounds")) {
                    JsonObject s = e.getAsJsonObject();
                    hearing.onSound(ResourceLocation.parse(s.get("id").getAsString()), soundSource(s.get("cat").getAsString()),
                            RecordingFormat.vec(s.get("pos")), s.get("vol").getAsFloat(), s.get("range").getAsFloat(),
                            s.get("gt").getAsLong());
                }
                PlayerState player = RecordingFormat.readPlayer(o.getAsJsonObject("player"));
                String hold = inventoryNow == null ? player.mainHand() : RecordingFormat.held(inventoryNow, player.slot());
                if (o.has("inventory")) inventoryNow = o.getAsJsonArray("inventory");
                List<EntityState> entities = new ArrayList<>();
                for (JsonElement e : o.getAsJsonArray("entities")) entities.add(RecordingFormat.readEntity(e.getAsJsonObject()));

                JsonObject wo = o.getAsJsonObject("world");
                int darken = wo.get("skyDarken").getAsInt();
                VisionFrame vf = vision.sense(world, p -> world.brightness(p, darken), player, entities, objects);
                HearingFrame hf = hearing.sense(player, gameTime);
                BodyFrame bf = body.sense(player);
                if (check && o.has("check")) {
                    checked++;
                    if (o.get("check").getAsInt() != vf.checksum()) {
                        mismatches++;
                        if (firstMismatch < 0) firstMismatch = o.get("t").getAsInt();
                    }
                }

                writeLine(out, w -> {
                    w.name("type").value("tick").name("t").value(o.get("t").getAsInt()).name("gameTime").value(gameTime);
                    w.name("world").jsonValue(wo.toString());
                    w.name("rot").beginArray().value(player.yRot()).value(player.xRot()).endArray();
                    w.name("hold").value(hold);
                    w.name("hand").value(player.mainHand());
                    w.name("actions").jsonValue(o.get("actions").toString());
                    if (o.has("inventory"))
                        w.name("inventory").jsonValue(RecordingFormat.inventorySummary(o.getAsJsonArray("inventory")).toString());
                    if (o.has("container")) w.name("container").jsonValue(o.get("container").toString());
                    writeVision(w.name("vision"), vf);
                    writeHearing(w.name("sounds"), hf);
                    writeBody(w.name("body"), bf);
                });
                ticks++;
            }
            int n = ticks;
            writeLine(out, w -> w.name("type").value("end").name("ticks").value(n));
            return new Result(ticks, checked, mismatches, firstMismatch, complete);
        }
    }

    /** Block changes first, then chunk snapshots (a snapshot is always the newest state of its chunk). */
    static void applyWorldChanges(ReplayWorld world, JsonObject tick) {
        for (JsonElement e : tick.getAsJsonArray("blocks")) {
            var a = e.getAsJsonArray();
            world.setBlock(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()),
                    RecordingFormat.parseState(a.get(3).getAsString()));
        }
        for (JsonElement e : tick.getAsJsonArray("chunks")) {
            JsonObject c = e.getAsJsonObject();
            ReplayWorld.Section[] secs = world.newChunkSections();
            for (JsonElement s : c.getAsJsonArray("s")) {
                JsonObject so = s.getAsJsonObject();
                secs[so.get("i").getAsInt()] = RecordingFormat.readSection(so);
            }
            world.setChunk(c.get("x").getAsInt(), c.get("z").getAsInt(), secs);
        }
        for (JsonElement e : tick.getAsJsonArray("light")) {
            JsonObject l = e.getAsJsonObject();
            world.setLight(l.get("x").getAsInt(), l.get("i").getAsInt(), l.get("z").getAsInt(),
                    RecordingFormat.readLight(l.getAsJsonArray("r")));
        }
    }

    static SoundSource soundSource(String name) {
        for (SoundSource s : SoundSource.values()) if (s.getName().equals(name)) return s;
        return SoundSource.MASTER;
    }

    // --- dataset serialization ----------------------------------------------------------------

    private interface Body {
        void write(JsonWriter w) throws IOException;
    }

    private static void writeLine(Writer out, Body body) throws IOException {
        StringWriter s = new StringWriter();
        JsonWriter w = new JsonWriter(s);
        w.beginObject();
        body.write(w);
        w.endObject();
        w.close();
        out.write(s.toString());
        out.write('\n');
    }

    /** Rays as parallel arrays, row-major from the top-left: d, dx/dy/dz (unit), block (with state), face, entity. */
    public static void writeVision(JsonWriter w, VisionFrame f) throws IOException {
        VisionFrame.Ray[] rays = f.rays();
        w.beginObject();
        w.name("d").beginArray();
        for (VisionFrame.Ray r : rays) w.value(r2(r.distance()));
        w.endArray();
        w.name("dx").beginArray();
        for (VisionFrame.Ray r : rays) w.value(r3(r.dir().x));
        w.endArray();
        w.name("dy").beginArray();
        for (VisionFrame.Ray r : rays) w.value(r3(r.dir().y));
        w.endArray();
        w.name("dz").beginArray();
        for (VisionFrame.Ray r : rays) w.value(r3(r.dir().z));
        w.endArray();
        w.name("block").beginArray();
        for (VisionFrame.Ray r : rays) w.value(r.block() == null ? null : RecordingFormat.stateName(r.block()));
        w.endArray();
        w.name("face").beginArray();
        for (VisionFrame.Ray r : rays) w.value(r.face() == null ? null : r.face().getSerializedName());
        w.endArray();
        w.name("entity").beginArray();
        for (VisionFrame.Ray r : rays) w.value(r.entity() == null ? null : r.entity().type());
        w.endArray();
        w.name("light").beginArray();
        for (VisionFrame.Ray r : rays) w.value(r.light());
        w.endArray();

        VisionFrame.Ray c = f.cross(); // the crosshair ray, see VisionFrame
        w.name("cross").beginObject().name("d").value(r2(c.distance()))
                .name("block").value(c.block() == null ? null : RecordingFormat.stateName(c.block()))
                .name("face").value(c.face() == null ? null : c.face().getSerializedName())
                .name("entity").value(c.entity() == null ? null : c.entity().type())
                .name("light").value(c.light()).endObject();

        w.name("seen").beginArray();
        for (VisionFrame.SeenEntity e : f.entities()) {
            w.beginObject().name("type").value(e.entity().type()).name("id").value(e.entity().id())
                    .name("d").value(r2(e.distance())).name("yaw").value(r2(e.yawOffset()))
                    .name("pitch").value(r2(e.pitchOffset()));
            if (e.entity().health() != 0) w.name("hp").value(e.entity().health());
            if (!e.entity().tags().isEmpty()) w.name("tags").jsonValue(new com.google.gson.Gson().toJson(e.entity().tags()));
            if (!e.entity().armor().isEmpty()) w.name("armor").jsonValue(new com.google.gson.Gson().toJson(e.entity().armor()));
            if (e.entity().mainHand() != null) w.name("main").value(e.entity().mainHand());
            w.endObject();
        }
        w.endArray();
        w.name("objects").beginArray(); // ObjectSensor: nearest first; d/yaw/pitch to its anchor (a tree: lowest log)
        for (var o : f.objects()) {
            w.beginObject().name("kind").value(o.kind()).name("block").value(o.block()).name("d").value(r2(o.distance()))
                    .name("yaw").value(r2(o.yawOffset())).name("pitch").value(r2(o.pitchOffset()))
                    .name("h").value(o.height()).name("parts").value(o.parts()).name("seen").value(o.seen())
                    .name("near").value(r2(o.nearest())).endObject(); // to its nearest part: in reach at <= 4.5
        }
        w.endArray();
        w.endObject();
    }

    public static void writeHearing(JsonWriter w, HearingFrame f) throws IOException {
        w.beginArray();
        for (HearingFrame.Heard h : f.sounds()) {
            w.beginObject().name("id").value(h.id().toString()).name("cat").value(h.category().getName())
                    .name("i").value(r3(h.intensity())).name("d").value(r2(h.distance()))
                    .name("yaw").value(r2(h.yawOffset())).name("pitch").value(r2(h.pitchOffset()))
                    .name("age").value(h.ageTicks()).endObject();
        }
        w.endArray();
    }

    public static void writeBody(JsonWriter w, BodyFrame b) throws IOException {
        w.beginObject()
                .name("health").value(b.health()).name("maxHealth").value(b.maxHealth())
                .name("absorption").value(b.absorption()).name("healthDelta").value(r2(b.healthDelta()))
                .name("damageType").value(b.damageType());
        // NaN is not valid JSON: no direction -> null
        w.name("damageYaw");
        if (Float.isNaN(b.damageYaw())) w.nullValue(); else w.value(r2(b.damageYaw()));
        w.name("damagePitch");
        if (Float.isNaN(b.damagePitch())) w.nullValue(); else w.value(r2(b.damagePitch()));
        w.name("food").value(b.food()).name("saturation").value(r2(b.saturation()))
                .name("armor").value(b.armor()).name("armorToughness").value(b.armorToughness())
                .name("air").value(b.air()).name("maxAir").value(b.maxAir())
                .name("onGround").value(b.onGround()).name("inWater").value(b.inWater())
                .name("inLava").value(b.inLava()).name("onFire").value(b.onFire()).name("frozen").value(b.frozen())
                .name("fallDistance").value(r2(b.fallDistance()));
        w.name("velocity").beginArray().value(r3(b.velocity().x)).value(r3(b.velocity().y)).value(r3(b.velocity().z)).endArray();
        w.name("effects").beginArray();
        for (var e : b.effects()) w.beginArray().value(e.id()).value(e.amplifier()).value(e.duration()).endArray();
        w.endArray();
        w.endObject();
    }

    private static double r2(double x) {
        return Math.round(x * 100) / 100.0;
    }

    private static double r3(double x) {
        return Math.round(x * 1000) / 1000.0;
    }
}
