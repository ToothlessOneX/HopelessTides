package com.hopeless.tides.core;

/**
 * The wave model of Hopeless Tides. Pure Java with no Minecraft types, so tests and previews run it offline, and written so the same
 * maths runs in GLSL (shaders/tides_waves.glsl): the server, the client renderer and the shader pack all get the same surface.
 *
 * The surface is a sum of six travelling waves round the wind direction. How high they are depends on:
 *  - the sea state (calm .. storm), from the weather (rain, thunder) above ground, or from a slow cycle in caves with no sky;
 *  - the exposure of the spot (0..15: how much open water is round it - a pond is sheltered, a sea is open);
 *  - the depth (0..15 blocks): waves grow as the water shallows (shoaling) and break when they get taller than 0.78 of the depth.
 * Time is the day time (ticks 0..24000) so the server, every client and the shader agree. Each wave's frequency is a whole number
 * of cycles per Minecraft day, so the pattern joins up seamlessly when the day wraps.
 */
public final class WaveModel {
    private WaveModel() {}
    public static final double G = 9.8;                       // blocks per second squared (a block is a metre)
    public static final double DAY_SECONDS = 1200.0;
    public static final double BREAK = 0.55;                  // waves break when their height passes this share of the depth
    // Twelve components with FIXED wavelengths (blocks) and FIXED directions (radians): if either changed with the weather or the wind,
    // the pattern would slide sideways, fast far from the origin. Six directions 60 degrees apart, a long and a shorter wave on each.
    // The wind and the sea state only change how much height each component carries.
    public static final int N = 12;
    public static final double[] LEN = {44.0, 13.0, 33.0, 9.5, 38.0, 16.0, 29.0, 7.0, 48.0, 11.0, 36.0, 19.0};
    public static final double[] DIR = new double[N];
    static final double[] PHASE = {0.0, 2.1, 4.4, 1.3, 3.7, 5.6, 0.8, 2.9, 5.1, 1.9, 4.0, 0.4};
    static { for (int i = 0; i < N; i++) DIR[i] = 0.31 + (i / 2) * Math.PI / 3 + (i % 2) * 0.17; }
    public static double[] phases() { return PHASE.clone(); }
    /** Sea state 0..1 from the weather above ground. */
    public static double seaStateWeather(double rain, double thunder) { return clamp(0.10 + 0.40 * rain + 0.50 * thunder, 0, 1); }
    /** Sea state in caves with no sky: calm and rough spells that come and go over a few days. dayTime in ticks (with day count). */
    public static double seaStateCave(double dayTime) {
        double t = dayTime / (24000.0 * 1.7); long i = (long) Math.floor(t); double f = t - i, s = f * f * (3 - 2 * f);
        double a = hash01(i * 7919 + 13), b = hash01((i + 1) * 7919 + 13), v = a + (b - a) * s;
        return 0.06 + 0.50 * v * v;
    }
    /** Wind direction (radians): a new direction each day, turning smoothly from one to the next. */
    public static double wind(double dayTime) {
        double t = dayTime / 24000.0; long i = (long) Math.floor(t); double f = t - i, s = f * f * (3 - 2 * f);
        double a = hash01(i * 104729 + 7) * 2 * Math.PI, b = hash01((i + 1) * 104729 + 7) * 2 * Math.PI;
        double d = b - a; d -= Math.floor((d + Math.PI) / (2 * Math.PI)) * 2 * Math.PI;   // the short way round
        return a + d * s;
    }
    /** Significant wave height on open, deep water (blocks) and the peak wavelength (blocks) for a sea state. */
    public static double heightOpen(double s) { return 0.06 + 1.55 * s * s; }
    public static double peakLength(double s) { return 9.0 + 26.0 * s; }

    /** A surface sample. */
    public static final class Sample { public double h, dhdt, sx, sz, ux, uz; public boolean breaking; public double heightLocal; }

    /**
     * The surface at (x, z) at time tSec (seconds into the day, 0..1200) for sea state s and wind angle w, at a spot of the given
     * exposure (0..15) and depth (0..15). h is the height above still water (blocks); dhdt its rate; (sx, sz) the slope; (ux, uz) the
     * water's horizontal speed at the surface (blocks per second); breaking when the waves top out against the bottom.
     */
    public static Sample sample(double x, double z, double tSec, double s, double w, int exposure, int depth, Sample out) {
        if (out == null) out = new Sample();
        out.h = out.dhdt = out.sx = out.sz = out.ux = out.uz = 0; out.breaking = false; out.heightLocal = 0;
        if (exposure <= 0 || depth <= 0) return out;
        Table t = table(s, w); int c = Math.min(15, exposure) * 16 + Math.min(15, depth);
        double[] amp = t.amp[c]; if (amp == null) amp = t.fill(c, Math.min(15, exposure), Math.min(15, depth));
        out.breaking = t.breaking[c]; out.heightLocal = t.height[c];
        for (int i = 0; i < N; i++) {
            double a = amp[i], ph = K[i] * (DX[i] * x + DZ[i] * z) - OM[i] * tSec + PHASE[i];
            double sn = Math.sin(ph), cs = Math.cos(ph);
            out.h += a * sn;
            out.dhdt += -a * OM[i] * cs;
            out.sx += a * K[i] * DX[i] * cs; out.sz += a * K[i] * DZ[i] * cs;
            double u = a * OM[i] * sn;                        // surface orbital speed, along the wave's travel
            out.ux += u * DX[i]; out.uz += u * DZ[i];
        }
        return out;
    }
    static final double[] K = new double[N], OM = new double[N], DX = new double[N], DZ = new double[N];
    static { for (int i = 0; i < N; i++) { K[i] = 2 * Math.PI / LEN[i]; OM[i] = omega(K[i]); DX[i] = Math.cos(DIR[i]); DZ[i] = Math.sin(DIR[i]); } }

    /** The amplitudes for every exposure and depth (16 x 16) at one sea state and wind: they change slowly, so they are worked out once
     *  and reused until the sea state or wind moves on (the same numbers as computing them for each sample). */
    static final class Table { final double s, w; final double[][] amp = new double[256][]; final boolean[] breaking = new boolean[256]; final double[] height = new double[256];
        Table(double s, double w) { this.s = s; this.w = w; }
        /** One cell, worked out the first time it is asked for. Published only when complete (safe if two threads race: same numbers). */
        double[] fill(int c, int exposure, int depth) {
            double[] a = new double[N];
            double e = exposure / 15.0, hs = heightOpen(s) * e * e, lp = peakLength(s) * (0.45 + 0.55 * e), D = Math.max(depth, 0.5);
            double[] wt = new double[N]; double wsum = 0, total = 0;
            for (int i = 0; i < N; i++) { double r = Math.log(LEN[i] / lp) / 0.45, cc = Math.max(0, Math.cos(DIR[i] - w)); wt[i] = Math.exp(-0.5 * r * r) * (cc * cc + 0.04); wsum += wt[i]; }
            for (int i = 0; i < N; i++) { double ks = Math.min(Math.pow(Math.tanh(K[i] * D), -0.25), 1.8); a[i] = 0.25 * hs * Math.sqrt(2 * wt[i] / wsum) * ks; total += a[i] * a[i]; }
            total = 4 * Math.sqrt(total / 2);
            double cap = BREAK * D;
            if (total > cap) { double sc = cap / total; for (int i = 0; i < N; i++) a[i] *= sc; breaking[c] = true; }
            height[c] = Math.min(total, cap); cellsFilled++;
            amp[c] = a; return a;
        } }
    /** 0.9.0: how high the waves stand at a spot (exposure, depth) against the open sea, from the same table the 12-wave model uses:
     *  shelter (e^2), shoaling and the breaking cap at the bed. The Fourier layer is scaled by it per corner (FftCorner). */
    public static double localScale(double s, double w, int exposure, int depth) {
        if (exposure <= 0 || depth <= 0) return 0;
        Table t = table(s, w); int c = Math.min(15, exposure) * 16 + Math.min(15, depth);
        if (t.amp[c] == null) t.fill(c, Math.min(15, exposure), Math.min(15, depth));
        return t.height[c] / heightOpen(s);
    }
    public static boolean depthBreaking(double s, double w, int exposure, int depth) {
        if (exposure <= 0 || depth <= 0) return false;
        Table t = table(s, w); int c = Math.min(15, exposure) * 16 + Math.min(15, depth);
        if (t.amp[c] == null) t.fill(c, Math.min(15, exposure), Math.min(15, depth));
        return t.breaking[c];
    }
    // a few recent tables (weather surfaces and cave surfaces can be asked for in the same tick)
    static final Table[] RECENT = new Table[4]; static int next;
    public static volatile long tablesBuilt, cellsFilled;   // probes
    static Table table(double s, double w) {
        Table[] r = RECENT;
        for (int i = 0; i < r.length; i++) { Table t = r[i]; if (t != null && t.s == s && t.w == w) return t; }
        synchronized (RECENT) { Table t = new Table(s, w); r[next] = t; next = (next + 1) % r.length; tablesBuilt++; return t; }
    }

    /** Deep-water frequency for wavenumber k, rounded to a whole number of cycles per day (seamless across midnight). */
    public static double omega(double k) {
        double om = Math.sqrt(G * k), n = Math.max(1, Math.round(om * DAY_SECONDS / (2 * Math.PI)));
        return n * 2 * Math.PI / DAY_SECONDS;
    }
    /** Seconds into the day from the day time in ticks (with partial ticks). */
    /** 0.6.0: the sea state a cave wind of U m/s gives open water (fetch-limited growth, Shore Protection Manual 1984:
     *  Hs = 1.6e-3 U sqrt(F / g), F = 44 m, the widest water the exposure probe sees). Below 0.06 m it stays calm (state 0). */
    public static double caveStateFromWind(double u) { double hs = 1.6e-3 * Math.max(0, u) * Math.sqrt(44 / 9.81); return Math.sqrt(Math.max(0, (hs - 0.06) / 1.55)); }
    /** 0.6.0: how strong the small ripples on the water are (0..1) for a wind of U m/s: wind ripples start at about 0.5 m/s and the
     *  water is fully roughened by 6 m/s (Beaufort 1-4 descriptions: ripples, small wavelets, large wavelets, small waves). */
    /** 0.11.0: the tide (blocks above mean level) on open water: two highs a Minecraft day (semi-diurnal, as the Moon's M2 tide gives
     *  two a lunar day), spring tides at full and new moon, neap at the quarters (x 0.45; real springs/neaps differ about 2-3 x).
     *  amplitude: blocks at spring tide (config). moonPhase 0..7 (0 full, 4 new). dayTime in ticks (with day count). */
    public static double tide(double dayTime, int moonPhase, double amplitude) {
        double f = (dayTime % 24000.0) / 24000.0; int q = Math.floorMod(moonPhase, 8);
        double spring = (q == 0 || q == 4) ? 1.0 : (q == 2 || q == 6) ? 0.45 : 0.72;
        return amplitude * spring * Math.cos(2 * Math.PI * 2 * f + 0.6 * q);
    }
    /** 0.13.0: the tide on a planet from its tide-raising bodies: amplitude = spring amplitude of the config x the planet's equilibrium
     *  tide over Earth's; lunarShare = the moons' part (0..1). Spring at full and new moon (moons and star in line: L + S), neap at the
     *  quarters (L - S, so x |2 lunarShare - 1|), the mid phases half way. A planet with no real moon has a steady solar tide (no
     *  spring/neap). The moon-phase shift of the high water scales with the moons' share (Earth's 0.6 rad per phase at share 0.69). */
    public static double tide(double dayTime, int moonPhase, double amplitude, double lunarShare) {
        double f = (dayTime % 24000.0) / 24000.0; int q = Math.floorMod(moonPhase, 8);
        double neap = Math.abs(2 * lunarShare - 1), spring = (q == 0 || q == 4) ? 1.0 : (q == 2 || q == 6) ? neap : 0.5 * (1 + neap);
        return amplitude * spring * Math.cos(2 * Math.PI * 2 * f + 0.6 * q * lunarShare / EARTH_LUNAR_SHARE);
    }
    /** The Moon's share of Earth's equilibrium tide: 1 / (1 + 0.46) (solar/lunar tide-raising ratio 0.46, Pugh & Woodworth 2014). */
    public static final double EARTH_LUNAR_SHARE = 1 / 1.46;
    /** Weight of the tide at a spot: open water only (exposure 8 -> 14), so ponds and pools keep their level. */
    public static double tideWeight(int exposure) { double t = clamp((exposure - 8) / 6.0, 0, 1); return t * t * (3 - 2 * t); }
    public static double rippleFromWind(double u) { double t = clamp((u - 0.3) / 5.7, 0, 1); return 0.12 + 0.88 * t * t * (3 - 2 * t); }
    public static double daySeconds(double dayTime) { double d = dayTime % 24000.0; if (d < 0) d += 24000.0; return d / 20.0; }

    // a small integer hash that GLSL can repeat exactly with uint maths
    public static double hash01(long v) {
        int x = (int) v; x ^= x >>> 16; x *= 0x7feb352d; x ^= x >>> 15; x *= 0x846ca68b; x ^= x >>> 16;
        return (x & 0xFFFFFF) / (double) 0x1000000;
    }
    static double clamp(double v, double a, double b) { return v < a ? a : v > b ? b : v; }
}
