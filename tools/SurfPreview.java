import com.hopeless.tides.core.*;
import com.hopeless.infinity.planet.*;
import java.awt.*; import java.awt.image.BufferedImage; import javax.imageio.ImageIO; import java.io.File;
/** SurfPreview seed x z seaState outDir: frames of the surf model (core/Surf) on Infinity's real shore round (x, z): top view (bores lit by
 *  their slope, foam where a broken wave's front is, the swash sheet, foam lace and wet sand on the beach) and a side cut across the
 *  shore. The same numbers the game reads (Field.corner, Field.swash). Saved tool. */
public class SurfPreview {
    public static void main(String[] a) throws Exception {
        long seed = Long.parseLong(a[0]); int cx = Integer.parseInt(a[1]), cz = Integer.parseInt(a[2]); double s = Double.parseDouble(a[3]); File out = new File(a[4]); out.mkdirs();
        int N = 128; double g = 11.53, u = FftSea.u10(s), hs = WaveModel.heightOpen(s) * FftSea.heightScale(u, g); double[] p = FftSea.parameters(u, FftSea.FETCH, g); double tp = 2 * Math.PI / p[1];
        double heading = Double.parseDouble(a.length > 5 ? a[5] : "-1.5708");
        Surf.Input in = SurfTest.input(seed, cx - N / 2, cz - N / 2, N, hs, tp, heading, g); Surf.Field f = Surf.compute(in);
        System.out.println(f.describe());
        int S = 5, W = N * S; int frames = 50; double dt = 0.12;
        int cutJ = N / 2;   // the side cut along this row
        for (int fr = 0; fr < frames; fr++) {
            double t = 1000 + fr * dt, clk = Surf.clock(f.om[Surf.DOM], t);
            BufferedImage img = new BufferedImage(W, W + 220, BufferedImage.TYPE_INT_RGB); Graphics2D d = img.createGraphics();
            d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            double[] cutEta = new double[W];
            for (int py = 0; py < W; py++) for (int px = 0; px < W; px++) {
                double x = px / (double) S, y = py / (double) S; int i = (int) x, j = (int) y, c = i + N * j; int rgb;
                if (f.seaCol[c]) {
                    double fx = x - i, fy = y - j; double[] e00 = f.corner(i, j, clk, x, y), e10 = f.corner(i + 1, j, clk, x, y), e01 = f.corner(i, j + 1, clk, x, y), e11 = f.corner(i + 1, j + 1, clk, x, y);
                    double[] e = lerp4(e00, e10, e01, e11, fx, fy);
                    double lit = 0.6 + 2.5 * (-e[2] * 0.6 + e[3] * 0.4); double dep = Math.min(1, f.depth[c] / 8.0);
                    int r = (int) (40 + 30 * (1 - dep)), gg = (int) (110 + 50 * (1 - dep)), b = (int) (170 + 30 * (1 - dep));
                    r = (int) (r * lit); gg = (int) (gg * lit); b = (int) (b * lit);
                    double fo = Math.min(1, e[4] * 1.4); r = (int) (r + (240 - r) * fo); gg = (int) (gg + (245 - gg) * fo); b = (int) (b + (248 - b) * fo);
                    rgb = clamp(r) << 16 | clamp(gg) << 8 | clamp(b); if (j == cutJ) cutEta[px] = e[0];
                } else {
                    int r = 214, gg = 199, b = 145; double bed = in.bed[c]; double shade = 1 - 0.04 * Math.max(0, Math.min(5, bed - in.sea));
                    if (!Float.isNaN(f.shoreX[c])) {
                        double xs = f.shoreX[c] + (x - i - 0.5) * f.upX[c] + (y - j - 0.5) * f.upY[c];
                        double[] sw = f.swash(c, Math.max(0, xs), clk, x, y);
                        double wet = Math.max(sw[2] * 0.55, sw[0]); r = (int) (r * (1 - 0.45 * wet)); gg = (int) (gg * (1 - 0.45 * wet)); b = (int) (b * (1 - 0.40 * wet));
                        if (sw[0] > 0) { r = (int) (r * 0.75 + 60 * 0.25); gg = (int) (gg * 0.75 + 120 * 0.25); b = (int) (b * 0.75 + 170 * 0.25); }
                        if (sw[0] > 0 && sw[1] > 0) { double fo = Math.min(1, sw[1] / 2.0) * 0.8; r = (int) (r + (240 - r) * fo); gg = (int) (gg + (243 - gg) * fo); b = (int) (b + (245 - b) * fo); }
                    }
                    rgb = clamp((int) (r * shade)) << 16 | clamp((int) (gg * shade)) << 8 | clamp((int) (b * shade));
                }
                img.setRGB(px, py, rgb);
            }
            d.setColor(new Color(255, 255, 255, 90)); d.drawLine(0, cutJ * S + S / 2, W, cutJ * S + S / 2);
            // the side cut: bed, still water, surface (x 4 vertical)
            int oy = W + 120; d.setColor(new Color(20, 22, 28)); d.fillRect(0, W, W, 220);
            for (int px = 0; px < W; px++) { int i = px / S, c = i + N * cutJ; double bed = in.bed[c] - in.sea; int yb = (int) (oy - bed * 16);
                d.setColor(new Color(150, 130, 90)); d.drawLine(px, yb, px, W + 220);
                if (f.seaCol[c]) { int ys = (int) (oy - cutEta[px] * 16); d.setColor(new Color(70, 140, 210)); d.drawLine(px, ys, px, yb); } }
            d.setColor(Color.WHITE); d.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            d.drawString(String.format("Surf (Hydro 0.11 port) on seed 1's spawn shore: sea state %.1f, Hs %.2f m, Tp %.1f s, g %.2f. t %.2f s", s, hs, tp, g, t - 1000), 10, W + 20);
            d.drawString("Side cut along the white line, height x 16. " + String.format("%d in the surf, %d swash columns, run-up R2 %.2f m", f.breakingColumns, f.swashColumns, f.meanR2), 10, W + 38);
            d.dispose(); ImageIO.write(img, "png", new File(out, String.format("s_%03d.png", fr)));
        }
    }
    static double[] lerp4(double[] a, double[] b, double[] c, double[] d, double fx, double fy) {
        double[] z = new double[6]; double[][] v = {a, b, c, d}; double[] w = {(1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy}; double ws = 0;
        for (int k = 0; k < 4; k++) if (v[k] != null) { ws += w[k]; for (int q = 0; q < 6; q++) z[q] += v[k][q] * w[k]; }
        if (ws > 0) for (int q = 0; q < 6; q++) z[q] /= ws; return z;
    }
    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
