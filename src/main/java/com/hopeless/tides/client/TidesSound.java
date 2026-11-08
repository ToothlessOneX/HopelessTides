package com.hopeless.tides.client;

import com.hopeless.tides.Tides;
import com.hopeless.tides.TidesSounds;
import com.hopeless.tides.core.AudioPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * 0.8.0: THE WATER'S SOUND. Hopeless Hydro 0.10.0's water audio (Revale's, Hopeless Water visual8/9) in Minecraft: an open-water bed
 * and a shore bed, crossfaded from 24 probes (8 directions x 10/20/30 m) twice a second, and wading steps every 1.25 m in shallow water.
 * Policy: core.AudioPolicy (the beds follow the wave height). Probes read the water map TidesLook already keeps round the camera
 * (the water surface nearest the camera's level per column), so they cost no block reads. Minecraft keeps its own under-water and
 * splash sounds. Beds play on the ambient volume slider, steps on the players slider. Probes: scans, steps, last gains.
 */
public final class TidesSound {
    private TidesSound() {}
    public static volatile long scans, steps; public static volatile float lastWater, lastShore;
    static final AudioPolicy model = new AudioPolicy();
    static Bed ocean, shore; static float nearWater, nearShore; static int tick;

    static final class Bed extends AbstractTickableSoundInstance {
        float target;
        Bed(SoundEvent e) { super(e, SoundSource.AMBIENT, SoundInstance.createUnseededRandom()); looping = true; delay = 0; volume = 0.0001f; relative = true; attenuation = Attenuation.NONE; x = y = z = 0; }
        @Override public void tick() { volume = Math.max(0.0001f, target); }
        @Override public boolean canStartSilent() { return true; }
        void finish() { stop(); }
    }

    static int surfY(int k, int cy) { int sy = TidesLook.SURFY[k] & 255, d = ((sy - (cy & 255)) % 256 + 256) % 256; if (d >= 128) d -= 256; return cy + d; }
    /** Hydro WaterAudio.Scan, on the water map. */
    static void scan(Vec3 eye) {
        nearWater = nearShore = 0; scans++;
        if (TidesLook.wox == Integer.MIN_VALUE) return;
        int cy = (int) Math.floor(eye.y);
        int ci = (int) Math.floor(eye.x) - TidesLook.wox, cj = (int) Math.floor(eye.z) - TidesLook.woz;
        if (ci < 0 || cj < 0 || ci >= TidesLook.WS || cj >= TidesLook.WS) return;
        int ck = cj * TidesLook.WS + ci; boolean center = TidesLook.HASW[ck] != 0;
        double centerDepth = center ? Math.abs(eye.y - (surfY(ck, cy) + 1)) : Double.NaN;
        if (center) nearWater = (float) Math.max(0, 1 - centerDepth / 60);
        for (double radius : AudioPolicy.RADII) for (int dir = 0; dir < AudioPolicy.DIRS; dir++) {
            double a = dir * Math.PI / 4; int i = (int) Math.floor(eye.x + Math.cos(a) * radius) - TidesLook.wox, j = (int) Math.floor(eye.z + Math.sin(a) * radius) - TidesLook.woz;
            if (i < 0 || j < 0 || i >= TidesLook.WS || j >= TidesLook.WS) continue;
            int k = j * TidesLook.WS + i; boolean wet = TidesLook.HASW[k] != 0;
            double altitude = center ? centerDepth : wet ? Math.abs(eye.y - (surfY(k, cy) + 1)) : Double.NaN;
            float g = AudioPolicy.probeGain(radius, altitude); if (g <= 0) continue;
            if (wet) nearWater = Math.max(nearWater, g);
            if (wet != center) nearShore = Math.max(nearShore, g);
        }
        lastWater = nearWater; lastShore = nearShore;
    }
    static double feetDepth(ClientLevel l, net.minecraft.world.entity.player.Player p) {
        BlockPos b = BlockPos.containing(p.getX(), p.getY(), p.getZ()); double top = Double.NaN;
        for (int k = 0; k < 3; k++) { BlockPos q = b.above(k); FluidState f = l.getFluidState(q); if (!f.is(FluidTags.WATER)) break; top = q.getY() + f.getHeight(l, q); }
        return Double.isNaN(top) ? -1 : top - p.getY();
    }
    static void tick(ClientLevel l, Minecraft mc) {
        var p = mc.player; if (p == null) { stop(); return; }
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        if (tick++ % 10 == 0) scan(eye);
        double sea = Tides.seaState(l, BlockPos.containing(eye));
        double speed = Math.hypot(p.getX() - p.xo, p.getZ() - p.zo) * 20;
        model.tick(0.05, p.isEyeInFluid(FluidTags.WATER) ? 1 : 0, nearWater, nearShore, sea, feetDepth(l, p), speed, p.onGround());
        float scale = AudioPolicy.budget(model.ocean + model.shore);
        var sm = mc.getSoundManager();
        if (ocean == null || !sm.isActive(ocean)) { ocean = new Bed(TidesSounds.OCEAN.get()); sm.play(ocean); }
        if (shore == null || !sm.isActive(shore)) { shore = new Bed(TidesSounds.SHORE.get()); sm.play(shore); }
        ocean.target = model.ocean * scale; shore.target = model.shore * scale;
        if (model.step) { l.playLocalSound(p.getX(), p.getY(), p.getZ(), TidesSounds.WADE.get(), SoundSource.PLAYERS, 0.6f * scale, 0.9f + 0.2f * l.random.nextFloat(), false); steps++; }
    }
    public static void stop() { if (ocean != null) ocean.finish(); if (shore != null) shore.finish(); ocean = shore = null; }
    public static String probe() { return String.format("sound: scans %d, near water %.2f, shore %.2f, beds ocean %.2f shore %.2f, steps %d", scans, lastWater, lastShore, model.ocean, model.shore, steps); }
}
