import com.hopeless.tides.core.*;
import com.hopeless.infinity.planet.*;
/** SurfTest seed x z: the surf model (core/Surf, ported from Hydro 0.11) on real terrain: 128 x 128 columns of Hopeless Infinity's
 *  face 0 round (x, z), from its Column plans. Measures refraction (crest normal vs the shore normal), shoaling, breaking, the run-up
 *  against Stockdon's formula, and the cost. Saved tool. */
public class SurfTest {
    public static Surf.Input input(long seed, int x0, int z0, int N, double hs, double tp, double heading, double g) {
        PlanetWorld w = new PlanetWorld(seed); Surf.Input in = new Surf.Input(); in.n = N; in.g = g;
        in.bed = new double[N * N]; in.depth = new double[N * N]; in.known = new boolean[N * N];
        double seaTop = Terrain.SEA + 0.888;   // a water source block's surface (Minecraft: 8/9)
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) { Column c = Column.of(w, 0, x0 + i, z0 + j); int k = i + N * j;
            in.known[k] = !c.voidCol; in.bed[k] = c.surfaceY + 1; in.depth[k] = c.surfaceY < Terrain.SEA ? seaTop - (c.surfaceY + 1) : 0; }
        in.sea = seaTop; in.sea(hs, tp, heading); return in;
    }
    public static void main(String[] a) {
        long seed = Long.parseLong(a[0]); int cx = Integer.parseInt(a[1]), cz = Integer.parseInt(a[2]); int N = 128; double g = 11.53;
        double s = 0.5, u = FftSea.u10(s), hs = WaveModel.heightOpen(s) * FftSea.heightScale(u, g); double[] p = FftSea.parameters(u, FftSea.FETCH, g); double tp = 2 * Math.PI / p[1];
        int pass = 0, n = 0;
        for (double heading : new double[]{-Math.PI / 2, 0.0}) {
            Surf.Input in = input(seed, cx - N / 2, cz - N / 2, N, hs, tp, heading, g);
            Surf.Field f = Surf.compute(in);
            System.out.printf("heading %.0f deg, Hs %.2f m Tp %.1f s: %s%n", Math.toDegrees(heading), hs, tp, f.describe());
            // shoaling and breaking along depth bands
            double[] sumH = new double[8]; int[] cnt = new int[8]; double[] rhoB = new double[8];
            for (int c = 0; c < N * N; c++) if (f.seaCol[c]) { int b = Math.min(7, (int) (f.depth[c] / 1.0)); double hc = f.hc[Surf.DOM][c]; sumH[b] += hc; rhoB[b] += f.rho[c]; cnt[b]++; }
            for (int b = 0; b < 8; b++) if (cnt[b] > 0) System.out.printf("   depth %d-%d m: %4d columns, peak H %.2f m (cap 0.6 h %.2f), rho %.2f%n", b, b + 1, cnt[b], sumH[b] / cnt[b], 0.6 * (b + 0.5), rhoB[b] / cnt[b]);
            // refraction: at columns 1-3 m deep, angle between the peak's phase gradient and the direction to the nearest dry column
            double angSum = 0, offSum = 0; int na = 0;
            for (int j = 2; j < N - 2; j++) for (int i = 2; i < N - 2; i++) { int c = i + N * j; if (!f.seaCol[c] || f.depth[c] > 3 || f.depth[c] < 0.5) continue;
                if (!f.seaCol[c - 1] || !f.seaCol[c + 1] || !f.seaCol[c - N] || !f.seaCol[c + N]) continue;
                double gx = f.phi[Surf.DOM][c + 1] - f.phi[Surf.DOM][c - 1], gy = f.phi[Surf.DOM][c + N] - f.phi[Surf.DOM][c - N]; double gl = Math.hypot(gx, gy); if (gl == 0) continue;
                double bx = in.bed[c + 1] - in.bed[c - 1], by = in.bed[c + N] - in.bed[c - N], bl = Math.hypot(bx, by); if (bl == 0) continue;   // up-slope: toward the shore
                angSum += Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (gx * bx + gy * by) / (gl * bl))))); offSum += Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (Math.cos(heading) * bx + Math.sin(heading) * by) / bl)))); na++; }
            double ang = angSum / Math.max(1, na), off = offSum / Math.max(1, na);
            System.out.printf("   refraction at 0.5-3 m depth (%d columns): crests travel %.0f deg off up-slope; the open sea's heading is %.0f deg off%n", na, ang, off);
            n++; if (na > 0 && ang < off) pass++;
            // run-up against Stockdon (the shore column's own R2 vs the formula from Hs0)
            System.out.printf("   swash: %d columns, mean R2 %.2f m on slope %.3f; Stockdon from Hs0 on that slope %.2f m%n", f.swashColumns, f.meanR2, f.meanBeta, Surf.r2(hs, g * tp * tp / (2 * Math.PI), f.meanBeta));
            n++; if (f.swashColumns > 0 && f.meanR2 > 0) pass++;
            n++; if (f.breakingColumns > 0) pass++;
            n++; if (f.ms < 500) pass++;
        }
        System.out.println("SurfTest: " + pass + " of " + n + " pass");
    }
}
