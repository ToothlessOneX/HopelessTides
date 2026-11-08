import com.hopeless.tides.core.*;

/** SteerTest [out.json]: the 0.16.1 steering (Surf.Field.steerA/steerK: how the open sea turns and grows or calms near shores) on three
 *  made-up seabeds, checked against the textbook: (1) deep flat water: no turn, height x1; (2) a straight beach with parallel depth
 *  contours, waves coming in 40 deg off the shore normal: the turn at each depth against Snell's law, sin(theta)/c = sin(theta0)/c0
 *  (c the phase speed from w^2 = g k tanh(k h)); (3) a round island: the lee behind it calmer than the open side. Writes the island
 *  case's texture and corners for tools/steer_parity.js (GPU check). Saved tool. */
public class SteerTest {
    static int pass, fail;
    static void check(String w, boolean ok, String got) { if (ok) pass++; else fail++; System.out.printf("%s %-62s %s%n", ok ? "PASS" : "FAIL", w, got); }
    interface Depth { double at(int i, int j); }
    static Surf.Field run(Depth d, double hs, double tp, double heading) {
        int N = 128; Surf.Input in = new Surf.Input(); in.n = N; in.g = 9.81; in.bed = new double[N * N]; in.depth = new double[N * N]; in.known = new boolean[N * N];
        double sea = 0.888; for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) { int k = i + N * j; double h = d.at(i, j); in.known[k] = true; in.depth[k] = Math.max(0, h); in.bed[k] = sea - h; }
        in.sea = sea; in.sea(hs, tp, heading); in.seedX0 = 1000; in.seedY0 = 2000; return Surf.compute(in);
    }
    public static void main(String[] a) throws Exception {
        double hs = 1.0, tp = 8.0, g = 9.81, om = 2 * Math.PI / tp;
        // (1) deep flat
        Surf.Field f = run((i, j) -> 60, hs, tp, 0.3); double mt = 0, mk = 0; int n = 0;
        for (int j = 20; j < 108; j++) for (int i = 20; i < 108; i++) { double[] s = f.steer(i, j); if (s == null) continue; mt = Math.max(mt, Math.abs(s[0])); mk = Math.max(mk, Math.abs(s[1] - 1)); n++; }
        check("deep flat water: turn under 2 deg", Math.toDegrees(mt) < 2, String.format("max %.2f deg over %d corners", Math.toDegrees(mt), n));
        check("deep flat water: height factor within 5 % of 1", mk < 0.05, String.format("max |k - 1| %.3f", mk));
        // (2) plane beach: shore along j = 120, depth 0.15 m a block seaward (j smaller), 15 m at most; waves head +j, 40 deg off the normal
        double th0 = Math.toRadians(40), heading = Math.PI / 2 - th0;   // toward +j, turned 40 deg toward +i
        f = run((i, j) -> Math.min(15, 0.15 * (120 - j)), hs, tp, heading);
        System.out.println("beach: " + f.describe());
        double k0 = Surf.k(om, 15, g), c0 = om / k0, s0 = Math.sin(th0) / c0; double err = 0; int ne = 0;
        for (int jj : new int[]{40, 60, 80, 95, 105, 112}) { double h = Math.min(15, 0.15 * (120 - jj)), c = om / Surf.k(om, h, g), th = Math.asin(Math.min(1, s0 * c));
            double want = Math.toDegrees(th - th0); double got = 0; int m = 0; for (int ii = 30; ii < 98; ii++) { double[] s = f.steer(ii, jj); if (s != null) { got += Math.toDegrees(s[0]); m++; } } got /= Math.max(1, m);
            // the field's turn is from the PEAK component's deep heading (dir[DOM] = heading + DIR_OFF[DOM]); Snell from its own deep angle
            double thD = th0 - Surf.DIR_OFF[Surf.DOM], cD = c, wantD = Math.toDegrees(thD - Math.asin(Math.min(1, Math.sin(thD) / c0 * cD)));   /* turning toward the normal raises the heading angle here */
            System.out.printf("   depth %5.2f m: turned %+6.1f deg, Snell %+6.1f deg%n", h, got, wantD); err = Math.max(err, Math.abs(got - wantD)); ne++; }
        check("beach: the turn follows Snell's law within 4 deg", err < 4, String.format("worst %.1f deg over %d depths", err, ne));
        // (3) island: radius 14 blocks round (64, 64) in 12 m water, waves heading +i
        f = run((i, j) -> { double r = Math.hypot(i - 64, j - 64); return r < 14 ? -1 : Math.min(12, (r - 14) * 0.6); }, hs, tp, 0.0);
        System.out.println("island: " + f.describe());
        double up = 0, lee = 0; int nu = 0, nl = 0;
        for (int j = 58; j <= 70; j++) { for (int i = 30; i <= 44; i++) { double[] s = f.steer(i, j); if (s != null) { up += s[1]; nu++; } } for (int i = 82; i <= 96; i++) { double[] s = f.steer(i, j); if (s != null) { lee += s[1]; nl++; } } }
        up /= Math.max(1, nu); lee /= Math.max(1, nl);
        check("island: the lee behind it is calmer than the open side", lee < 0.75 * up, String.format("lee x%.2f, open side x%.2f", lee, up));
        // GPU data: the island texture (row 4 only matters, rows 0-3 as TidesSurfGpu) and 256 corners with Field.steer
        if (a.length > 0) { int N = 128; StringBuilder b = new StringBuilder("{\"tex\":[");
            for (int r = 0; r < 4; r++) for (int c = 0; c < N * N; c++) b.append(r == 0 && f.seaCol[c] ? "0,0,0,1," : "0,0,0,-1,");
            for (int c = 0; c < N * N; c++) { boolean s = f.seaCol[c]; b.append(s ? f.steerA[c] : 0).append(',').append(s ? (Float.isNaN(f.steerK[c]) ? -1 : f.steerK[c]) : 0).append(",0,").append(s ? 1 : 0).append(c == N * N - 1 ? "" : ","); }
            b.append("],\"pts\":["); java.util.Random r = new java.util.Random(3);
            for (int q = 0; q < 256; q++) { int i = r.nextInt(N + 1), j = r.nextInt(N + 1); double[] s = f.steer(i, j); double[] o = s == null ? new double[]{0, 1, 0} : new double[]{s[0] * s[2], 1 + (s[1] - 1) * s[2], s[2]};
                b.append(String.format("[%d,%d,%.6f,%.6f,%.6f]", i, j, o[0], o[1], o[2])).append(q == 255 ? "" : ","); }
            b.append("]}"); java.nio.file.Files.writeString(java.nio.file.Path.of(a[0]), b.toString()); }
        System.out.printf("SteerTest: %d pass, %d fail%n", pass, fail); if (fail > 0) System.exit(1);
    }
}
