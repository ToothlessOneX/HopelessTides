import com.hopeless.tides.core.*;
/** WallTest: a straight sea wall (5 m high) on a 6 m deep sea, waves straight at it: measures the wall columns, Kr, R2 against the
 *  formulas, and the standing wave's envelope along a line out from the wall (antinode 1 + Kr at the wall, node 1 - Kr a quarter
 *  wavelength out). Saved tool. */
public class WallTest {
    public static void main(String[] a) {
        int N = 128; Surf.Input in = new Surf.Input(); in.n = N; in.bed = new double[N * N]; in.depth = new double[N * N]; in.known = new boolean[N * N]; in.sea = 0.889;
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) { int c = i + N * j; in.known[c] = true; if (i < 100) { in.bed[c] = -5.111; in.depth[c] = 6.0; } else { in.bed[c] = 5.889; in.depth[c] = 0; } }
        in.sea(1.0, 9.09, 0.0); Surf.Field f = Surf.compute(in); System.out.println(f.describe());
        int j = 64, c0 = 99 + N * j; double[] gr = f.grad(99, j); double k = Math.hypot(gr[0], gr[1]), L = 2 * Math.PI / k;
        double L0 = 9.81 * Math.pow(9.09 / 1.1, 2) / (2 * Math.PI), H = f.hrms[c0] * Math.sqrt(2), slope = (5.889 - 0.889) / 0.5, xi = slope / Math.sqrt(H / L0);
        System.out.printf("wall foot: Kr %.3f (formula %.3f at xi %.1f), R2 %.2f m (formula %.2f for Hm0 %.2f m); local wavelength %.1f m%n", f.wallKr[c0], Surf.reflect(xi), xi, f.wallR2[c0], Surf.wallRunup(H, xi), H, L);
        StringBuilder b = new StringBuilder("envelope by distance:"); double mn = 9, mx = 0, dMin = 0;
        for (int i = 99; i >= 70; i--) { int c = i + N * j; double amp = f.wallAmp(c); b.append(String.format(" %.0fm:%.2f", f.wallDist[c], amp)); if (amp < mn) { mn = amp; dMin = f.wallDist[c]; } mx = Math.max(mx, amp); }
        System.out.println(b); System.out.printf("max %.2f (want 1 + Kr = %.2f), min %.2f at %.1f m (want 1 - Kr = %.2f at L/4 = %.1f m)%n", mx, 1 + f.wallKr[c0], mn, dMin, 1 - f.wallKr[c0], L / 4);
        int pass = 0; if (f.wallColumns >= 120) pass++; if (Math.abs(mx - (1 + f.wallKr[c0])) < 0.02) pass++; if (Math.abs(dMin - L / 4) < 1.5) pass++; if (Math.abs(f.wallR2[c0] - Surf.wallRunup(H, xi)) < 0.05) pass++;
        System.out.println("WallTest: " + pass + " of 4 pass");
    }
}
