package com.hopeless.tides.core;

/**
 * 0.9.0: the Fourier layer at a block corner, BLENDED WITH THE COLUMN WATER. The layer is the open sea; the corner scales it:
 *   a = heightOpen(s) / Hs_full(U10) x localScale(s, w, exposure, depth) x flowCalm(current)
 *  - heightOpen(s) / Hs_full: Tides' own open-sea height for the sea state, over the full spectrum's height (the grid carries the waves of
 *    5 m and longer; shorter ones are the shader's detail waves), so heights stay what they were and the SHAPE comes from the spectrum;
 *  - localScale: shelter, shoaling and the breaking cap from the water under the corner (WaveModel's table);
 *  - flowCalm = 1 / (1 + (v / 0.3)^2), v the column simulation's current at the corner (Hopeless Hydro: the wind's waves halve on water
 *    of 0.3 m/s; "rivers and streams are more influenced by flow", owner, 2026-10-18).
 * The surface point over the corner is the rest point x0 with x0 + a D(x0) = x (three fixed-point steps, the same in the shader), so the
 * height drawn at the corner is a h(x0): crests sharp, troughs broad. Breaking: the scaled Jacobian under the field's threshold (the share
 * of the sea breaking = Monahan's active whitecaps), or the bed's breaking (depthBreaking). The time between two fields is a linear blend
 * (FftDrive.Pair), its rate the water's dh/dt and the displacement's rate the orbital speed.
 * The output layout is Surface's corner layout, so Surface.at blends corners exactly as the renderer does.
 */
public final class FftCorner {
    private FftCorner() {}
    public static final double FLOW_CALM = 0.3;
    public static double flowCalm(double v) { double r = v / FLOW_CALM; return 1 / (1 + r * r); }
    /** Amplitude scale of the field at a corner. */
    public static double scale(FftSea.Field f, double s, double w, int exposure, int depth, double current) {
        if (f == null || f.hsFull <= 0) return 0;
        return WaveModel.heightOpen(s) / f.hsFull * WaveModel.localScale(s, w, exposure, depth) * flowCalm(current);
    }
    /** The field pair at frac (0..1+) at rest point (x, z): out8 as FftSea.fetch. */
    static void pairFetch(FftSea.Field p, FftSea.Field c, double frac, double x, double z, double[] o, double[] tmp) {
        FftSea.fetch(c, x, z, o);
        if (p != null && frac != 1) { FftSea.fetch(p, x, z, tmp); for (int q = 0; q < 8; q++) o[q] = tmp[q] + (o[q] - tmp[q]) * frac; }
    }
    /**
     * One corner into out[off..] (Surface layout). prev/cur: the pair; frac where t lies between them; dt seconds between them.
     */
    public static void fill(double cx, double cz, int exposure, int depth, double s, double w, double current, FftSea.Field prev, FftSea.Field cur, double frac, double dt,
                            double[] out, int off, double[] o, double[] tmp) {
        double a = scale(cur, s, w, exposure, depth, current);
        out[off + Surface.CEXP] = exposure; out[off + Surface.CDEPTH] = depth;
        if (a <= 0) { out[off + Surface.CH] = out[off + Surface.CDHDT] = out[off + Surface.CUX] = out[off + Surface.CUZ] = 0; out[off + Surface.CBREAK] = 0; return; }
        double x0 = cx, z0 = cz;
        for (int it = 0; it < 3; it++) { pairFetch(prev, cur, frac, x0, z0, o, tmp); x0 = cx - a * o[1]; z0 = cz - a * o[2]; }
        pairFetch(prev, cur, frac, x0, z0, o, tmp);
        double h = a * o[0];
        double J = (1 + a * o[4]) * (1 + a * o[5]) - (a * o[6]) * (a * o[6]);
        // rates from the two fields at the same rest point
        double dhdt = 0, ux = 0, uz = 0;
        if (prev != null && dt > 0) {
            FftSea.fetch(cur, x0, z0, o); double hc = o[0], dxc = o[1], dzc = o[2];
            FftSea.fetch(prev, x0, z0, tmp);
            dhdt = a * (hc - tmp[0]) / dt; ux = a * (dxc - tmp[1]) / dt; uz = a * (dzc - tmp[2]) / dt;
        }
        out[off + Surface.CH] = h; out[off + Surface.CDHDT] = dhdt; out[off + Surface.CUX] = ux; out[off + Surface.CUZ] = uz;
        out[off + Surface.CBREAK] = (J < cur.breakJ || WaveModel.depthBreaking(s, w, exposure, depth)) ? 1 : 0;
    }
}
