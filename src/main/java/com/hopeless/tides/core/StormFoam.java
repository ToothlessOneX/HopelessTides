package com.hopeless.tides.core;

/**
 * 0.10.0: the storm sea's whitecaps and foam streaks (Hopeless Hydro / Water StormSea, shader half tw_stormScores in tides_look.glsl).
 * How much is white: Monahan & O'Muircheartaigh 1980, W = 3.84e-6 U10^3.41; 30 % of it active caps, 70 % the residual foam they leave
 * (StormSea's CHOSEN split). The residual foam is drawn into streaks along the wind from Beaufort 7 (13.9 m/s), fully by 9 (20.8 m/s).
 * The score thresholds that make the white AREA equal Monahan's fraction are found here from the scores' own distribution: this class
 * runs the shader's noise (tw_hash, tw_noise) number for number over random points, with the wave height Gaussian at sigma.
 */
public final class StormFoam {
    private StormFoam() {}
    static float hash(int cx, int cy) {
        int h = (cx * 1597334677) ^ (cy * (int) 3812015801L); h ^= h >>> 16; h *= (int) 2246822519L; h ^= h >>> 13; return (h & 0x00ffffff) / 16777215.0f;
    }
    static float noise(float px, float py) {
        int cx = (int) Math.floor(px), cy = (int) Math.floor(py); float fx = px - cx, fy = py - cy;
        float ux = fx * fx * fx * (fx * (fx * 6 - 15) + 10), uy = fy * fy * fy * (fy * (fy * 6 - 15) + 10);
        float a = hash(cx, cy), b = hash(cx + 1, cy), c = hash(cx, cy + 1), d = hash(cx + 1, cy + 1);
        return (a + (b - a) * ux) + ((c + (d - c) * ux) - (a + (b - a) * ux)) * uy;
    }
    static float smooth(float e0, float e1, float x) { float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0))); return t * t * (3 - 2 * t); }
    public static double monahan(double u) { return Math.max(0, Math.min(1, 3.84e-6 * Math.pow(Math.max(0, u), 3.41))); }
    /** The scores {active, residual} at wind-frame point (px downwind, py across), height h over sigma (hs = h / sigma), time t. */
    public static float[] scores(float px, float py, float hs, float t, float U) {
        float qx = px - 0.03f * U * t, qy = py;
        float grp = noise(qx * 0.010f + t * 0.05f, qy * 0.022f + 3.7f);
        float ax = qx * 0.35f + 11.3f, ay = qy * 0.50f - t * 0.2f;
        float torn = (4 * noise(ax, ay) + 2 * noise(ax * 2.03f + 5.7f, ay * 2.03f + 5.7f) + noise(ax * 4.11f - 3.1f, ay * 4.11f - 3.1f)) / 7;
        float high = Math.max(0, Math.min(1, (hs - 0.6f) / 1.4f));
        float sA = 0.45f * grp + 0.30f * high + 0.25f * torn;
        float streaky = smooth(13.9f, 20.8f, U);
        float sx = qx * (0.05f + (0.010f - 0.05f) * streaky) - 5.1f, sy = qy * (0.08f + (0.35f - 0.08f) * streaky) + 2.3f;
        float str = (2 * noise(sx, sy) + noise(sx * 2.03f + 1.9f, sy * 2.03f + 1.9f)) / 3;
        float lace = 1 - Math.abs(2 * str - 1);
        float fx = qx * 0.30f + 2.9f, fy = qy * 0.80f + 8.1f;
        float fine2 = (2 * noise(fx, fy) + noise(fx * 2.07f - 4.3f, fy * 2.07f - 4.3f)) / 3;
        float brk = noise(qx * 0.08f + 7.7f, qy * 0.20f - 1.3f);
        float sR = 0.55f * lace + 0.25f * brk + 0.20f * fine2;
        return new float[]{sA, sR};
    }
    static final java.util.concurrent.ConcurrentHashMap<Integer, float[]> CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    /** The thresholds if already worked out (the render thread never waits): else null, and the worker starts on all winds 0-30 m/s. */
    public static float[] ready(double U) {
        float[] r = CACHE.get((int) Math.round(Math.max(0, Math.min(30, U)) * 2));
        if (r == null && !warming) { warming = true; Thread th = new Thread(() -> { for (int k = 0; k <= 60; k++) thresholds(k / 2.0); }, "Tides storm foam"); th.setDaemon(true); th.start(); }
        return r;
    }
    static volatile boolean warming;
    /** Thresholds {active, residual} for wind U (m/s, cached by 0.5 m/s): above them a pixel is white. +inf when nothing is white. */
    public static float[] thresholds(double U) {
        int key = (int) Math.round(Math.max(0, U) * 2);
        return CACHE.computeIfAbsent(key, k -> {
            float u = k / 2f; double W = monahan(u);
            if (W <= 1e-5) return new float[]{9f, 9f};
            java.util.Random r = new java.util.Random(12345); int n = 40000; float[] a = new float[n], b = new float[n];
            for (int i = 0; i < n; i++) { float[] s = scores(r.nextFloat() * 4000 - 2000, r.nextFloat() * 4000 - 2000, (float) r.nextGaussian(), r.nextFloat() * 1200, u); a[i] = s[0]; b[i] = s[1]; }
            java.util.Arrays.sort(a); java.util.Arrays.sort(b);
            float ta = a[Math.max(0, Math.min(n - 1, (int) Math.round(n * (1 - 0.3 * W))))], tr = b[Math.max(0, Math.min(n - 1, (int) Math.round(n * (1 - 0.7 * W))))];
            return new float[]{ta, tr};
        });
    }
}
