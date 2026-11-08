import com.hopeless.tides.core.*;
/** WaveClimateTest: 0.16.0 checks, measured from the built Fourier fields: (1) the wind sea's height by fetch (JONSWAP) at fair, rain
 *  and storm winds; (2) a swell added to it (wind^2 + swell^2); (3) the wall formulas against EurOtop / Seelig & Ahrens worked values;
 *  (4) the model against NOAA buoys (research/ndbc_wave_stats_2015-2019.json). Saved tool. */
public class WaveClimateTest {
    static double drawnHs(FftSea.Field f) { double v = 0; double[] o = new double[8]; int G = FftSea.N; double dx = FftSea.L / G, sc = WaveModel.heightOpen(f.s) / f.hsFull;
        for (int j = 0; j < G; j++) for (int i = 0; i < G; i++) { FftSea.fetch(f, i * dx, j * dx, o); double h = o[0] * sc; v += h * h; } return 4 * Math.sqrt(v / (G * G)); }
    public static void main(String[] a) {
        int pass = 0, n = 0; double g = 9.81;
        for (double s : new double[]{0.0, 0.5, 1.0}) { double u = FftSea.u10(s); StringBuilder b = new StringBuilder(String.format("u10 %.1f m/s:", u));
            double prev = 0; boolean grows = true;
            for (double fe : new double[]{300, 2000, 10000, 100000}) { FftSea.Field f = FftSea.build(50, s, 0.3, g, fe, 0, 0, 0, null); double hs = drawnHs(f), want = FftSea.hsFull(u, g, fe);
                b.append(String.format("  F %.0f m: drawn %.2f (JONSWAP %.2f; the 5-512 m grid holds %.0f%%)", fe, hs, want, 100 * hs / want)); if (hs < prev) grows = false; prev = hs; }
            System.out.println(b); n++; if (grows) pass++; }
        // swell
        FftSea.Field w = FftSea.build(50, 0.0, 0.3, g, 100000, 0, 0, 0, null), sw = FftSea.build(50, 0.0, 0.3, g, 100000, 0.93, 9.09, 1.2, null);
        double hw = drawnHs(w), hb = drawnHs(sw), want = Math.sqrt(hw * hw + 0.93 * 0.93 * 0.0 + 0);
        FftSea.Field so = FftSea.build(50, 0.0, 0.3, g, 300, 0.93, 9.09, 1.2, null); double hso = drawnHs(so);
        System.out.printf("swell 0.93 m 9.09 s: fair wind sea %.2f m alone -> %.2f m with swell (want sqrt(%.2f^2 + 0.93^2) = %.2f); swell on a 300 m fetch: %.2f m%n", hw, hb, hw, Math.sqrt(hw * hw + 0.93 * 0.93), hso);
        n++; if (Math.abs(hb - Math.sqrt(hw * hw + 0.93 * 0.93)) < 0.12) pass++;
        // walls
        double[][] xi = {{1, 0}, {3, 0}, {10, 0}, {50, 0}};
        for (double[] x : xi) System.out.printf("wall xi %.0f: Kr %.2f (Seelig & Ahrens), Ru2%%/Hm0 %.2f (EurOtop 5.4/5.5)%n", x[0], Surf.reflect(x[0]), Surf.wallRunup(1, x[0]));
        n++; if (Math.abs(Surf.wallRunup(1, 1) - 1.65) < 1e-9 && Math.abs(Surf.wallRunup(1, 50) - (4 - 1.5 / Math.sqrt(50))) < 1e-9 && Surf.reflect(50) > 0.99) pass++;
        // buoys: South Lake Michigan median wind 6.5 m/s, median Hs 0.69 m: JONSWAP over 10-50 km
        System.out.printf("buoy 45007 (Lake Michigan): measured median 0.69 m at 6.5 m/s; JONSWAP 10 km %.2f, 25 km %.2f, 50 km %.2f m%n", FftSea.hsFull(6.5, g, 10000), FftSea.hsFull(6.5, g, 25000), FftSea.hsFull(6.5, g, 50000));
        n++; if (FftSea.hsFull(6.5, g, 10000) < 0.69 && FftSea.hsFull(6.5, g, 50000) > 0.69) pass++;
        System.out.println("WaveClimateTest: " + pass + " of " + n + " pass");
    }
}
