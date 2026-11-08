package com.hopeless.tides.core;

/** The wave surface at any point, blended from the four block corners round it exactly as the renderer draws it. */
public final class Surface {
    private Surface() {}
    public static final class Point { public double h, dhdt, ux, uz, sx, sz; public boolean breaking; public int exposure, depth; }
    /** One block corner's sample: h, dhdt, ux, uz, breaking (0/1), exposure, depth. A caller can cache these (they repeat for every
     *  entity near the same corner in the same tick). */
    public interface Corners { void corner(int cx, int cz, double[] out, int off); }
    public static final int CH = 0, CDHDT = 1, CUX = 2, CUZ = 3, CBREAK = 4, CEXP = 5, CDEPTH = 6, CSIZE = 7;
    /** Corners worked out directly from the nodes. */
    public static Corners direct(SeaInfo.Nodes nodes, int y, double tSec, double s, double w) {
        WaveModel.Sample smp = new WaveModel.Sample();
        return (cx, cz, out, off) -> { int ed = SeaInfo.cornerPacked(nodes, cx, cz, y); fill(cx, cz, ed >> 4, ed & 15, tSec, s, w, smp, out, off); };
    }
    public static void fill(int cx, int cz, int exposure, int depth, double tSec, double s, double w, WaveModel.Sample smp, double[] out, int off) {
        WaveModel.sample(cx, cz, tSec, s, w, exposure, depth, smp);
        out[off + CH] = smp.h; out[off + CDHDT] = smp.dhdt; out[off + CUX] = smp.ux; out[off + CUZ] = smp.uz; out[off + CBREAK] = smp.breaking ? 1 : 0; out[off + CEXP] = exposure; out[off + CDEPTH] = depth;
    }
    public static Point at(SeaInfo.Nodes nodes, double x, double z, int y, double tSec, double s, double w, Point out) {
        return at(direct(nodes, y, tSec, s, w), x, z, out, new double[4 * CSIZE]);
    }
    /** scratch: 4 * CSIZE doubles, reused by the caller. */
    public static Point at(Corners corners, double x, double z, Point out, double[] scratch) {
        if (out == null) out = new Point();
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z); double fx = x - x0, fz = z - z0;
        out.h = out.dhdt = out.ux = out.uz = 0; out.breaking = false; out.exposure = out.depth = 0;
        for (int c = 0; c < 4; c++) {
            int cx = x0 + (c & 1), cz = z0 + (c >> 1); double wgt = ((c & 1) == 1 ? fx : 1 - fx) * ((c >> 1) == 1 ? fz : 1 - fz);
            int o = c * CSIZE; corners.corner(cx, cz, scratch, o); double[] k = scratch;
            out.h += wgt * k[o + CH]; out.dhdt += wgt * k[o + CDHDT]; out.ux += wgt * k[o + CUX]; out.uz += wgt * k[o + CUZ];
            out.breaking |= k[o + CBREAK] > 0.5 && wgt >= 0.25; out.exposure = Math.max(out.exposure, (int) k[o + CEXP]); out.depth = Math.max(out.depth, (int) k[o + CDEPTH]);
        }
        double h0 = scratch[CH], h1 = scratch[CSIZE + CH], h2 = scratch[2 * CSIZE + CH], h3 = scratch[3 * CSIZE + CH];
        out.sx = ((h1 - h0) * (1 - fz) + (h3 - h2) * fz); out.sz = ((h2 - h0) * (1 - fx) + (h3 - h1) * fx);
        return out;
    }
}
