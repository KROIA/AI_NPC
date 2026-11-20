package net.kroia.ainpc.mixin;

import net.kroia.ainpc.client.AiNpcClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Counts attack / use / pick-block clicks. Keys are only sampled once per tick, so a quick click
 * (pressed and released between two ticks) would otherwise be lost.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "startAttack", at = @At("HEAD"))
    private void ainpc$attack(CallbackInfoReturnable<Boolean> cir) {
        AiNpcClient.onClick(0);
    }

    @Inject(method = "startUseItem", at = @At("HEAD"))
    private void ainpc$use(CallbackInfo ci) {
        AiNpcClient.onClick(1);
    }

    @Inject(method = "pickBlock", at = @At("HEAD"))
    private void ainpc$pick(CallbackInfo ci) {
        AiNpcClient.onClick(2);
    }
}
