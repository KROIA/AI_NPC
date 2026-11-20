package net.kroia.ainpc.mixin;

import net.kroia.ainpc.client.AiNpcClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every inventory/container click the player makes, for recording container interaction. */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Inject(method = "handleInventoryMouseClick", at = @At("HEAD"))
    private void ainpc$onClick(int containerId, int slotId, int button, ClickType clickType, Player player, CallbackInfo ci) {
        AiNpcClient.onContainerClick(slotId, button, clickType);
    }
}
