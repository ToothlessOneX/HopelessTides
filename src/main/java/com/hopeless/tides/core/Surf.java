package com.hopeless.tides.core;

/**
 * 0.14.0: THE SURF, ported from Hopeless Hydro 0.11.0 (Surf.cs, 2026-10-19; owner, with a recording of a real beach: "this is how our
 * ocean should look as water hits the shore"). Pure Java, no Minecraft types: tests and previews run it offline.
 *
 * Processes, not shapes: the waves come in from the open sea and the seabed does the rest.
 *  1. Refraction: each swell component's phase obeys the eikonal |grad phi| = k(h), k from w^2 = g k tanh(k h), solved by fast marching
 *     from the open sea (Sethian 1996). Crests turn to face the shore because the seabed slows them.
 *  2. Shoaling: the energy flux E cg along the rays is conserved (upwind finite volume in marching order), with a little sideways
 *     leak for diffraction.
 *  3. Breaking: a component's height is capped at 0.6 h (Thornton & Guza 1983); single waves break where they pass 0.78 h (McCowan
 *     1894), so bigger waves of a group break further out: several lines of surf.
 *  4. Shape: Ruessink, Ramaekers & van Rijn (2012) nonlinearity from the Ursell number, into Abreu et al.'s (2010) analytic wave:
 *     sinusoid offshore, peaked as it shoals, saw-toothed (a bore) in the surf.
 *  5. Run-up: Stockdon et al. (2006) R2 from the deep-water height, wavelength and beach slope; each bore runs up and back
 *     ballistically (Shen & Meyer 1963) on the GPU.
 * Grid: N x N columns of Dx m (Tides: 1 m, the blocks). Hydro's per-component heights and shape are kept; Tides' Fourier sea carries
 * the open sea, so the GPU gets the peak component (the bores) and the blend weight.
 */
public final class Surf {
    private Surf() {}
    public static final double GAMMA_B = 0.78, SETTLED_BORE = 0.6, Q2 = 1.97788, DEPTH_LIMIT = 0.6;
    public static final int K = 4, DOM = 1;
    public static final double[] DIR_OFF = {-0.44, -0.14, 0.17, 0.47}, PER_MUL = {1.25, 1.0, 0.85, 0.7}, PHASE0 = {0.0, 2.1, 4.4, 1.3};   // Hydro Swell, CHOSEN
    public static final double UNIT = 1.0 / (4 * Math.sqrt(K / 2.0));
    public static final double DEEP_FACTOR = 0.5, SWASH_REACH = 1.6, MIN_SLOPE = 0.008, MAX_SLOPE = 0.35;
    public static final double GROUP_WAVES = 6.5, CREST_METRES = 45;
    /** 0.14.0: the wave frame repeats every FRAME m of world (a multiple of CREST_METRES): one seam in 46 km. */
    public static final int FRAME = 46080;
    /** 0.16.1 the plane swell train (deep water, straight crests) for the sea the field was solved for: the K components at their deep
     *  wavenumbers and headings, height UNIT x hs0 x share each, sinusoids. Beyond the field and in its edge fade. {eta, slope x, slope z}. */
    public static double[] planeSwell(Field f, double[] clk, double share, double Xm, double Zm) {
        double e = 0, sx = 0, sz = 0, a = UNIT * f.hs0 * share; if (a <= 0) return new double[3];
        for (int q = 0; q < K; q++) { double k0 = f.om[q] * f.om[q] / f.g, d = f.heading + DIR_OFF[q], ca = Math.cos(d), sa = Math.sin(d), th = k0 * ((Xm - 0.5) * ca + (Zm - 0.5) * sa) - clk[q] + PHASE0[q];   /* - 0.5: the field's phase is at a cell's low corner and a block corner averages the 4 cells round it */
            e += a * Math.sin(-th); double ds = a * Math.cos(-th); sx -= ds * k0 * ca; sz -= ds * k0 * sa; }
        return new double[]{e, sx, sz}; }
    public static final int SWELL_EDGE = 16;
    /** 0.16.2 LOCAL FETCH: the wind sea grows with the open water upwind (fetch-limited growth, JONSWAP: FftSea.hsFull(u, g, fetch)), so next
     *  to a shore the wind blows off, the chop is small and grows out to sea, and on a shore the wind blows onto it arrives full. For each sea
     *  column: Saville's effective fetch over FETCH_RAYS radials within 24 deg of upwind (F = sum F_i cos^2 a / sum cos a; Saville 1962, as
     *  TidesSurf does at the viewer), each radial marched over the field until dry ground; a radial that leaves the field takes the viewer's
     *  fetch (measured over the world map); all radials out: the viewer's fetch. The factor: Hs(F) / Hs(fetchDrawn), capped at FETCH_KMAX (CHOSEN; the drawn sea is the viewer's fetch). */
    public static final int FETCH_RAYS = 5; public static final double FETCH_KMAX = 1.5;
    public static void fetchFactors(Field f, double windToward, double u10, double fetchDrawn) {
        int n = f.n, n2 = n * n; f.fetchK = new float[n2]; double hd = FftSea.hsFullCached(u10, f.g, FftSea.quantFetch(fetchDrawn)); if (!(hd > 0)) { f.fetchK = null; return; }
        double up = windToward + Math.PI, sum = 0; int m = 0; double[] ca = new double[FETCH_RAYS], sa = new double[FETCH_RAYS], cw = new double[FETCH_RAYS]; double den = 0;
        for (int r = 0; r < FETCH_RAYS; r++) { double off = Math.toRadians(-24 + 12 * r), a = up + off; ca[r] = Math.cos(a); sa[r] = Math.sin(a); cw[r] = Math.cos(off); den += cw[r]; }
        for (int c = 0; c < n2; c++) { if (!f.seaCol[c]) continue; int ci = c % n, cj = c / n; double num = 0; int openRays = 0;
            for (int r = 0; r < FETCH_RAYS; r++) { double d = 1, F = Double.POSITIVE_INFINITY;
                for (; d < 2 * n; d += 1) { int x = (int) Math.floor(ci + 0.5 + ca[r] * d), z = (int) Math.floor(cj + 0.5 + sa[r] * d); if (x < 0 || z < 0 || x >= n || z >= n) break; if (!f.seaCol[x + n * z]) { F = d * f.dx; break; } }
                if (Double.isInfinite(F)) { openRays++; F = fetchDrawn; } num += F * cw[r] * cw[r]; }   /* a radial that leaves the field: the viewer's own fetch beyond (TidesSurf measured it over the world map) */
            double fe = openRays == FETCH_RAYS ? fetchDrawn : num / den, k = Math.min(FETCH_KMAX, FftSea.hsFullCached(u10, f.g, FftSea.quantFetch(fe)) / hd); f.fetchK[c] = (float) k; sum += k; m++; if (k < 0.9) f.fetchCalmed++; }
        f.fetchMean = m > 0 ? sum / m : 1; }
    /** The fetch factor at block corner (I, J): the 4 cells' mean (sea cells), fading to 1 over SWELL_EDGE cells at the field's edge. */
    public static double fetchCorner(Field f, int I, int J) {
        if (f.fetchK == null) return 1; double k = 0; int m = 0;
        for (int dj = -1; dj <= 0; dj++) for (int di = -1; di <= 0; di++) { int i = I + di, j = J + dj; if (i < 0 || j < 0 || i >= f.n || j >= f.n) continue; int c = i + f.n * j; if (!f.seaCol[c]) continue; k += f.fetchK[c]; m++; }
        if (m == 0) return 1; double we = Field.fade(Math.min(I, f.n - I) / (double) SWELL_EDGE) * Field.fade(Math.min(J, f.n - J) / (double) SWELL_EDGE); return 1 + (k / m - 1) * we; }
    /** 0.16.1 steering: the turn is drawn as a blend of the field rotated in STEER_BIN steps (the field itself cannot bend); the height
     *  factor is capped at STEER_KMAX (ray focusing; CHOSEN); STEER_LEE: the height factor where no wave from the open sea reaches (behind
     *  land: only the local chop, CHOSEN); STEER_KMIN: the least height factor (CHOSEN: the fetch already calms the wind sea near a lee shore,
     *  so the steering only adds the swell's shadow); STEER_EDGE: cells over which the steering fades out at the field's edge. */
    public static final double STEER_BIN = Math.toRadians(10), STEER_KMAX = 1.5, STEER_KMIN = 0.5, STEER_LEE = 0.5; public static final int STEER_EDGE = 24;
    /** 0.16.1: the steering smoothed over a (2r+1)^2 box of reached sea cells, twice (unit vectors for the turn): the field turns over
     *  several blocks, not from block to block (the drawn sea blends rotated copies; a jittery turn shows as noise). STEER_SMOOTH: r, CHOSEN. */
    public static final int STEER_SMOOTH = 2;
    static void smoothSteer(Field f, int N, boolean[] sea, int r) {
        int n2 = N * N; float[] cx = new float[n2], cy = new float[n2], k = new float[n2]; float[] tx = new float[n2], ty = new float[n2], tk = new float[n2]; float[] wt = new float[n2], tw = new float[n2];
        for (int c = 0; c < n2; c++) if (sea[c] && !Float.isNaN(f.steerK[c])) { cx[c] = (float) Math.cos(f.steerA[c]); cy[c] = (float) Math.sin(f.steerA[c]); k[c] = f.steerK[c]; wt[c] = 1; }
        for (int pass = 0; pass < 2; pass++) for (int axis = 0; axis < 2; axis++) {
            for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) { int c = i + N * j; float sx = 0, sy = 0, sk = 0, sw = 0;
                for (int d = -r; d <= r; d++) { int ii = axis == 0 ? i + d : i, jj = axis == 0 ? j : j + d; if (ii < 0 || jj < 0 || ii >= N || jj >= N) continue; int q = ii + N * jj; if (wt[q] <= 0) continue; sx += cx[q]; sy += cy[q]; sk += k[q]; sw += wt[q]; }
                tx[c] = sx; ty[c] = sy; tk[c] = sk; tw[c] = sw; }
            for (int c = 0; c < n2; c++) { if (wt[c] <= 0 || tw[c] <= 0) continue; cx[c] = tx[c] / tw[c]; cy[c] = ty[c] / tw[c]; k[c] = tk[c] / tw[c]; } }
        for (int c = 0; c < n2; c++) if (wt[c] > 0) { f.steerA[c] = (float) Math.atan2(cy[c], cx[c]); f.steerK[c] = k[c]; }
    }
    /** The two rotation bins a turn falls between and the blend: {angle0, angle1, weight of angle1, variance factor}. The two rotated copies
     *  are nearly independent seas, so a plain blend would lose height between bins (1/sqrt 2 at the middle) and show bands; the factor
     *  1 / sqrt((1-w)^2 + w^2) keeps the sea's variance (the usual blend for mixing random fields). */
    public static double[] steerBins(double a) { double b = a / STEER_BIN, b0 = Math.floor(b), w = b - b0; return new double[]{b0 * STEER_BIN, (b0 + 1) * STEER_BIN, w, 1 / Math.sqrt((1 - w) * (1 - w) + w * w)}; }

    // ------------------------------------------------------------------ the maths (Hydro SurfMath)
    /** Wavenumber for w in water h deep: w^2 = g k tanh(k h) (Newton from Eckart's guess). */
    public static double k(double om, double h, double g) {
        h = Math.max(0.01, h); double k0 = om * om / g, k = k0 / Math.sqrt(Math.tanh(k0 * h));
        for (int it = 0; it < 6; it++) { double t = Math.tanh(k * h), f = g * k * t - om * om, df = g * t + g * k * h * (1 - t * t), dk = f / df; k -= dk; if (Math.abs(dk) < 1e-10 * k) break; }
        return k;
    }
    public static double cg(double om, double k, double h) { double kh = Math.min(k * Math.max(h, 0.01), 40); return om / k * 0.5 * (1 + 2 * kh / Math.sinh(2 * kh)); }
    /** Ruessink et al. (2012) eq. 9-11: {B, psi}. */
    public static double[] ruessink(double hs, double k, double h) {
        final double p1 = 0, p2 = 0.857, p3 = -0.471, p4 = 0.297, p5 = 0.815, p6 = 0.672;
        double kh = k * Math.max(h, 0.01), ur = 0.375 * hs * k / (kh * kh * kh);
        if (!(ur > 1e-9)) return new double[]{0, -Math.PI / 2};
        return new double[]{p1 + (p2 - p1) / (1 + Math.exp((p3 - Math.log10(ur)) / p4)), (-90 + 90 * Math.tanh(p5 / Math.pow(ur, p6))) * Math.PI / 180};
    }
    /** Abreu's {r, phi} from Ruessink's B, psi (eq. 12-14); r under 0.6. */
    public static double[] abreu(double B, double psi) { double b = B * Math.sqrt(2) / Math.sqrt(9 + 2 * B * B); return new double[]{Math.min(0.6, 2 * b / (1 + b * b)), -psi - Math.PI / 2}; }
    /** Abreu et al. (2010) eq. 7 (Uw = 1): {S, dS/d(wt)}. Zero mean, half range 1. */
    public static double[] shape(double wt, double r, double phi) {
        double f = Math.sqrt(1 - r * r), off = r * Math.sin(phi) / (1 + f), s = Math.sin(wt), c = Math.cos(wt), den = 1 - r * Math.cos(wt + phi), num = s + off;
        return new double[]{f * num / den, f * (c * den - num * r * Math.sin(wt + phi)) / (den * den)};
    }
    /** Stockdon et al. (2006) eq. 19 (eq. 18 when xi0 under 0.3): 2 % run-up (m). */
    public static double r2(double h0, double l0, double beta) {
        if (!(h0 > 0) || !(l0 > 0)) return 0; double hl = Math.sqrt(h0 * l0), xi = beta / Math.sqrt(h0 / l0);
        if (xi < 0.3) return 0.043 * hl;
        return 1.1 * (0.35 * beta * hl + Math.sqrt(h0 * l0 * (0.563 * beta * beta + 0.004)) / 2);
    }
    /** HW_Hash / HW_Noise (32-bit unsigned), bit for bit as the shader (tides_look.glsl tw_surfHash). */
    public static double hash(int x, int y) { int h = x * 1597334677 ^ y * (int) 3812015801L; h ^= h >>> 16; h *= (int) 2246822519L; h ^= h >>> 13; return (h & 0x00ffffff) / 16777215.0; }
    public static double noise(double px, double py) {
        int ix = (int) Math.floor(px), iy = (int) Math.floor(py); double fx = px - ix, fy = py - iy;
        double ux = fx * fx * fx * (fx * (fx * 6 - 15) + 10), uy = fy * fy * fy * (fy * (fy * 6 - 15) + 10);
        double a = hash(ix, iy) + (hash(ix + 1, iy) - hash(ix, iy)) * ux, b = hash(ix, iy + 1) + (hash(ix + 1, iy + 1) - hash(ix, iy + 1)) * ux;
        return a + (b - a) * uy;
    }
    /** Wave n's height over the rms at (x, y): Rayleigh (Longuet-Higgins 1952), in groups, wandering along the crest. */
    public static double waveQ(int n, double x, double y) { return waveBase(n) * (0.78 + 0.44 * noise(x / CREST_METRES + n * 0.37, y / CREST_METRES + n * 0.61)); }
    public static double waveBase(int n) { double u = Math.max(0.002, hash(n, 7919)); return Math.sqrt(-Math.log(u)) * (0.72 + 0.56 * noise(n / GROUP_WAVES, 3.5)); }
    /** A bore's height from its unbroken height: unchanged to 0.78 h, then settling to 0.6 h. */
    public static double boreHeight(double qh, double h) {
        double hb = GAMMA_B * h; if (qh <= hb) return qh;
        double over = Math.min(1, (qh / hb - 1) / 0.5); over = over * over * (3 - 2 * over); return hb + (SETTLED_BORE * h - hb) * over;
    }
    static double smooth(double u) { u = Math.max(0, Math.min(1, u)); return u * u * (3 - 2 * u); }
    /** CHOSEN (Hydro): the sea gives way to the bore train as rho goes 0.35 -> 0.8. */
    public static double blend(double rho) { return smooth((rho - 0.35) / 0.45); }
    /** w t wrapped every 4096 waves (float precision on the GPU; the CPU wraps the same way). */
    public static final double WRAP = 2 * Math.PI * 4096;
    public static double clock(double om, double t) { double a = om * t; return a - WRAP * Math.floor(a / WRAP); }

    // ------------------------------------------------------------------ input and field
    /** What the model reads. bed: ground top (m, world y) per column; depth: water depth (m) of sea columns (0 = dry); known: read. */
    public static final class Input {
        public int n; public double dx = 1, g = 9.81, sea = Double.NaN, hs0, tp, heading;
        public double[] bed, depth; public boolean[] known;
        public final double[] om = new double[K], dir = new double[K];
        public int anchor = -1; public Field match;
        /** 0.16.3: the same water for another sea (the wind sea): bed, depth and frame shared. */
        public Input copyFor(double hs, double tp, double heading) { Input c = new Input(); c.n = n; c.dx = dx; c.g = g; c.sea = sea; c.bed = bed; c.depth = depth; c.known = known; c.seedX0 = seedX0; c.seedY0 = seedY0; c.sea(hs, tp, heading); return c; }
        /** 0.14.0: the wave frame of column 0 (m): the open sea's plane-wave phase and the waves' height noise are taken in this frame
         *  (world position mod FRAME), so two grids over the same sea (the server's, the client's, a moved one) agree. */
        public double seedX0, seedY0;
        /** Components from the deep-water sea: Hs (m), peak period (s), heading the waves travel toward (rad, in the grid's x/y). */
        public void sea(double hs, double tp, double heading) {
            hs0 = hs; this.tp = tp; this.heading = heading;
            for (int i = 0; i < K; i++) { om[i] = 2 * Math.PI / Math.max(0.1, tp * PER_MUL[i]); dir[i] = heading + DIR_OFF[i]; }
        }
    }
    public static final class Field {
        public int n; public double dx, g, hs0, tp, heading, sea; public final double[] om = new double[K];
        public float[][] phi = new float[K][], hc = new float[K][];
        public float[] rho, hrms, shR, shP, depth, bed; public boolean[] seaCol;
        public float[] shoreX, shoreTheta, beta, run2, upX, upY;   // swash, per dry column within reach (NaN elsewhere)
        // 0.16.0 steep shores (cliffs, rock walls, sea walls, docks): per SEA column, the distance (m) to the nearest wall foot, that wall's
        // reflection Kr (Seelig & Ahrens 1981), its 2 % run-up R2 (EurOtop 2018 eq. 5.4-5.5) and the direction from the wall out to sea;
        // NaN where no wall is within WALL_REACH
        public float[] wallDist, wallKr, wallR2, wallNx, wallNy; public int wallColumns; public double meanWallR2, meanWallKr;
        /** 0.16.1 STEERING: the open sea (Tides' Fourier field, one direction for the world) follows the surf's solution here. steerA: how far
         *  the peak wave has turned from its deep-water heading at this column (rad; the eikonal's phase gradient: refraction toward the
         *  shore, bending round headlands); steerK: its height over the deep height with the shoaling taken out (Kr x Kd: rays spreading,
         *  focusing and the lee behind land), NaN where no wave reaches. */
        public float[] steerA, steerK; public int steeredColumns, leeColumns;
        /** 0.16.2: the wind sea's height here over the height the Fourier sea is drawn at (fetchFactors); null: 1 everywhere. */
        public float[] fetchK; public double fetchMean = 1; public int fetchCalmed;
        /** 0.16.1 THE SWELL TRAIN at block corner (I, J) of the field (tides_surf.glsl tides_swellCorner, number for number): the swell's K
         *  components from the surf's solution (refracted phase phi_i, height hc_i, Abreu shape), less the bore train's weight, times share
         *  (the swell's part of the sea the surf was solved for); fading over SWELL_EDGE cells at the field's edge into the plane train
         *  (planeSwell) that runs beyond it. clk: the K clocks; Xm, Zm: the corner's world position mod FRAME. {eta, slope x, slope z}. */
        public double[] swellCorner(int I, int J, double[] clk, double share, double Xm, double Zm) {
            double[] pl = planeSwell(this, clk, share, Xm, Zm);
            double ex = fade(Math.min(I, n - I) / (double) SWELL_EDGE), ez = fade(Math.min(J, n - J) / (double) SWELL_EDGE), we = ex * ez;
            if (we <= 0) return pl;
            double[] ph = new double[K], hh = new double[K]; double r = 0, pp = 0, rh = 0, gx = 0, gz = 0; int m = 0, sea = 0;
            for (int dj = -1; dj <= 0; dj++) for (int di = -1; di <= 0; di++) { int i = I + di, j = J + dj; if (i < 0 || j < 0 || i >= n || j >= n) continue; int c = i + n * j; if (!seaCol[c]) continue; sea++;
                if (Float.isNaN(steerK[c])) continue; m++; for (int q = 0; q < K; q++) { ph[q] += phi[q][c]; hh[q] += hc[q][c]; } r += shR[c]; pp += shP[c]; rh += rho[c];
                double[] g2 = grad(i, j); gx += g2[0]; gz += g2[1]; }   // as the GPU's row 1
            if (sea == 0) return pl;
            double e = 0, sx = 0, sz = 0;
            if (m > 0) { for (int q = 0; q < K; q++) { ph[q] /= m; hh[q] /= m; } r /= m; pp /= m; rh /= m; gx /= m; gz /= m;
                double wb = smooth((rh - 0.35) / 0.45), kd = om[DOM] * om[DOM];
                for (int q = 0; q < K; q++) { double a = UNIT * hh[q] * (1 - wb) * share; if (a <= 0) continue; double th = ph[q] - clk[q] + PHASE0[q], kr = om[q] * om[q] / kd;
                    double[] S = shape(-th, r, pp); e += a * S[0]; sx -= a * S[1] * gx * kr; sz -= a * S[1] * gz * kr; } }
            return new double[]{pl[0] + (e - pl[0]) * we, pl[1] + (sx - pl[1]) * we, pl[2] + (sz - pl[2]) * we};
        }
        /** 0.16.3 THE WIND SEA IN SHALLOW WATER at block corner (I, J), for a field solved for the WIND SEA (TidesSurf's second solve): the
         *  short waves feel the bottom where the water is shallower than half their deep wavelength L0 (DEEP_FACTOR; Hydro's own seeding line),
         *  and there they turn to the shore, slow, steepen and calm behind land just as the swell does. Deep water keeps the Fourier sea; the
         *  blend weight w = (1 - smoothstep(0.3 L0 .. 0.5 L0, depth)) x the field's edge fade, the drawn sea = Fourier (1 - w) + this w.
         *  {eta, slope x, slope z, w} (tides_surf.glsl tides_chopCorner, number for number). */
        public double[] chopCorner(int I, int J, double[] clk, double L0) {
            double[] ph = new double[K], hh = new double[K]; double r = 0, pp = 0, dep = 0, gx = 0, gz = 0; int m = 0, sea = 0;
            for (int dj = -1; dj <= 0; dj++) for (int di = -1; di <= 0; di++) { int i = I + di, j = J + dj; if (i < 0 || j < 0 || i >= n || j >= n) continue; int c = i + n * j; if (!seaCol[c]) continue; sea++; dep += depth[c];
                if (Float.isNaN(steerK[c])) continue; m++; for (int q = 0; q < K; q++) { ph[q] += phi[q][c]; hh[q] += hc[q][c]; } r += shR[c]; pp += shP[c]; double[] g2 = grad(i, j); gx += g2[0]; gz += g2[1]; }
            if (sea == 0) return new double[4]; dep /= sea;
            double we = fade(Math.min(I, n - I) / (double) SWELL_EDGE) * fade(Math.min(J, n - J) / (double) SWELL_EDGE), w = (1 - smooth((dep - 0.3 * L0) / (0.2 * L0))) * we;
            if (w <= 0) return new double[4]; if (m == 0) return new double[]{0, 0, 0, w};   // behind land: calm
            for (int q = 0; q < K; q++) { ph[q] /= m; hh[q] /= m; } r /= m; pp /= m; gx /= m; gz /= m;
            double e = 0, sx = 0, sz = 0, kd = om[DOM] * om[DOM];
            for (int q = 0; q < K; q++) { double a = UNIT * hh[q]; if (a <= 0) continue; double th = ph[q] - clk[q] + PHASE0[q], kr = om[q] * om[q] / kd; double[] S = shape(-th, r, pp); e += a * S[0]; sx -= a * S[1] * gx * kr; sz -= a * S[1] * gz * kr; }
            return new double[]{e, sx, sz, w}; }
        static double fade(double e) { return e >= 1 ? 1 : e <= 0 ? 0 : e * e * (3 - 2 * e); }
        /** The steering at block corner (ci, cj) (the 4 cells round it, as corner()): {turn rad, height factor, weight 0..1}; the weight
         *  fades to 0 over the last STEER_EDGE cells of the field so the steered sea meets the plain one; null with no sea round it. */
        public double[] steer(int ci, int cj) {
            if (steerA == null) return null; double cx = 0, cy = 0, kk = 0; int m = 0, lee = 0;
            for (int dj = -1; dj <= 0; dj++) for (int di = -1; di <= 0; di++) { int i = ci + di, j = cj + dj; if (i < 0 || j < 0 || i >= n || j >= n) continue; int c = i + n * j; if (!seaCol[c]) continue;
                if (Float.isNaN(steerK[c])) { lee++; continue; } cx += Math.cos(steerA[c]); cy += Math.sin(steerA[c]); kk += steerK[c]; m++; }
            if (m + lee == 0) return null;
            double a = m > 0 ? Math.atan2(cy, cx) : 0, k = (kk + lee * STEER_LEE) / (m + lee);
            double w = fade(Math.min(ci, n - ci) / (double) STEER_EDGE) * fade(Math.min(cj, n - cj) / (double) STEER_EDGE);   // per axis: no crease on the diagonals
            return new double[]{a, Math.max(STEER_KMIN, Math.min(STEER_KMAX, k)), w}; }
        public int seaColumns, shallowColumns, seedColumns, swashColumns, breakingColumns, shadowColumns; public double maxHs, meanR2, meanBeta, ms;
        double[] phiDomRaw;
        public double theta(int i, int c, double t) { return phi[i][c] - clock(om[i], t) + PHASE0[i]; }
        public String describe() { return String.format("%d sea columns (%d shoaling, %d in the surf, %d in the lee), %d seeds; steered %d, no wave reaches %d; wind sea x%.2f mean, calmed by short fetch %d; highest Hs %.2f m; %d swash columns, mean R2 %.2f m on slope %.3f; %d wall columns (mean Kr %.2f, R2 %.2f m); %.1f ms",
            seaColumns, shallowColumns, breakingColumns, shadowColumns, seedColumns, steeredColumns, leeColumns, fetchMean, fetchCalmed, maxHs, swashColumns, meanR2, meanBeta, wallColumns, meanWallKr, meanWallR2, ms); }

        // ---- 0.14.0 what the game reads: the same numbers as the shader (tides_surf.glsl), so the bore the water is lifted by is the
        // one that carries swimmers. Corners average the sea columns touching them (Hydro SurfField.At); points interpolate corners.
        /** The bore train at corner (I, J) (0..n), time clock (= clock(om[DOM], t)), wave-noise frame point (xm, ym): {eta, w, slope x,
         *  slope y, foam 0..1, front speed m/s}; null when no sea column touches it. */
        public double[] corner(int I, int J, double clk, double xm, double ym) {
            int k = 0; double r = 0, ph = 0, rh = 0, hr = 0, pd = 0, gx = 0, gy = 0, dp = 0;
            for (int dj = -1; dj <= 0; dj++) for (int di = -1; di <= 0; di++) { int ci = I + di, cj = J + dj; if (ci < 0 || cj < 0 || ci >= n || cj >= n) continue; int c = ci + n * cj; if (!seaCol[c]) continue;
                k++; r += shR[c]; ph += shP[c]; rh += rho[c]; hr += hrms[c]; pd += phi[DOM][c]; dp += depth[c]; double[] gg = grad(ci, cj); gx += gg[0]; gy += gg[1]; }
            if (k == 0) return null;
            r /= k; ph /= k; rh /= k; hr /= k; pd /= k; gx /= k; gy /= k; dp /= k;
            return bore(pd, r, ph, rh, hr, dp, gx, gy, clk, xm, ym);
        }
        /** The bore train from a point's averaged data (tides_surf.glsl tides_bore, number for number). */
        public static double[] bore(double phiD, double r, double ph, double rh, double hr, double depth, double gx, double gy, double clk, double xm, double ym) {
            double w = blend(rh); if (w <= 0) return new double[]{0, 0, 0, 0, 0, 0};
            double thD = phiD - clk + PHASE0[DOM], wt = -thD, cyc = -(thD - ph) / (2 * Math.PI); int nn = (int) Math.floor(cyc); double fr = cyc - nn;
            double hh = hr / Math.max(1e-6, GAMMA_B * rh), q1 = waveQ(nn, xm, ym), q = q1;
            if (fr < 0.25) { double q0 = waveQ(nn - 1, xm, ym); q = q0 + (q1 - q0) * smooth(fr / 0.25); }
            double H = boreHeight(hr * q, hh), a = 0.5 * H * w; double[] S = shape(wt, r, ph);
            double broken = smooth((hr * q / Math.max(1e-6, GAMMA_B * hh) - 0.85) / 0.3);   // this wave has passed 0.78 h: white
            double foam = w * broken * Math.exp(-fr * 5.0);                                // brightest at the front, fading behind it
            double speed = Math.sqrt(9.81 * Math.max(depth, 0.05)) * H / Math.max(depth, 0.05) * broken;   // the water behind a bore: c H / h (bore relation)
            return new double[]{a * S[0], w, -a * S[1] * gx, -a * S[1] * gy, foam, speed};
        }
        /** 0.16.0: the standing wave in front of a reflecting wall: the incident wave and its reflection (Kr) add to an envelope
         *  |1 + Kr e^(2ikd)| = sqrt(1 + Kr^2 + 2 Kr cos 2kd) at distance d from the wall (linear theory; Dean & Dalrymple, Water Wave
         *  Mechanics, partial standing waves). k: the peak's local wavenumber (its phase gradient). 1 away from walls. */
        public double wallAmp(int c) {
            if (wallDist == null || Float.isNaN(wallDist[c])) return 1; double[] g = grad(c % n, c / n); double k = Math.hypot(g[0], g[1]); double kr = wallKr[c];
            return Math.sqrt(1 + kr * kr + 2 * kr * Math.cos(2 * k * wallDist[c])); }
        /** The peak component's phase gradient (rad/m) at a column, central differences over sea neighbours (Hydro Grad). */
        public double[] grad(int ci, int cj) {
            int c = ci + n * cj; float[] p = phi[DOM];
            boolean l = ci > 0 && seaCol[c - 1], rr = ci < n - 1 && seaCol[c + 1], d = cj > 0 && seaCol[c - n], u = cj < n - 1 && seaCol[c + n];
            double px = l && rr ? (p[c + 1] - p[c - 1]) / (2 * dx) : rr ? (p[c + 1] - p[c]) / dx : l ? (p[c] - p[c - 1]) / dx : 0;
            double py = d && u ? (p[c + n] - p[c - n]) / (2 * dx) : u ? (p[c + n] - p[c]) / dx : d ? (p[c] - p[c - n]) / dx : 0;
            return new double[]{px, py};
        }
        /** The swash at a dry column (tides_surf.glsl tides_swash): the last four bores run up and back (Shen & Meyer 1963: front
         *  x = u0 t - a t^2 / 2, u0 = sqrt(2 g R), a = g beta). x: metres up the beach from the still-water line. {sheet 0..1, the
         *  sheet's speed up the beach m/s (negative: backwash), wet 0..1}. */
        public double[] swash(int c, double x, double clk, double xm, double ym) {
            if (Float.isNaN(shoreX[c])) return new double[]{0, 0, 0};
            return swashAt(shoreTheta[c], x, beta[c], run2[c], 2 * Math.PI / om[DOM], g, clk, xm, ym);
        }
        public static double[] swashAt(double theta, double x, double beta, double r2v, double T, double g, double clk, double xm, double ym) {
            double wt = -(theta - clk + PHASE0[DOM]), cyc = wt / (2 * Math.PI); int nn = (int) Math.floor(cyc); double fr = cyc - nn, dt0 = fr * T, a = g * Math.max(0.005, beta);
            double sheet = 0, vel = 0, wet = 0;
            for (int m = 0; m < 4; m++) {
                double R = r2v * waveQ(nn - m, xm, ym) / Q2, u0 = Math.sqrt(2 * g * R), ts = 2 * u0 / a, age = dt0 + m * T;
                if (age < ts) { double xf = u0 * age - 0.5 * a * age * age; if (x < xf) { double sh = 1 - 0.6 * Math.min(1, age / ts); if (sh > sheet) { sheet = sh; vel = u0 - a * age; } } }
            }
            double pr = Math.pow(0.02, (x * beta / r2v) * (x * beta / r2v)); wet = 1 - Math.pow(1 - pr, 25);
            return new double[]{sheet, vel, wet};
        }

        /** Sea elevation (m above still water) at column c, time t, world point (x, y): the components shaped, and in the surf the bore
         *  train (Hydro SurfField.Eta at a column; show = 1). {eta, w (the bore train's weight)}. */
        public double[] eta(int c, double t, double x, double y) {
            if (!seaCol[c]) return new double[]{0, 0};
            double r = shR[c], ph = shP[c], w = blend(rho[c]), e = 0;
            if (w < 1) for (int i = 0; i < K; i++) { double a = UNIT * hc[i][c] * (1 - w); if (a <= 0) continue; double th = theta(i, c, t); e += a * shape(-th, r, ph)[0]; }
            if (w > 0) {
                double thD = theta(DOM, c, t), wt = -thD, cyc = -(thD - ph) / (2 * Math.PI); int nn = (int) Math.floor(cyc); double fr = cyc - nn;
                double hr = hrms[c], hh = hr / Math.max(1e-6, GAMMA_B * rho[c]), q1 = waveQ(nn, x, y), q = q1;
                if (fr < 0.25) { double q0 = waveQ(nn - 1, x, y); q = q0 + (q1 - q0) * smooth(fr / 0.25); }
                e += 0.5 * boreHeight(hr * q, hh) * w * shape(wt, r, ph)[0];
            }
            return new double[]{e, w};
        }
    }

    // ------------------------------------------------------------------ solve (Hydro SurfField.Compute)
    public static Field compute(Input s) {
        long t0 = System.nanoTime(); Field f = new Field(); int N = s.n, n2 = N * N; double dx = s.dx, g = s.g;
        f.n = N; f.dx = dx; f.g = g; f.hs0 = s.hs0; f.tp = s.tp; f.heading = s.heading; f.sea = s.sea; System.arraycopy(s.om, 0, f.om, 0, K);
        double[] h = new double[n2]; boolean[] sea = new boolean[n2]; f.seaCol = sea; f.depth = new float[n2]; f.bed = new float[n2]; for (int c = 0; c < n2; c++) f.bed[c] = (float) s.bed[c];
        if (!Double.isNaN(s.sea)) for (int c = 0; c < n2; c++) if (s.known[c] && s.depth[c] >= 0.05) { sea[c] = true; h[c] = s.depth[c]; f.depth[c] = (float) h[c]; f.seaColumns++; }
        for (int i = 0; i < K; i++) { f.phi[i] = new float[n2]; f.hc[i] = new float[n2]; }
        f.rho = new float[n2]; f.hrms = new float[n2]; f.shR = new float[n2]; f.shP = new float[n2];
        f.steerA = new float[n2]; f.steerK = nan(n2);
        f.shoreX = nan(n2); f.shoreTheta = nan(n2); f.beta = nan(n2); f.run2 = nan(n2); f.upX = nan(n2); f.upY = nan(n2); f.wallDist = nan(n2); f.wallKr = nan(n2); f.wallR2 = nan(n2); f.wallNx = nan(n2); f.wallNy = nan(n2);
        if (f.seaColumns == 0 || !(s.hs0 > 0) || !(s.tp > 0)) { f.ms = (System.nanoTime() - t0) / 1e6; return f; }
        double[] hnb2 = new double[n2]; boolean[] lit = lit(sea, N, s.heading);
        for (int c = 0; c < n2; c++) if (sea[c] && !lit[c]) f.shadowColumns++;
        double[] phi = new double[n2], P = new double[n2], Pnb = new double[n2], kk = new double[n2], cgv = new double[n2];
        float[] kx = new float[n2], ky = new float[n2]; byte[] state = new byte[n2]; int[] order = new int[n2]; boolean[] done = new boolean[n2];
        for (int i = 0; i < K; i++) {
            double om = s.om[i], k0 = om * om / g, dir = s.dir[i], ca = Math.cos(dir), sa = Math.sin(dir), L0 = 2 * Math.PI / k0, cg0 = g / (2 * om);
            int nOrder = 0, seeds = 0; Heap heap = new Heap(4096);
            for (int c = 0; c < n2; c++) {
                state[c] = 0; P[c] = 0; Pnb[c] = 0; phi[c] = Double.POSITIVE_INFINITY; done[c] = false;
                if (!sea[c]) continue;
                kk[c] = k(om, h[c], g); cgv[c] = cg(om, kk[c], h[c]);
                int ci = c % N, cj = c / N; boolean deep = h[c] >= DEEP_FACTOR * L0, edge = ci == 0 || cj == 0 || ci == N - 1 || cj == N - 1;
                boolean inward = (ci == 0 && ca > 0.05) || (ci == N - 1 && ca < -0.05) || (cj == 0 && sa > 0.05) || (cj == N - 1 && sa < -0.05);
                if (!lit[c] || !(deep || (edge && inward))) continue;
                phi[c] = kk[c] * ((s.seedX0 + ci * dx) * ca + (s.seedY0 + cj * dx) * sa); /* 0.16.0: the local wavenumber (with the deep k0 a seed on a shallow edge made a kink where two seed edges meet, and energy piled up there) */ state[c] = 2; order[nOrder++] = c; seeds++;
                double H0 = s.hs0 * (deep ? 1 : Math.sqrt(cg0 / cgv[c])), Hb = DEPTH_LIMIT * h[c];
                P[c] = Math.min(H0, Hb) * Math.min(H0, Hb) * cgv[c]; Pnb[c] = H0 * H0 * cgv[c]; kx[c] = (float) ca; ky[c] = (float) sa;
            }
            if (i == DOM) f.seedColumns = seeds;
            for (int q = 0; q < nOrder; q++) push(order[q], N, sea, state, phi, kk, dx, heap);
            while (heap.count > 0) { double key = heap.topKey(); int c = heap.pop(); if (state[c] == 2 || key > phi[c] + 1e-9) continue; state[c] = 2; order[nOrder++] = c; push(c, N, sea, state, phi, kk, dx, heap); }
            for (int q = 0; q < nOrder; q++) done[order[q]] = P[order[q]] > 0 || Pnb[order[q]] > 0;
            for (int q = 0; q < nOrder; q++) {
                int c = order[q]; if (done[c]) continue; int ci = c % N, cj = c / N;
                double pl = ci > 0 && state[c - 1] == 2 ? phi[c - 1] : Double.POSITIVE_INFINITY, pr = ci < N - 1 && state[c + 1] == 2 ? phi[c + 1] : Double.POSITIVE_INFINITY;
                double pd = cj > 0 && state[c - N] == 2 ? phi[c - N] : Double.POSITIVE_INFINITY, pu = cj < N - 1 && state[c + N] == 2 ? phi[c + N] : Double.POSITIVE_INFINITY;
                double gx = 0, gy = 0;
                if (pl < phi[c] || pr < phi[c]) gx = pl <= pr ? (phi[c] - pl) / dx : -(phi[c] - pr) / dx;
                if (pd < phi[c] || pu < phi[c]) gy = pd <= pu ? (phi[c] - pd) / dx : -(phi[c] - pu) / dx;
                double gl = Math.sqrt(gx * gx + gy * gy); if (gl <= 0) continue;
                kx[c] = (float) (gx / gl); ky[c] = (float) (gy / gl);
                double inC = 0, inN = 0;
                if (ci > 0 && done[c - 1] && kx[c - 1] > 0) { inC += kx[c - 1] * P[c - 1]; inN += kx[c - 1] * Pnb[c - 1]; }
                if (ci < N - 1 && done[c + 1] && kx[c + 1] < 0) { inC -= kx[c + 1] * P[c + 1]; inN -= kx[c + 1] * Pnb[c + 1]; }
                if (cj > 0 && done[c - N] && ky[c - N] > 0) { inC += ky[c - N] * P[c - N]; inN += ky[c - N] * Pnb[c - N]; }
                if (cj < N - 1 && done[c + N] && ky[c + N] < 0) { inC -= ky[c + N] * P[c + N]; inN -= ky[c + N] * Pnb[c + N]; }
                double outw = Math.abs(kx[c]) + Math.abs(ky[c]);
                double kap = 0.5 * Math.min(1, 1 / (2 * kk[c] * dx)), tx = -ky[c], ty = kx[c], sC = 0, sN = 0, wS = 0;   // diffraction leak, CHOSEN 0.5 (Hydro)
                if (ci > 0 && done[c - 1]) { double w = Math.abs(tx); sC += w * P[c - 1]; sN += w * Pnb[c - 1]; wS += w; }
                if (ci < N - 1 && done[c + 1]) { double w = Math.abs(tx); sC += w * P[c + 1]; sN += w * Pnb[c + 1]; wS += w; }
                if (cj > 0 && done[c - N]) { double w = Math.abs(ty); sC += w * P[c - N]; sN += w * Pnb[c - N]; wS += w; }
                if (cj < N - 1 && done[c + N]) { double w = Math.abs(ty); sC += w * P[c + N]; sN += w * Pnb[c + N]; wS += w; }
                double pc = (inC + kap * sC) / (outw + kap * wS), pn = (inN + kap * sN) / (outw + kap * wS), Hcap = DEPTH_LIMIT * h[c];
                if (pc > Hcap * Hcap * cgv[c]) pc = Hcap * Hcap * cgv[c];
                P[c] = pc; Pnb[c] = pn; done[c] = true;
            }
            double shift = 0;
            if (s.match != null && s.anchor >= 0 && s.anchor < n2 && sea[s.anchor] && state[s.anchor] == 2 && s.match.n == N && s.match.seaCol[s.anchor]) shift = s.match.phi[i][s.anchor] - phi[s.anchor];
            for (int c = 0; c < n2; c++) {
                if (!sea[c] || state[c] != 2) continue;
                f.phi[i][c] = (float) (phi[c] + shift);
                double Hc = Math.sqrt(Math.max(0, P[c]) / cgv[c]), Hn = Math.sqrt(Math.max(0, Pnb[c]) / cgv[c]);
                f.hc[i][c] = (float) Hc; hnb2[c] += Hn * Hn; if (i == DOM) f.shR[c] = (float) kk[c];
                if (i == DOM) f.steerK[c] = (float) (Hn / (s.hs0 * Math.sqrt(cg0 / cgv[c])));   // Kr Kd = H / (H0 Ks), Ks = sqrt(cg0 / cg)
            }
            if (i == DOM) for (int c = 0; c < n2; c++) if (sea[c] && state[c] != 2) f.steerK[c] = Float.NaN;
            if (i == DOM) { f.phiDomRaw = phi.clone(); if (shift != 0) for (int c = 0; c < n2; c++) f.phiDomRaw[c] += shift; }
        }
        for (int c = 0; c < n2; c++) {
            if (!sea[c]) continue;
            double hs = 0; for (int i = 0; i < K; i++) hs += f.hc[i][c] * (double) f.hc[i][c]; hs = Math.sqrt(hs / K); if (hs > f.maxHs) f.maxHs = hs;
            double hrms = Math.sqrt(hnb2[c] / K) / Math.sqrt(2); f.hrms[c] = (float) hrms; f.rho[c] = (float) (hrms / (GAMMA_B * Math.max(h[c], 0.01)));
            if (f.rho[c] > 0.5) f.breakingColumns++;
            if (h[c] < DEEP_FACTOR * 2 * Math.PI / (s.om[DOM] * s.om[DOM] / g)) f.shallowColumns++;
            double[] bp = ruessink(hs, f.shR[c], h[c]), rp = abreu(bp[0], bp[1]); f.shR[c] = (float) rp[0]; f.shP[c] = (float) rp[1];
        }
        for (int c = 0; c < n2; c++) { if (!sea[c]) continue; if (Float.isNaN(f.steerK[c])) { f.leeColumns++; continue; }
            int ci = c % N, cj = c / N; float[] p = f.phi[DOM]; float[] K2 = f.steerK;   // the phase gradient over neighbours the wave reached (an unreached cell has no phase)
            boolean l = ci > 0 && sea[c - 1] && !Float.isNaN(K2[c - 1]), rr = ci < N - 1 && sea[c + 1] && !Float.isNaN(K2[c + 1]), d = cj > 0 && sea[c - N] && !Float.isNaN(K2[c - N]), u = cj < N - 1 && sea[c + N] && !Float.isNaN(K2[c + N]);
            double gx = l && rr ? p[c + 1] - p[c - 1] : rr ? p[c + 1] - p[c] : l ? p[c] - p[c - 1] : 0, gy = d && u ? p[c + N] - p[c - N] : u ? p[c + N] - p[c] : d ? p[c] - p[c - N] : 0;
            if (gx == 0 && gy == 0) { f.steerA[c] = 0; continue; }
            double a = Math.atan2(gy, gx) - s.dir[DOM]; f.steerA[c] = (float) Math.atan2(Math.sin(a), Math.cos(a)); if (Math.abs(f.steerA[c]) > 0.05 || Math.abs(f.steerK[c] - 1) > 0.05) f.steeredColumns++; }
        smoothSteer(f, N, sea, STEER_SMOOTH);   // the turn as it is drawn: a block-grid phase gradient jitters cell to cell near the shore
        swash(f, s, h, sea); f.phiDomRaw = null; f.ms = (System.nanoTime() - t0) / 1e6; return f;
    }
    static float[] nan(int n) { float[] a = new float[n]; java.util.Arrays.fill(a, Float.NaN); return a; }
    static boolean[] lit(boolean[] sea, int N, double heading) {
        float[] lit = new float[N * N]; double ca = Math.cos(heading), sa = Math.sin(heading);
        Integer[] idx = new Integer[N * N]; double[] key = new double[N * N];
        for (int c = 0; c < N * N; c++) { idx[c] = c; key[c] = (c % N) * ca + (c / N) * sa; }
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(key[a], key[b]));
        for (int c : idx) {
            if (!sea[c]) continue; double x = c % N - ca, y = c / N - sa;
            if (x < 0 || y < 0 || x > N - 1 || y > N - 1) { lit[c] = 1; continue; }
            int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y); double fx = x - x0, fy = y - y0; int x1 = Math.min(N - 1, x0 + 1), y1 = Math.min(N - 1, y0 + 1);
            double v = (lit[x0 + N * y0] * (1 - fx) + lit[x1 + N * y0] * fx) * (1 - fy) + (lit[x0 + N * y1] * (1 - fx) + lit[x1 + N * y1] * fx) * fy;
            lit[c] = v > 0.5 ? 1 : 0;
        }
        boolean[] b = new boolean[N * N]; for (int c = 0; c < b.length; c++) b[c] = lit[c] > 0.5f; return b;
    }
    static void push(int c, int N, boolean[] sea, byte[] state, double[] phi, double[] kk, double dx, Heap heap) {
        int ci = c % N, cj = c / N;
        for (int d = 0; d < 4; d++) {
            int x = ci + (d == 0 ? 1 : d == 1 ? -1 : 0), y = cj + (d == 2 ? 1 : d == 3 ? -1 : 0); if (x < 0 || y < 0 || x >= N || y >= N) continue;
            int u = x + N * y; if (!sea[u] || state[u] == 2) continue;
            double v = update(u, N, state, phi, kk[u] * dx); if (v < phi[u]) { phi[u] = v; state[u] = 1; heap.push(v, u); }
        }
    }
    static double update(int c, int N, byte[] state, double[] phi, double kd) {
        int ci = c % N, cj = c / N; double inf = Double.POSITIVE_INFINITY;
        double a = Math.min(ci > 0 && state[c - 1] == 2 ? phi[c - 1] : inf, ci < N - 1 && state[c + 1] == 2 ? phi[c + 1] : inf);
        double b = Math.min(cj > 0 && state[c - N] == 2 ? phi[c - N] : inf, cj < N - 1 && state[c + N] == 2 ? phi[c + N] : inf);
        if (Double.isInfinite(a) && Double.isInfinite(b)) return inf;
        if (Double.isInfinite(a) || Double.isInfinite(b) || Math.abs(a - b) >= kd) return Math.min(a, b) + kd;
        return 0.5 * (a + b + Math.sqrt(2 * kd * kd - (a - b) * (a - b)));
    }
    /** The swash (Hydro Swash): every dry column within reach of the sea gets the distance to the still-water line, the bores' phase
     *  there, the beach-face slope, the 2 % run-up and the up-beach direction. */
    static void swash(Field f, Input s, double[] h, boolean[] sea) {
        int N = f.n, n2 = N * N; double dx = f.dx, g = f.g, om = s.om[DOM], L0 = g * s.tp * s.tp / (2 * Math.PI), cg0 = g / (2 * om); double[] phiD = f.phiDomRaw;
        int[] src = new int[n2]; float[] dist = new float[n2]; final float BIG = 1e9f, D1 = 1f, D2 = 1.41421356f;
        java.util.Arrays.fill(src, -1); java.util.Arrays.fill(dist, BIG);
        double[] sBeta = new double[n2], sR2 = new double[n2]; double sumR = 0, sumB = 0; int nShore = 0;
        for (int c = 0; c < n2; c++) {
            if (!sea[c] || Double.isInfinite(phiD[c])) continue;
            int ci = c % N, cj = c / N; boolean shore = false;
            for (int d = 0; d < 4 && !shore; d++) { int x = ci + (d == 0 ? 1 : d == 1 ? -1 : 0), y = cj + (d == 2 ? 1 : d == 3 ? -1 : 0); if (x < 0 || y < 0 || x >= N || y >= N) continue; int u = x + N * y; if (s.known[u] && !sea[u]) shore = true; }
            if (!shore) continue;
            double sl = 0; int ns = 0;
            for (int dj = -3; dj <= 3; dj++) for (int di = -3; di <= 3; di++) {
                int x = ci + di, y = cj + dj; if (x < 1 || y < 1 || x >= N - 1 || y >= N - 1) continue;
                int u = x + N * y; if (!s.known[u] || !s.known[u + 1] || !s.known[u - 1] || !s.known[u + N] || !s.known[u - N]) continue;
                double gx = (s.bed[u + 1] - s.bed[u - 1]) / (2 * dx), gy = (s.bed[u + N] - s.bed[u - N]) / (2 * dx); sl += Math.sqrt(gx * gx + gy * gy); ns++;
            }
            if (ns == 0) continue;
            double beta = sl / ns, hnb = f.hrms[c] * Math.sqrt(2), ks = Math.sqrt(cg0 / cg(om, k(om, h[c], g), h[c]));
            double share = Math.max(0, Math.min(1, hnb / Math.max(1e-6, s.hs0 * ks)));
            double rr = beta >= MIN_SLOPE && beta <= MAX_SLOPE ? r2(s.hs0 * share * share, L0, beta) : 0;
            src[c] = c; dist[c] = 0; sBeta[c] = beta; sR2[c] = rr; if (rr > 0) { sumR += rr; sumB += beta; nShore++; }
        }
        f.meanR2 = nShore > 0 ? sumR / nShore : 0; f.meanBeta = nShore > 0 ? sumB / nShore : 0;
        walls(f, s, h, sea, phiD, L0, cg0, om);
        for (int pass = 0; pass < 2; pass++) {
            int start = pass == 0 ? 0 : n2 - 1, step = pass == 0 ? 1 : -1;
            for (int c = start; c >= 0 && c < n2; c += step) {
                if (sea[c] || !s.known[c]) continue; int ci = c % N, cj = c / N;
                for (int d = 0; d < 4; d++) {
                    int x, y; if (d == 0) { x = ci - step; y = cj; } else if (d == 1) { x = ci; y = cj - step; } else if (d == 2) { x = ci + step; y = cj - step; } else { x = ci - step; y = cj - step; }
                    if (x < 0 || y < 0 || x >= N || y >= N) continue; int u = x + N * y; if (src[u] < 0) continue;
                    float nd = dist[u] + (d < 2 ? D1 : D2); if (nd < dist[c]) { dist[c] = nd; src[c] = src[u]; }
                }
            }
        }
        for (int c = 0; c < n2; c++) {
            if (sea[c] || src[c] < 0) continue; int o = src[c]; double rr = sR2[o], beta = sBeta[o]; if (!(rr > 0)) continue;
            double x = dist[c] * dx - 0.5 * dx; if (x > SWASH_REACH * rr / beta + dx) continue;
            f.shoreX[c] = (float) Math.max(0, x); f.shoreTheta[c] = f.phi[DOM][o] - f.shP[o]; f.beta[c] = (float) beta; f.run2[c] = (float) rr;
            double ux = (c % N) - (o % N), uy = (c / N) - (o / N), ul = Math.hypot(ux, uy); f.upX[c] = (float) (ul > 0 ? ux / ul : 0); f.upY[c] = (float) (ul > 0 ? uy / ul : 0);
            f.swashColumns++;
        }
    }
    public static final double WALL_REACH = 32, WALL_RISE = 1.5;
    /** Seelig & Ahrens (1981) reflection from a smooth impermeable slope (Coastal Engineering Manual II-7): Kr = xi^2 / (5.5 + xi^2). */
    public static double reflect(double xi) { return xi * xi / (5.5 + xi * xi); }
    /** EurOtop (2018) eq. 5.4 with its maximum eq. 5.5, smooth slope (gamma 1): Ru2% / Hm0 = 1.65 xi, at most 4.0 - 1.5 / sqrt(xi). */
    public static double wallRunup(double hm0, double xi) { if (!(hm0 > 0) || !(xi > 0)) return 0; return hm0 * Math.min(1.65 * xi, Math.max(0, 4.0 - 1.5 / Math.sqrt(xi))); }
    /** Steep shores: a sea column beside a dry one whose ground rises WALL_RISE m or more above the still water within 2 m, steeper than
     *  MAX_SLOPE (no swash: the water climbs the face and is thrown up). xi from the face slope and the local Hs (as the swash's R2). */
    static void walls(Field f, Input s, double[] h, boolean[] sea, double[] phiD, double L0, double cg0, double om) {
        int N = f.n, n2 = N * N; double dx = f.dx, g = f.g, Tm = s.tp / 1.1;   // Tm-1,0 ~ Tp / 1.1 (EurOtop, single-peaked spectra)
        double Lm = g * Tm * Tm / (2 * Math.PI);
        f.wallDist = nan(n2); f.wallKr = nan(n2); f.wallR2 = nan(n2); f.wallNx = nan(n2); f.wallNy = nan(n2);
        float[] dist = new float[n2]; int[] src = new int[n2]; java.util.Arrays.fill(dist, 1e9f); java.util.Arrays.fill(src, -1);
        double[] kr = new double[n2], ru = new double[n2], nx = new double[n2], ny = new double[n2]; double sR = 0, sK = 0; int nw = 0;
        for (int c = 0; c < n2; c++) {
            if (!sea[c] || Double.isInfinite(phiD[c])) continue; int ci = c % N, cj = c / N; double best = 0, bx = 0, by = 0;
            for (int dj = -2; dj <= 2; dj++) for (int di = -2; di <= 2; di++) { if (di == 0 && dj == 0) continue; int x = ci + di, y = cj + dj; if (x < 0 || y < 0 || x >= N || y >= N) continue;
                int u = x + N * y; if (!s.known[u] || sea[u]) continue; double rise = s.bed[u] - s.sea, d = Math.hypot(di, dj) * dx - 0.5 * dx, sl = rise / Math.max(0.5, d);
                if (rise >= WALL_RISE && sl > best) { best = sl; bx = -di; by = -dj; } }
            if (best <= MAX_SLOPE) continue;
            double hnb = f.hrms[c] * Math.sqrt(2), H = Math.max(0.01, hnb), xi = best / Math.sqrt(H / Lm);
            kr[c] = reflect(xi); ru[c] = wallRunup(H, xi); double bl = Math.hypot(bx, by); nx[c] = bx / bl; ny[c] = by / bl;
            src[c] = c; dist[c] = 0; sR += ru[c]; sK += kr[c]; nw++;
        }
        f.wallColumns = nw; f.meanWallR2 = nw > 0 ? sR / nw : 0; f.meanWallKr = nw > 0 ? sK / nw : 0;
        final float D1 = 1f, D2 = 1.41421356f;
        for (int pass = 0; pass < 2; pass++) { int start = pass == 0 ? 0 : n2 - 1, step = pass == 0 ? 1 : -1;
            for (int c = start; c >= 0 && c < n2; c += step) { if (!sea[c]) continue; int ci = c % N, cj = c / N;
                for (int d = 0; d < 4; d++) { int x, y; if (d == 0) { x = ci - step; y = cj; } else if (d == 1) { x = ci; y = cj - step; } else if (d == 2) { x = ci + step; y = cj - step; } else { x = ci - step; y = cj - step; }
                    if (x < 0 || y < 0 || x >= N || y >= N) continue; int u = x + N * y; if (src[u] < 0) continue; float nd = dist[u] + (d < 2 ? D1 : D2); if (nd < dist[c]) { dist[c] = nd; src[c] = src[u]; } } } }
        for (int c = 0; c < n2; c++) { if (src[c] < 0 || dist[c] * dx > WALL_REACH) continue; int o = src[c];
            f.wallDist[c] = (float) (dist[c] * dx); f.wallKr[c] = (float) kr[o]; f.wallR2[c] = (float) ru[o]; f.wallNx[c] = (float) nx[o]; f.wallNy[c] = (float) ny[o]; }
    }
    /** A binary min-heap of (key, column). */
    static final class Heap {
        double[] k; int[] v; int count;
        Heap(int cap) { k = new double[cap]; v = new int[cap]; }
        void push(double key, int val) { if (count == k.length) { k = java.util.Arrays.copyOf(k, count * 2); v = java.util.Arrays.copyOf(v, count * 2); }
            int i = count++; while (i > 0) { int p = (i - 1) >> 1; if (k[p] <= key) break; k[i] = k[p]; v[i] = v[p]; i = p; } k[i] = key; v[i] = val; }
        double topKey() { return k[0]; }
        int pop() { int top = v[0]; double lk = k[--count]; int lv = v[count]; int i = 0;
            while (true) { int a = 2 * i + 1; if (a >= count) break; int b = a + 1, m = b < count && k[b] < k[a] ? b : a; if (k[m] >= lk) break; k[i] = k[m]; v[i] = v[m]; i = m; }
            if (count > 0) { k[i] = lk; v[i] = lv; } return top; }
    }
}
