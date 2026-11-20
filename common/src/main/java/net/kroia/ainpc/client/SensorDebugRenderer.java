package net.kroia.ainpc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.kroia.ainpc.sensor.SensorGizmos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the sensor gizmos: the local player's (F6) and those of a replayed NPC sent by the server, plus the
 * recording's influence area. Called by each loader's world-render hook with a pose stack whose rotation is
 * already applied (model-view matrix); this class applies the camera translation.
 */
public final class SensorDebugRenderer {

    public static void render(PoseStack poseStack, Vec3 camPos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        SensorGizmos remote = AiNpcClient.remoteGizmos();
        SensorGizmos live = AiNpcClient.sensorDebug && remote == null ? AiNpcClient.liveGizmos : null;
        boolean area = AiNpcClient.recorder.active();
        if (live == null && remote == null && !area) return;

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        if (area) renderInfluenceArea(poseStack, lines, mc);
        if (live != null) draw(poseStack, lines, live);
        if (remote != null) draw(poseStack, lines, remote);

        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
    }

    private static void draw(PoseStack poseStack, VertexConsumer lines, SensorGizmos g) {
        for (SensorGizmos.Box b : g.boxes()) {
            int c = b.argb();
            LevelRenderer.renderLineBox(poseStack, lines, b.box(),
                    (c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, (c >>> 24) / 255f);
        }
        PoseStack.Pose pose = poseStack.last();
        for (SensorGizmos.Line l : g.lines()) line(pose, lines, l.a(), l.b(), l.argb());
    }

    private static void line(PoseStack.Pose pose, VertexConsumer lines, Vec3 a, Vec3 b, int argb) {
        float r = (argb >> 16 & 255) / 255f, g = (argb >> 8 & 255) / 255f, bl = (argb & 255) / 255f, al = (argb >>> 24) / 255f;
        Vec3 n = b.subtract(a).normalize();
        lines.addVertex(pose, (float) a.x, (float) a.y, (float) a.z).setColor(r, g, bl, al).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
        lines.addVertex(pose, (float) b.x, (float) b.y, (float) b.z).setColor(r, g, bl, al).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
    }

    /** Red outline of the recorded area (5x5 chunks around the player) with posts at the chunk borders. */
    private static void renderInfluenceArea(PoseStack poseStack, VertexConsumer lines, Minecraft mc) {
        var c = mc.player.chunkPosition();
        int r = Recorder.CHUNK_RADIUS;
        double x0 = (c.x - r) * 16, z0 = (c.z - r) * 16, x1 = (c.x + r + 1) * 16, z1 = (c.z + r + 1) * 16;
        double y = Math.floor(mc.player.getY());
        LevelRenderer.renderLineBox(poseStack, lines, new AABB(x0, y - 2, z0, x1, y + 6, z1), 1f, 0.2f, 0.2f, 1f);
        PoseStack.Pose pose = poseStack.last();
        for (double x = x0; x <= x1; x += 16)
            for (double z = z0; z <= z1; z += 16) {
                if (x != x0 && x != x1 && z != z0 && z != z1) continue; // posts on the edge only
                line(pose, lines, new Vec3(x, y - 2, z), new Vec3(x, y + 6, z), 0xFFFF3333);
            }
    }
}
