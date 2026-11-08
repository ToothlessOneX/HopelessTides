import com.hopeless.tides.core.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** SteerPreview seed x z outDir [frames]: the 0.16.1 steering on real Hopeless Infinity terrain round (x, z), from the mod's code: the surf
 *  solved (core/Surf, as TidesSurf does; tools/SurfTest input), the open sea built (FftSea.build with the swell, as FftDrive), and every
 *  block corner drawn from FftCorner.fill (0.16.0, left: one direction everywhere) and FftCorner.fillSteered (0.16.1, right: turned and
 *  calmed by Surf.Field.steer) -- the numbers the shader draws (GPU parity: tools/steer_parity.js). Shaded by height and slope, land in
 *  green. The swell comes from the open ocean (core/OceanSwell over Infinity's map, as in the game). Writes steer_NN.png frames (0.5 s apart) for a GIF. Saved tool. */
public class SteerPreview {
    public static void main(String[] a) throws Exception {
        long seed = Long.parseLong(a[0]); int cx = Integer.parseInt(a[1]), cz = Integer.parseInt(a[2]); File out = new File(a[3]); out.mkdirs(); int frames = a.length > 4 ? Integer.parseInt(a[4]) : 16;
        int N = 128; double g = 11.53, s = 0.6, wind = Math.toRadians(a.length > 5 ? Double.parseDouble(a[5]) : 20), u = FftSea.u10(s);
        double swH = TidesSwell.HS * 9.81 / g, swT = TidesSwell.TP * 9.81 / g;   // TidesSurf: both x 9.81 / g on a planet
        com.hopeless.infinity.planet.PlanetWorld pw = new com.hopeless.infinity.planet.PlanetWorld(seed);
        double[] os = OceanSwell.from((x, z) -> { var c = com.hopeless.infinity.planet.Column.of(pw, 0, (int) Math.floor(x), (int) Math.floor(z)); return c.voidCol ? -1 : c.surfaceY < com.hopeless.infinity.planet.Terrain.SEA ? 1 : 0; }, cx, cz, wind + Math.toRadians(25));
        double swDir = os == null ? 0 : os[0]; if (os == null) swH = 0; System.out.println(os == null ? "swell: no open ocean" : String.format("swell from the open ocean at %.0f deg (%d directions open), toward %.0f deg", Math.toDegrees(os[2]), (int) os[1], Math.toDegrees(os[0])));
        // the fetch as TidesSurf measures it (Saville 1962: 15 radials at 6 deg within 42 deg of upwind, F = sum F_i cos^2 a / sum cos a, to 8 km)
        double num = 0, den = 0; for (int r = 0; r < 15; r++) { double an = wind + Math.PI + Math.toRadians(-42 + 6 * r), d = 8; for (; d <= 8000; d += 8) { var c = com.hopeless.infinity.planet.Column.of(pw, 0, (int) Math.floor(cx + Math.cos(an) * d), (int) Math.floor(cz + Math.sin(an) * d)); if (c.surfaceY >= com.hopeless.infinity.planet.Terrain.SEA) break; }
            double cw = Math.cos(Math.toRadians(-42 + 6 * r)); num += Math.min(d, 8000) * cw * cw; den += cw; }
        double fetch = FftSea.quantFetch(num / den >= 8000 * 0.98 ? FftSea.FETCH : num / den); System.out.printf("fetch %.0f m%n", fetch);
        double hw = FftSea.hsFullCached(u, g, fetch), hs = Math.sqrt(hw * hw + swH * swH); double[] p = FftSea.parameters(u, fetch, g); double tw = 2 * Math.PI / p[1];
        boolean swellLeads = swH > hw; double tp = swellLeads ? swT : tw, heading = swellLeads ? swDir : wind;
        Surf.Input in = SurfTest.input(seed, cx - N / 2, cz - N / 2, N, hs, tp, heading, g); in.seedX0 = Math.floorMod(cx - N / 2, Surf.FRAME); in.seedY0 = Math.floorMod(cz - N / 2, Surf.FRAME);
        Surf.Field sf = Surf.compute(in); System.out.println(sf.describe());
        int ox = cx - N / 2, oz = cz - N / 2; double px = Math.floorDiv(ox, 512) * 512.0, pz = Math.floorDiv(oz, 512) * 512.0;
        int Z = 4, W = 2 * N * Z + 30, H = N * Z + 70; FftSea.Field f = null; double[] o = new double[8], tmp = new double[8], v = new double[Surface.CSIZE + 1], v2 = new double[Surface.CSIZE + 1];
        for (int fr = 0; fr < frames; fr++) {
            double t = 1000 + fr * 0.5; f = FftSea.build(t, s, wind, g, fetch, swH, swT, swDir, f);
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB); Graphics2D gr = img.createGraphics(); gr.setColor(new Color(14, 18, 26)); gr.fillRect(0, 0, W, H);
            gr.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (int side = 0; side < 2; side++) {
                double[] hh = new double[(N + 1) * (N + 1)];
                for (int j = 0; j <= N; j++) for (int i = 0; i <= N; i++) { int c = Math.min(N - 1, i) + N * Math.min(N - 1, j); if (!sf.seaCol[c]) { hh[i + (N + 1) * j] = Double.NaN; continue; }
                    int dep = (int) Math.min(15, Math.max(1, sf.depth[c]));
                    double[] st = side == 1 ? sf.steer(i, j) : null;
                    if (st == null || st[2] <= 0) FftCorner.fill(ox + i, oz + j, 15, dep, s, wind, 0, null, f, 1, 0, v, 0, o, tmp);
                    else FftCorner.fillSteered(ox + i, oz + j, 15, dep, s, wind, 0, null, f, 1, 0, st[0] * st[2], 1 + (st[1] - 1) * st[2], px, pz, v, 0, o, tmp, v2);
                    hh[i + (N + 1) * j] = v[Surface.CH]; }
                for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) { int c = i + N * j; Color col;
                    if (!sf.seaCol[c]) col = new Color(70, 120, 55);
                    else { double h = hh[i + (N + 1) * j], hx = hh[i + 1 + (N + 1) * j] - h, hz = hh[i + (N + 1) * (j + 1)] - h; if (Double.isNaN(hx)) hx = 0; if (Double.isNaN(hz)) hz = 0;
                        double lit = 0.55 + 1.6 * (-hx * 0.6 - hz * 0.8) + 0.25 * h, dpt = Math.min(1, sf.depth[c] / 20.0);
                        col = new Color(clamp((40 + 30 * (1 - dpt)) * lit), clamp((95 + 60 * (1 - dpt)) * lit), clamp((140 + 40 * (1 - dpt)) * lit)); }
                    gr.setColor(col); gr.fillRect(10 + side * (N * Z + 10) + i * Z, 60 + j * Z, Z, Z); } }
            gr.setColor(Color.WHITE); gr.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
            gr.drawString("0.16.0: the open sea runs one way everywhere", 10, 22); gr.drawString("0.16.1: turned toward the shore, calm behind land", N * Z + 20, 22);
            gr.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12)); gr.drawString(String.format("seed %d, %d %d, %d x %d blocks, t = %.1f s; wind sea + swell (Hs %.2f m, peak heading %.0f deg); green = land", seed, cx, cz, N, N, t - 1000, hs, Math.toDegrees(heading)), 10, 44);
            gr.dispose(); ImageIO.write(img, "png", new File(out, String.format("steer_%02d.png", fr)));
        }
        System.out.println("frames written: " + frames);
    }
    static int clamp(double v) { return (int) Math.max(0, Math.min(255, v)); }
    /** TidesSurf's measured swell (NOAA buoys, Diamond Shoals calm median), copied here so the preview needs no game classes. */
    static final class TidesSwell { static final double HS = 0.93, TP = 9.09; }
}
