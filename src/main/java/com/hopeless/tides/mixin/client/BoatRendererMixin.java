package com.hopeless.tides.mixin.client;

import com.hopeless.tides.BoatTilt;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.BoatRenderer;
import net.minecraft.world.entity.vehicle.Boat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The boat model tilts with the wave slope (after it is turned to face its heading). Runtime (SRG) names in the strings. */
@Mixin(value = BoatRenderer.class, remap = false)
public abstract class BoatRendererMixin {
    @Inject(method = "m_7392_(Lnet/minecraft/world/entity/vehicle/Boat;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;m_252781_(Lorg/joml/Quaternionf;)V", ordinal = 0, shift = At.Shift.AFTER))
    private void tides$tilt(Boat boat, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (!(boat instanceof BoatTilt t)) return;
        pose.mulPose(Axis.XP.rotationDegrees(t.tides$pitch(partial)));
        pose.mulPose(Axis.ZP.rotationDegrees(t.tides$roll(partial)));
    }
}
