package net.kroia.ainpc.replay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import net.kroia.ainpc.sensor.EntityState;
import net.kroia.ainpc.sensor.PlayerState;
import net.kroia.ainpc.sensor.VisionFrame;
import net.kroia.ainpc.sensor.VisionSensor;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Recording a world and replaying it must give the sensors exactly the same input. */
class ReplayRoundTripTest {
    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static final BlockState LOG_X = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);

    @Test
    void blockStatesKeepTheirProperties() {
        assertEquals("minecraft:oak_log[axis=x]", RecordingFormat.stateName(LOG_X));
        assertEquals(LOG_X, RecordingFormat.parseState("minecraft:oak_log[axis=x]"));
        RecordingFormat.parseState("somemod:missing_block");
        assertTrue(RecordingFormat.UNKNOWN_STATES.contains("somemod:missing_block"));
    }

    @Test
    void replayedWorldAndEntitiesGiveIdenticalVision() throws IOException {
        // "Live" world: stone floor at y=63, a log on it, water pool next to it.
        ReplayWorld live = new ReplayWorld(-64, 384);
        live.setChunk(0, 0, live.newChunkSections());
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) live.setBlock(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState());
        live.setBlock(new BlockPos(8, 64, 12), LOG_X);
        live.setBlock(new BlockPos(5, 64, 12), Blocks.WATER.defaultBlockState());

        // Record it the way the recorder does (section JSON per non-empty section), then rebuild like replay.
        ReplayWorld replayed = new ReplayWorld(-64, 384);
        ReplayWorld.Section[] secs = replayed.newChunkSections();
        for (int si = 0; si < secs.length; si++) {
            int baseY = -64 + si * 16;
            StringWriter s = new StringWriter();
            JsonWriter w = new JsonWriter(s);
            w.beginObject();
            RecordingFormat.writeSection(w, idx -> live.getBlockState(new BlockPos(idx & 15, baseY + (idx >> 8), (idx >> 4) & 15)));
            w.endObject();
            w.close();
            secs[si] = RecordingFormat.readSection(JsonParser.parseString(s.toString()).getAsJsonObject());
        }
        replayed.setChunk(0, 0, secs);
        assertEquals(LOG_X, replayed.getBlockState(new BlockPos(8, 64, 12)));

        // Player and a cow, also through their JSON round trip.
        PlayerState player = new PlayerState(new Vec3(8.3, 64, 6.7), 3.5f, 20.25f, 1.62f, "STANDING", 0.6f, 1.8f,
                Vec3.ZERO, true, false, false, false, false, 0, 20, 20, 0, 20, 5, 0, 0, 300, 300,
                0, null, null, 0, "minecraft:iron_axe", null, 0,
                List.of(new PlayerState.Effect("minecraft:poison", 0, 100)));
        EntityState cow = new EntityState(42, "minecraft:cow", new Vec3(10.5, 64, 11.5), 90, 0, 90,
                0.9f, 1.4f, 1.3f, "STANDING", false, false, false, 10, 0, 0, null, null, null, 1, 0,
                java.util.Arrays.asList(null, null, null, "minecraft:leather_helmet"), List.of("color=white", "sheared"));
        PlayerState player2 = RecordingFormat.readPlayer(json(w -> RecordingFormat.writePlayer(w, player)));
        EntityState cow2 = RecordingFormat.readEntity(json(w -> RecordingFormat.writeEntity(w, cow)));
        assertEquals(player, player2);
        assertEquals(cow, cow2);

        VisionFrame a = VisionSensor.DEFAULT.sense(live, player, List.of(cow));
        VisionFrame b = VisionSensor.DEFAULT.sense(replayed, player2, List.of(cow2));
        assertEquals(a.checksum(), b.checksum());

        // And the scene is actually seen: the cow, the log and the floor.
        assertTrue(a.entities().stream().anyMatch(e -> e.entity().id() == 42), "cow seen");
        boolean logHit = false, floorHit = false;
        for (VisionFrame.Ray r : a.rays()) {
            if (r.block() == LOG_X) logHit = true;
            if (r.block() != null && r.block().is(Blocks.STONE)) floorHit = true;
        }
        assertTrue(logHit && floorHit, "log and floor hit");
        assertNotNull(a.rays()[a.rays().length - 1].pos(), "bottom-right ray hits the floor");
        // the crosshair ray points exactly where the head looks, whatever the grid, and replays the same
        var look = net.minecraft.world.phys.Vec3.directionFromRotation(player.xRot(), player.yRot());
        assertTrue(a.cross().dir().distanceTo(look) < 1e-6, "crosshair ray along the view");
        assertEquals(a.cross().block(), b.cross().block());
        assertEquals(a.cross().distance(), b.cross().distance());
    }

    @Test
    void lightRoundTripAndBrightness() {
        byte[] light = new byte[RecordingFormat.SECTION_VOLUME];
        java.util.Arrays.fill(light, (byte) (15 << 4)); // open sky
        light[ReplayWorld.Section.index(3, 4, 5)] = (byte) (2 << 4 | 7); // torch-lit cave spot
        StringWriter s = new StringWriter();
        try (JsonWriter w = new JsonWriter(s)) {
            w.beginObject();
            RecordingFormat.writeLight(w, light);
            w.endObject();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        byte[] back = RecordingFormat.readLight(JsonParser.parseString(s.toString()).getAsJsonObject().getAsJsonArray("r"));
        assertTrue(java.util.Arrays.equals(light, back));

        ReplayWorld w = new ReplayWorld(-64, 384);
        w.setLight(0, 8, 0, back); // section 8 = y 64..79
        assertEquals(15, w.brightness(new BlockPos(0, 64, 0), 0), "noon sky");
        assertEquals(4, w.brightness(new BlockPos(0, 64, 0), 11), "night sky");
        assertEquals(7, w.brightness(new BlockPos(3, 68, 5), 11), "torch light wins at night");
        assertEquals(4, w.brightness(new BlockPos(100, 64, 100), 11), "unrecorded = open sky");
    }

    private interface Writes {
        void write(JsonWriter w) throws IOException;
    }

    private static JsonObject json(Writes body) throws IOException {
        StringWriter s = new StringWriter();
        JsonWriter w = new JsonWriter(s);
        body.write(w);
        w.close();
        return JsonParser.parseString(s.toString()).getAsJsonObject();
    }
}
