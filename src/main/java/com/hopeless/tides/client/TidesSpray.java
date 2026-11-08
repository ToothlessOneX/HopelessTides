package com.hopeless.tides.client;

import com.hopeless.tides.Tides;
import com.hopeless.tides.core.WaveModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * 0.11.0: the water's small particles, client tick, all near the camera:
 *  - DRIPPING: a body that comes out of the water drips for 4 s, less and less;
 *  - RAIN CROWNS: in rain, drops that land on open water throw a tiny crown (2-4 drops), about 1 per block per 3 s in heavy rain
 *    near you (CHOSEN: few, so the rings stay the main show);
 *  - STORM SPRAY: over Beaufort 8 (17.2 m/s) spindrift tears off the crests of the open sea and blows downwind (WMO Beaufort table);
 *  - BOW SPRAY: a boat faster than 4 m/s throws drops sideways at its bow;
 *  - WATERFALLS: where falling water meets a pool, mist and drops at the foot;
 *  - STEAM: where water touches lava, steam rises.
 * Samples random columns round the camera each tick (no block scans). Probes: counts of each.
 */
public final class TidesSpray {
    private TidesSpray() {}
    public static volatile long drips, crowns, spindrift, bow, falls, steam;
    static final Map<Integer, double[]> OUT = new HashMap<>();   // id -> {seconds since it came out}
    static final Map<Integer, Boolean> WAS = new HashMap<>();

    public static void tick(ClientLevel l, Vec3 cam, float rain, double windMs) {
        RandomSource r = l.random;
        // dripping
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Entity e : l.entitiesForRendering()) {
            if (e.position().distanceToSqr(cam) > 24 * 24 || !(e instanceof net.minecraft.world.entity.LivingEntity)) continue;
            int id = e.getId(); seen.add(id); boolean in = e.isInWater(); Boolean was = WAS.put(id, in);
            if (was != null && was && !in) OUT.put(id, new double[]{0});
            double[] o = OUT.get(id); if (o == null) continue;
            if (in || o[0] > 4) { OUT.remove(id); continue; }
            o[0] += 0.05; double rate = 1.2 * (1 - o[0] / 4) * Math.max(0.5, e.getBbWidth() * e.getBbHeight());
            if (r.nextDouble() < rate) { l.addParticle(ParticleTypes.DRIPPING_WATER, e.getX() + (r.nextDouble() - 0.5) * e.getBbWidth(), e.getY() + r.nextDouble() * e.getBbHeight(), e.getZ() + (r.nextDouble() - 0.5) * e.getBbWidth(), 0, 0, 0); drips++; }
        }
        WAS.keySet().retainAll(seen); OUT.keySet().retainAll(seen);
        // random open-water columns round the camera
        int samples = 24; BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        double gale = windMs >= 17.2 ? Math.min(1, (windMs - 17.2) / 6) + 0.2 : 0;
        for (int i = 0; i < samples; i++) {
            int x = (int) Math.floor(cam.x + (r.nextDouble() * 2 - 1) * 16), z = (int) Math.floor(cam.z + (r.nextDouble() * 2 - 1) * 16);
            if (!l.hasChunkAt(new BlockPos(x, 0, z))) continue;
            int top = l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1; if (top < l.getMinBuildHeight()) continue;
            m.set(x, top, z); FluidState fs = l.getFluidState(m);
            if (!fs.is(FluidTags.WATER)) {
                // lava next to water: steam
                if (fs.is(FluidTags.LAVA)) for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos n = m.relative(d); if (l.getFluidState(n).is(FluidTags.WATER) && r.nextInt(3) == 0) {
                    l.addParticle(TidesParticles.MIST.get(), n.getX() + 0.5, n.getY() + 1.0, n.getZ() + 0.5, (r.nextDouble() - 0.5) * 0.02, 0.05 + r.nextDouble() * 0.04, (r.nextDouble() - 0.5) * 0.02); steam++; break; } }
                continue;
            }
            double y = top + fs.getHeight(l, m);
            boolean falling = fs.hasProperty(net.minecraft.world.level.material.FlowingFluid.FALLING) && fs.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING);
            if (falling) {   // the top of a waterfall column: walk down to its foot
                int yy = top; while (yy > top - 64 && l.getFluidState(m.set(x, yy - 1, z)).is(FluidTags.WATER) && l.getFluidState(m).hasProperty(net.minecraft.world.level.material.FlowingFluid.FALLING) && l.getFluidState(m).getValue(net.minecraft.world.level.material.FlowingFluid.FALLING)) yy--;
                double drop = top - yy; if (drop >= 3) { m.set(x, yy - 1, z); if (l.getFluidState(m).is(FluidTags.WATER)) {
                    for (int k = 0; k < 2; k++) l.addParticle(TidesParticles.MIST.get(), x + r.nextDouble(), yy + 0.2, z + r.nextDouble(), (r.nextDouble() - 0.5) * 0.06, 0.02 + 0.01 * Math.min(drop, 20), (r.nextDouble() - 0.5) * 0.06);
                    for (int k = 0; k < 3; k++) l.addParticle(TidesParticles.DROP.get(), x + r.nextDouble(), yy + 0.1, z + r.nextDouble(), (r.nextDouble() - 0.5) * 0.15, 0.1 + 0.15 * r.nextDouble(), (r.nextDouble() - 0.5) * 0.15);
                    falls++; } }
                continue;
            }
            boolean openSky = l.canSeeSky(m.above());
            if (rain > 0.2 && openSky && r.nextDouble() < rain * 0.6) {   // rain crowns
                int n = 2 + r.nextInt(3); double px = x + r.nextDouble(), pz = z + r.nextDouble();
                for (int k = 0; k < n; k++) { double a = r.nextDouble() * 2 * Math.PI, sp = 0.03 + 0.03 * r.nextDouble(); l.addParticle(TidesParticles.DROP.get(), px, y + 0.02, pz, Math.cos(a) * sp, 0.06 + 0.05 * r.nextDouble(), Math.sin(a) * sp); }
                crowns++;
            }
            if (gale > 0 && openSky && r.nextDouble() < gale * 0.5) {   // spindrift off the open sea's crests
                int[] ed = com.hopeless.tides.core.SeaInfo.corner(Tides.nodes(l), x, z, top);
                if (ed != null && ed[0] >= 12 && ed[1] >= 6) {
                    double w = WaveModel.wind(l.getDayTime()), sp = windMs / 20 * 0.35;
                    for (int k = 0; k < 3; k++) l.addParticle(TidesParticles.DROP.get(), x + r.nextDouble(), y + 0.3, z + r.nextDouble(), Math.cos(w) * sp * (0.6 + 0.4 * r.nextDouble()), 0.04 + 0.04 * r.nextDouble(), Math.sin(w) * sp * (0.6 + 0.4 * r.nextDouble()));
                    l.addParticle(TidesParticles.MIST.get(), x + r.nextDouble(), y + 0.4, z + r.nextDouble(), Math.cos(w) * sp, 0.01, Math.sin(w) * sp);
                    spindrift++;
                }
            }
        }
        // bow spray
        for (Entity e : l.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.vehicle.Boat) || e.position().distanceToSqr(cam) > 32 * 32) continue;
            Vec3 v = e.getDeltaMovement(); double sp = v.horizontalDistance() * 20; if (sp < 4) continue;
            Vec3 f = new Vec3(v.x, 0, v.z).normalize(), side = new Vec3(-f.z, 0, f.x); double k = Math.min(1, (sp - 4) / 6);
            int n = 1 + (int) (3 * k);
            for (int i = 0; i < n; i++) { double sgn = r.nextBoolean() ? 1 : -1; Vec3 p = e.position().add(f.scale(0.9)).add(side.scale(sgn * 0.5));
                Vec3 dv = side.scale(sgn * (0.05 + 0.1 * k)).add(f.scale(0.03)); l.addParticle(TidesParticles.DROP.get(), p.x, e.getY() + 0.2, p.z, dv.x, 0.08 + 0.12 * k * r.nextDouble(), dv.z); }
            bow++;
        }
    }
    public static String probe() { return "spray: drips " + drips + " rain crowns " + crowns + " spindrift " + spindrift + " bow " + bow + " waterfalls " + falls + " steam " + steam; }
}
