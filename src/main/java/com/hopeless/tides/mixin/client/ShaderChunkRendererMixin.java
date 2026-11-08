package com.hopeless.tides.mixin.client;

import com.hopeless.tides.client.TidesRender;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After Embeddium binds its terrain program for a pass, set the wave uniforms on it. */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.ShaderChunkRenderer", remap = false)
public abstract class ShaderChunkRendererMixin {
    @Inject(method = "begin", at = @At("TAIL"), require = 0)
    private void tides$uniforms(TerrainRenderPass pass, CallbackInfo ci) { TidesRender.setUniforms(); }
}
