package com.hopeless.tides.core;

/**
 * 0.9.0: THE FOURIER LAYER OVER THE COLUMN WATER. Ported from Hopeless Water 0.10.0 FftSea.cs (Tessendorf; NVIDIA OceanCS; Horvath 2015:
 * JONSWAP x TMA x Donelan-Banner; Tessendorf's choppy displacement and Jacobian; the breaking share of Monahan & O'Muircheartaigh),
 * changed for Minecraft:
 *  - ONE grid, 256 x 256 over 512 m (a point every 2 m), carrying only the waves 1 m block corners can show: 5 m to 512 m. Hydro's
 *    three cascades go down to centimetres because its mesh is fine; here everything shorter than 5 m stays in the shader's detail
 *    waves (the visual7 look), as before.
 *  - STATELESS: the field at a time is a function of that time, the sea state and the wind only (no breaking-episode memory), so the
 *    server and every client build the same field and boats ride exactly the drawn waves.
 *  - The random draw is made once per cell from a fixed seed and kept; a change of weather changes only the amplitudes, so the sea
 *    grows and calms without a new pattern (Hydro fades between two draws instead).
 *  - Blended with the column water by the caller (FftCorner): the field is the open sea; each block corner scales it by its own
 *    water (the depth/shelter table of WaveModel: shoaling and breaking at the bed; the column simulation's current calms it).
 * The field is stored as half floats, as the GPU texture holds it; the CPU samples the same halves.
 * Fields per grid point: A = (h, Dx, Dz, J) with J the Jacobian of the horizontal displacement at full amplitude (for breaking),
 * B = (Jxx, Jzz, Jxz, 0) so a corner can scale it: J(a) = (1 + a Jxx)(1 + a Jzz) - (a Jxz)^2. The breaking threshold is the J(aOpen)
 * quantile at Monahan's share, aOpen being the open sea's scale for this sea state.
 */
public final class FftSea {
    private FftSea() {}
    public static final int N = 256;                       // grid points a side
    public static final double L = 512.0;                  // m: the tile
    public static final double LAMBDA_MIN = 5.0;           // m: the shortest wave carried (5 corner cells a wave)
    public static final double G = 9.81, GAMMA = 3.3, U195 = 1.06, FETCH = 100000.0;   // JONSWAP's peak factor; U19.5/U10; the open sea's fetch (Hydro)
    public static final double SLOT = 0.25;                // s: a field every quarter second; readers blend two
    public static final int SEED = 0x71de5;
    static final double TWO_PI = 2 * Math.PI;
    public static final double REPEAT = 1200.0;            // s: tSec (WaveModel.daySeconds) wraps here, so the field must too
    static final double W_REPEAT = TWO_PI / REPEAT;
    /** The sea state the spectrum shape is built for: steps of 1/32, so server and client build the same field from nearly equal states. */
    public static double quantize(double s) { return Math.rint(Math.max(0, Math.min(1, s)) * 32) / 32; }

    /** Wind speed for a sea state (CHOSEN: Hydro's no-weather breeze 5 m/s, a thunderstorm 10 m/s). It sets the SHAPE of the spectrum
     *  (its peak wavelength 21..85 m); the height stays Tides' own heightOpen(s) (FftCorner normalises by the full spectrum's Hs). */
    public static double u10(double s) { return 5.0 + 5.0 * Math.max(0, Math.min(1, s)); }

    // ------------------------------------------------------------------ the spectrum (Hopeless Water WaveSpectrum / FftSea, unchanged)
    /** JONSWAP (Hasselmann et al. 1973) S(w), m^2 s. */
    public static double jonswap(double w, double alpha, double wp) { return jonswap(w, alpha, wp, G); }
    /** 0.13.0: the same at surface gravity g (m/s2): a planet's own gravity (TidesPlanets). */
    public static double jonswap(double w, double alpha, double wp, double G) {
        if (w <= 0) return 0;
        double sigma = w <= wp ? 0.07 : 0.09, r = Math.exp(-(w - wp) * (w - wp) / (2 * sigma * sigma * wp * wp));
        return alpha * G * G / Math.pow(w, 5) * Math.exp(-1.25 * Math.pow(wp / w, 4)) * Math.pow(GAMMA, r);
    }
    /** alpha and the peak frequency from the wind and the fetch (fetch-limited, capped at Pierson-Moskowitz). {alpha, wp}. */
    public static double[] parameters(double u10, double fetch) { return parameters(u10, fetch, G); }
    public static double[] parameters(double u10, double fetch, double G) {
        u10 = Math.max(0.3, u10); fetch = Math.max(10, fetch);
        double alpha = 0.076 * Math.pow(u10 * u10 / (fetch * G), 0.22), wp = 22 * Math.pow(G * G / (u10 * fetch), 1.0 / 3.0);
        double wpPM = 0.877 * G / (U195 * u10);
        if (wp <= wpPM) { wp = wpPM; alpha = 8.1e-3; }
        return new double[]{alpha, wp};
    }
    /** Donelan-Banner spreading, normalised over -pi..pi. */
    public static double spread(double r, double th) {
        double beta = r < 0.95 ? 2.61 * Math.pow(r, 1.3) : r < 1.6 ? 2.28 * Math.pow(r, -1.3) : Math.pow(10, -0.4 + 0.8393 * Math.exp(-0.567 * Math.log(r * r)));
        double ch = Math.cosh(beta * th);
        return 0.5 * beta / (ch * ch) / Math.tanh(beta * Math.PI);
    }
    /** S(k) (m^4) for the wind blowing toward windAngle, deep water. */
    public static double spectrumK(double kx, double kz, double alpha, double wp, double windAngle) { return spectrumK(kx, kz, alpha, wp, windAngle, G); }
    public static double spectrumK(double kx, double kz, double alpha, double wp, double windAngle, double G) {
        double k = Math.sqrt(kx * kx + kz * kz); if (k <= 0) return 0;
        double w = Math.sqrt(G * k), dwdk = G / (2 * w);
        double th = Math.atan2(kz, kx) - windAngle; th = Math.atan2(Math.sin(th), Math.cos(th));
        return jonswap(w, alpha, wp, G) * spread(w / wp, th) * dwdk / k;
    }
    /** Hs (m) of the WHOLE spectrum (every wavelength, not only the grid's band): 4 sqrt(integral of S(w) dw); spreading integrates to 1. */
    public static double hsFull(double u10) { return hsFull(u10, G); }
    public static double hsFull(double u10, double G) {
        double[] p = parameters(u10, FETCH, G); double m0 = 0, w0 = 0.02, w1 = 12.0; int n = 4000; double dw = (w1 - w0) / n;
        for (int i = 0; i <= n; i++) { double w = w0 + i * dw, f = (i == 0 || i == n) ? 1 : (i % 2 == 1 ? 4 : 2); m0 += f * jonswap(w, p[0], p[1], G); }
        return 4 * Math.sqrt(m0 * dw / 3);
    }
    /** Tessendorf's choppiness (Hopeless Water ChoppyFor): 1 to Beaufort 5 (8 m/s), rising to 2 at Beaufort 10. */
    public static double choppy(double u10) { double f = Math.max(0, Math.min(1, (u10 - 8.0) / 16.5)); return 1 + f * f * (3 - 2 * f); }
    /** Monahan & O'Muircheartaigh 1980 whitecap fraction x 0.30 active (Hopeless Water StormSea). */
    public static double breakingShare(double u10) { return 0.30 * Math.max(0, Math.min(1, 3.84e-6 * Math.pow(Math.max(0, u10), 3.41))); }

    // ------------------------------------------------------------------ the fixed draw
    static final double[] XR = new double[N * N], XI = new double[N * N], KX = new double[N * N], KZ = new double[N * N], W = new double[N * N];
    static final boolean[] IN = new boolean[N * N];
    static {
        java.util.Random rnd = new java.util.Random(SEED);
        double dk = TWO_PI / L, kMax = TWO_PI / LAMBDA_MIN;
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) {
            int id = i + N * j, n = i <= N / 2 ? i : i - N, m = j <= N / 2 ? j : j - N;
            double kx = n * dk, kz = m * dk, k = Math.sqrt(kx * kx + kz * kz);
            double u1 = 1 - rnd.nextDouble(), u2 = rnd.nextDouble(), rr = Math.sqrt(-2 * Math.log(u1));   // drawn for every cell, used or not
            XR[id] = rr * Math.cos(TWO_PI * u2); XI[id] = rr * Math.sin(TWO_PI * u2);
            KX[id] = kx; KZ[id] = kz; W[id] = omegaGrid(k, G);   // whole turns in REPEAT s: the field repeats with the day clock (tSec wraps at 1200)
            IN[id] = k > 0 && k < kMax && i != N / 2 && j != N / 2;
        }
    }

    /** Deep-water frequency sqrt(g k), rounded to whole turns in REPEAT s (the field repeats with the day clock: tSec wraps at 1200). */
    static double omegaGrid(double k, double g) { return Math.rint(Math.sqrt(g * k) / W_REPEAT) * W_REPEAT; }
    static final java.util.concurrent.ConcurrentHashMap<Long, double[]> WG = new java.util.concurrent.ConcurrentHashMap<>();
    /** 0.13.0: the grid frequencies at gravity g (Earth's: the table above). */
    static double[] frequencies(double g) {
        if (g == G) return W;
        return WG.computeIfAbsent(Double.doubleToLongBits(g), b -> { double[] w = new double[N * N]; for (int id = 0; id < N * N; id++) w[id] = omegaGrid(Math.sqrt(KX[id] * KX[id] + KZ[id] * KZ[id]), g); return w; });
    }

    // ------------------------------------------------------------------ one field
    public static final class Field {
        public final short[] a = new short[N * N * 4], b = new short[N * N * 4];   // half floats: A (h, Dx, Dz, J), B (Jxx, Jzz, Jxz, 0)
        public double t, s, wind, u10, hsBand, hsFull, choppy, breakJ, breakShare, ms, aOpen; public long slot;
        /** 0.13.0: the surface gravity the field was built for, and heightScale = Hs of the full spectrum at g over Hs at Earth's g, same
         *  wind and fetch (JONSWAP, Hasselmann et al. 1973: fetch-limited Hs goes as g^-1/2, a fully developed sea, Pierson & Moskowitz
         *  1964, as 1/g). hsFull is the normaliser every reader divides heightOpen(s) by, so it carries the scale: hsFull = Hs(g) / heightScale. */
        public double g = G, heightScale = 1, fetch = FETCH, swellHs, swellTp, windHs;
    }
    /** Builds the field for time t (s), sea state s, wind toward 'wind' (rad). Thread-safe (no shared mutable state). */
    public static Field build(double t, double s, double wind, Field into) { return build(t, s, wind, G, into); }
    /** 0.13.0: the same at surface gravity g (m/s2). */
    public static Field build(double t, double s, double wind, double g, Field into) { return build(t, s, wind, g, FETCH, 0, 0, 0, into); }
    /**
     * 0.16.0: the sea with its own fetch and a swell. fetch (m): the open water the wind blows over to here (Saville's effective fetch,
     * TidesSurf): wind waves grow with it (JONSWAP, Hasselmann et al. 1973: fetch-limited until Pierson-Moskowitz), so a lake gets the
     * waves its width allows. swellHs (m), swellTp (s), swellDir (rad, toward): waves from storms far away (0: none), a narrow JONSWAP
     * (gamma 7) with cos^2s spreading s = 75 (Goda & Suzuki 1975: swell with a short decay distance). The field is in metres: hsFull =
     * heightOpen(s), so every reader's scale (heightOpen / hsFull) is 1 and its height is wind Hs^2 + swell Hs^2 combined.
     */
    public static Field build(double t, double s, double wind, double g, double fetch, double swellHs, double swellTp, double swellDir, Field into) {
        long t0 = System.nanoTime();
        Field F = into != null ? into : new Field();
        fetch = Math.max(10, Math.min(FETCH, fetch));
        double u = u10(s), lam = choppy(u), dk = TWO_PI / L; double[] p = parameters(u, fetch, g); double[] W = frequencies(g); double hScale = heightScale(u, g, fetch);
        // 0.16.0: the wind sea's height is JONSWAP's own at this wind, fetch and gravity (Hasselmann et al. 1973; checked against NOAA buoys:
        // research/ndbc_wave_stats_2015-2019.json, e.g. South Lake Michigan median 0.69 m at its median 6.5 m/s wind, JONSWAP 0.40-0.99 m
        // for 10-50 km), no longer Tides' chosen heightOpen(s) (which gave 0.08 m in fair weather on the open sea; real seas are near 0.74 m)
        double windRaw = hsFullCached(u, g, fetch), windHs = windRaw, wScale = 1; hScale = windHs / WaveModel.heightOpen(s);
        double wpS = swellTp > 0 ? TWO_PI / swellTp : 0, swRaw = swellHs > 0 && wpS > 0 ? swellRaw(wpS) : 1, sScale = swellHs > 0 && wpS > 0 ? swellHs / swRaw : 0;
        double[] r0 = new double[N * N], i0 = new double[N * N], r1 = new double[N * N], i1 = new double[N * N], r2 = new double[N * N], i2 = new double[N * N];
        double[] h0r = new double[N * N], h0i = new double[N * N]; double m0 = 0;
        for (int id = 0; id < N * N; id++) {
            if (!IN[id]) continue;
            double sk = (spectrumK(KX[id], KZ[id], p[0], p[1], wind, g) * wScale * wScale + (sScale > 0 ? swellK(KX[id], KZ[id], wpS, swellDir, g) * sScale * sScale : 0)) * dk * dk, a = Math.sqrt(sk / 2) / Math.sqrt(2);   // E|h0|^2 = sk / 2
            h0r[id] = XR[id] * a; h0i[id] = XI[id] * a; m0 += sk;
        }
        for (int j = 0; j < N; j++) {
            int jm = j == 0 ? 0 : N - j;
            for (int i = 0; i < N; i++) {
                int id = i + N * j, im = (i == 0 ? 0 : N - i) + N * jm;
                double aR = h0r[id], aI = h0i[id], bR = h0r[im], bI = h0i[im];
                if (aR == 0 && aI == 0 && bR == 0 && bI == 0) continue;
                double ph = -W[id] * t, cs = Math.cos(ph), sn = Math.sin(ph);
                double hr = aR * cs - aI * sn + bR * cs - bI * sn, hi = aR * sn + aI * cs - (bR * sn + bI * cs);   // h0 e^{-iwt} + conj(h0(-k)) e^{iwt}: travels along +k, i.e. with the wind (measured, FftCheck 4)
                double kx = KX[id], kz = KZ[id], k = Math.sqrt(kx * kx + kz * kz), ux = kx / k, uz = kz / k;
                double dxR = -lam * ux * hi, dxI = lam * ux * hr, dzR = -lam * uz * hi, dzI = lam * uz * hr;   // D~ = i lam k^ h~
                double cxx = -lam * kx * ux, czz = -lam * kz * uz, cxz = -lam * kx * uz;                        // J~ = d D / dx
                double jxxR = cxx * hr, jxxI = cxx * hi, jzzR = czz * hr, jzzI = czz * hi, jxzR = cxz * hr, jxzI = cxz * hi;
                // pairs (A + iB comes back as a + ib for Hermitian A~, B~): 0 h + i Dx ; 1 Dz + i Jxz ; 2 Jxx + i Jzz
                r0[id] = hr - dxI; i0[id] = hi + dxR;
                r1[id] = dzR - jxzI; i1[id] = dzI + jxzR;
                r2[id] = jxxR - jzzI; i2[id] = jxxI + jzzR;
            }
        }
        ifft2(r0, i0); ifft2(r1, i1); ifft2(r2, i2);
        double var = 0; double[] jac = new double[N * N];
        double aOpen = 1;                                                                    // 0.16.0: the field is in metres
        for (int id = 0; id < N * N; id++) {
            double h = r0[id], dx = i0[id], dz = r1[id], jxz = i1[id], jxx = r2[id], jzz = i2[id];
            double J = (1 + jxx) * (1 + jzz) - jxz * jxz; var += h * h;
            jac[id] = (1 + aOpen * jxx) * (1 + aOpen * jzz) - aOpen * jxz * aOpen * jxz;
            int o = 4 * id;
            F.a[o] = half(h); F.a[o + 1] = half(dx); F.a[o + 2] = half(dz); F.a[o + 3] = half(J);
            F.b[o] = half(jxx); F.b[o + 1] = half(jzz); F.b[o + 2] = half(jxz); F.b[o + 3] = 0;
        }
        F.t = t; F.s = s; F.wind = wind; F.u10 = u; F.choppy = lam; F.hsBand = 4 * Math.sqrt(var / (N * N)); F.hsFull = WaveModel.heightOpen(s); F.g = g; F.heightScale = hScale; F.fetch = fetch; F.swellHs = swellHs; F.swellTp = swellTp; F.windHs = windHs;
        double share = breakingShare(u); double[] sorted = jac.clone(); java.util.Arrays.sort(sorted);
        F.aOpen = aOpen; F.breakJ = share <= 0 ? -1e9 : sorted[Math.max(0, Math.min(sorted.length - 1, (int) Math.floor(share * sorted.length)))];
        int nb = 0; for (double J : jac) if (J < F.breakJ) nb++; F.breakShare = nb / (double) (N * N);
        F.ms = (System.nanoTime() - t0) / 1e6; built++;
        return F;
    }
    static final java.util.concurrent.ConcurrentHashMap<Long, Double> HS = new java.util.concurrent.ConcurrentHashMap<>();
    static double hsFullCached(double u) { return hsFullCached(u, G); }
    /** 0.13.1: wind-wave height at gravity g over Earth's, same wind and fetch, from the spectrum itself. */
    public static double heightScale(double u10, double g) { return g == G ? 1 : hsFullCached(u10, g) / hsFullCached(u10, G); }
    /** 0.16.0: wind-wave height at gravity g and fetch over Earth's open sea (100 km), same wind, from the spectrum itself. */
    public static double heightScale(double u10, double g, double fetch) { return hsFullCached(u10, g, fetch) / hsFullCached(u10, G); }
    /** 0.16.0: a fetch rounded to steps of x1.25 (the open sea: FETCH), so caches and drives see few distinct values. */
    public static double quantFetch(double f) { if (!(f < FETCH)) return FETCH; return Math.min(FETCH, Math.pow(1.25, Math.round(Math.log(Math.max(10, f)) / Math.log(1.25)))); }
    public static double hsFullCached(double u, double g, double fetch) { return HS.computeIfAbsent((Double.doubleToLongBits(u) * 31 + Double.doubleToLongBits(g)) * 31 + Double.doubleToLongBits(fetch), k -> hsFull(u, g, fetch)); }
    public static double hsFull(double u10, double g, double fetch) {
        double[] p = parameters(u10, fetch, g); double m0 = 0, w0 = 0.02, w1 = 12.0; int n = 4000; double dw = (w1 - w0) / n;
        for (int i = 0; i <= n; i++) { double w = w0 + i * dw, f = (i == 0 || i == n) ? 1 : (i % 2 == 1 ? 4 : 2); m0 += f * jonswap(w, p[0], p[1], g); }
        return 4 * Math.sqrt(m0 * dw / 3); }
    // ---- 0.16.0 the swell
    public static final double SWELL_GAMMA = 7.0, SWELL_S = 75;
    /** The swell's frequency spectrum (alpha 1: scaled to its Hs by the caller). */
    static double swellW(double w, double wp, double g) {
        if (w <= 0) return 0; double sigma = w <= wp ? 0.07 : 0.09, r = Math.exp(-(w - wp) * (w - wp) / (2 * sigma * sigma * wp * wp));
        return g * g / Math.pow(w, 5) * Math.exp(-1.25 * Math.pow(wp / w, 4)) * Math.pow(SWELL_GAMMA, r); }
    static final java.util.concurrent.ConcurrentHashMap<Long, Double> SW = new java.util.concurrent.ConcurrentHashMap<>();
    /** Hs of the unscaled swell spectrum. */
    static double swellRaw(double wp) { return SW.computeIfAbsent(Double.doubleToLongBits(wp), k -> { double m0 = 0, w0 = 0.02, w1 = 12.0; int n = 4000; double dw = (w1 - w0) / n;
        for (int i = 0; i <= n; i++) { double w = w0 + i * dw, f = (i == 0 || i == n) ? 1 : (i % 2 == 1 ? 4 : 2); m0 += f * swellW(w, wp, G); } return 4 * Math.sqrt(m0 * dw / 3); }); }
    static final double SWELL_NORM = swellNorm();
    static double swellNorm() { double sum = 0; int n = 4000; for (int i = 0; i < n; i++) { double th = -Math.PI + (i + 0.5) * 2 * Math.PI / n; sum += Math.pow(Math.abs(Math.cos(th / 2)), 2 * SWELL_S); } return sum * 2 * Math.PI / n; }
    /** S(k) of the swell toward dir (deep water at g). */
    static double swellK(double kx, double kz, double wp, double dir, double g) {
        double k = Math.sqrt(kx * kx + kz * kz); if (k <= 0) return 0;
        double w = Math.sqrt(g * k), dwdk = g / (2 * w), th = Math.atan2(kz, kx) - dir; th = Math.atan2(Math.sin(th), Math.cos(th));
        return swellW(w, wp, G) * Math.pow(Math.abs(Math.cos(th / 2)), 2 * SWELL_S) / SWELL_NORM * dwdk / k; }
    static double hsFullCached(double u, double g) { return HS.computeIfAbsent(Double.doubleToLongBits(u) * 31 + Double.doubleToLongBits(g), k -> hsFull(u, g)); }
    public static volatile long built;

    // ------------------------------------------------------------------ sampling (the shader's tides_fftFetch, number for number)
    /** The 8 values at (x, z) m, bilinear on the periodic grid, from the halves: out = {h, Dx, Dz, J, Jxx, Jzz, Jxz, 0}. */
    public static void fetch(Field F, double x, double z, double[] out) {
        double u = x / L; u -= Math.floor(u); double v = z / L; v -= Math.floor(v);
        double fx = u * N, fz = v * N; int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz); double tx = fx - ix, tz = fz - iz;
        if (ix >= N) ix -= N; if (iz >= N) iz -= N;
        int ix1 = ix + 1 == N ? 0 : ix + 1, iz1 = iz + 1 == N ? 0 : iz + 1;
        int a = 4 * (ix + N * iz), b = 4 * (ix1 + N * iz), c = 4 * (ix + N * iz1), d = 4 * (ix1 + N * iz1);
        for (int q = 0; q < 4; q++) {
            out[q] = (HALF[F.a[a + q] & 0xFFFF] * (1 - tx) + HALF[F.a[b + q] & 0xFFFF] * tx) * (1 - tz) + (HALF[F.a[c + q] & 0xFFFF] * (1 - tx) + HALF[F.a[d + q] & 0xFFFF] * tx) * tz;
            out[4 + q] = (HALF[F.b[a + q] & 0xFFFF] * (1 - tx) + HALF[F.b[b + q] & 0xFFFF] * tx) * (1 - tz) + (HALF[F.b[c + q] & 0xFFFF] * (1 - tx) + HALF[F.b[d + q] & 0xFFFF] * tx) * tz;
        }
    }

    // ------------------------------------------------------------------ half floats (IEEE binary16, round to nearest even)
    public static final float[] HALF = new float[65536];
    static {
        for (int h = 0; h < 65536; h++) {
            int sgn = (h >> 15) & 1, e = (h >> 10) & 31, m = h & 1023;
            double v = e == 0 ? m * Math.pow(2, -24) : e == 31 ? (m == 0 ? Double.POSITIVE_INFINITY : Double.NaN) : (1 + m / 1024.0) * Math.pow(2, e - 15);
            HALF[h] = (float) (sgn != 0 ? -v : v);
        }
    }
    public static short half(double d) {
        int x = Float.floatToRawIntBits((float) d);
        int sign = (x >>> 16) & 0x8000, e = ((x >>> 23) & 0xff) - 127 + 15, m = x & 0x7fffff;
        if (e >= 31) return (short) (sign | 0x7bff);
        if (e <= 0) {
            if (e < -10) return (short) sign;
            m |= 0x800000; int shift = 14 - e; int hm = m >>> shift, rem = m & ((1 << shift) - 1), halfway = 1 << (shift - 1);
            if (rem > halfway || (rem == halfway && (hm & 1) != 0)) hm++;
            return (short) (sign | hm);
        }
        int h = sign | (e << 10) | (m >>> 13), r = m & 0x1fff;
        if (r > 0x1000 || (r == 0x1000 && (h & 1) != 0)) h++;
        return (short) h;
    }

    // ------------------------------------------------------------------ the transform (in place, no 1/N): out[x] = sum_k in[k] e^{+2 pi i k x / N}
    public static void ifft2(double[] re, double[] im) {
        for (int r = 0; r < N; r++) fft1(re, im, r * N);
        transpose(re, im);
        for (int r = 0; r < N; r++) fft1(re, im, r * N);
        transpose(re, im);
    }
    static void transpose(double[] re, double[] im) {
        for (int j = 0; j < N; j++) for (int i = j + 1; i < N; i++) { int a = i + N * j, b = j + N * i; double t = re[a]; re[a] = re[b]; re[b] = t; t = im[a]; im[a] = im[b]; im[b] = t; }
    }
    static final int[] REV = new int[N]; static final double[] TWR = new double[N], TWI = new double[N];
    static {
        int bits = 0; while ((1 << bits) < N) bits++;
        for (int i = 0; i < N; i++) { int r = 0; for (int b = 0; b < bits; b++) if ((i & (1 << b)) != 0) r |= 1 << (bits - 1 - b); REV[i] = r; }
        int o = 0; for (int len = 2; len <= N; len <<= 1) for (int k = 0; k < len / 2; k++) { TWR[o] = Math.cos(TWO_PI * k / len); TWI[o] = Math.sin(TWO_PI * k / len); o++; }
    }
    static void fft1(double[] re, double[] im, int start) {
        double[] cr = new double[N], ci = new double[N];
        for (int i = 0; i < N; i++) { int s = start + REV[i]; cr[i] = re[s]; ci[i] = im[s]; }
        int o = 0;
        for (int len = 2; len <= N; len <<= 1) {
            int half = len >> 1;
            for (int i = 0; i < N; i += len) for (int k = 0; k < half; k++) {
                double wr = TWR[o + k], wi = TWI[o + k]; int a = i + k, b = a + half;
                double br = cr[b], bi = ci[b], ur = br * wr - bi * wi, ui = br * wi + bi * wr, ar = cr[a], ai = ci[a];
                cr[b] = ar - ur; ci[b] = ai - ui; cr[a] = ar + ur; ci[a] = ai + ui;
            }
            o += half;
        }
        System.arraycopy(cr, 0, re, start, N); System.arraycopy(ci, 0, im, start, N);
    }
}
