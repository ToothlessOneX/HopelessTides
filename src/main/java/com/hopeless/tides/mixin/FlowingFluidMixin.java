package com.hopeless.tides.mixin;

import com.hopeless.tides.HydroSim;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 0.7.0: where the column simulation holds the water (HydroSim), vanilla's spreading of it does not run. */
@Mixin(value = FlowingFluid.class, remap = false)
public abstract class FlowingFluidMixin {
    @Inject(method = "m_6292_", at = @At("HEAD"), cancellable = true, require = 0)   // tick(Level, BlockPos, FluidState)
    private void tides$hydro(Level level, BlockPos pos, FluidState state, CallbackInfo ci) {
        if (state.is(FluidTags.WATER) && HydroSim.owns(level, pos)) ci.cancel();
    }
}
