package net.kroia.ainpc;

import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.kroia.ainpc.replay.ReplayCommand;
import net.kroia.ainpc.replay.Theater;
import net.kroia.ainpc.sensor.SensorGizmos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common (both sides) entry point. */
public final class AiNpc {
    public static final String MOD_ID = "ainpc";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private AiNpc() {
    }

    public static void init() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> ReplayCommand.register(dispatcher));
        Theater.register();
        net.kroia.ainpc.npc.Brain.register();
        net.kroia.ainpc.replay.RecordingUpload.register();
        net.kroia.ainpc.replay.VisionSetting.register();
        // On a client the receiver registration (AiNpcClient) also registers the payload type.
        if (Platform.getEnvironment() == Env.SERVER) {
            NetworkManager.registerS2CPayloadType(SensorGizmos.TYPE, SensorGizmos.CODEC);
            NetworkManager.registerS2CPayloadType(net.kroia.ainpc.replay.VisionSetting.Sync.TYPE, net.kroia.ainpc.replay.VisionSetting.Sync.CODEC);
        }
    }
}
