package com.hopeless.tides.core;

import java.util.Arrays;

/**
 * 0.17.0 THE SHORE MAP: where the shore is, for every water column in view (N x N blocks round the camera), so that every wave layer
 * knows it. Owner, 2026-11-02: "it has no idea where the shore is so waves dont propogate against it and it continues through the land as
 * if it doesnt exist". Before this only the surf field (128 x 128 round the player) knew the land; the open sea (FFT) and the swell beyond
 * it ran straight through islands.
 *
 * Built on a worker from the water depth of each column (client world scan), so the GPU only reads it (tides_surf.glsl tides_shoreCorner,
 * number for number with corner() here):
 *  1. PHASE TO THE SHORE (refraction). In shallow water the crests turn until they run along the depth contours (Snell's law; the shore
 *     normal is the limit; Dean and Dalrymple, Water Wave Mechanics, ch. 4). The phase is the travel time of the wave front from the shore
 *     outward, Phi = min over paths of the integral of k(h) ds (the eikonal equation |grad Phi| = k(h); Dijkstra on 16 neighbours, about 2.6 %
 *     error against the true distance), k from the linear dispersion relation at the local depth. Crests are the lines Phi + w t = const:
 *     parallel to the shore, closer together where the water is shallower, moving toward it.
 *  2. LEE (the swell is blocked by land): the share of the open swell that reaches each column along its heading (a semi-Lagrangian sweep
 *     from the map's upwave edge, land = 0), blurred over L0 / 8 for diffraction into the shadow (CHOSEN; Penney and Price 1952 give half
 *     the height on the shadow line).
 *  3. FETCH (the wind sea grows with open water upwind): Saville's (1962) effective fetch from 5 radials within 24 deg of upwind (as
 *     Surf.fetchFactors), each a sweep; past the map's edge the viewer's own fetch. Factor = Hs(F) / Hs(F viewer), at most 1.5.
 *  4. HEIGHT: the incoming rms height (swell x lee and wind sea x fetch together), shoaled by Ks = sqrt(cg0 / cg(h)) (energy flux; Green's
 *     law in shallow water); each wave's height Rayleigh in groups (Surf.waveQ) and depth-limited as a bore (Surf.boreHeight: 0.78 h, then
 *     settling to 0.6 h; Battjes and Janssen 1978). Shape: Ruessink et al. (2012) into Abreu et al. (2010), as Surf.
 *  5. REFLECTION: Kr = 0.1 xi^2, at most 1 (Battjes 1974), xi = tan beta / sqrt(H0 / L0) from the bank slope at the shore point the wave
 *     runs to: steep banks and walls reflect, gentle beaches absorb; broken waves do not reflect (Kr x (1 - broken)). The reflected wave
 *     runs back out along -grad Phi, so a wall shows a standing wave.
 *  6. FOAM: where a wave has passed 0.78 h (Surf.bore's own rule), brightest at its front.
 * Where it is drawn: water shallower than 0.5 L0 (Hydro's deep line, as the wind sea's own solve), outside the surf field (which solves
 * this properly round the player), fading over EDGE cells at the map's edge.
 */
public final class ShoreMap {
    /** The map's size in the game (blocks round the camera: 256 each way, the view distance at 16 chunks). */
    public static final int N_GAME = 512;
    /** /tides shore on|off (read by the client's TidesShore). On by default. */
    public static volatile boolean on = true;
    public static final int EDGE = 16, ROOT_R = 12, FETCH_RAYS = 5, GRAD_R = 1;
    /** Not expanded deeper than DEEP_STOP x L0 (the waves there do not feel the bottom; drawn only under 0.5 L0). */
    public static final double DEEP_STOP = 0.6, FETCH_KMAX = 1.5, KR_MAX = 1.0;
    /** A bank deeper than WALL_H at the shore is a wall (CHOSEN: Minecraft cliffs into the sea are vertical; 2 m of water at the toe). */
    public static final double WALL_H = 2.0;
    /** The phase speed never below the shallow-water speed at H_MIN (a bore runs at sqrt(g (h + H)), its own height keeps it going in the
     *  last few decimetres; CHOSEN 0.5 m, Minecraft's shallowest water is 0.9 m). */
    public static final double H_MIN = 0.5;
    public final int n; public final double g, om, L0, hs0;
    /** per cell (i + n j): Phi (rad; NaN not reached), depth (m; 0 land, NaN unknown), grad Phi, lee share, fetch factor, Kr, Abreu r, phi, shoaled rms height */
    public final float[] phi, h, gx, gz, lee, fk, kr, ar, ap, hr;
    public long water, reached, roots, ms;

    ShoreMap(int n, double g, double om, double hs0) {
        this.n = n; this.g = g; this.om = om; this.L0 = 2 * Math.PI * g / (om * om); this.hs0 = hs0; int n2 = n * n;
        phi = new float[n2]; h = new float[n2]; gx = new float[n2]; gz = new float[n2]; lee = new float[n2]; fk = new float[n2]; kr = new float[n2]; ar = new float[n2]; ap = new float[n2]; hr = new float[n2];
    }

    static final int[][] NB = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}, {2, 1}, {2, -1}, {-2, 1}, {-2, -1}, {1, 2}, {1, -2}, {-1, 2}, {-1, -2}};

    /**
     * depth: water depth (m) per column, 0 = land, NaN = not known (treated as deep open water). tp: the period the surf is drawn for (s);
     * swellToward, windToward: headings (rad, the direction the waves travel; x = cos, z = sin); swellHs, windHs: deep-water Hs (m) of each;
     * u10: the wind (m/s); fetchEdge: the viewer's own fetch (m) for radials that leave the map.
     */
    public static ShoreMap build(float[] depth, int n, double g, double tp, double swellToward, double swellHs, double windToward, double u10, double windHs, double fetchEdge) {
        long t0 = System.nanoTime(); int n2 = n * n; double om = 2 * Math.PI / tp, hs0 = Math.sqrt(swellHs * swellHs + windHs * windHs);
        ShoreMap m = new ShoreMap(n, g, om, hs0);
        boolean[] wet = new boolean[n2]; double[] hh = new double[n2];
        for (int c = 0; c < n2; c++) { float d = depth[c]; boolean w = Float.isNaN(d) || d > 0; wet[c] = w; hh[c] = Float.isNaN(d) ? 4 * m.L0 : d; m.h[c] = w ? (float) hh[c] : 0; if (w) m.water++; }
        // k at each depth (depths are block counts, a few distinct values)
        java.util.HashMap<Long, Double> kc = new java.util.HashMap<>(); double[] kk = new double[n2];
        for (int c = 0; c < n2; c++) if (wet[c]) { long key = Math.round(hh[c] * 64); Double v = kc.get(key); if (v == null) { v = Surf.k(om, Math.max(H_MIN, hh[c]), g); kc.put(key, v); } kk[c] = v; }
        // 1. the phase from the shore: Phi = the integral of k(h) along the straight line from the nearest land point (the exact Euclidean
        // feature transform, Felzenszwalb and Huttenlocher 2012), sampled every cell. Crests (Phi + w t = const) then follow the shore's
        // shape at every angle (no grid staircase), closer together where the water is shallower. The rays are straight (the bending by
        // refraction between the shore and the point is left out; along a coast the depth contours run with the shore, where it is small).
        int[] near = new int[n2]; double[] dist = edt(wet, n, near); int[] root = new int[n2]; Arrays.fill(root, -1);
        double stop = DEEP_STOP * m.L0; boolean[] done = new boolean[n2]; double[] P = new double[n2];
        for (int c = 0; c < n2; c++) { if (!wet[c] || hh[c] > stop || near[c] < 0) continue;
            int L = near[c]; double lx = L % n, lz = L / n, cx = c % n, cz = c / n, D = dist[c]; if (!(D > 0)) continue;
            double ux = (cx - lx) / D, uz = (cz - lz) / D, sum = 0; int steps = (int) Math.ceil(D - 0.5); double ds = (D - 0.5) / Math.max(1, steps); int first = -1;
            for (int k = 0; k < Math.max(1, steps); k++) { double tt = 0.5 + (k + 0.5) * ds, px = lx + ux * tt, pz = lz + uz * tt; int x = (int) Math.round(px), z = (int) Math.round(pz); int d = x + n * z;
                if (first < 0 && d >= 0 && d < n2 && wet[d]) first = d;
                // k bilinear over the water cells round the sample (a block's depth is one value; between blocks it changes smoothly)
                int x0 = (int) Math.floor(px), z0 = (int) Math.floor(pz); double fx = px - x0, fz = pz - z0, ks = 0, ws = 0;
                for (int q2 = 0; q2 < 4; q2++) { int xx = x0 + (q2 & 1), zz = z0 + (q2 >> 1); if (xx < 0 || zz < 0 || xx >= n || zz >= n) continue; int e = xx + n * zz; if (!wet[e]) continue; double wq = ((q2 & 1) == 1 ? fx : 1 - fx) * ((q2 >> 1) == 1 ? fz : 1 - fz); ks += wq * kk[e]; ws += wq; }
                sum += (ws > 1e-9 ? ks / ws : kk[c]) * ds; }
            P[c] = 0.5 * kk[first >= 0 ? first : c] * 0.5 + sum; root[c] = first >= 0 ? first : c; done[c] = true; m.reached++; }
        { double[] Pb = blur(P, done, n, 1); for (int c = 0; c < n2; c++) if (done[c]) P[c] = Pb[c]; }   // the land points' half-cell staircase
        for (int c = 0; c < n2; c++) if (root[c] == c) m.roots++;
        for (int c = 0; c < n2; c++) m.phi[c] = done[c] ? (float) P[c] : Float.NaN;
        // grad Phi: central differences of Phi itself (so the drawn slopes match the drawn crests), smoothed over GRAD_R
        { double[] ax = new double[n2], az = new double[n2];
          for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) { int c = i + n * j; if (!done[c]) continue;
              boolean l = i > 0 && done[c - 1], r = i < n - 1 && done[c + 1], dn = j > 0 && done[c - n], up = j < n - 1 && done[c + n];
              ax[c] = l && r ? (P[c + 1] - P[c - 1]) / 2 : r ? P[c + 1] - P[c] : l ? P[c] - P[c - 1] : 0;
              az[c] = dn && up ? (P[c + n] - P[c - n]) / 2 : up ? P[c + n] - P[c] : dn ? P[c] - P[c - n] : 0; }
          ax = blur(ax, done, n, GRAD_R); az = blur(az, done, n, GRAD_R);
          for (int c = 0; c < n2; c++) if (done[c]) { m.gx[c] = (float) ax[c]; m.gz[c] = (float) az[c]; } }
        // 2. the swell's lee, 3. the wind sea's fetch
        double[] E = swellHs > 0 ? sweep(wet, n, swellToward, false, 1, 0) : null;
        if (E != null) { int rb = (int) Math.max(1, Math.min(8, Math.round(m.L0 / 8))); E = blur(E, wet, n, rb); E = blur(E, wet, n, rb); }
        double hd = windHs > 0 ? FftSea.hsFullCached(u10, g, FftSea.quantFetch(fetchEdge)) : 0; double[] F = null;
        if (hd > 0) { F = new double[n2]; double den = 0; for (int r = 0; r < FETCH_RAYS; r++) den += Math.cos(Math.toRadians(-24 + 12 * r));
            for (int r = 0; r < FETCH_RAYS; r++) { double off = Math.toRadians(-24 + 12 * r), cw = Math.cos(off); double[] f = sweep(wet, n, windToward + off, true, 0, fetchEdge); for (int c = 0; c < n2; c++) F[c] += f[c] * cw * cw / den; } }
        // 5. the bank slope at each shore point, so Kr
        double[] krRoot = new double[n2];
        for (int c = 0; c < n2; c++) if (root[c] == c) { int ci = c % n, cj = c / n; double hr0 = hh[c], beta;
            if (hr0 >= WALL_H) beta = 10; else { double best = Double.POSITIVE_INFINITY;
                for (int dz = -ROOT_R; dz <= ROOT_R; dz++) for (int dx = -ROOT_R; dx <= ROOT_R; dx++) { int x = ci + dx, z = cj + dz; if (x < 0 || z < 0 || x >= n || z >= n) continue; int d = x + n * z; if (wet[d] && hh[d] >= hr0 + 0.99) best = Math.min(best, Math.hypot(dx, dz)); }
                beta = 1 / Math.min(Math.max(best, 1), ROOT_R) * (Double.isInfinite(best) ? 0.5 : 1); }   // none within ROOT_R: gentler than 1 in ROOT_R
            double xi = beta / Math.sqrt(Math.max(1e-6, hs0) / m.L0); krRoot[c] = Math.min(KR_MAX, 0.1 * xi * xi); }
        // 4. heights and shape
        double cg0 = g / (2 * om);
        for (int c = 0; c < n2; c++) { if (!wet[c]) continue;
            double e = E == null ? 0 : E[c], fkc = F == null ? 1 : Math.min(FETCH_KMAX, FftSea.hsFullCached(u10, g, FftSea.quantFetch(Math.max(1, F[c]))) / hd);
            m.lee[c] = (float) e; m.fk[c] = (float) fkc; if (!done[c]) continue; m.kr[c] = root[c] >= 0 ? (float) krRoot[root[c]] : 0;
            double hin = Math.sqrt((swellHs * e) * (swellHs * e) + (windHs * fkc) * (windHs * fkc)) / Math.sqrt(2);   // rms (Rayleigh: Hs = sqrt 2 Hrms)
            double ks = Math.sqrt(cg0 / Surf.cg(om, kk[c], hh[c])), hrc = hin * ks; m.hr[c] = (float) hrc;
            double[] bp = Surf.ruessink(Math.sqrt(2) * Math.min(hrc, Surf.GAMMA_B * hh[c]), kk[c], hh[c]), rp = Surf.abreu(bp[0], bp[1]); m.ar[c] = (float) rp[0]; m.ap[c] = (float) rp[1]; }
        m.ms = (System.nanoTime() - t0) / 1_000_000; return m;
    }

    /**
     * A sweep along 'toward' (rad) from the map's upwind edge: each cell takes the value one cell back along the direction (bilinear between
     * the two cells there). fetch = false: share reaching (land 0, edge 'edgeVal'); fetch = true: open water length (m; land 0, edge
     * 'edgeVal' + steps).
     */
    static double[] sweep(boolean[] wet, int n, double toward, boolean fetch, double edgeVal, double edgeFetch) {
        double dx = Math.cos(toward), dz = Math.sin(toward); boolean xMajor = Math.abs(dx) >= Math.abs(dz); double[] v = new double[n * n];
        double big = xMajor ? dx : dz, small = xMajor ? dz : dx, step = 1 / Math.abs(big), off = -small * step;   // back one cell on the major axis, 'off' on the minor
        int sgn = big > 0 ? 1 : -1;
        for (int s = 0; s < n; s++) { int a = sgn > 0 ? s : n - 1 - s, ab = a - sgn;
            for (int b = 0; b < n; b++) { int c = xMajor ? a + n * b : b + n * a; if (!wet[c]) { v[c] = 0; continue; }
                double bb = b + off; double up;
                if (ab < 0 || ab >= n || bb < 0 || bb > n - 1) up = fetch ? edgeFetch : edgeVal;
                else { int b0 = (int) Math.floor(bb), b1 = Math.min(n - 1, b0 + 1); double f = bb - b0; int c0 = xMajor ? ab + n * b0 : b0 + n * ab, c1 = xMajor ? ab + n * b1 : b1 + n * ab; up = v[c0] * (1 - f) + v[c1] * f; }
                v[c] = fetch ? up + step : up; } }
        return v;
    }
    /** Euclidean distance (cells) from each cell to the nearest land cell's centre (exact; Felzenszwalb and Huttenlocher 2012, two 1-D passes). */
    static double[] edt(boolean[] wet, int n, int[] near) {
        double INF = 1e12; double[] f = new double[n * n], t = new double[n], d = new double[n]; int[] v = new int[n], arg = new int[n], col = new int[n * n]; double[] z = new double[n + 1];
        for (int c = 0; c < n * n; c++) f[c] = wet[c] ? INF : 0;
        for (int pass = 0; pass < 2; pass++) for (int line = 0; line < n; line++) {
            for (int q = 0; q < n; q++) t[q] = pass == 0 ? f[q + n * line] : f[line + n * q];
            int k = 0; v[0] = 0; z[0] = -INF; z[1] = INF;
            for (int q = 1; q < n; q++) { double sq; while (true) { int p = v[k]; sq = ((t[q] + q * (double) q) - (t[p] + p * (double) p)) / (2.0 * q - 2.0 * p); if (sq <= z[k] && k > 0) k--; else break; } if (sq <= z[k]) { v[0] = q; z[0] = -INF; z[1] = INF; k = 0; continue; } k++; v[k] = q; z[k] = sq; z[k + 1] = INF; }
            k = 0; for (int q = 0; q < n; q++) { while (z[k + 1] < q) k++; int p = v[k]; d[q] = (q - p) * (double) (q - p) + t[p]; arg[q] = p; }
            for (int q = 0; q < n; q++) if (pass == 0) { f[q + n * line] = d[q]; col[q + n * line] = arg[q]; } else { f[line + n * q] = d[q]; int p = arg[q]; near[line + n * q] = d[q] >= INF / 2 ? -1 : col[line + n * p] + n * p; } }
        for (int c = 0; c < n * n; c++) f[c] = Math.sqrt(f[c]); return f;
    }
    /** Box blur (radius r) over water cells only. */
    static double[] blur(double[] v, boolean[] wet, int n, int r) {
        double[] a = new double[n * n], b = new double[n * n];
        for (int j = 0; j < n; j++) { double s = 0; int k = 0; for (int i = -r; i < n; i++) { int add = i + r, rem = i - r - 1; if (add < n && wet[add + n * j]) { s += v[add + n * j]; k++; } if (rem >= 0 && wet[rem + n * j]) { s -= v[rem + n * j]; k--; } if (i >= 0) a[i + n * j] = wet[i + n * j] && k > 0 ? s / k : 0; } }
        for (int i = 0; i < n; i++) { double s = 0; int k = 0; for (int j = -r; j < n; j++) { int add = j + r, rem = j - r - 1; if (add < n && wet[i + n * add]) { s += a[i + n * add]; k++; } if (rem >= 0 && wet[i + n * rem]) { s -= a[i + n * rem]; k--; } if (j >= 0) b[i + n * j] = wet[i + n * j] && k > 0 ? s / k : 0; } }
        return b;
    }

    static double fade(double e) { return e >= 1 ? 1 : e <= 0 ? 0 : e * e * (3 - 2 * e); }
    static double smooth(double u) { u = Math.max(0, Math.min(1, u)); return u * u * (3 - 2 * u); }
    /** The cells round block corner (I, J): {reached count, phi, h, gx, gz, lee, fk, kr, r, phi_a, hr, water count}: the lee and fetch
     *  averaged over the water cells, the rest over the reached ones (null: no water). */
    public double[] cells(int I, int J) {
        double[] a = new double[12]; int k = 0, w = 0;
        for (int dj = -1; dj <= 0; dj++) for (int di = -1; di <= 0; di++) { int i = I + di, j = J + dj; if (i < 0 || j < 0 || i >= n || j >= n) continue; int c = i + n * j; if (!(h[c] > 0)) continue;
            w++; a[5] += lee[c]; a[6] += fk[c]; if (Float.isNaN(phi[c])) continue;
            k++; a[1] += phi[c]; a[2] += h[c]; a[3] += gx[c]; a[4] += gz[c]; a[7] += kr[c]; a[8] += ar[c]; a[9] += ap[c]; a[10] += hr[c]; }
        if (w == 0) return null; a[5] /= w; a[6] /= w; if (k > 0) for (int q = 1; q < 11; q++) if (q != 5 && q != 6) a[q] /= k; a[0] = k; a[11] = w; return a;
    }
    /** The weight the shore train has at corner (I, J): shallower than 0.5 L0, inside the map's edge fade, outside the surf field (fieldW). */
    public double weight(double[] a, int I, int J, double fieldW) {
        if (a == null || a[0] < 0.5) return 0; double we = fade(Math.min(I, n - I) / (double) EDGE) * fade(Math.min(J, n - J) / (double) EDGE);
        return we * (1 - smooth((a[2] - 0.3 * L0) / (0.2 * L0))) * (1 - fieldW);
    }
    /**
     * The shore train at block corner (I, J) (tides_shoreCorner, number for number). clk: Surf.clock(om, t); xm, zm: the corner's world
     * position mod Surf.FRAME (the wave groups). {eta, slope x, slope z, weight, foam}.
     */
    public double[] corner(int I, int J, double clk, double xm, double zm, double fieldW) {
        double[] a = cells(I, J); double w = weight(a, I, J, fieldW); if (w <= 0) return new double[5];
        double wt = clk + a[1], cyc = wt / (2 * Math.PI), nf = Math.floor(cyc), fr = cyc - nf; int nn = (int) nf;
        double q1 = Surf.waveQ(nn, xm, zm), q = q1; if (fr < 0.25) { double q0 = Surf.waveQ(nn - 1, xm, zm); q = q0 + (q1 - q0) * smooth(fr / 0.25); }
        double hd = Math.max(a[2], 0.05), H = Surf.boreHeight(a[10] * q, hd), amp = 0.5 * H; double[] S = Surf.shape(wt, a[8], a[9]);
        double broken = smooth((a[10] * q / (Surf.GAMMA_B * hd) - 0.85) / 0.3), foam = w * broken * Math.exp(-fr * 5);
        double eta = amp * S[0], sx = amp * S[1] * a[3], sz = amp * S[1] * a[4];
        double kre = a[7] * (1 - broken), wr = clk - a[1]; eta += kre * amp * Math.sin(wr); sx -= kre * amp * Math.cos(wr) * a[3]; sz -= kre * amp * Math.cos(wr) * a[4];
        return new double[]{eta, sx, sz, w, foam};
    }
    /** The lee share (swell) and the fetch factor (wind sea) at corner (I, J); {1, 1} where nothing is known. */
    public double[] factors(int I, int J) { double[] a = cells(I, J); return a == null ? new double[]{1, 1} : new double[]{a[5], a[6]}; }
    public String describe() { return String.format("shore map %d x %d: %d water, %d reached, %d shore points, L0 %.0f m, Hs in %.2f m, built in %d ms", n, n, water, reached, roots, L0, hs0, ms); }

    /** A binary min-heap of (index, key) with lazy deletion. */
    static final class Heap {
        int[] idx; double[] key; int size;
        Heap(int cap) { idx = new int[cap]; key = new double[cap]; }
        void push(int i, double k) { if (size == idx.length) { idx = Arrays.copyOf(idx, size * 2); key = Arrays.copyOf(key, size * 2); }
            int p = size++; while (p > 0) { int u = (p - 1) >> 1; if (key[u] <= k) break; idx[p] = idx[u]; key[p] = key[u]; p = u; } idx[p] = i; key[p] = k; }
        int popIdx() { int top = idx[0]; int li = idx[--size]; double lk = key[size]; int p = 0;
            while (true) { int c = 2 * p + 1; if (c >= size) break; if (c + 1 < size && key[c + 1] < key[c]) c++; if (key[c] >= lk) break; idx[p] = idx[c]; key[p] = key[c]; p = c; }
            idx[p] = li; key[p] = lk; return top; }
    }
}
