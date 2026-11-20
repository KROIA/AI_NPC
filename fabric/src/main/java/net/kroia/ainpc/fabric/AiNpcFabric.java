package net.kroia.ainpc.fabric;

import net.fabricmc.api.ModInitializer;
import net.kroia.ainpc.AiNpc;

public final class AiNpcFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        AiNpc.init();
    }
}
