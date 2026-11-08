package com.hopeless.tides;

import com.hopeless.tides.core.FftSea;
import com.hopeless.tides.core.FftCorner;
import com.hopeless.tides.core.FftDrive;
import com.hopeless.tides.core.SeaInfo;
import com.hopeless.tides.core.Surface;
import com.hopeless.tides.core.WaveModel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FluidState;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** The waves in a running world: which water surface a spot belongs to, its exposure and depth (cached), the sea state, and the surface. */
public final class Tides {
    private Tides() {}
    public static volatile long samples, nodeMisses, surfaceHits, surfChecks, surfPushes, iced, surfSamples, swashPushes, wallSamples, wallPushes;            // probes
    public static volatile double overrideState = -1;                         // /tides sea (tests): -1 = from the weather
    static final int TTL = 600;                                               // node cache lifetime (ticks): picks up changed shores

    static final class Cache { final ConcurrentHashMap<Long, double[]> nodes = new ConcurrentHashMap<>(); long sweep; }
    static final Map<Level, Cache> CACHES = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    static Cache cache(Level l) { return CACHES.computeIfAbsent(l, k -> new Cache()); }

    public static SeaInfo.Probe probe(Level l) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // 0.4.0: water with ice on it is not open water, so it has no waves (Star Citizen's frozen seas have no wave settings at all)
        return (x, y, z) -> { m.set(x, y, z); if (!l.isLoaded(m) || !l.getFluidState(m).is(FluidTags.WATER)) return false;
            m.set(x, y + 1, z); if (l.getBlockState(m).is(net.minecraft.tags.BlockTags.ICE)) { iced++; return false; } return true; };
    }
    static long key(int nx, int nz, int y) { long k = ((long) nx * 0x9E3779B1L) ^ ((long) nz * 0x85EBCA77L) ^ ((long) y * 0xC2B2AE3DL); return k ^ (k >>> 29); }
    public static SeaInfo.Nodes nodes(Level l) {
        Cache c = cache(l); long now = l.getGameTime(); SeaInfo.Probe p = probe(l);
        if (now - c.sweep > TTL) { c.sweep = now; c.nodes.entrySet().removeIf(e -> now - (long) e.getValue()[2] > TTL); }
        return (nx, nz, y) -> {
            long k = key(nx, nz, y); double[] v = c.nodes.get(k);
            if (v == null || v[3] != nx || v[4] != nz || v[5] != y) { nodeMisses++;
                v = new double[]{SeaInfo.exposureNode(p, nx * SeaInfo.STEP, y, nz * SeaInfo.STEP), SeaInfo.depthNode(p, nx * SeaInfo.STEP, y, nz * SeaInfo.STEP), now, nx, nz, y};
                c.nodes.put(k, v); }
            return v;
        };
    }

    /** The sea state at a surface: the weather where the sky reaches the water, the slow cave cycle where it does not. */
    public static double seaState(Level l, BlockPos surfaceBlock) {
        if (overrideState >= 0) return overrideState;
        boolean sky = l.dimensionType().hasSkyLight() && l.getBrightness(LightLayer.SKY, surfaceBlock.above()) >= 8;
        if (sky) return WaveModel.seaStateWeather(l.getRainLevel(1f), l.getThunderLevel(1f));
        // 0.6.0: in a cave, the sea state comes from the cave wind (CaveWind: falling and moving water, flood pistons); on a client the
        // wind its server sent for this player; with no reading, still air
        double[] w = l.isClientSide ? (caveWindClient == null ? null : new double[]{caveWindClient[0]}) : CaveWind.near(surfaceBlock.getX() + 0.5, surfaceBlock.getY(), surfaceBlock.getZ() + 0.5);
        return WaveModel.caveStateFromWind(w == null ? 0 : w[0]);
    }
    /** 0.6.0: the cave wind the server last sent this client {U m/s, direction rad}, or null under the open sky / none yet. */
    public static volatile float[] caveWindClient;

    /** The top water block of the water at (x, z) near y: if y + up is in water, the top of that water (up to 32 above); otherwise the
     *  first water found going down to y - down, whose top it is. null if none (or not loaded). */
    public static BlockPos surfaceBlock(Level l, int x, int y, int z, int up, int down) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int start = y + up; m.set(x, start, z); if (!l.isLoaded(m)) return null;
        if (l.getFluidState(m).is(FluidTags.WATER)) {
            for (int yy = start + 1; yy <= start + 32; yy++) { m.set(x, yy, z); if (!l.getFluidState(m).is(FluidTags.WATER)) return new BlockPos(x, yy - 1, z); }
            return null;
        }
        for (int yy = start - 1; yy >= y - down; yy--) { m.set(x, yy, z); if (l.getFluidState(m).is(FluidTags.WATER)) return new BlockPos(x, yy, z); }
        return null;
    }

    /** The wave surface over a water block: absolute surface height (y), plus the sample. The Wave returned is reused by the next call
     *  on the same thread: read it, do not keep it. */
    public static final class Wave { public double surfaceY; public final Surface.Point p = new Surface.Point(); }

    /** Per thread (server tick, client tick): the corner samples and sea states of the current tick, shared by every entity that asks
     *  about the same corner or surface block in that tick, so a crowd of items or a fleet of boats costs little more than one. */
    static final class TickCache {
        Level level; long tick = Long.MIN_VALUE; SeaInfo.Nodes nodes; double tSec, wind;
        FftDrive.Pair pair = new FftDrive.Pair(); boolean fftReady; boolean sky;   // 0.9.0: the Fourier pair this tick (null: not ready / off)
        final double[] fo = new double[8], ft = new double[8];
        final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<double[]> corners = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        final it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap states = new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();
        final WaveModel.Sample smp = new WaveModel.Sample(); final double[] scratch = new double[4 * Surface.CSIZE]; final Wave wave = new Wave();
        int y; double s;
        final Surface.Corners src = (cx, cz, out, off) -> {
            long k = ((long) (cx & 0x3FFFFFF) << 38) | ((long) (cz & 0x3FFFFFF) << 12) | (y & 0xFFF);
            double[] v = corners.get(k);
            double tag = sky && fftReady ? s + 2 : s;                       // a corner of open-sky water (FFT) and of cave water are different samples
            if (v == null || v[Surface.CSIZE] != tag) {
                if (v == null) { v = new double[Surface.CSIZE + 1]; corners.put(k, v); }
                int ed = SeaInfo.cornerPacked(nodes, cx, cz, y);
                if (sky && fftReady) { FftCorner.fill(cx, cz, ed >> 4, ed & 15, s, wind, cornerCurrent(level, cx, cz), pair.prev, pair.cur, pair.frac, pair.dt, v, 0, fo, ft); fftCorners++;
                    double fk = TidesSurf.fetchAt(level, cx, cz); if (fk != 1) for (int q : new int[]{Surface.CH, Surface.CDHDT, Surface.CUX, Surface.CUZ}) v[q] *= fk;   // 0.16.2: the local fetch
                    double ts = TidesSurf.seconds(level, 0f); double[] cp = TidesSurf.chopAt(level, cx, cz, ts), cp2 = cp == null ? null : TidesSurf.chopAt(level, cx, cz, ts + 0.05);   // 0.16.3: the wind sea in shallow water
                    if (cp != null) { double wc = cp[3]; v[Surface.CH] = v[Surface.CH] * (1 - wc) + cp[0] * fk * wc; v[Surface.CDHDT] = v[Surface.CDHDT] * (1 - wc) + (cp2 == null ? 0 : (cp2[0] - cp[0]) / 0.05) * fk * wc;
                        v[Surface.CUX] *= 1 - wc; v[Surface.CUZ] *= 1 - wc; chopCorners++; } }
                else Surface.fill(cx, cz, ed >> 4, ed & 15, tSec, s, wind, smp, v, 0);
                v[Surface.CSIZE] = tag; cornerMisses++;
            }
            System.arraycopy(v, 0, out, off, Surface.CSIZE);
        };
        void at(Level l) {
            long now = l.getGameTime();
            if (l != level || now != tick) {
                level = l; tick = now; corners.clear(); states.clear(); nodes = nodes(l);
                double dayTime = l.getDayTime(); tSec = WaveModel.daySeconds(dayTime); wind = WaveModel.wind(dayTime);
                fftReady = false;
                FftDrive d = fftOn && l.dimensionType().hasSkyLight() ? drive(l) : null;
                if (d != null) {
                    double dayStart = Math.floor(dayTime / 24000.0) * 24000.0;
                    d.want(tSec, skyState(l), ts -> WaveModel.wind(dayStart + ts * 20.0));
                    fftReady = d.pair(tSec, pair) != null;
                }
            }
        }
    }
    // ------------------------------------------------------------------ 0.9.0: the Fourier sea over the column water
    /** Server config waves.fft; on a client, what its server sent (TidesNet.Sea). */
    public static volatile boolean fftOn = true;
    /** 0.11.0: the tide on open water (server config waves.tide / tideAmplitude; clients follow the server). */
    public static volatile boolean tideOn = true; public static volatile double tideAmp = 0.25;
    public static volatile long chopCorners, swellSamples, fftCorners, fftSky, fftNotSky, fftNotReady;   // probes
    static final Map<String, FftDrive> DRIVES = new ConcurrentHashMap<>();
    /** One drive per side and dimension (they outlive Level objects: the field is a function of time, state and wind only). */
    public static FftDrive drive(Level l) { String k = (l.isClientSide ? "client " : "server ") + l.dimension().location(); FftDrive d = DRIVES.computeIfAbsent(k, FftDrive::new); d.g = gravity(l);
        // 0.16.0: the fetch and the swell where the viewer is (TidesSurf: the client's camera, or the first server player's in this level),
        // quantized so small changes do not rebuild: fetch in steps of x1.25, the swell to 5 cm, 0.1 s, 0.05 rad
        TidesSurf.Host h = TidesSurf.viewer(l);
        if (h != null) { d.fetch = FftSea.quantFetch(h.fetch);
            d.swellHs = TidesSurf.share(h) > 0 ? 0 : Math.round(h.swellHs * 20) / 20.0; d.swellTp = Math.round(h.swellTp * 10) / 10.0; d.swellDir = Math.round(h.swellDir * 20) / 20.0; }
        return d; }
    // ------------------------------------------------------------------ 0.13.0: the planet a dimension is on (TidesPlanets)
    public static com.hopeless.tides.api.TidesPlanets.Planet planet(Level l) { return l == null ? null : com.hopeless.tides.api.TidesPlanets.get(l.dimension().location().toString()); }
    /** Surface gravity here (m/s2): the planet's, or Earth's. */
    public static double gravity(Level l) { var p = planet(l); return p == null ? com.hopeless.tides.core.FftSea.G : p.g(); }
    /** Height scale of wind waves here at the open-sky sea state: Hs(g) / Hs(9.81) from the spectrum (FftSea.heightScale). */
    public static double heightScale(Level l) { double s = skyState(l), u = FftSea.u10(s); TidesSurf.Host h = TidesSurf.viewer(l); double f = h == null ? FftSea.FETCH : FftSea.quantFetch(h.fetch);
        return FftSea.hsFullCached(u, gravity(l), f) / WaveModel.heightOpen(s); }   // 0.16.0: the real (JONSWAP, fetch-limited) wind sea over Tides' old chosen height
    /** The tide now (blocks above mean, before the open-water weight): Earth's (config) or the planet's. */
    public static double tideNow(Level l) { return tideNow(l, l.getDayTime()); }
    public static double tideNow(Level l, double dayTime) {
        var p = planet(l);
        if (p != null) { var real = com.hopeless.tides.api.TidesPlanets.tide(l.dimension().location().toString());   // 0.17.3: the real sky's tide (Hopeless Infinity)
            if (real != null) try { double v = real.applyAsDouble(l); com.hopeless.tides.api.TidesPlanets.tideCalls++; if (Double.isFinite(v)) return tideAmp * p.tideRatio() * v; } catch (Throwable t) { com.hopeless.tides.api.TidesPlanets.tideErrors++; } }
        return p == null ? WaveModel.tide(dayTime, l.getMoonPhase(), tideAmp) : WaveModel.tide(dayTime, l.getMoonPhase(), tideAmp * p.tideRatio(), p.lunarShare()); }
    public static String fftProbe() { StringBuilder b = new StringBuilder("fft " + (fftOn ? "on" : "off") + ", fft corners " + fftCorners + ", swell train samples " + swellSamples + ", shallow wind-sea corners " + chopCorners + ", samples sky " + fftSky + " not sky " + fftNotSky + " not ready " + fftNotReady); for (FftDrive d : DRIVES.values()) b.append(" | ").append(d.probe()); return b.toString(); }
    /** The open-sky sea state of a level (the state the drive builds its fields for). */
    public static double skyState(Level l) { return overrideState >= 0 ? overrideState : WaveModel.seaStateWeather(l.getRainLevel(1f), l.getThunderLevel(1f)); }
    /** Open-sky water: the same test seaState makes (and the shader: sky light 8 or more). */
    public static boolean isSky(Level l, BlockPos surfaceBlock) { return l.dimensionType().hasSkyLight() && l.getBrightness(LightLayer.SKY, surfaceBlock.above()) >= 8; }
    /** Speed class of the column simulation at a column (0 none, 1..15 = (k-1) x 0.3 m/s): HydroSim on a server, what it sent on a client. */
    public static volatile java.util.function.IntBinaryOperator clientFlowClass = (x, z) -> 0;
    static int flowClass(Level l, int x, int z) {
        if (l.isClientSide) return clientFlowClass.applyAsInt(x, z) & 15;
        float[] f = HydroSim.flow((net.minecraft.server.level.ServerLevel) l, x, z);
        return f == null ? 0 : Math.min(15, 1 + (int) Math.round(f[2] / 0.3));
    }
    /** The current at a block corner (m/s): the fastest of the four columns round it, as speed classes, so server, client and shader agree. */
    public static double cornerCurrent(Level l, int cx, int cz) {
        int k = Math.max(Math.max(flowClass(l, cx - 1, cz - 1), flowClass(l, cx, cz - 1)), Math.max(flowClass(l, cx - 1, cz), flowClass(l, cx, cz)));
        return k <= 1 ? 0 : (k - 1) * 0.3;
    }

    static final ThreadLocal<TickCache> TICK = ThreadLocal.withInitial(TickCache::new);
    public static volatile long cornerMisses;   // probe

    public static Wave wave(Level l, double x, double z, BlockPos top, float partial) {
        samples++;
        TickCache t = TICK.get(); t.at(l);
        long key = top.asLong();
        double s = t.states.getOrDefault(key, -1);
        if (s < 0) { s = seaState(l, top); t.states.put(key, s); }
        FluidState fs = l.getFluidState(top); double base = top.getY() + fs.getHeight(l, top);
        t.y = top.getY(); t.s = s; boolean skyNow = isSky(l, top); t.sky = t.fftReady && skyNow; if (t.fftReady) { if (t.sky) fftSky++; else fftNotSky++; } else fftNotReady++;
        Wave w = t.wave; Surface.at(t.src, x, z, w.p, t.scratch);
        // 0.14.0: the surf (TidesSurf, Hydro 0.11): in the shallows the open sea gives way to the bore train, whose water runs with the bore
        if (skyNow && TidesSurf.on) { double[] b = TidesSurf.bore(l, x, z, TidesSurf.seconds(l, partial));
            if (b != null && b[1] > 0) { double keep = 1 - b[1]; double[] b2 = TidesSurf.bore(l, x, z, TidesSurf.seconds(l, partial) + 0.05);
                w.p.h = w.p.h * keep + b[0]; w.p.dhdt = w.p.dhdt * keep + (b2 == null ? 0 : (b2[0] - b[0]) / 0.05); w.p.sx = w.p.sx * keep + b[2]; w.p.sz = w.p.sz * keep + b[3];
                w.p.ux = w.p.ux * keep + b[6] * b[5]; w.p.uz = w.p.uz * keep + b[7] * b[5]; if (b[4] > 0.3) w.p.breaking = true; surfSamples++; }
            double[] wl = TidesSurf.wall(l, x, z, TidesSurf.seconds(l, partial));   // 0.16.0: the standing wave in front of a wall
            if (wl != null) { w.p.h *= wl[0]; w.p.dhdt *= wl[0]; w.p.sx *= wl[0]; w.p.sz *= wl[0]; wallSamples++; }
            double[] sw = TidesSurf.swell(l, x, z, TidesSurf.seconds(l, partial));   // 0.16.1: the swell train (the Fourier sea has only the wind sea then; the bore weight is in it)
            if (sw != null) { w.p.h += sw[0]; w.p.sx += sw[1]; w.p.sz += sw[2]; w.p.dhdt += sw[3]; swellSamples++; } }
        w.surfaceY = base + w.p.h + (tideOn && skyNow ? tideNow(l) * WaveModel.tideWeight(w.p.exposure) : 0); return w;   // 0.11.0 the tide
    }
}
