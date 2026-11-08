package com.hopeless.tides;

import com.hopeless.tides.core.FftSea;
import com.hopeless.tides.core.Surf;
import com.hopeless.tides.core.WaveModel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 0.14.0: the surf in the game (core/Surf, ported from Hopeless Hydro 0.11). One host per viewer: the client's (drawn) and each server
 * player's (the physics). A host reads a 128 x 128 square of columns round its viewer from the heightmaps (ocean floor = the bed or the
 * ground top, world surface = the water's top) on the game thread, then solves the surf on one worker thread; the game thread only reads
 * the last finished field. Rebuilt when the viewer moves 24 blocks from the square's middle, the sea state or the wind turns, or every
 * 10 s. Sea columns: water whose top is at the square's sea level (the most common water top) with the sea's depth under it.
 * Probes: builds, last and max ms, sea/surf/swash columns, skipped (busy), errors.
 */
public final class TidesSurf {
    private TidesSurf() {}
    public static final int N = 128, MOVE = 24, PERIOD_TICKS = 200;
    public static volatile boolean on = true;
    /** 0.16.1: the swell train: the swell drawn from the surf's solution (refracted to the shore, calm behind land), the Fourier sea carries
     *  the wind sea only (/tides surf train). On by default. */
    public static volatile boolean trainOn = true;
    /** 0.16.3: the wind sea turns, steepens and breaks in shallow water (a second solve of the surf for the wind sea; /tides surf chop). On by default. */
    public static volatile boolean chopOn = true; public static volatile long chops;
    public static volatile long builds, errors, skipped, reads; public static volatile double lastMs, maxMs; public static volatile String lastError = "", lastField = "none";

    public static final class Host {
        public final String key; public volatile Surf.Field field; /** 0.16.3: the same water solved for the wind sea (its period and heading) */ public volatile Surf.Field chop; public volatile double chopL0; public volatile int ox, oz; public volatile double seaTop = Double.NaN; public volatile double seedX0, seedY0;
        volatile boolean busy; long builtTick = Long.MIN_VALUE; double hs, tp, heading; int wantOx, wantOz;
        /** 0.16.0: the effective fetch here (m, Saville 1962; FftSea.FETCH = open sea) and the swell (Hs m, Tp s, toward rad; 0: none). */
        public volatile double fetch = Surf.FRAME, swellHs, swellTp, swellDir; public volatile String fetchNote = "";
        /** 0.16.1: where the swell comes from here (toward, rad): the open ocean round this place (NaN: no world map, the wandering heading). */
        public volatile double oceanDir = Double.NaN;
        /** 0.16.3: where the fetch and the open ocean were measured from (the field's deepest sea column). */
        public volatile double fetchX, fetchZ;
        Host(String k) { key = k; }
        /** Does the field cover world column (x, z)? */
        public boolean covers(double x, double z) { Surf.Field f = field; return f != null && x >= ox && z >= oz && x < ox + N && z < oz + N; }
    }
    static final Map<String, Host> HOSTS = new ConcurrentHashMap<>();
    static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Tides surf"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY + 1); return t; });

    /** The surf's clock: game time in seconds (synced to clients by the game). */
    public static double seconds(Level l, float partial) { return (l.getGameTime() + partial) / 20.0; }

    /** Keep the host for this viewer up to date (game thread; call every tick or every few). */
    public static void tick(Level l, String who, double cx, double cz) {
        if (!on || !l.dimensionType().hasSkyLight()) return;
        String key = (l.isClientSide ? "client " : "server ") + l.dimension().location() + " " + who;
        Host h = HOSTS.computeIfAbsent(key, Host::new);
        if (h.busy) return;
        h.swellDir = Double.isNaN(h.oceanDir) ? swellDir(l) : h.oceanDir;   // 0.16.1: from the open ocean where the world map tells us
        long now = l.getGameTime();
        double s = Tides.skyState(l), g = Tides.gravity(l), u = FftSea.u10(s), fetch = FftSea.quantFetch(h.fetch);
        double hw = FftSea.hsFullCached(u, g, fetch); double[] p = FftSea.parameters(u, fetch, g); double tw = 2 * Math.PI / p[1], hw0 = WaveModel.wind(l.getDayTime());
        // 0.16.0: the surf's deep-water sea is the wind sea (with its fetch) and the swell together: Hs = sqrt(Hw^2 + Hs^2); the period
        // and heading are the bigger one's (Hydro's surf takes one sea)
        // 0.16.1: with a swell the surf is solved for the SWELL's period and heading (it is the swell that comes in from the ocean and turns
        // to the shore; the wind sea is local and runs with the wind), the height of both together (the bores break with all of it)
        double hsw = h.swellHs, hs = Math.sqrt(hw * hw + hsw * hsw); boolean swellLeads = hsw > 0 && trainOn;
        if (!trainOn) swellLeads = hsw > hw;   // 0.16.0
        double tp = swellLeads ? h.swellTp : tw, heading = swellLeads ? h.swellDir : hw0;
        int wox = (int) Math.floor(cx) - N / 2, woz = (int) Math.floor(cz) - N / 2;
        boolean moved = h.field == null || Math.abs(wox - h.ox) > MOVE || Math.abs(woz - h.oz) > MOVE;
        boolean turned = Math.abs(hs - h.hs) > 0.1 * Math.max(0.05, h.hs) || Math.abs(Math.atan2(Math.sin(heading - h.heading), Math.cos(heading - h.heading))) > 0.2;
        if (!moved && !turned && now - h.builtTick < PERIOD_TICKS) return;
        // read the columns (heightmaps: two lookups a column, loaded chunks only)
        Surf.Input in = new Surf.Input(); in.n = N; in.g = g; in.bed = new double[N * N]; in.depth = new double[N * N]; in.known = new boolean[N * N];
        int[] top = new int[N * N]; java.util.HashMap<Integer, Integer> tops = new java.util.HashMap<>(); BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) {
            int x = wox + i, z = woz + j, k = i + N * j;
            if (!l.hasChunk(x >> 4, z >> 4)) continue;
            int floor = l.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z), surf = l.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            in.known[k] = true; in.bed[k] = floor; top[k] = Integer.MIN_VALUE;
            if (surf >= floor && l.getFluidState(m.set(x, surf, z)).is(FluidTags.WATER)) { top[k] = surf; tops.merge(surf, 1, Integer::sum); }
        }
        reads += N * N;
        int seaY = Integer.MIN_VALUE, best = 0; for (var e : tops.entrySet()) if (e.getValue() > best) { best = e.getValue(); seaY = e.getKey(); }
        if (seaY == Integer.MIN_VALUE || best < 64) { h.field = null; h.builtTick = now; return; }   // no sea here
        double seaTop = seaY + 8.0 / 9.0;   // a source block's surface
        for (int k = 0; k < N * N; k++) if (top[k] == seaY && in.known[k]) in.depth[k] = Math.max(0, seaTop - in.bed[k]);
        in.sea = seaTop; in.sea(hs, tp, heading);
        in.seedX0 = Math.floorMod(wox, Surf.FRAME); in.seedY0 = Math.floorMod(woz, Surf.FRAME);
        // 0.16.0: the fetch (Saville 1962: 15 radials at 6 deg within 42 deg of upwind, F = sum F_i cos^2 a / sum cos a) and the swell's
        // way in (the ray toward where it comes from): marched here over loaded columns, finished on the worker from the world mod's map
        // 0.16.3: measured FROM THE WATER, not from the viewer: the deepest sea column of the field (a viewer on the beach or over land got the
        // fetch of the land behind them: 7 m in the 2026-11-02 08:13 log, and the whole sea was drawn as a 7 m fetch's ripples)
        int bk = -1; double bd = -1; for (int k = 0; k < N * N; k++) if (in.depth[k] > bd) { bd = in.depth[k]; bk = k; }
        final double ocx = bk < 0 ? cx : wox + (bk % N) + 0.5, ocz = bk < 0 ? cz : woz + (bk / N) + 0.5; h.fetchX = ocx; h.fetchZ = ocz;
        final double up = hw0 + Math.PI; final double[] rayD = new double[16]; final boolean[] rayOpen = new boolean[16]; final int seaLevelY = seaY;
        for (int r = 0; r < 16; r++) { double a = r < 15 ? up + Math.toRadians(-42 + 6 * r) : swellDir(l) + Math.PI, ca = Math.cos(a), sa = Math.sin(a); double d = 0; boolean open = true;
            for (d = 8; d <= RAY_MAX; d += 8) { int x = (int) Math.floor(ocx + ca * d), z = (int) Math.floor(ocz + sa * d);
                if (!l.hasChunk(x >> 4, z >> 4)) break;                                         // the rest on the worker
                int surf = l.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1; if (surf != seaLevelY || !l.getFluidState(m.set(x, surf, z)).is(FluidTags.WATER)) { open = false; break; } }
            rayD[r] = Math.min(d, RAY_MAX); rayOpen[r] = open; }
        final String dim = l.dimension().location().toString(); final double fcx = ocx, fcz = ocz, gNow = g, wanderNow = swellDir(l), windNow = hw0, uNow = u, hwNow = hw, twNow = tw; final long day3 = l.getDayTime() / 72000;
        Surf.Field old = h.field; if (old != null && old.n == N && wox == h.ox && woz == h.oz) { in.match = old; in.anchor = N / 2 + N * (N / 2); }
        h.busy = true; h.builtTick = now; h.hs = hs; h.tp = tp; h.heading = heading; final int fox = wox, foz = woz; final double sx = in.seedX0, sy = in.seedY0;
        WORKER.submit(() -> {
            try { finishFetch(h, dim, fcx, fcz, up, rayD, rayOpen, gNow, day3, heading, wanderNow);
                Surf.Field f = Surf.compute(in); Surf.fetchFactors(f, windNow, uNow, h.fetch);
                Surf.Field ch = null; if (chopOn && hwNow > 0.01) { Surf.Input ic = in.copyFor(hwNow, twNow, windNow); Surf.Field oc = h.chop; if (oc != null && oc.n == N && fox == h.ox && foz == h.oz) { ic.match = oc; ic.anchor = N / 2 + N * (N / 2); } else { ic.match = null; ic.anchor = -1; } ch = Surf.compute(ic); chops++; }
                h.chop = ch; h.chopL0 = 2 * Math.PI * gNow / Math.pow(2 * Math.PI / twNow, 2); h.ox = fox; h.oz = foz; h.seedX0 = sx; h.seedY0 = sy; h.seaTop = seaTop; h.field = f; builds++; lastMs = f.ms; maxMs = Math.max(maxMs, f.ms); lastField = f.describe() + " | " + h.fetchNote; }
            catch (Throwable e) { errors++; lastError = String.valueOf(e); }
            finally { h.busy = false; }
        });
    }
    public static final double RAY_MAX = 8000;
    // 0.16.0 the swell, MEASURED: NOAA NDBC buoys 2015-2019 (research/ndbc_wave_stats_2015-2019.json, tools/buoy_stats.js), significant
    // height and dominant period in hours with wind under 3 m/s at the buoy (no local wind sea: the swell from far away). The five ocean
    // buoys' calm medians: Monterey 1.73 m 12.1 s, San Francisco 1.42 m 12.1 s, Diamond Shoals 0.93 m 9.1 s, Boston 0.50 m 8.3 s, Gulf of
    // Mexico 0.48 m 5.6 s. Tides takes the middle one, Diamond Shoals (0.93 m, 9.09 s), and lets it wander over three days between its
    // own 25th percentile and the matching ratio above (0.74 / 0.93 = x0.80 .. x1.25). Only where the swell can get in: the ray toward
    // where it comes from runs over open water to the end (8 km). A planet: x 9.81 / g for both (a fully developed sea scales so).
    public static final double SWELL_HS = 0.93, SWELL_TP = 9.09, SWELL_LOW = 0.74 / 0.93;
    static double hash01(long v) { long x = v * 0x9E3779B97F4A7C15L; x ^= x >>> 31; x *= 0xBF58476D1CE4E5B9L; x ^= x >>> 29; return (x >>> 11) / (double) (1L << 53); }
    /** The swell's heading (toward, rad): a new one every three days, turning smoothly (CHOSEN, like the wind). */
    public static double swellDir(Level l) { double t = l.getDayTime() / 72000.0; long i = (long) Math.floor(t); double f = t - i, s = f * f * (3 - 2 * f);
        double a = hash01(i * 7 + 3) * 2 * Math.PI, b = hash01((i + 1) * 7 + 3) * 2 * Math.PI, d = b - a; d -= Math.floor((d + Math.PI) / (2 * Math.PI)) * 2 * Math.PI; return a + d * s; }
    static volatile String oceanNote = "";
    static void finishFetch(Host h, String dim, double cx, double cz, double up, double[] rayD, boolean[] rayOpen, double g, long day3, double heading, double swellWander) {
        int known = 0, guessed = 0;
        for (int r = 0; r < 16; r++) {
            if (!rayOpen[r] || rayD[r] >= RAY_MAX) continue;
            double a = r < 15 ? up + Math.toRadians(-42 + 6 * r) : Math.atan2(Math.sin(h.swellDir + Math.PI), Math.cos(h.swellDir + Math.PI)), ca = Math.cos(a), sa = Math.sin(a), d = rayD[r];
            if (com.hopeless.tides.api.TidesPlanets.waterAt(dim, cx, cz) < 0) { rayD[r] = RAY_MAX; guessed++; continue; }   // no map beyond the loaded chunks: open water assumed
            for (; d <= RAY_MAX; d += 32) { int w = com.hopeless.tides.api.TidesPlanets.waterAt(dim, cx + ca * d, cz + sa * d); if (w == 0) { rayOpen[r] = false; break; } }
            rayD[r] = Math.min(d, RAY_MAX); known++;
        }
        double num = 0, den = 0; for (int r = 0; r < 15; r++) { double c = Math.cos(Math.toRadians(-42 + 6 * r)); num += rayD[r] * c * c; den += c; }
        double fe = num / den; h.fetch = fe >= RAY_MAX * 0.98 ? FftSea.FETCH : fe;
        boolean swellIn = rayOpen[15] && rayD[15] >= RAY_MAX;
        // 0.16.1 the swell comes from the OPEN OCEAN (core/OceanSwell over the world mod's map; the wandering heading picks within the open
        // sector). No open direction: an enclosed sea, no swell. No world map: the 0.16.0 way (the one ray toward the wandering source).
        if (com.hopeless.tides.api.TidesPlanets.waterAt(dim, cx, cz) >= 0) {
            double[] os = com.hopeless.tides.core.OceanSwell.from((x, z) -> com.hopeless.tides.api.TidesPlanets.waterAt(dim, x, z), cx, cz, swellWander);
            if (os == null) { swellIn = false; h.oceanDir = Double.NaN; oceanNote = "no open ocean within 8 km in any direction"; }
            else { h.oceanDir = os[0]; swellIn = true; oceanNote = String.format("from the open ocean at %.0f deg (%d of %d directions open)", Math.toDegrees(os[2]), (int) os[1], com.hopeless.tides.core.OceanSwell.RAYS); }
        } else oceanNote = "no world map: wandering heading";
        double day = hash01(day3 * 13 + 5), fac = SWELL_LOW + (1 / SWELL_LOW - SWELL_LOW) * day, gs = 9.81 / Math.max(0.1, g);
        h.swellHs = swellIn ? SWELL_HS * fac * gs : 0; h.swellTp = SWELL_TP * gs;
        h.fetchNote = String.format("fetch %.0f m measured from the water at %.0f %.0f (rays from the world map %d, assumed open %d), swell %s; %s", h.fetch, cx, cz, known, guessed, swellIn ? String.format("%.2f m %.1f s", h.swellHs, h.swellTp) : "blocked (land within 8 km of where it comes from)", oceanNote);
    }
    /** The first host of this level and side with a field (the client's camera; on a server the first player's). */
    public static Host viewer(Level l) { String pre = (l.isClientSide ? "client " : "server ") + l.dimension().location() + " "; for (Host h : HOSTS.values()) if (h.key.startsWith(pre) && h.field != null) return h; return null; }
    /** The host that covers (x, z) for this level and side (the nearest viewer's), or null. */
    public static Host at(Level l, double x, double z) {
        String pre = (l.isClientSide ? "client " : "server ") + l.dimension().location() + " ";
        for (Host h : HOSTS.values()) if (h.key.startsWith(pre) && h.covers(x, z)) return h;
        return null;
    }
    /** 0.16.1: the swell's share of the sea this host's surf was solved for (0: no train here: no swell, or the train is off). */
    public static double share(Host h) { Surf.Field f = h == null ? null : h.field; if (!trainOn || f == null || !(h.swellHs > 0) || !(f.hs0 > 0) || Math.abs(Math.atan2(Math.sin(f.heading - h.swellDir), Math.cos(f.heading - h.swellDir))) > 0.3) return 0; return Math.min(1, h.swellHs / f.hs0); }
    /** Is a swell train drawn in this level (the viewer's host has one)? Then the Fourier sea is built without the swell. */
    public static boolean trainActive(Level l) { return share(viewer(l)) > 0; }
    public static double pivot(int o) { return Math.floorDiv(o, 512) * 512.0; }
    /** 0.16.2: the wind sea's local height factor at block corner (cx, cz) (Surf.fetchFactors); 1 outside every field. */
    public static double fetchAt(Level l, int cx, int cz) { if (!on) return 1; Host h = at(l, cx - 0.5, cz - 0.5); if (h == null) h = at(l, cx + 0.5, cz + 0.5); if (h == null || h.field == null) return 1; fetchReads++; return Surf.fetchCorner(h.field, cx - h.ox, cz - h.oz); }
    public static volatile long fetchReads, chopReads;
    /** 0.16.3: the wind sea in shallow water at block corner (cx, cz), time t: {eta, slope x, slope z, weight} (Surf.Field.chopCorner); null: none. */
    public static double[] chopAt(Level l, int cx, int cz, double t) { if (!on || !chopOn) return null; Host h = at(l, cx - 0.5, cz - 0.5); if (h == null) h = at(l, cx + 0.5, cz + 0.5); if (h == null || h.chop == null) return null;
        Surf.Field c = h.chop; double[] clk = new double[Surf.K]; for (int q = 0; q < Surf.K; q++) clk[q] = Surf.clock(c.om[q], t); double[] o = c.chopCorner(cx - h.ox, cz - h.oz, clk, h.chopL0); if (o[3] <= 0) return null; chopReads++; return o; }
    /** 0.16.1 the swell train at world (x, z), time t: {eta, slope x, slope z, d eta/dt} between block corners as the shader; null: none here.
     *  Inside the viewer's field the refracted train, beyond it the plane train (the same as the shader's u_TidesSw uniforms). */
    public static double[] swell(Level l, double x, double z, double t) {
        Host h = at(l, x, z); if (h == null) h = viewer(l); double sh = share(h); if (sh <= 0) return null; Surf.Field f = h.field;
        double[] o = swellPoint(h, f, sh, x, z, t), o2 = swellPoint(h, f, sh, x, z, t + 0.05); return new double[]{o[0], o[1], o[2], (o2[0] - o[0]) / 0.05}; }
    static double[] swellPoint(Host h, Surf.Field f, double sh, double x, double z, double t) {
        double[] clk = new double[Surf.K]; for (int q = 0; q < Surf.K; q++) clk[q] = Surf.clock(f.om[q], t);
        int i = (int) Math.floor(x), j = (int) Math.floor(z); double fx = x - i, fz = z - j, ws[] = {(1 - fx) * (1 - fz), fx * (1 - fz), (1 - fx) * fz, fx * fz}; double[] o = new double[3];
        for (int q = 0; q < 4; q++) { int cx = i + (q & 1), cz = j + (q >> 1); double Xm = Math.floorMod(cx, Surf.FRAME), Zm = Math.floorMod(cz, Surf.FRAME);
            int I = cx - h.ox, J = cz - h.oz; double[] v = I >= 0 && J >= 0 && I <= N && J <= N ? f.swellCorner(I, J, clk, sh, Xm, Zm) : Surf.planeSwell(f, clk, sh, Xm, Zm);
            for (int k = 0; k < 3; k++) o[k] += v[k] * ws[q]; }
        return o; }
    /** The bore train at world (x, z), time t (s): {eta, w, slope x, slope z, foam, speed, travel x, travel z} interpolated between
     *  block corners as the shader does; null outside any field or away from the sea. */
    public static double[] bore(Level l, double x, double z, double t) {
        Host h = at(l, x, z); if (h == null) return null; Surf.Field f = h.field; if (f == null) return null;
        double lx = x - h.ox, lz = z - h.oz; int i = (int) Math.floor(lx), j = (int) Math.floor(lz); double fx = lx - i, fz = lz - j;
        double clk = Surf.clock(f.om[Surf.DOM], t), xm = h.seedX0 + lx, ym = h.seedY0 + lz;
        double[][] v = {f.corner(i, j, clk, xm, ym), f.corner(i + 1, j, clk, xm, ym), f.corner(i, j + 1, clk, xm, ym), f.corner(i + 1, j + 1, clk, xm, ym)};
        double[] w = {(1 - fx) * (1 - fz), fx * (1 - fz), (1 - fx) * fz, fx * fz}; double[] o = new double[8]; double ws = 0;
        for (int q = 0; q < 4; q++) if (v[q] != null) { ws += w[q]; for (int k = 0; k < 6; k++) o[k] += v[q][k] * w[q]; }
        if (ws <= 0) return null; for (int k = 0; k < 6; k++) o[k] /= ws;
        int c = Math.max(0, Math.min(N - 1, i)) + N * Math.max(0, Math.min(N - 1, j)); double[] g = f.grad(c % N, c / N); double gl = Math.hypot(g[0], g[1]);
        if (gl > 0) { o[6] = g[0] / gl; o[7] = g[1] / gl; }
        return o;
    }
    /** 0.16.0: the wall in front of world (x, z): {amplitude factor of the standing wave, distance to the wall foot m, normal x, z (out to
     *  sea), Kr, R2 m, the peak wave's front fraction 0..1 (0: the front is here now)}; null when no wall within reach. */
    public static double[] wall(Level l, double x, double z, double t) {
        Host h = at(l, x, z); if (h == null) return null; Surf.Field f = h.field; if (f == null || f.wallDist == null) return null;
        int i = (int) Math.floor(x - h.ox), j = (int) Math.floor(z - h.oz); if (i < 0 || j < 0 || i >= N || j >= N) return null; int c = i + N * j;
        if (Float.isNaN(f.wallDist[c]) || !f.seaCol[c]) return null;
        double thD = f.phi[Surf.DOM][c] - Surf.clock(f.om[Surf.DOM], t) + Surf.PHASE0[Surf.DOM], cyc = -(thD - f.shP[c]) / (2 * Math.PI);
        return new double[]{f.wallAmp(c), f.wallDist[c], f.wallNx[c], f.wallNy[c], f.wallKr[c], f.wallR2[c], cyc - Math.floor(cyc)};
    }
    /** The swash at world (x, z) on the dry beach: {sheet 0..1, speed up the beach m/s (negative: backwash), up x, up z, wet}; null when none. */
    public static double[] swash(Level l, double x, double z, double t) {
        Host h = at(l, x, z); if (h == null) return null; Surf.Field f = h.field; if (f == null) return null;
        double lx = x - h.ox, lz = z - h.oz; int i = (int) Math.floor(lx), j = (int) Math.floor(lz); int c = i + N * j;
        if (i < 0 || j < 0 || i >= N || j >= N || Float.isNaN(f.shoreX[c])) return null;
        double xs = Math.max(0, f.shoreX[c] + (lx - i - 0.5) * f.upX[c] + (lz - j - 0.5) * f.upY[c]);
        double[] s = f.swash(c, xs, Surf.clock(f.om[Surf.DOM], t), h.seedX0 + lx, h.seedY0 + lz);
        return new double[]{s[0], s[1], f.upX[c], f.upY[c], s[2]};
    }
    public static String probe() { return String.format("surf %s: builds %d (last %.1f ms, max %.1f ms), hosts %d, skipped %d, errors %d%s | %s", on ? "on" : "off", builds, lastMs, maxMs, HOSTS.size(), skipped, errors, errors > 0 ? " (" + lastError + ")" : "", lastField); }
}
