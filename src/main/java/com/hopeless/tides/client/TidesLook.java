package com.hopeless.tides.client;

import com.hopeless.tides.HopelessTides;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.7.0: the client half of Hopeless Hydro's look and body reactions, carried over to Minecraft.
 *  - Sun, moon and sky for the water shader (tides_look.glsl).
 *  - Bodies in the water near the camera (Hydro Ripples/Wake): up to 8, nearest first, with the time since they went in.
 *  - Splashes (Hydro Splash.cs, Star Citizen's four layers, Hydro's numbers): a jet at 1.2x the impact speed within 5 deg, droplets
 *    at 0.8x within 20 deg, a crown of 16, 3 mist puffs; full at 10 m/s, none under 1 m/s.
 *  - Wet banks (Hydro WetBank.cs, Star Citizen's wet-edge colour): ground the water has LEFT is dark at 0.45 and dries in 300 s.
 *    A 128 x 128 map round the camera, uploaded as a texture the terrain shader reads.
 *  - Breath bubbles under water (Hydro VolumeLook's bubbles).
 *  - River sound (Star Citizen's river audio: speed, width, distance): vanilla's water sound at the middle of the flowing water near
 *    the player, louder with more of it and with falls.
 */
public final class TidesLook {
    private TidesLook() {}
    public static volatile long splashes, wetMarked, bodiesSent, sounds, bubbles, wetUploads;   // probes
    public static volatile boolean enabled = true;

    // ------------------------------------------------------------------ sun and sky
    static float[] sun = {0, 1, 0, 1}, sky = {0.5f, 0.7f, 1f, 3f};
    static void sunSky(ClientLevel l, Vec3 cam, float pt, double windMs) {
        float tod = l.getTimeOfDay(pt); double a = tod * 2 * Math.PI;
        double sx = -Math.sin(a), sy = Math.cos(a);
        float dark = l.getSkyDarken(pt);                                   // 1 at a clear noon, 0.2 at night, less in rain
        float day = Math.max(0, (dark - 0.2f) / 0.8f);
        if (sy < -0.05) { sx = -sx; sy = -sy; day = 0.12f * (1 - l.getRainLevel(pt)); }   // the moon, opposite the sun
        double n = Math.hypot(sx, sy); sun[0] = (float) (sx / n); sun[1] = (float) (sy / n); sun[2] = 0.08f; sun[3] = day;
        Vec3 c = l.getSkyColor(cam, pt); sky[0] = (float) c.x; sky[1] = (float) c.y; sky[2] = (float) c.z; sky[3] = (float) windMs;
    }

    // ------------------------------------------------------------------ bodies
    static final Map<Integer, double[]> ENTERED = new HashMap<>();        // entity id -> {time went in (s), in the water now 1/0, was in last tick}
    static final float[] BODY = new float[32], BODYV = new float[32]; static int bodyN;
    static double now() { return System.nanoTime() * 1e-9; }
    static void bodies(ClientLevel l, Vec3 cam) {
        List<Object[]> near = new ArrayList<>(); double t = now();
        for (Entity e : l.entitiesForRendering()) {
            double d = e.position().distanceTo(cam); if (d > 32) continue;
            double[] st = ENTERED.get(e.getId());
            if (st == null || t - st[0] > 4.0 && !e.isInWater()) continue;
            near.add(new Object[]{d, e, st});
        }
        near.sort((x, y) -> Double.compare((double) x[0], (double) y[0]));
        bodyN = Math.min(8, near.size());
        for (int i = 0; i < bodyN; i++) {
            Entity e = (Entity) near.get(i)[1]; double[] st = (double[]) near.get(i)[2]; Vec3 v = e.getDeltaMovement();
            BODY[i * 4] = (float) (e.getX() - TidesRender.OX); BODY[i * 4 + 1] = (float) e.getY(); BODY[i * 4 + 2] = (float) (e.getZ() - TidesRender.OZ); BODY[i * 4 + 3] = Math.max(0.15f, e.getBbWidth() * 0.5f);
            BODYV[i * 4] = (float) (v.x * 20); BODYV[i * 4 + 1] = (float) (v.z * 20); BODYV[i * 4 + 2] = (float) (t - st[0]); BODYV[i * 4 + 3] = e.isInWater() ? 1 : 0;
        }
        bodiesSent += bodyN;
    }

    // ------------------------------------------------------------------ splashes
    static void tickEntities(ClientLevel l, Vec3 cam) {
        double t = now();
        for (Entity e : l.entitiesForRendering()) {
            if (e.position().distanceToSqr(cam) > 64 * 64) continue;
            boolean in = e.isInWater(); double[] st = ENTERED.get(e.getId());
            if (in && (st == null || st[2] < 0.5)) {                       // went in this tick
                double vy = -e.getDeltaMovement().y * 20, vh = e.getDeltaMovement().horizontalDistance() * 20;
                double impact = Math.max(vy, 0) + 0.3 * vh;
                if (st == null) ENTERED.put(e.getId(), st = new double[]{t, 1, 1}); else { st[0] = t; st[1] = 1; }
                if (impact >= 1.0) splash(l, e, impact);
            }
            if (st != null) { st[1] = in ? 1 : 0; st[2] = in ? 1 : 0; }
        }
        ENTERED.entrySet().removeIf(en -> { Entity e = l.getEntity(en.getKey()); return e == null || e.isRemoved() || (en.getValue()[1] < 0.5 && t - en.getValue()[0] > 6); });
    }
    /** Hydro's four layers. Speeds in blocks per tick for the particles (m/s / 20). */
    static long crowns;   // splash crowns laid out (probe)
    static void splash(ClientLevel l, Entity e, double impact) {
        double f = Math.min(1, (impact - 1.0) / 9.0); if (f <= 0) return;
        BlockPos top = surface(l, e.blockPosition()); if (top == null) return;
        double y = top.getY() + l.getFluidState(top).getHeight(l, top) + 0.02, x = e.getX(), z = e.getZ(); var r = l.random;
        int jets = (int) Math.round(4 * f), drops = (int) Math.round(22 * f), crown = 16, mist = 3;
        for (int i = 0; i < jets; i++) { double a = r.nextDouble() * 2 * Math.PI, c = Math.toRadians(5) * r.nextDouble(), sp = 1.2 * impact / 20 * (0.85 + 0.3 * r.nextDouble());
            l.addParticle(TidesParticles.DROP.get(), x, y, z, Math.sin(c) * Math.cos(a) * sp, Math.cos(c) * sp, Math.sin(c) * Math.sin(a) * sp); }
        for (int i = 0; i < drops; i++) { double a = r.nextDouble() * 2 * Math.PI, c = Math.toRadians(20) * Math.sqrt(r.nextDouble()), sp = 0.8 * impact / 20 * (0.5 + 0.5 * r.nextDouble());
            l.addParticle(TidesParticles.DROP.get(), x, y, z, Math.sin(c) * Math.cos(a) * sp, Math.cos(c) * sp, Math.sin(c) * Math.sin(a) * sp); }
        double rad = Math.max(0.3, e.getBbWidth() * 0.6);
        double crown0 = com.hopeless.tides.Credit.crownPhase(e.getId(), ++crowns); for (int i = 0; i < crown; i++) { double a = crown0 + i * 2 * Math.PI / crown + r.nextDouble() * 0.2, sp = (0.06 + 0.1 * f);
            l.addParticle(TidesParticles.DROP.get(), x + Math.cos(a) * rad, y, z + Math.sin(a) * rad, Math.cos(a) * sp * 0.6, sp, Math.sin(a) * sp * 0.6); }
        for (int i = 0; i < mist; i++) l.addParticle(TidesParticles.MIST.get(), x + r.nextGaussian() * 0.3, y + 0.2, z + r.nextGaussian() * 0.3, r.nextGaussian() * 0.01, 0.01 + 0.02 * f, r.nextGaussian() * 0.01);
        // 0.10.0 (Hopeless Worlds Splashes, after Aristoff et al. 2010, Gekle & Gordillo 2010): over Froude 4 (F = U^2 / g R) the body
        // drags an air cavity down; it pinches off 2.2 sqrt(R / g) after the entry and its collapse throws the Worthington jet straight
        // up at 1.5 U (at most 30 m/s; GUESS, as in the plugin), 5 degree cone; the cavity's air rises as bubbles
        double R = Math.max(0.1, e.getBbWidth() * 0.5), F = impact * impact / (com.hopeless.tides.Tides.gravity(l) * R);
        if (F > 4) {
            LATER.add(new double[]{now() + 2.2 * Math.sqrt(R / com.hopeless.tides.Tides.gravity(l)), x, y, z, Math.min(1.5 * impact, 30), R});
            int nb = (int) Math.min(40, 6 + 3 * impact);
            for (int i = 0; i < nb; i++) l.addParticle(net.minecraft.core.particles.ParticleTypes.BUBBLE, x + r.nextGaussian() * R, y - 0.3 - r.nextDouble() * Math.min(3, impact * 0.25), z + r.nextGaussian() * R, 0, 0.02 + 0.04 * r.nextDouble(), 0);
            cavities++;
        }
        splashes++;
        markWet(l, top, 0.6);                                                 // the bank beside the splash gets wet too
    }
    static final java.util.List<double[]> LATER = new java.util.ArrayList<>();   // {when, x, y, z, jet speed m/s, R}
    public static volatile long cavities, worthington;
    /** Throws the Worthington jets whose cavities have pinched off. Client tick. */
    static void jets(ClientLevel l) {
        double t = now(); var r = l.random;
        for (java.util.Iterator<double[]> it = LATER.iterator(); it.hasNext(); ) { double[] j = it.next(); if (j[0] > t) continue; it.remove();
            int n = (int) Math.min(30, 6 + j[4] * 1.5);
            for (int i = 0; i < n; i++) { double a = r.nextDouble() * 2 * Math.PI, c = Math.toRadians(5) * Math.sqrt(r.nextDouble()), sp = j[4] / 20 * (0.6 + 0.4 * r.nextDouble());
                l.addParticle(TidesParticles.DROP.get(), j[1] + r.nextGaussian() * j[5] * 0.2, j[2], j[3] + r.nextGaussian() * j[5] * 0.2, Math.sin(c) * Math.cos(a) * sp, Math.cos(c) * sp, Math.sin(c) * Math.sin(a) * sp); }
            worthington++; }
        if (LATER.size() > 64) LATER.subList(0, LATER.size() - 64).clear();
    }
    static BlockPos surface(ClientLevel l, BlockPos p) {
        for (int dy = 2; dy >= -3; dy--) { BlockPos b = p.offset(0, dy, 0); if (l.getFluidState(b).is(FluidTags.WATER) && !l.getFluidState(b.above()).is(FluidTags.WATER)) return b; }
        return null;
    }

    // ------------------------------------------------------------------ wet banks
    static final int WS = 128; static final float DRY_S = 300f;
    static final float[] WET = new float[WS * WS]; static final byte[] WETY = new byte[WS * WS], SURFY = new byte[WS * WS], HASW = new byte[WS * WS];
    public static volatile long waterScans;
    static int wox = Integer.MIN_VALUE, woz; static int wetTex = -1; static boolean wetDirty; static final ByteBuffer WBUF = BufferUtils.createByteBuffer(WS * WS * 4);
    static void recenter(Vec3 cam) {
        int nx = ((int) Math.floor(cam.x) >> 4 << 4) - WS / 2, nz = ((int) Math.floor(cam.z) >> 4 << 4) - WS / 2;
        if (nx == wox && nz == woz) return;
        float[] w2 = new float[WS * WS]; byte[] y2 = new byte[WS * WS], s2 = new byte[WS * WS], h2 = new byte[WS * WS];
        if (wox != Integer.MIN_VALUE) for (int j = 0; j < WS; j++) for (int i = 0; i < WS; i++) {
            int ox = i + nx - wox, oz = j + nz - woz; if (ox < 0 || oz < 0 || ox >= WS || oz >= WS) continue;
            int a = oz * WS + ox, b = j * WS + i; w2[b] = WET[a]; y2[b] = WETY[a]; s2[b] = SURFY[a]; h2[b] = HASW[a]; }
        System.arraycopy(w2, 0, WET, 0, WET.length); System.arraycopy(y2, 0, WETY, 0, WETY.length);
        System.arraycopy(s2, 0, SURFY, 0, SURFY.length); System.arraycopy(h2, 0, HASW, 0, HASW.length); wox = nx; woz = nz; wetDirty = true;
    }
    /** The ground at or under pos (the block the water stood on) gets wet. */
    static void markWet(ClientLevel l, BlockPos water, double amount) {
        if (wox == Integer.MIN_VALUE) return;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            double a = (dx == 0 && dz == 0) ? amount : amount * 0.5;
            BlockPos c = water.offset(dx, 0, dz); BlockPos g = null;
            for (int dy = 0; dy >= -2; dy--) { BlockPos b = c.offset(0, dy, 0); BlockState s = l.getBlockState(b); if (s.isSolidRender(l, b)) { g = b; break; } }
            if (g == null) continue;
            int i = g.getX() - wox, j = g.getZ() - woz; if (i < 0 || j < 0 || i >= WS || j >= WS) continue;
            int k = j * WS + i; WET[k] = (float) Math.max(WET[k], a); WETY[k] = (byte) (g.getY() & 255); wetMarked++; wetDirty = true;
        }
    }
    /** The water map (0.7.0, for the bed caustics and the shore contact foam): per column, the water surface nearest the camera's
     *  level (from 12 above to 40 below), 768 columns a tick in turn and at once where a block changed. */
    static int scanAt; public static volatile long shoreErrors; static final java.util.ArrayDeque<Integer> SCAN_NOW = new java.util.ArrayDeque<>();
    static void scanWater(ClientLevel l, Vec3 cam) {
        if (wox == Integer.MIN_VALUE) return;
        int cy = (int) Math.floor(cam.y); BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int k = 0; k < 768 + SCAN_NOW.size(); k++) {
            int c; if (!SCAN_NOW.isEmpty()) c = SCAN_NOW.poll(); else { c = scanAt; scanAt = (scanAt + 1) % (WS * WS); }
            int x = wox + c % WS, z = woz + c / WS; byte has = 0, sy = 0; boolean above = false;
            for (int y = cy + 12; y >= cy - 40; y--) {
                boolean w = l.getFluidState(m.set(x, y, z)).is(FluidTags.WATER);
                if (w && !above) { has = (byte) 255; sy = (byte) (y & 255); break; }
                above = w;
            }
            if (HASW[c] != has || SURFY[c] != sy) { HASW[c] = has; SURFY[c] = sy; wetDirty = true; }
            waterScans++;
        }
    }
    /** Called by the client level when a block changes: water gone from here -> the ground it stood on is wet. */
    public static void blockChanged(BlockPos pos, BlockState old, BlockState now) {
        if (!enabled) return;
        try {
            ClientLevel l = Minecraft.getInstance().level; if (l == null) return;
            if (old.getFluidState().is(FluidTags.WATER) && !now.getFluidState().is(FluidTags.WATER)) markWet(l, pos, 1.0);
            if (wox != Integer.MIN_VALUE && old.getFluidState().is(FluidTags.WATER) != now.getFluidState().is(FluidTags.WATER)) { int i = pos.getX() - wox, j = pos.getZ() - woz; if (i >= 0 && j >= 0 && i < WS && j < WS && SCAN_NOW.size() < 4096) SCAN_NOW.add(j * WS + i); }
        } catch (Throwable t) { /* never break block updates */ }
    }
    /** 0.8.0: rain wets the ground (Star Citizen's wet terrain edge; Hydro 0.9.2 rain). Where it is raining on a column right now
     *  (Minecraft's own rule: the sky reaches it and the biome rains there), the top block gets wet by 1/RAIN_S a second up to RAIN_WET
     *  seconds. When the rain stops it dries like any wet bank (DRY_S). 768 columns a tick in turn, so the whole map in about 1 s. */
    public static volatile float rainNow;
    static final float RAIN_WET = 0.7f, RAIN_S = 60f; static int rainAt; public static volatile long rainWetted; static int soundErrors;
    static void rainWet(ClientLevel l) {
        if (wox == Integer.MIN_VALUE || !l.isRaining()) return;
        float add = (WS * WS / 768f) / 20f / RAIN_S; BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int n = 0; n < 768; n++) {
            int c = rainAt; rainAt = (rainAt + 1) % (WS * WS);
            int x = wox + c % WS, z = woz + c / WS;
            if (!l.hasChunkAt(m.set(x, 0, z))) continue;
            int h = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
            if (!l.isRainingAt(m.set(x, h, z))) continue;
            if (!l.getFluidState(m.set(x, h - 1, z)).isEmpty()) continue;          // the water itself is not "wet ground"
            byte gy = (byte) ((h - 1) & 255);
            if (WETY[c] == gy) { if (WET[c] < RAIN_WET) { WET[c] = Math.min(RAIN_WET, WET[c] + add); wetDirty = true; rainWetted++; } }
            else if (WET[c] < 0.2f) { WET[c] = add; WETY[c] = gy; wetDirty = true; rainWetted++; }     // a bank wet at another height keeps it until it dries
        }
    }
    /** 0.8.0: how hard it rains at the camera, 0..1 for the rain rings (Hydro's rain level over 15: level = 15 sqrt(mm/h / 30)).
     *  Minecraft rain 5 mm/h, a thunderstorm 30 mm/h (Hydro's RainLight..ThunderstormHeavy rates, CHOSEN mapping). 0 where the biome
     *  snows or never rains. */
    public static float rainAtCamera(ClientLevel l, Vec3 cam, float pt) {
        BlockPos b = BlockPos.containing(cam);
        if (l.getBiome(b).value().getPrecipitationAt(b) != net.minecraft.world.level.biome.Biome.Precipitation.RAIN) return 0;
        double mm = l.getRainLevel(pt) * (5 + 25 * l.getThunderLevel(pt));
        return (float) (Math.round(15 * Math.sqrt(Math.max(0, Math.min(1, mm / 30)))) / 15.0);
    }
    static void dry(float dt) {
        float d = dt / DRY_S; boolean any = false;
        for (int k = 0; k < WET.length; k++) if (WET[k] > 0) { WET[k] = Math.max(0, WET[k] - d); any = true; }
        if (any) wetDirty = true;
    }
    static void upload() {
        if (wetTex < 0) { wetTex = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, wetTex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR); GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE); GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE); wetDirty = true; }
        else GL11.glBindTexture(GL11.GL_TEXTURE_2D, wetTex);
        if (!wetDirty) return;
        WBUF.clear(); for (int k = 0; k < WET.length; k++) { WBUF.put((byte) Math.round(Math.min(1, WET[k]) * 255)); WBUF.put(WETY[k]); WBUF.put(SURFY[k]); WBUF.put(HASW[k]); } WBUF.flip();
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        { int[] tgl = TidesGl.begin(); GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, WS, WS, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, WBUF); TidesGl.end(tgl); }
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        wetDirty = false; wetUploads++;
    }

    // ------------------------------------------------------------------ bubbles and river sound
    static int ticks;
    static void ambience(ClientLevel l, Minecraft mc) {
        var p = mc.player; if (p == null) return; var r = l.random;
        if (p.isEyeInFluid(FluidTags.WATER) && ticks % 50 == 0 && r.nextFloat() < 0.8f) {     // breath: a few bubbles every ~2.5 s
            Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
            int n = 3 + r.nextInt(4);
            for (int i = 0; i < n; i++) l.addParticle(net.minecraft.core.particles.ParticleTypes.BUBBLE, eye.x + look.x * 0.3 + r.nextGaussian() * 0.05, eye.y - 0.1, eye.z + look.z * 0.3 + r.nextGaussian() * 0.05, r.nextGaussian() * 0.01, 0.06 + r.nextFloat() * 0.04, r.nextGaussian() * 0.01);
            bubbles += n;
        }
        if (ticks % 40 != 7) return;
        // flowing water round the player: how much, how much of it falls, and where its middle is
        BlockPos c = p.blockPosition(); int flow = 0, falls = 0; double mx = 0, my = 0, mz = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -16; dx <= 16; dx += 2) for (int dz = -16; dz <= 16; dz += 2) for (int dy = -6; dy <= 6; dy += 2) {
            m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz); FluidState fs = l.getFluidState(m);
            if (!fs.is(FluidTags.WATER) || fs.isSource()) continue;
            flow++; if (fs.hasProperty(FlowingFluid.FALLING) && fs.getValue(FlowingFluid.FALLING)) falls++;
            mx += m.getX(); my += m.getY(); mz += m.getZ();
        }
        if (flow < 3) return;
        mx /= flow; my /= flow; mz /= flow;
        float vol = (float) Math.min(1.0, 0.15 + flow / 60.0 + falls / 20.0), pitch = (float) (falls > flow / 4 ? 0.7 : 0.9 + 0.2 * r.nextFloat());
        l.playLocalSound(mx + 0.5, my + 0.5, mz + 0.5, SoundEvents.WATER_AMBIENT, SoundSource.AMBIENT, vol, pitch, false);
        sounds++;
    }

    // ------------------------------------------------------------------ hooks
    static double lastDry = now();
    public static volatile long washFrames;
    /** Revale's underwater wash (Hopeless Water visual5, owner-accepted): under water the view takes a blue-green wash of at most 0.22,
     *  coming in between 0.03 and 0.48 m below the surface, dimmed at night so it is not an equally bright overlay. Here it is blended
     *  into vanilla's underwater fog colour. */
    static void wash(net.minecraftforge.client.event.ViewportEvent.ComputeFogColor e) {
        if (!enabled || e.getCamera().getFluidInCamera() != net.minecraft.world.level.material.FogType.WATER) return;
        ClientLevel l = Minecraft.getInstance().level; if (l == null) return;
        Vec3 c = e.getCamera().getPosition(); BlockPos p = BlockPos.containing(c); double depth = 0;
        for (int k = 0; k < 8; k++) { BlockPos b = p.above(k); FluidState f = l.getFluidState(b); if (!f.is(FluidTags.WATER)) break; depth = b.getY() + f.getHeight(l, b) - c.y; }
        double a = 0.22 * smooth((depth - 0.03) / 0.45);
        float day = Math.max(0.15f, (l.getSkyDarken((float) e.getPartialTick()) - 0.2f) / 0.8f);
        float r = 0.05f * day, g = 0.32f * day, b = 0.36f * day;
        if (TidesOptics.on && TidesOptics.builds > 0) {   // 0.15.0: the fog takes the water's own hue (the colour under water follows the surface's: owner, 2026-10-31)
            double[] hu = com.hopeless.tides.core.WaterOptics.hue(TidesOptics.cameraType); double lv = 0.2126 * e.getRed() + 0.7152 * e.getGreen() + 0.0722 * e.getBlue(), lh = com.hopeless.tides.core.WaterOptics.lum(hu);
            double k = Math.max(lv, 0.02) / Math.max(lh, 1e-3); e.setRed((float) Math.min(1, hu[0] * k)); e.setGreen((float) Math.min(1, hu[1] * k)); e.setBlue((float) Math.min(1, hu[2] * k)); fogColours++; }
        e.setRed((float) (e.getRed() * (1 - a) + r * a)); e.setGreen((float) (e.getGreen() * (1 - a) + g * a)); e.setBlue((float) (e.getBlue() * (1 - a) + b * a));
        washFrames++;
    }
    static double smooth(double x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }
    public static volatile long fogColours, fogSights;
    /** 0.15.0: how far one sees under water: the played sight of the water's type (core/WaterOptics: 500 m by the owner's choice, or the
     *  published records), never past the render distance. */
    static void fog(net.minecraftforge.client.event.ViewportEvent.RenderFog e) {
        if (!enabled || !TidesOptics.on || e.getType() != net.minecraft.world.level.material.FogType.WATER) return;
        double rd = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0, sight = Math.min(rd, com.hopeless.tides.core.WaterOptics.playedSight(TidesOptics.cameraType));
        e.setNearPlaneDistance(-8f); e.setFarPlaneDistance((float) sight); e.setCanceled(true); fogSights++;
    }
    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(TidesLook::wash);
        MinecraftForge.EVENT_BUS.addListener(TidesLook::fog);   // 0.15.0
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
            if (e.phase != TickEvent.Phase.END) return;
            if (!enabled) { TidesSound.stop(); return; }                      // 0.8.0: the beds go quiet with the look
            Minecraft mc = Minecraft.getInstance(); ClientLevel l = mc.level; if (l == null || mc.player == null) { TidesSound.stop(); return; }
            try {
                ticks++; Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
                tickEntities(l, cam); jets(l); try { TidesSpray.tick(l, cam, rainNow, 3 + 9 * l.getRainLevel(1f) + 6 * l.getThunderLevel(1f)); } catch (Throwable sp) { sprayErrors++; } try { TidesLavaMap.tick(l, cam); } catch (Throwable lv) { lavaErrors++; } try { TidesShore.tick(l, cam); } catch (Throwable sh) { shoreErrors++; if (shoreErrors < 3) HopelessTides.LOG.error("[Hopeless Tides] shore map tick failed", sh); } try { TidesRipples.tick(l, cam); } catch (Throwable rt) { rippleErrors++; } try { com.hopeless.tides.TidesSurf.tick(l, "camera", cam.x, cam.z); } catch (Throwable st) { com.hopeless.tides.TidesSurf.errors++; } try { TidesOptics.tick(l, cam); } catch (Throwable ot) { opticErrors++; } try { TidesSurfSpray.tick(l, cam); } catch (Throwable sp2) { opticErrors++; } recenter(cam); scanWater(l, cam); rainWet(l); ambience(l, mc);
                try { TidesSound.tick(l, mc); } catch (Throwable st) { soundErrors++; if (soundErrors < 3) HopelessTides.LOG.error("[Hopeless Tides] water sound failed", st); }
                double t = now(); if (t - lastDry >= 1.0) { dry((float) (t - lastDry)); lastDry = t; }
            } catch (Throwable t) { if (ticks < 5 || ticks % 6000 == 0) HopelessTides.LOG.error("[Hopeless Tides] look tick failed", t); }
        });
    }
    /** Sets the look uniforms on the bound terrain program (locations cached per program). Called from TidesRender.setUniforms. */
    static final Map<Integer, int[]> LOC = new HashMap<>();
    public static volatile long rippleErrors, sprayErrors, lavaErrors, opticErrors;
    static void setUniforms(int prog, ClientLevel l, Vec3 cam, float pt, double windMs) {
        TidesRipples.setUniforms(prog);   // 0.10.0
        TidesLavaMap.setUniforms(prog);   // 0.12.0
        int[] loc = LOC.computeIfAbsent(prog, p -> new int[]{GL20.glGetUniformLocation(p, "u_TidesSun"), GL20.glGetUniformLocation(p, "u_TidesSky"), GL20.glGetUniformLocation(p, "u_TidesBody"),
            GL20.glGetUniformLocation(p, "u_TidesBodyV"), GL20.glGetUniformLocation(p, "u_TidesBodyN"), GL20.glGetUniformLocation(p, "u_TidesWet"), GL20.glGetUniformLocation(p, "u_TidesWetOrigin"), GL20.glGetUniformLocation(p, "u_TidesFoam"), GL20.glGetUniformLocation(p, "u_TidesRain")});
        if (loc[0] < 0) return;
        sunSky(l, cam, pt, windMs); bodies(l, cam);
        GL20.glUniform4f(loc[0], sun[0], sun[1], sun[2], sun[3]);
        GL20.glUniform4f(loc[1], sky[0], sky[1], sky[2], sky[3]);
        if (loc[2] >= 0) GL20.glUniform4fv(loc[2], BODY);
        if (loc[3] >= 0) GL20.glUniform4fv(loc[3], BODYV);
        if (loc[4] >= 0) GL20.glUniform1i(loc[4], bodyN);
        if (loc.length > 8 && loc[8] >= 0) { rainNow = enabled ? rainAtCamera(l, cam, pt) : 0; GL20.glUniform4f(loc[8], rainNow, 0, 0, 0); }   // 0.8.0
        if (loc[5] >= 0 && loc[6] >= 0) {
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE), bound;
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 7); bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            upload();                                                          // binds the wet map on unit 7
            GL20.glUniform1i(loc[5], 7);
            if (loc[7] >= 0) { GL13.glActiveTexture(GL13.GL_TEXTURE0 + 6); foam(); GL20.glUniform1i(loc[7], 6); }
            GL13.glActiveTexture(active);
            GL20.glUniform4f(loc[6], (float) (wox - TidesRender.OX), (float) (woz - TidesRender.OZ), WS, enabled && wox != Integer.MIN_VALUE ? 1 : 0);
            if (bound != wetTex && bound != 0) { /* unit 7 was someone else's: leave ours bound while terrain draws; nothing else samples unit 7 in the terrain pass */ }
        }
    }
    /** Revale's sea-foam texture (Hopeless Water visual6/7: original artwork for the project, 512 x 512, coverage in alpha), loaded
     *  once from the jar, mipmapped, bound on unit 6. */
    static int foamTex = -1; public static volatile String foamState = "not loaded";
    static void foam() {
        if (foamTex >= 0) { GL11.glBindTexture(GL11.GL_TEXTURE_2D, foamTex); return; }
        foamTex = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, foamTex);
        try (var in = TidesLook.class.getResourceAsStream("/assets/hopelesstides/textures/foam.png")) {
            var img = com.mojang.blaze3d.platform.NativeImage.read(in); int w = img.getWidth(), h = img.getHeight();
            ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) { int abgr = img.getPixelRGBA(x, y); buf.put((byte) (abgr & 255)).put((byte) ((abgr >> 8) & 255)).put((byte) ((abgr >> 16) & 255)).put((byte) ((abgr >>> 24) & 255)); }
            buf.flip(); img.close();
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            { int[] tgl = TidesGl.begin(); GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf); TidesGl.end(tgl); }
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR); GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT); GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            foamState = "loaded " + w + "x" + h;
        } catch (Throwable t) { foamState = "failed: " + t; HopelessTides.LOG.error("[Hopeless Tides] foam texture failed", t); }
    }
    public static String probe() { return "rain at camera " + rainNow + ", ground wetted by rain " + rainWetted + " | " + TidesSound.probe() + " | look: underwater wash frames " + washFrames + " foam " + foamState + " water scans " + waterScans + " | " + FlowClient.probe() + " | splashes " + splashes + " wet marked " + wetMarked + " wet uploads " + wetUploads + " bodies sent " + bodiesSent + " river sounds " + sounds + " bubbles " + bubbles + " cavities " + cavities + " worthington jets " + worthington + " ripple errors " + rippleErrors + " | " + TidesRipples.probe() + " | under frames " + TidesRender.underFrames + " | " + TidesLens.probe() + " | " + TidesSpray.probe() + " spray errors " + sprayErrors + " | " + TidesLavaMap.probe() + " lava errors " + lavaErrors + " | " + TidesOptics.probe() + " errors " + opticErrors + " | " + TidesSurfSpray.probe() + ", fog colours " + fogColours + " sights " + fogSights; }
}
