import com.hopeless.tides.core.*;
/** ChopTest [out.json]: the 0.16.3 wind sea in shallow water (Surf.Field.chopCorner) on SteerTest's beach, solved for a wind sea (Hs 0.5 m,
 *  Tp 3.5 s, 40 deg off the shore normal). Checks: weight 0 in water deeper than half the deep wavelength, 1 in the shallows; the waves'
 *  travel turns toward the shore normal; heights grow as they shoal and are capped at the breaking limit. Writes the texture rows and 256
 *  corners for tools/chop_parity.js. Saved tool. */
public class ChopTest { static int pass, fail; static void check(String w, boolean ok, String got) { if (ok) pass++; else fail++; System.out.printf("%s %-60s %s%n", ok ? "PASS" : "FAIL", w, got); }
  public static void main(String[] a) throws Exception {
    double hs = 0.5, tp = 3.5, th0 = Math.toRadians(40), heading = Math.PI / 2 - th0, L0 = 9.81 * tp * tp / (2 * Math.PI); int N = 128;
    Surf.Field f = SteerTest.run((i, j) -> Math.min(15, 0.15 * (120 - j)), hs, tp, heading); System.out.printf("L0 %.1f m; %s%n", L0, f.describe());
    double[] c = new double[Surf.K]; for (int q = 0; q < Surf.K; q++) c[q] = Surf.clock(f.om[q], 500);
    double wDeep = f.chopCorner(64, 30, c, L0)[3], wShallow = f.chopCorner(64, 110, c, L0)[3];
    check("weight 0 in deep water, 1 in the shallows", wDeep < 0.01 && wShallow > 0.95, String.format("15 m: %.2f, 1.5 m: %.2f (L0/2 = %.1f m)", wDeep, wShallow, L0 / 2));
    double prev = 99; boolean turning = true; StringBuilder sb = new StringBuilder(); double hPrev = 0; boolean grows = true;
    for (int jj : new int[]{100, 106, 112, 116}) { double sx = 0, sz = 0, e2 = 0; int n = 0;
      for (int s = 0; s < 40; s++) { double t = 500 + s * 0.11; double[] c1 = new double[Surf.K], c2 = new double[Surf.K]; for (int q = 0; q < Surf.K; q++) { c1[q] = Surf.clock(f.om[q], t); c2[q] = Surf.clock(f.om[q], t + 0.02); }
        for (int ii = 40; ii < 90; ii += 2) { double[] p1 = f.chopCorner(ii, jj, c1, L0), p2 = f.chopCorner(ii, jj, c2, L0); double dt = (p2[0] - p1[0]) / 0.02; sx -= p1[1] * dt; sz -= p1[2] * dt; e2 += p1[0] * p1[0]; n++; } }
      double ang = Math.toDegrees(Math.atan2(sx, sz)), hrms = Math.sqrt(e2 / n) * 2 * Math.sqrt(2), dep = 0.15 * (120 - jj); sb.append(String.format(" %.1f m: %.0f deg, H %.2f m;", dep, ang, hrms));
      if (Math.abs(ang) > prev + 2) turning = false; prev = Math.abs(ang); if (dep > 1.5 && hrms < hPrev * 0.9) grows = false; hPrev = hrms; }
    check("travel turns toward the shore as it shoals", turning && prev < 25, sb.toString());
    check("heights capped by the depth near the shore (0.6 h)", hPrev <= 0.6 * 0.6 + 0.05, String.format("H %.2f m in 0.6 m", hPrev));
    if (a.length > 0) { StringBuilder b = new StringBuilder("{\"tex\":[");
      for (int k = 0; k < N * N; k++) b.append(f.seaCol[k] ? "0,0,0,1," : "0,0,0,-1,");
      for (int k = 0; k < N * N; k++) b.append("0,").append(f.depth[k]).append(",0,0,");
      for (int r = 2; r < 7; r++) for (int k = 0; k < N * N; k++) b.append("0,0,0,0,");
      for (int k = 0; k < N * N; k++) { boolean s = f.seaCol[k]; for (int q = 0; q < 4; q++) b.append(s ? f.phi[q][k] : 0).append(','); }
      for (int k = 0; k < N * N; k++) { boolean s = f.seaCol[k] && !Float.isNaN(f.steerK[k]); for (int q = 0; q < 4; q++) b.append(s ? f.hc[q][k] : -1).append(','); }
      for (int k = 0; k < N * N; k++) { boolean s = f.seaCol[k]; double[] g = s ? f.grad(k % N, k / N) : new double[2]; b.append(s ? f.shR[k] : 0).append(',').append(s ? f.shP[k] : 0).append(',').append((float) g[0]).append(',').append((float) g[1]).append(k == N * N - 1 ? "" : ","); }
      b.append("],\"clk\":["); for (int q = 0; q < 4; q++) b.append((float) c[q]).append(q < 3 ? "," : ""); b.append("],\"k\":["); for (int q = 0; q < 4; q++) b.append((float) (f.om[q] * f.om[q] / f.g)).append(q < 3 ? "," : "");
      b.append(String.format("],\"L0\":%.6f,\"pts\":[", L0)); java.util.Random r = new java.util.Random(11);
      for (int q = 0; q < 256; q++) { int i = r.nextInt(N + 1), j = 70 + r.nextInt(N + 1 - 70); double[] o = f.chopCorner(i, j, c, L0); b.append(String.format("[%d,%d,%.6f,%.6f,%.6f,%.6f]", i, j, o[0], o[1], o[2], o[3])).append(q == 255 ? "" : ","); }
      b.append("]}"); java.nio.file.Files.writeString(java.nio.file.Path.of(a[0]), b.toString()); }
    System.out.printf("ChopTest: %d pass, %d fail%n", pass, fail); if (fail > 0) System.exit(1); } }
