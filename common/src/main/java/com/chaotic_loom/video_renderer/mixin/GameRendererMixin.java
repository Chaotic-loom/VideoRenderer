package com.chaotic_loom.video_renderer.mixin;

import com.chaotic_loom.video_renderer.events.core.RenderEvents;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;applyModelViewMatrix()V",
                    ordinal = 1
            )
    )
    private void beforeProfilerPush(float tickDelta, long l, boolean bl, CallbackInfo ci, @Local GuiGraphics guiGraphics) {
        RenderEvents.RENDER.invoker().invoke(guiGraphics, tickDelta);
    }
}
