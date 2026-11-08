package com.hopeless.tides.mixin.client;

import com.hopeless.tides.client.TidesRender;
import me.jellysquid.mods.sodium.client.model.quad.ModelQuadView;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import me.jellysquid.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Embeddium's fluid mesher: water-surface vertices carry their corner's exposure and depth in the vertex colour. */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.FluidRenderer", remap = false)
public abstract class FluidRendererMixin {
    @Shadow private int[] quadColors;

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void tides$begin(WorldSlice world, FluidState fluid, BlockPos blockPos, BlockPos offset, ChunkBuildBuffers buffers, CallbackInfo ci) {
        TidesRender.beginFluid(fluid, blockPos);
    }
    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void tides$end(WorldSlice world, FluidState fluid, BlockPos blockPos, BlockPos offset, ChunkBuildBuffers buffers, CallbackInfo ci) {
        TidesRender.FLUID.remove();
    }
    @Inject(method = "writeQuad", at = @At("HEAD"), require = 0)
    private void tides$mark(ChunkModelBuilder builder, Material material, BlockPos offset, ModelQuadView quad, ModelQuadFacing facing, boolean flip, CallbackInfo ci) {
        Object[] f = TidesRender.FLUID.get(); if (f == null) return;
        if (f.length > 1) { for (int i = 0; i < 4; i++) quadColors[i] = TidesRender.markLava(quadColors[i]); TidesRender.lavaVertsMarked += 4; return; }   // 0.12.0 lava
        BlockPos pos = (BlockPos) f[0]; boolean any = false; int flow = com.hopeless.tides.client.FlowClient.code(pos.getX(), pos.getZ());
        for (int i = 0; i < 4; i++) {
            int[] ed = TidesRender.corner(pos, quad.getX(i), quad.getY(i), quad.getZ(i));
            if (ed == null) continue;
            // 0.9.0: the speed class is the corner's (fastest of the 4 columns round it), so every quad meeting at a corner calms its
            // Fourier waves alike and the surface stays closed; the direction stays the block's
            int cx = Math.round(pos.getX() + quad.getX(i)), cz = Math.round(pos.getZ() + quad.getZ(i));
            int vflow = (flow & 0xF0) | com.hopeless.tides.client.FlowClient.cornerSpeed(cx, cz);
            quadColors[i] = TidesRender.encode(quadColors[i], ed[0], ed[1], vflow); TidesRender.vertsMarked++; any = true;
        }
        if (any) TidesRender.quadsMarked++;
    }
}
