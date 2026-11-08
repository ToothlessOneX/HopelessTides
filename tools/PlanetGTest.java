import com.hopeless.tides.core.*;
/** 0.13.0 test: the Fourier sea at Earth's and a planet's gravity, measured from the built fields (not worked out): drawn height
 *  (heightOpen / hsFull x field h, as every reader scales it) and the mean wavelength of the drawn surface; and the planet tide. Saved tool. */
public class PlanetGTest {
    /** Peak wavelength from the published laws, written out here (not the mod's code): JONSWAP fetch-limited wp = 22 (g^2 / (U F))^1/3
     *  (Hasselmann et al. 1973), capped at Pierson-Moskowitz wp = 0.877 g / U19.5 (U19.5 = 1.06 U10); lambda = 2 pi g / wp^2. */
    static double lamPM(double u, double g) { double w = 0.877 * g / (1.06 * u); return 2 * Math.PI * g / (w * w); }
    static double lamRef(double u, double g) { double wf = 22 * Math.pow(g * g / (u * 100000.0), 1.0 / 3.0), wp = 0.877 * g / (1.06 * u); return wf <= wp ? lamPM(u, g) : 2 * Math.PI * g / (wf * wf); }
    public static void main(String[] a) {
        double g2 = a.length > 0 ? Double.parseDouble(a[0]) : 11.53   /* seed 1 start planet */; int pass = 0, n = 0;
        for (double s : new double[]{0.2, 0.5, 1.0}) {
            double[] hs = new double[2], lam = new double[2]; double[] gs = {9.81, g2};
            for (int k = 0; k < 2; k++) {
                FftSea.Field f = FftSea.build(100.0, s, 0.7, gs[k], null);
                double sc = WaveModel.heightOpen(s) / f.hsFull, v = 0; long cross = 0, cnt = 0; double[] o = new double[8]; int G = FftSea.N; double dx = FftSea.L / G;
                for (int r = 0; r < G; r++) { double pr = 0, pc = 0;   // every row and column of the whole 512 m tile
                    for (int i = 0; i < G; i++) { FftSea.fetch(f, i * dx, r * dx, o); double h = o[0] * sc; v += h * h; cnt++; if (i > 0 && (h > 0) != (pr > 0)) cross++; pr = h;
                        FftSea.fetch(f, r * dx, i * dx, o); double hc = o[0] * sc; if (i > 0 && (hc > 0) != (pc > 0)) cross++; pc = hc; } }
                hs[k] = 4 * Math.sqrt(v / cnt); lam[k] = 2.0 * (2.0 * G * FftSea.L) / Math.max(1, cross);   // mean zero-crossing wavelength over all rows and columns
            }
            double want = FftSea.heightScale(FftSea.u10(s), g2);   // the spectrum's own Hs ratio (JONSWAP fetch-limited / PM capped)
            System.out.printf("sea state %.1f: Hs drawn %.3f -> %.3f m (x%.3f, want x%.3f) | zero-crossing wavelength %.1f -> %.1f m (x%.3f, grid band only)%n", s, hs[0], hs[1], hs[1] / hs[0], want, lam[0], lam[1], lam[1] / lam[0]);
            n++; if (Math.abs(hs[1] / hs[0] - want) < 0.02) pass++;
            // the spectrum's peak wavelength (the code's own parameters, 2 pi g / wp^2): the zero-crossing length above is cut by the 5 m..512 m grid band
            double u = FftSea.u10(s); double[] p0 = FftSea.parameters(u, FftSea.FETCH, 9.81), p1 = FftSea.parameters(u, FftSea.FETCH, g2);
            double lp0 = 2 * Math.PI * 9.81 / (p0[1] * p0[1]), lp1 = 2 * Math.PI * g2 / (p1[1] * p1[1]);
            double wantL = lamRef(u, g2) / lamRef(u, 9.81); boolean pm = lamRef(u, g2) == lamPM(u, g2);
            System.out.printf("   peak wavelength %.1f -> %.1f m (x%.3f, want x%.3f from the published formulas; planet %s)%n", lp0, lp1, lp1 / lp0, wantL, pm ? "fully developed" : "fetch-limited");
            n++; if (Math.abs(lp1 / lp0 - wantL) < 0.01) pass++;
        }
        // speed: a wave of 20 m moves at sqrt(g lambda / 2 pi)
        System.out.printf("20 m wave speed: Earth %.2f m/s, planet %.2f m/s%n", Math.sqrt(9.81 * 20 / (2 * Math.PI)), Math.sqrt(g2 * 20 / (2 * Math.PI)));
        // the tide: spring/neap from the lunar share
        double amp = 0.25 * 0.283; double mx0 = 0, mx2 = 0, mxE0 = 0, mxE2 = 0;
        for (int t = 0; t < 24000; t += 10) { mx0 = Math.max(mx0, WaveModel.tide(t, 0, amp, 0.002)); mx2 = Math.max(mx2, WaveModel.tide(t, 2, amp, 0.002));
            mxE0 = Math.max(mxE0, WaveModel.tide(t, 0, 0.25, WaveModel.EARTH_LUNAR_SHARE)); mxE2 = Math.max(mxE2, WaveModel.tide(t, 2, 0.25, WaveModel.EARTH_LUNAR_SHARE)); }
        System.out.printf("tide, start planet (share 0.002): high water full moon %.4f, quarter %.4f (no spring/neap: ratio %.3f) | Earth by bodies: %.3f / %.3f (neap x%.2f; Tides' chosen Earth x0.45)%n", mx0, mx2, mx2 / mx0, mxE0, mxE2, mxE2 / mxE0);
        n++; if (Math.abs(mx2 / mx0 - 0.996) < 0.01) pass++;
        n++; if (Math.abs(mxE2 / mxE0 - 0.37) < 0.01) pass++;
        System.out.println("PlanetGTest: " + pass + " of " + n + " pass");
    }
}
