package com.hopeless.tides.mixin.client;

import com.hopeless.tides.client.TidesLook;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 0.7.0: every block change the client sees; water that leaves a place wets the ground it stood on (wet banks). */
@Mixin(value = ClientLevel.class, remap = false)
public abstract class ClientLevelMixin {
    @Inject(method = "m_7260_", at = @At("HEAD"), require = 0)   // sendBlockUpdated(BlockPos, BlockState old, BlockState new, int)
    private void tides$changed(BlockPos pos, BlockState old, BlockState now, int flags, CallbackInfo ci) { TidesLook.blockChanged(pos, old, now); }
}
