import com.hopeless.tides.core.*;

/** SwellTest [out.json]: the 0.16.1 swell train (Surf.Field.swellCorner, Surf.planeSwell) on SteerTest's made-up seabeds. Checks: (1) in
 *  deep flat water the refracted train equals the plane train (no seam where the field ends); (2) on the beach the crests turn toward the
 *  shore (the train's slope direction against the depth contour normal, shallower = more turned); (3) behind the island the train is
 *  calmer than in front; (4) at the field's edge the train is the plane train exactly. Writes the island case's texture, uniforms and 256
 *  corners for tools/swell_parity.js (GPU check). Saved tool. */
public class SwellTest {
    static int pass, fail;
    static void check(String w, boolean ok, String got) { if (ok) pass++; else fail++; System.out.printf("%s %-62s %s%n", ok ? "PASS" : "FAIL", w, got); }
    static Surf.Field run(SteerTest.Depth d, double hs, double tp, double heading) { return SteerTest.run(d, hs, tp, heading); }
    static double[] clk(Surf.Field f, double t) { double[] c = new double[Surf.K]; for (int q = 0; q < Surf.K; q++) c[q] = Surf.clock(f.om[q], t); return c; }
    public static void main(String[] a) throws Exception {
        double hs = 1.0, tp = 8.0, t = 777.3; int N = 128;
        // (1) deep flat: train vs plane over the interior (seed frame 1000, 2000 as SteerTest)
        Surf.Field f = run((i, j) -> 60, hs, tp, 0.3); double[] c = clk(f, t); double md = 0, mx = 0;
        for (int j = 30; j < 98; j += 3) for (int i = 30; i < 98; i += 3) { double[] tr = f.swellCorner(i, j, c, 1, 1000 + i, 2000 + j), pl = Surf.planeSwell(f, c, 1, 1000 + i, 2000 + j); md = Math.max(md, Math.abs(tr[0] - pl[0])); mx = Math.max(mx, Math.abs(pl[0])); }
        check("deep flat: the refracted train matches the plane train", md < 0.15 * mx, String.format("max |train - plane| %.3f m (plane up to %.3f m)", md, mx));
        // (4) edge: identical
        double ed = 0; for (int k = 0; k <= N; k += 8) { double[] tr = f.swellCorner(k, 0, c, 1, 1000 + k, 2000), pl = Surf.planeSwell(f, c, 1, 1000 + k, 2000); ed = Math.max(ed, Math.abs(tr[0] - pl[0])); }
        check("field edge: the train is the plane train", ed < 1e-9, String.format("max diff %.2e m", ed));
        // (2) beach: crests turn to the shore. The wave's travel direction from the slope over a cycle: average of slope * d eta/dt sign
        double th0 = Math.toRadians(40), heading = Math.PI / 2 - th0; f = run((i, j) -> Math.min(15, 0.15 * (120 - j)), hs, tp, heading);
        double prev = 99; boolean turning = true; StringBuilder sb = new StringBuilder();
        for (int jj : new int[]{40, 80, 100, 110}) { double sx = 0, sz = 0;
            for (int s = 0; s < 40; s++) { double tt = t + s * 0.2; double[] c1 = clk(f, tt), c2 = clk(f, tt + 0.05); for (int ii = 40; ii < 90; ii += 2) { double[] p1 = f.swellCorner(ii, jj, c1, 1, 1000 + ii, 2000 + jj), p2 = f.swellCorner(ii, jj, c2, 1, 1000 + ii, 2000 + jj);
                double dt = (p2[0] - p1[0]) / 0.05; sx -= p1[1] * dt; sz -= p1[2] * dt; } }   // a wave moving along k: d eta/dt = -c k . grad eta, so -grad eta * d eta/dt points along k
            double ang = Math.toDegrees(Math.atan2(sx, sz));   // off the shore normal (+j)
            sb.append(String.format(" depth %.1f m: %.0f deg;", Math.min(15, 0.15 * (120 - jj)), ang)); if (Math.abs(ang) > prev + 1) turning = false; prev = Math.abs(ang); }
        check("beach: travel turns toward the shore normal as it shoals", turning && prev < 30, sb.toString());
        // (3) island lee
        f = run((i, j) -> { double r = Math.hypot(i - 64, j - 64); return r < 14 ? -1 : Math.min(12, (r - 14) * 0.6); }, hs, tp, 0.0);
        double up = 0, lee = 0; int nu = 0, nl = 0; for (int s = 0; s < 30; s++) { double[] cc = clk(f, t + s * 0.37);
            for (int j = 58; j <= 70; j += 2) { for (int i = 30; i <= 44; i += 2) { up += Math.abs(f.swellCorner(i, j, cc, 1, 1000 + i, 2000 + j)[0]); nu++; } for (int i = 84; i <= 98; i += 2) { lee += Math.abs(f.swellCorner(i, j, cc, 1, 1000 + i, 2000 + j)[0]); nl++; } } }
        up /= nu; lee /= nl; check("island: calmer behind it than in front", lee < 0.75 * up, String.format("mean |eta| lee %.3f m, front %.3f m", lee, up));
        // (5) local fetch on the island, wind toward +i: upwind of the island the chop is full, just downwind of it (its lee) small and growing
        Surf.fetchFactors(f, 0.0, 8.0, FftSea.FETCH); double near = 0, far = 0; int nn = 0, nf = 0;
        for (int j = 60; j <= 68; j++) { for (int i = 80; i <= 84; i++) { near += Surf.fetchCorner(f, i, j); nn++; } for (int i = 104; i <= 110; i++) { far += Surf.fetchCorner(f, i, j); nf++; } }
        near /= nn; far /= nf; double front = Surf.fetchCorner(f, 30, 64);
        check("fetch: chop small just behind land, growing downwind, full on the open side", near < 0.6 && far > near && front > 0.95, String.format("behind %.2f, 25 blocks on %.2f, open side %.2f", near, far, front));
        if (a.length > 1) { StringBuilder b = new StringBuilder("{\"tex\":[");
            for (int r = 0; r < 6; r++) for (int k = 0; k < N * N; k++) b.append(r == 0 ? (f.seaCol[k] ? "0,0,0,1," : "0,0,0,-1,") : "0,0,0,0,");
            for (int k = 0; k < N * N; k++) b.append(f.seaCol[k] ? f.fetchK[k] : 1).append(",0,0,").append(f.seaCol[k] ? 1 : 0).append(k == N * N - 1 ? "" : ",");
            b.append("],\"pts\":["); java.util.Random r = new java.util.Random(9);
            for (int q = 0; q < 256; q++) { int i = r.nextInt(N + 1), j = r.nextInt(N + 1); b.append(String.format("[%d,%d,%.6f,0,0]", i, j, Surf.fetchCorner(f, i, j))).append(q == 255 ? "" : ","); }
            b.append("]}"); java.nio.file.Files.writeString(java.nio.file.Path.of(a[1]), b.toString()); }
        if (a.length > 0) { StringBuilder b = new StringBuilder("{\"tex\":["); double sh = 0.8; double[] cc = clk(f, t);
            for (int k = 0; k < N * N; k++) { boolean s = f.seaCol[k]; b.append(s ? f.phi[Surf.DOM][k] : 0).append(',').append(s ? f.shR[k] : 0).append(',').append(s ? f.shP[k] : 0).append(',').append(s ? f.hrms[k] : -1).append(','); }
            for (int k = 0; k < N * N; k++) { double[] g = f.seaCol[k] ? f.grad(k % N, k / N) : new double[2]; b.append(f.rho[k]).append(',').append(f.depth[k]).append(',').append((float) g[0]).append(',').append((float) g[1]).append(','); }
            for (int k = 0; k < 2 * N * N; k++) b.append("0,0,0,0,");
            for (int k = 0; k < N * N; k++) { boolean s = f.seaCol[k]; for (int q = 0; q < 4; q++) b.append(s ? f.phi[q][k] : 0).append(','); }
            for (int k = 0; k < N * N; k++) { boolean s = f.seaCol[k] && !Float.isNaN(f.steerK[k]); for (int q = 0; q < 4; q++) b.append(s ? f.hc[q][k] : -1).append(k == N * N - 1 && q == 3 ? "" : ","); }
            b.append("],\"clk\":["); for (int q = 0; q < 4; q++) b.append((float) cc[q]).append(q < 3 ? "," : "");
            b.append("],\"k\":["); for (int q = 0; q < 4; q++) b.append((float) (f.om[q] * f.om[q] / f.g)).append(q < 3 ? "," : "");
            b.append("],\"d\":["); for (int q = 0; q < 4; q++) b.append((float) (f.heading + Surf.DIR_OFF[q])).append(q < 3 ? "," : "");
            b.append(String.format("],\"amp\":%.8f,\"unit\":%.8f,\"share\":%.3f,\"pts\":[", Surf.UNIT * f.hs0 * sh, Surf.UNIT, sh)); java.util.Random r = new java.util.Random(5);
            for (int q = 0; q < 256; q++) { int i = r.nextInt(N + 41) - 20, j = r.nextInt(N + 41) - 20; double Xm = 1000 + i, Zm = 2000 + j;
                double[] o = i >= 0 && j >= 0 && i <= N && j <= N ? f.swellCorner(i, j, cc, sh, Xm, Zm) : Surf.planeSwell(f, cc, sh, Xm, Zm);
                b.append(String.format("[%d,%d,%.6f,%.6f,%.6f]", i, j, o[0], o[1], o[2])).append(q == 255 ? "" : ","); }
            b.append("]}"); java.nio.file.Files.writeString(java.nio.file.Path.of(a[0]), b.toString()); }
        System.out.printf("SwellTest: %d pass, %d fail%n", pass, fail); if (fail > 0) System.exit(1);
    }
}
