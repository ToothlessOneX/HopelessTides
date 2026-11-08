package com.hopeless.tides.mixin.client;

import com.hopeless.tides.client.TidesRender;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Embeddium reads its shaders from its own jar: the terrain vertex shader gets the wave model added. */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader", remap = false)
public abstract class ShaderLoaderMixin {
    @Inject(method = "getShaderSource", at = @At("RETURN"), require = 0, cancellable = true)
    private static void tides$patch(ResourceLocation id, CallbackInfoReturnable<String> cir) {
        if (id != null && id.getNamespace().equals("sodium") && id.getPath().equals("blocks/block_layer_opaque.vsh")) cir.setReturnValue(TidesRender.patchVertexShader(cir.getReturnValue()));
        if (id != null && id.getNamespace().equals("sodium") && id.getPath().equals("blocks/block_layer_opaque.fsh")) cir.setReturnValue(TidesRender.patchFragmentShader(cir.getReturnValue()));   // 0.6.0: ripples
    }
}
