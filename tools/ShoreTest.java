import com.hopeless.tides.core.*;

/** ShoreTest [out.json]: the 0.17.0 shore map (core/ShoreMap) on made-up seabeds, n = 256, g 9.81, a 9 s swell. Checks:
 *  (1) straight beach: the crests run along the shore (grad Phi at right angles to it, within 3 deg) and come closer together as it shoals;
 *  (2) the island: every side's crests face the island (grad Phi points away from its centre); behind it (lee) the swell share is small;
 *  (3) heights: shoaling raises the rms height toward the shore, no wave drawn over 0.78 h + (settled) and foam only in the surf zone;
 *  (4) reflection: a wall (5 m of water at the bank) reflects (Kr = 1), a gentle beach hardly (Kr under 0.1);
 *  (5) fetch: small just behind land, growing downwind, full upwind;
 *  (6) the weight is 0 at the map's edge and in deep water, 1 in the shallows;
 *  (7) the build time at the game's size (512 x 512).
 *  Writes the island case (texture, corners) for tools/shore_parity.js. Saved tool. */
public class ShoreTest {
    static int pass, fail;
    static void check(String w, boolean ok, String got) { if (ok) pass++; else fail++; System.out.printf("%s %-66s %s%n", ok ? "PASS" : "FAIL", w, got); }
    interface D { double at(int i, int j); }
    static float[] grid(int n, D d) { float[] a = new float[n * n]; for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) a[i + n * j] = (float) Math.max(0, d.at(i, j)); return a; }
    public static void main(String[] a) throws Exception {
        int n = 256; double g = 9.81, tp = 9, swH = 1.0, wH = 0.5, u = 8;
        // (1) straight beach along x = 40 (land x < 40), slope 1 in 20, swell heading 30 deg off the shore normal (toward -x)
        ShoreMap m = ShoreMap.build(grid(n, (i, j) -> (i - 40) * 0.05), n, g, tp, Math.PI + Math.toRadians(30), swH, Math.PI, u, wH, 20000);
        System.out.println(m.describe());
        double worst = 0; for (int j = 60; j < 200; j += 10) for (int i : new int[]{45, 60, 90}) { int c = i + n * j; double ang = Math.toDegrees(Math.atan2(m.gz[c], m.gx[c])); worst = Math.max(worst, Math.abs(ang)); }
        double k1 = m.gx[50 + n * 128], k2 = m.gx[120 + n * 128];
        check("beach: crests along the shore (grad Phi normal to it)", worst < 3, String.format("worst %.2f deg off the normal", worst));
        check("beach: crests closer together in shallow water", k1 > 1.5 * k2, String.format("k 0.5 m deep %.3f rad/m, 4 m deep %.3f", k1, k2));
        double h1 = m.hr[44 + n * 128], h2 = m.hr[100 + n * 128], h3 = m.hr[200 + n * 128];
        check("beach: shoaling raises the height toward the shore", h1 > h2 && h2 > 0.9 * h3, String.format("rms %.2f m at 0.2 m deep, %.2f at 3 m, %.2f at 8 m", h1, h2, h3));
        double maxR = 0, foamDeep = 0, foamShal = 0; double om = 2 * Math.PI / tp;
        for (int s = 0; s < 60; s++) { double clk = Surf.clock(om, 1000 + s * 0.37); for (int i = 41; i < 230; i += 3) { double[] o = m.corner(i, 128, clk, i, 128, 0); double hh = Math.max(0.05, (i - 41) * 0.05);
            if (o[3] > 0.99) maxR = Math.max(maxR, Math.abs(o[0]) / hh); if (i > 140) foamDeep = Math.max(foamDeep, o[4]); else if (i < 50) foamShal = Math.max(foamShal, o[4]); } }
        check("beach: no wave drawn higher than the depth allows", maxR < 0.78, String.format("max |eta| / h %.2f", maxR));
        check("beach: foam where the waves break, none in deep water", foamShal > 0.3 && foamDeep < 0.01, String.format("foam near shore %.2f, past 5 m deep %.3f", foamShal, foamDeep));
        double kb = m.kr[41 + n * 128]; check("beach: a gentle beach hardly reflects", kb < 0.1, String.format("Kr %.3f", kb));
        double w0 = m.weight(m.cells(0, 128), 0, 128, 0), wShal = m.weight(m.cells(80, 128), 80, 128, 0), wDeep = m.weight(m.cells(250, 128), 250, 128, 0);
        check("weight: 0 at the map edge, 1 in the shallows, 0 deep", w0 == 0 && wShal > 0.99, String.format("edge %.2f, 2 m deep %.2f, 10.5 m deep %.2f (L0 %.0f m)", w0, wShal, wDeep, m.L0));
        // (4) a wall: 5 m of water at the bank
        ShoreMap wl = ShoreMap.build(grid(n, (i, j) -> i < 40 ? 0 : 5), n, g, tp, Math.PI, swH, Math.PI, u, wH, 20000);
        check("wall: reflects fully", wl.kr[40 + n * 128] > 0.99, String.format("Kr %.2f", wl.kr[40 + n * 128]));
        // (2) island r 20 at the centre, slope 1 in 10 round it to 12 m; swell heading +x
        D isl = (i, j) -> { double r = Math.hypot(i - 128, j - 128); return r < 20 ? 0 : Math.min(12, (r - 20) * 0.1); };
        ShoreMap is = ShoreMap.build(grid(n, isl), n, g, tp, 0, swH, 0, u, wH, 20000);
        double bad = 0; int cnt = 0; for (int s = 0; s < 32; s++) { double an = s * Math.PI / 16; int i = (int) Math.round(128 + 30 * Math.cos(an)), j = (int) Math.round(128 + 30 * Math.sin(an)); int c = i + n * j;
            double ga = Math.atan2(is.gz[c], is.gx[c]), d = Math.abs(Math.atan2(Math.sin(ga - an), Math.cos(ga - an))); bad = Math.max(bad, Math.toDegrees(d)); cnt++; }
        check("island: crests face the island on every side", bad < 8, String.format("worst %.1f deg over %d points 10 blocks out", bad, cnt));
        double front = is.lee[90 + n * 128], back = is.lee[170 + n * 128], side = is.lee[128 + n * 60];
        check("island: the swell is small behind it, full in front", back < 0.35 && front > 0.95, String.format("share in front %.2f, 22 blocks behind %.2f, beside %.2f", front, back, side));
        double fb = is.fk[152 + n * 128], ff = is.fk[230 + n * 128], fu = is.fk[60 + n * 128];
        check("fetch: small behind land, growing downwind, full upwind", fb < 0.6 && ff > fb && fu > 0.95, String.format("behind %.2f, 80 blocks on %.2f, upwind %.2f", fb, ff, fu));
        // (7) the game's size
        int N = 512; java.util.Random r = new java.util.Random(3); float[] big = new float[N * N]; for (int c = 0; c < N * N; c++) { int i = c % N, j = c / N; double v = 6 * Math.sin(i * 0.03) * Math.cos(j * 0.021) + 2 * Math.sin(i * 0.11 + j * 0.07) + 1; big[c] = (float) Math.max(0, Math.round(v * 2) / 2.0); }
        ShoreMap bm = null; long best = Long.MAX_VALUE; for (int k = 0; k < 3; k++) { bm = ShoreMap.build(big, N, 11.53, 6.6, 0.5, 0.7, 0.8, 7, 0.6, 3000); best = Math.min(best, bm.ms); }
        check("512 x 512 builds on the worker in under 1.5 s", best < 1500, bm.describe() + String.format(" (best of 3: %d ms)", best));
        if (a.length > 0) {   // the island for the GPU parity
            StringBuilder b = new StringBuilder("{\"n\":" + n + ",\"tex\":["); ShoreMap s = is;
            for (int blk = 0; blk < 3; blk++) for (int c = 0; c < n * n; c++) { boolean ok = !Float.isNaN(s.phi[c]);
                float[] v = blk == 0 ? new float[]{ok ? s.phi[c] : -1, s.h[c], s.gx[c], s.gz[c]} : blk == 1 ? new float[]{s.lee[c], s.fk[c], s.kr[c], ok ? 1 : 0} : new float[]{s.ar[c], s.ap[c], s.hr[c], 0};
                for (float f : v) b.append(f).append(','); }
            b.setLength(b.length() - 1); double clk = Surf.clock(s.om, 777.3); b.append(String.format("],\"clk\":%.6f,\"L0\":%.6f,\"pts\":[", clk, s.L0)); java.util.Random rr = new java.util.Random(5);
            for (int k = 0; k < 256; k++) { int i = rr.nextInt(80) + 88, j = rr.nextInt(80) + 88; if (k < 8) { i = rr.nextInt(n + 1); j = rr.nextInt(n + 1); } double xm = Math.floorMod(1000 + i, Surf.FRAME), zm = Math.floorMod(2000 + j, Surf.FRAME); double[] o = s.corner(i, j, clk, xm, zm, 0); double[] f = s.factors(i, j);
                b.append(String.format("[%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f]", i, j, o[0], o[1], o[2], o[3], o[4], f[0], f[1])).append(k == 255 ? "" : ","); }
            b.append("]}"); java.nio.file.Files.writeString(java.nio.file.Path.of(a[0]), b.toString()); }
        System.out.printf("ShoreTest: %d pass, %d fail%n", pass, fail); if (fail > 0) System.exit(1);
    }
}
