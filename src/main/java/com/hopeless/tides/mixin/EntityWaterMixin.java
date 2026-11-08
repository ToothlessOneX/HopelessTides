package com.hopeless.tides.mixin;

import com.hopeless.tides.Tides;
import com.hopeless.tides.TidesConfig;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swimmers, mobs and dropped items feel the waves. After vanilla works out how deep in water an entity is (from the water blocks),
 * that depth is measured against the wave surface instead: on a crest it is deeper (it can float above the top of the blocks), in a
 * trough shallower. The moving water pushes it with the wave's surface speed. On a shore where waves break, the run-up and backwash
 * push things on the beach. Runtime (SRG) names in the strings.
 */
@Mixin(value = Entity.class, remap = false)
public abstract class EntityWaterMixin {
    @Shadow protected Object2DoubleMap<TagKey<Fluid>> f_19799_;   // fluidHeight
    @Shadow protected boolean f_19798_;                           // wasTouchingWater

    @Inject(method = "m_20074_", at = @At("RETURN"))   // updateInWaterStateAndDoWaterCurrentPushing
    private void tides$waves(CallbackInfo ci) {
        Entity e = (Entity) (Object) this;
        if (e instanceof Boat || e.isSpectator() || e.isPassenger() || TidesConfig.exempt(e)) return;   // 0.7.1: listed entities keep their own water physics
        Level l = e.level();
        int x = (int) Math.floor(e.getX()), z = (int) Math.floor(e.getZ()), y = (int) Math.floor(e.getY());
        // cheap early out: no water at the feet or just below, and not near a shore
        BlockPos top = Tides.surfaceBlock(l, x, y, z, Math.max(1, (int) Math.ceil(e.getBbHeight())), 2);
        if (top != null) {
            Tides.Wave w = Tides.wave(l, e.getX(), e.getZ(), top, 0f);
            double depthIn = w.surfaceY - e.getY();
            // 0.16.0: a wave hitting a wall (cliff, sea wall, dock): as the front arrives, the water thrown back carries what is at the wall's
            // foot out to sea and up: toward Kr sqrt(g R2) (the reflected share of the run-up's speed; Seelig & Ahrens 1981, EurOtop 2018)
            double[] wl = com.hopeless.tides.TidesSurf.on ? com.hopeless.tides.TidesSurf.wall(l, e.getX(), e.getZ(), com.hopeless.tides.TidesSurf.seconds(l, 0f)) : null;
            if (wl != null && wl[1] < 2.5 && wl[6] < 0.12 && depthIn > -0.5) { Vec3 v0 = e.getDeltaMovement(); double sp = wl[4] * Math.sqrt(9.81 * Math.max(0, wl[5])) / 20.0;
                double k = e instanceof Player ? 0.12 : 0.2; e.setDeltaMovement(v0.x + (wl[2] * sp - v0.x) * k, v0.y + (0.5 * sp - v0.y) * k * 0.5, v0.z + (wl[3] * sp - v0.z) * k); Tides.wallPushes++; }
            // 0.5.0: a dense item sinks at any depth (vanilla would float it back up): its drop speed is set here, and the water height it
            // reports is kept under vanilla's float limit (0.1) so vanilla does not lift it; vanilla's gravity (-0.04) is allowed for
            if (e instanceof ItemEntity it && depthIn > 0) { com.hopeless.tides.Buoyancy.Kind k = com.hopeless.tides.Buoyancy.of(it.getItem());
                if (k.density() > 1) { Vec3 v = e.getDeltaMovement(); double sink = -Math.min(0.12, 0.08 * (k.density() - 1) / k.resistance());
                    float[] f = com.hopeless.tides.Currents.forEntity(e, x, z); double cx = f == null ? 0 : f[0] * f[2], cz = f == null ? 0 : f[1] * f[2];
                    e.setDeltaMovement(v.x + (cx / 20.0 - v.x) * 0.05, e.onGround() ? v.y : sink + 0.04, v.z + (cz / 20.0 - v.z) * 0.05);
                    f_19799_.put(FluidTags.WATER, 0.05); f_19798_ = true; com.hopeless.tides.Buoyancy.sinking++; Tides.surfaceHits++; return; } }
            if (depthIn > e.getBbHeight() + 1.5 || depthIn < -1.0) return;   // well under water, or well above it: the waves do not reach
            Vec3 v = e.getDeltaMovement();
            // 0.4.0: a river current adds to the waves' own motion (blocks per second)
            float[] f = depthIn > 0 ? com.hopeless.tides.Currents.forEntity(e, x, z) : null; double cx = f == null ? 0 : f[0] * f[2], cz = f == null ? 0 : f[1] * f[2];
            if (f != null) com.hopeless.tides.Currents.pushes++;
            if (e instanceof ItemEntity || e instanceof ExperienceOrb) {
                // small floating things ride the surface: their height is pulled to it, and they move with the water. 0.5.0: by what
                // they are made of (Buoyancy): light things ride high, wood a little lower, metal and stone sink
                com.hopeless.tides.Buoyancy.Kind k = e instanceof ItemEntity it ? com.hopeless.tides.Buoyancy.of(it.getItem()) : com.hopeless.tides.Buoyancy.PLAIN;
                double draft = 0.05 + 0.3 * k.density(), target = w.surfaceY - draft, vy = (target - e.getY()) * 0.25 + w.p.dhdt / 20.0;
                double keep = 1 - k.damping();
                e.setDeltaMovement(v.x + ((w.p.ux + cx) / 20.0 - v.x) * 0.15, v.y * keep + vy * (1 - keep), v.z + ((w.p.uz + cz) / 20.0 - v.z) * 0.15);   // moves with the water
                f_19799_.put(FluidTags.WATER, Math.max(0, depthIn)); f_19798_ = depthIn > 0;
                Tides.surfaceHits++; return;
            }
            if (depthIn > 0) { f_19799_.put(FluidTags.WATER, depthIn); if (!f_19798_) { f_19798_ = true; e.resetFallDistance(); } }
            else if (f_19798_ && depthIn < -0.3) { f_19799_.put(FluidTags.WATER, 0.0); f_19798_ = false; }   // a trough has left it out of the water
            double k = e instanceof Player ? 0.010 : 0.016;                  // the water's motion carries swimmers and mobs
            double kh = e instanceof Player ? 0.06 : 0.10;                    // swimmers and mobs are carried part of the way with the water
            e.setDeltaMovement(v.x + (w.p.ux / 20.0 - v.x) * kh * Math.min(1, depthIn), v.y + (depthIn > 0 ? w.p.dhdt * k * 0.5 : 0), v.z + (w.p.uz / 20.0 - v.z) * kh * Math.min(1, depthIn));
            if (f != null) { double kc = com.hopeless.tides.Currents.SWIM * Math.min(1, depthIn); Vec3 u = e.getDeltaMovement(); e.setDeltaMovement(u.x + cx / 20.0 * kc, u.y, u.z + cz / 20.0 * kc); }   // the current pushes swimmers downstream
            Tides.surfaceHits++;
            return;
        }
        // 0.14.0: the swash (TidesSurf, Hydro 0.11 / Shen & Meyer 1963): on the beach, a sheet that runs up and drains back carries what
        // stands in it: toward the sheet's own speed, by how deep it is (the sheet thins as it drains)
        if (e.onGround() && com.hopeless.tides.TidesSurf.on) { double[] sw = com.hopeless.tides.TidesSurf.swash(l, e.getX(), e.getZ(), com.hopeless.tides.TidesSurf.seconds(l, 0f));
            if (sw != null && sw[0] > 0.02) { Vec3 v = e.getDeltaMovement(); double k = (e instanceof Player ? 0.08 : 0.15) * sw[0], ux = sw[2] * sw[1] / 20.0, uz = sw[3] * sw[1] / 20.0;
                e.setDeltaMovement(v.x + (ux - v.x) * k, v.y, v.z + (uz - v.z) * k); Tides.swashPushes++; return; }
            if (sw != null) return; }   // inside the surf field: the swash decides (no sheet here now)
        // surf: on the ground beside breaking water (no surf field here)
        if (!e.onGround() || ((e.tickCount + e.getId()) & 3) != 0) return;   // checked every 4th tick
        Tides.surfChecks++;
        for (int q = 0; q < 4; q++) {
            int sx = x + (q == 0 ? 2 : q == 1 ? -2 : 0), sz = z + (q == 2 ? 2 : q == 3 ? -2 : 0);
            BlockPos st = Tides.surfaceBlock(l, sx, y, sz, 0, 2);
            if (st == null) continue;
            Tides.Wave w = Tides.wave(l, sx + 0.5, sz + 0.5, st, 0f);
            if (!w.p.breaking || w.p.h < 0.05) continue;
            double dx = x + 0.5 - (sx + 0.5), dz = z + 0.5 - (sz + 0.5), len = Math.sqrt(dx * dx + dz * dz);
            double push = (w.p.dhdt > 0 ? 0.14 : -0.08) * Math.min(1.0, w.p.h * 2);   // run-up landward, backwash seaward
            Vec3 v = e.getDeltaMovement(); e.setDeltaMovement(v.x + dx / len * push, v.y, v.z + dz / len * push);
            Tides.surfPushes++; return;
        }
    }
}
