package com.hopeless.tides.client;

import com.hopeless.tides.Tides;
import com.hopeless.tides.TidesSurf;
import com.hopeless.tides.core.FftSea;
import com.hopeless.tides.core.ShoreMap;
import com.hopeless.tides.core.Surf;
import com.hopeless.tides.core.WaveModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 0.17.0: the shore map on the client (core/ShoreMap; tides_surf.glsl tides_shoreCorner): the water depth of every column N x N round the
 * camera, read from the client's world a few thousand columns a tick (nearest first), solved on a worker when something changed, uploaded
 * to texture unit UNIT. Every wave layer reads it, so no wave runs through land. Probes: columns read, builds, build ms, uploads, frames.
 * /tides shore on|off.
 */
public final class TidesShore {
    private TidesShore() {}
    public static final int N = ShoreMap.N_GAME, UNIT = 10, PER_TICK = 2048, MAX_DEPTH = 48, RECENTRE = 64;
    public static volatile long columns, changed, builds, buildErrors, uploads, framesOn, framesOff; public static volatile long lastMs; public static volatile String last = "none";
    static final float[] DEPTH = new float[N * N]; static int ox = Integer.MIN_VALUE, oz; static int at; static boolean dirty; static int[] order;
    static volatile ShoreMap ready; static volatile int readyOx, readyOz; static volatile boolean busy; static long lastBuildTick = -1_000_000L;   // 0.17.1: was Long.MIN_VALUE, and now - MIN_VALUE overflowed negative: the map never built (the in-game probe showed builds 0)
    static double bTp, bSw, bWd, bHs, bWh; static int bOx = Integer.MIN_VALUE, bOz;
    static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Tides shore"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY + 1); return t; });
    static { java.util.Arrays.fill(DEPTH, Float.NaN); }

    /** Client tick: keep the map centred, read columns, start a build when needed. */
    public static void tick(ClientLevel l, Vec3 cam) {
        if (!ShoreMap.on || !l.dimensionType().hasSkyLight()) return;
        int nx = ((int) Math.floor(cam.x) >> 4 << 4) - N / 2, nz = ((int) Math.floor(cam.z) >> 4 << 4) - N / 2;
        if (ox == Integer.MIN_VALUE || Math.abs(nx - ox) > RECENTRE || Math.abs(nz - oz) > RECENTRE) recentre(nx, nz);
        if (order == null) order = nearestFirst();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int k = 0; k < PER_TICK; k++) { int c = order[at]; at = (at + 1) % (N * N);
            int x = ox + c % N, z = oz + c / N; float d;
            if (!l.hasChunk(x >> 4, z >> 4)) d = Float.NaN;
            else { int top = l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1; int cnt = 0;
                while (cnt < MAX_DEPTH && l.getFluidState(m.set(x, top - cnt, z)).is(FluidTags.WATER)) cnt++;
                d = cnt == 0 ? 0f : cnt >= MAX_DEPTH ? Float.NaN : cnt - 0.11f; }   // depth from the surface (8/9 of the top block) to the bed
            float o = DEPTH[c]; if (!(o == d || (Float.isNaN(o) && Float.isNaN(d)))) { DEPTH[c] = d; dirty = true; changed++; }
            columns++; }
        // the sea to draw for (as TidesSurf.tick): the swell's period when there is a swell, the wind sea's otherwise
        TidesSurf.Host h = TidesSurf.at(l, cam.x, cam.z);
        double s = Tides.skyState(l), g = Tides.gravity(l), u = FftSea.u10(s), fetch = h == null ? FftSea.FETCH : FftSea.quantFetch(h.fetch);
        double hw = FftSea.hsFullCached(u, g, fetch); double[] p = FftSea.parameters(u, fetch, g); double tw = 2 * Math.PI / p[1], wd = WaveModel.wind(l.getDayTime());
        double hsw = h == null || !TidesSurf.trainOn ? 0 : h.swellHs, tp = hsw > 0 ? h.swellTp : tw, sw = h == null ? wd : h.swellDir;
        long now = l.getGameTime();
        boolean turned = Math.abs(tp - bTp) > 0.05 * bTp || ang(sw, bSw) > 0.1 || ang(wd, bWd) > 0.1 || Math.abs(hsw - bHs) > 0.1 * Math.max(0.05, bHs) || Math.abs(hw - bWh) > 0.1 * Math.max(0.05, bWh);
        boolean moved = ox != bOx || oz != bOz;
        if (!due(now, lastBuildTick, busy, dirty, turned, moved)) return;
        busy = true; lastBuildTick = now; dirty = false; bTp = tp; bSw = sw; bWd = wd; bHs = hsw; bWh = hw; bOx = ox; bOz = oz;
        float[] snap = DEPTH.clone(); int sx = ox, sz = oz; double ftp = tp, fsw = sw, fhs = hsw, fwd = wd, fu = u, fhw = hw, ffe = fetch, fg = g;
        WORKER.submit(() -> { try { ShoreMap sm = ShoreMap.build(snap, N, fg, ftp, fsw, fhs, fwd, fu, fhw, ffe); readyOx = sx; readyOz = sz; ready = sm; builds++; lastMs = sm.ms; last = sm.describe(); }
            catch (Throwable t) { buildErrors++; if (buildErrors < 3) com.hopeless.tides.HopelessTides.LOG.error("[Hopeless Tides] shore map failed", t); } finally { busy = false; } });
    }
    /** 0.17.2: when a build may start (pure, tested by tools/ShoreDueTest): not busy, something changed, 40 ticks since the last; overflow-safe. */
    public static boolean due(long now, long last, boolean busy, boolean dirty, boolean turned, boolean moved) { return !busy && (dirty || turned || moved) && (last == Long.MIN_VALUE || now - last >= 40 || now < last); }
    static double ang(double a, double b) { return Math.abs(Math.atan2(Math.sin(a - b), Math.cos(a - b))); }
    static void recentre(int nx, int nz) {
        float[] d2 = new float[N * N]; java.util.Arrays.fill(d2, Float.NaN);
        if (ox != Integer.MIN_VALUE) for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) { int oi = i + nx - ox, oj = j + nz - oz; if (oi >= 0 && oj >= 0 && oi < N && oj < N) d2[i + N * j] = DEPTH[oi + N * oj]; }
        System.arraycopy(d2, 0, DEPTH, 0, d2.length); ox = nx; oz = nz; at = 0; dirty = true;
    }
    static int[] nearestFirst() { Integer[] o = new Integer[N * N]; for (int c = 0; c < N * N; c++) o[c] = c; double hc = N / 2.0;
        java.util.Arrays.sort(o, (a, b) -> Double.compare(Math.hypot(a % N - hc, a / N - hc), Math.hypot(b % N - hc, b / N - hc))); int[] r = new int[N * N]; for (int c = 0; c < N * N; c++) r[c] = o[c]; return r; }

    static int tex; static FloatBuffer buf; static ShoreMap uploaded; static int upOx, upOz;
    static final Map<Integer, int[]> LOC = new HashMap<>();
    /** On the bound terrain program: upload a new map, bind it, set its uniforms (off: u_TidesShT.z = 0). */
    public static void setUniforms(int prog, ClientLevel l, float pt) {
        int[] loc = LOC.computeIfAbsent(prog, p -> new int[]{GL20.glGetUniformLocation(p, "u_TidesShore"), GL20.glGetUniformLocation(p, "u_TidesShO"), GL20.glGetUniformLocation(p, "u_TidesShT")});
        if (loc[2] < 0) return;
        ShoreMap sm = ready;
        if (!ShoreMap.on || sm == null) { GL20.glUniform4f(loc[2], 0, 0, 0, 0); framesOff++; return; }
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE); GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        if (tex == 0) { tex = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            { int[] tgl = TidesGl.begin(); GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, N, 3 * N, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null); TidesGl.end(tgl); }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST); GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST); GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 0); }
        else GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        if (sm != uploaded) {
            if (buf == null) buf = org.lwjgl.BufferUtils.createFloatBuffer(N * N * 4 * 3); buf.clear(); fill(sm, buf); buf.flip();
            { int[] tgl = TidesGl.begin(); GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, N, 3 * N, GL11.GL_RGBA, GL11.GL_FLOAT, buf); TidesGl.end(tgl); }
            uploaded = sm; upOx = readyOx; upOz = readyOz; uploads++; }
        GL13.glActiveTexture(active);
        if (loc[0] >= 0) GL20.glUniform1i(loc[0], UNIT);
        if (loc[1] >= 0) GL20.glUniform4f(loc[1], (float) (upOx - TidesRender.OX), (float) (upOz - TidesRender.OZ), (float) Math.floorMod((long) TidesRender.OX, (long) Surf.FRAME), (float) Math.floorMod((long) TidesRender.OZ, (long) Surf.FRAME));
        GL20.glUniform4f(loc[2], (float) Surf.clock(sm.om, TidesSurf.seconds(l, pt)), (float) sm.L0, 1f, N);
        framesOn++;
    }
    /** The texture's three blocks (tides_surf.glsl's layout; tools/ShoreTest writes the same). */
    public static void fill(ShoreMap s, FloatBuffer b) {
        int n2 = s.n * s.n;
        for (int c = 0; c < n2; c++) { boolean ok = !Float.isNaN(s.phi[c]); b.put(ok ? s.phi[c] : -1).put(s.h[c]).put(s.gx[c]).put(s.gz[c]); }
        for (int c = 0; c < n2; c++) { boolean ok = !Float.isNaN(s.phi[c]); b.put(s.lee[c]).put(s.fk[c]).put(s.kr[c]).put(ok ? 1 : 0); }
        for (int c = 0; c < n2; c++) b.put(s.ar[c]).put(s.ap[c]).put(s.hr[c]).put(0);
    }
    public static String probe() { return "shore map: " + (ShoreMap.on ? "on" : "off") + ", columns read " + columns + " (changed " + changed + "), builds " + builds + " (errors " + buildErrors + ", last " + lastMs + " ms), uploads " + uploads + ", frames on " + framesOn + " off " + framesOff + " | " + last; }
}
