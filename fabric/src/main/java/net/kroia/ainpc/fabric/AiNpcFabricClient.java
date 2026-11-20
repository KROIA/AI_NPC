package net.kroia.ainpc.fabric;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.kroia.ainpc.client.AiNpcClient;
import net.kroia.ainpc.client.SensorDebugRenderer;

public final class AiNpcFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AiNpcClient.init();
        // Before vanilla's debug renderers, which come before the translucent layer: water is drawn over the
        // gizmos (they show through it) instead of hiding everything below its surface. The camera rotation is
        // on RenderSystem's model-view stack there, so only translation is ours.
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(ctx -> SensorDebugRenderer.render(new PoseStack(), ctx.camera().getPosition()));
    }
}
