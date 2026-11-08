package com.hopeless.tides.mixin;

import com.hopeless.tides.Tides;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Boats ride the wave surface. Vanilla floats a boat on the top of the water blocks under it (waterLevel); here that level is the wave
 * surface, which can be above the blocks (a crest) or below (a trough), so the boat rises, falls and pitches with the waves. The tilt
 * is kept for the renderer (BoatTilt). Runtime (SRG) names in the strings.
 */
@Mixin(value = Boat.class, remap = false)
public abstract class BoatMixin implements com.hopeless.tides.BoatTilt {
    @Shadow private double f_38277_;   // waterLevel
    private float tides$pitch, tides$roll, tides$pitchO, tides$rollO;
    public float tides$pitch(float pt) { return tides$pitchO + (tides$pitch - tides$pitchO) * pt; }
    public float tides$roll(float pt) { return tides$rollO + (tides$roll - tides$rollO) * pt; }

    private double tides$surface = Double.NaN, tides$dhdt;
    /** Not "under water" while the boat is near the wave surface: a crest washing over it should lift it, not sink it. */
    @Inject(method = "m_38394_", at = @At("RETURN"), cancellable = true)   // isUnderwater
    private void tides$underwater(CallbackInfoReturnable<Object> cir) {
        Boat b = (Boat) (Object) this;
        if (cir.getReturnValue() != null && !Double.isNaN(tides$surface) && b.getBoundingBox().minY > tides$surface - 1.2) cir.setReturnValue(null);
    }
    /** After vanilla's own float: a spring that keeps the boat at its floating depth on the moving surface. */
    @Inject(method = "m_8119_", at = @At("TAIL"))   // tick
    private void tides$ride(CallbackInfo ci) {
        Boat b = (Boat) (Object) this;
        if (Double.isNaN(tides$surface) || b.isVehicle() && b.level().isClientSide != (b.getControllingPassenger() instanceof net.minecraft.world.entity.player.Player)) return;
        double target = tides$surface - DRAFT, dy = target - b.getY();
        if (Math.abs(dy) > 2.5) return;
        Vec3 v = b.getDeltaMovement(); b.setDeltaMovement(v.x, v.y + dy * 0.10 + (tides$dhdt / 20.0 - v.y) * 0.25, v.z);
    }
    private static final double DRAFT = 0.37;

    @Inject(method = "m_38393_", at = @At("RETURN"), cancellable = true)   // checkInWater
    private void tides$checkInWater(CallbackInfoReturnable<Boolean> cir) {
        Boat b = (Boat) (Object) this;
        double minY = b.getBoundingBox().minY;
        BlockPos top = Tides.surfaceBlock(b.level(), (int) Math.floor(b.getX()), (int) Math.floor(minY), (int) Math.floor(b.getZ()), 1, 3);
        if (top == null) { tides$surface = Double.NaN; return; }
        Tides.Wave w = Tides.wave(b.level(), b.getX(), b.getZ(), top, 0f);
        tides$surface = w.surfaceY; tides$dhdt = w.p.dhdt;
        if (minY < w.surfaceY) { f_38277_ = w.surfaceY; cir.setReturnValue(true); }
        else if (cir.getReturnValueZ()) cir.setReturnValue(false);   // a trough has dropped away under the boat
        // tilt follows the slope of the surface, turned into the boat's frame
        double yaw = Math.toRadians(b.getYRot()), fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double along = w.p.sx * fx + w.p.sz * fz, across = w.p.sx * fz - w.p.sz * fx;
        tides$pitchO = tides$pitch; tides$rollO = tides$roll;
        tides$pitch += ((float) Math.toDegrees(Math.atan(along)) - tides$pitch) * 0.35f;
        tides$roll += ((float) Math.toDegrees(Math.atan(across)) - tides$roll) * 0.35f;
        // the water moves the boat a little with it
        if (minY < w.surfaceY + 0.2) { Vec3 v = b.getDeltaMovement(); b.setDeltaMovement(v.x + w.p.ux * 0.004, v.y, v.z + w.p.uz * 0.004);
            // 0.4.0: a river carries the boat downstream (server: a boat nobody steers; client: the boat its own player steers)
            float[] f = b.level() instanceof net.minecraft.server.level.ServerLevel sl ? (b.isControlledByLocalInstance() ? com.hopeless.tides.Currents.at(sl, (int) Math.floor(b.getX()), (int) Math.floor(b.getZ())) : null)
                : (b.isControlledByLocalInstance() ? com.hopeless.tides.Currents.clientFlow : null);
            if (f != null) { Vec3 u = b.getDeltaMovement(); double k = com.hopeless.tides.Currents.BOAT; b.setDeltaMovement(u.x + (f[0] * f[2] / 20.0 - u.x) * k, u.y, u.z + (f[1] * f[2] / 20.0 - u.z) * k); com.hopeless.tides.Currents.pushes++; } }
    }
}
