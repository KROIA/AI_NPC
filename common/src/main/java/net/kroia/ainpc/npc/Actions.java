package net.kroia.ainpc.npc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

/**
 * What a player (or the network driving the NPC) does in one tick: the inputs of the client, nothing it observes.
 * Look direction is absolute; attacks / uses count the clicks of this tick.
 * hold: the item to have in the main hand (null = empty hand), taken from anywhere in the inventory;
 * slot positions are not part of the action.
 * screen: the open screen ("inventory", a menu id, ...) or null; clicks: container clicks while it is open.
 */
public record Actions(boolean forward, boolean back, boolean left, boolean right, boolean jump, boolean sneak,
                      boolean sprint, boolean attack, boolean use, boolean drop, boolean swapHands,
                      int attacks, int uses, float yRot, float xRot, String hold, String screen, List<Click> clicks) {
    public record Click(int slot, int button, ClickType type) {
    }

    /**
     * From a recorded tick line. inventoryBefore: the recorded slots before this tick, so hold is what was in the
     * selected slot when the tick's clicks happened (after placing the last sapling the hand is empty, but the
     * sapling was held while placing it).
     */
    public static Actions of(JsonObject tick, com.google.gson.JsonArray inventoryBefore) {
        JsonObject a = tick.getAsJsonObject("actions"), k = a.getAsJsonObject("keys"), p = tick.getAsJsonObject("player");
        List<Click> clicks = new ArrayList<>();
        for (JsonElement e : a.getAsJsonArray("clicks")) {
            var c = e.getAsJsonArray();
            clicks.add(new Click(c.get(0).getAsInt(), c.get(1).getAsInt(), ClickType.valueOf(c.get(2).getAsString())));
        }
        return new Actions(key(k, "forward"), key(k, "back"), key(k, "left"), key(k, "right"), key(k, "jump"),
                key(k, "sneak"), key(k, "sprint"), key(k, "attack"), key(k, "use"), key(k, "drop"), key(k, "swapHands"),
                a.get("attacks").getAsInt(), a.get("uses").getAsInt(),
                p.get("rot").getAsJsonArray().get(0).getAsFloat(), p.get("rot").getAsJsonArray().get(1).getAsFloat(),
                inventoryBefore == null ? (p.get("mainHand").isJsonNull() ? null : p.get("mainHand").getAsString())
                        : net.kroia.ainpc.replay.RecordingFormat.held(inventoryBefore, p.get("slot").getAsInt()),
                a.get("screen").isJsonNull() ? null : a.get("screen").getAsString(), clicks);
    }

    private static boolean key(JsonObject keys, String name) {
        return keys.has(name) && keys.get(name).getAsBoolean();
    }
}
