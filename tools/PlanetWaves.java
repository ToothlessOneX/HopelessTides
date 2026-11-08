import com.hopeless.tides.core.*;
import java.awt.*; import java.awt.image.BufferedImage; import javax.imageio.ImageIO; import java.io.File;
/**
 * PlanetWaves g outDir: the Fourier sea Tides builds (FftSea.build, the same field the GPU draws), at Earth's gravity and at a planet's,
 * side by side: shaded surface (sun from the south-west, foam where the surface folds: J under breakJ, as the shader does) and a profile
 * line with a 1 m scale, for rain and storm sea states; plus frames of the moving storm sea for a GIF. Heights use every reader's scale
 * (heightOpen / hsFull). Saved tool.
 */
public class PlanetWaves {
    static final int P = 300; static final double SPAN = 160;   // panel pixels, metres shown
    public static void main(String[] a) throws Exception {
        double g = Double.parseDouble(a[0]); File out = new File(a[1]); out.mkdirs();
        double[] states = {0.5, 1.0}; String[] sn = {"rain (sea state 0.5)", "storm (sea state 1.0)"}; double[] gs = {9.81, g}; String[] gn = {"Earth  g 9.81", String.format("start planet  g %.2f", g)};
        int W = 2 * P + 60, H = 2 * (P + 200) + 70; BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB); Graphics2D d = img.createGraphics();
        d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        d.setColor(new Color(18, 20, 26)); d.fillRect(0, 0, W, H);
        for (int r = 0; r < 2; r++) for (int c = 0; c < 2; c++) {
            FftSea.Field f = FftSea.build(300.0, states[r], 0.6, gs[c], null); int ox = 20 + c * (P + 20), oy = 50 + r * (P + 200);
            double sc = WaveModel.heightOpen(states[r]) / f.hsFull; double[] st = panel(img, f, sc, ox, oy);
            // profile along the panel's middle row
            int py = oy + P + 70; d.setColor(new Color(40, 44, 54)); d.fillRect(ox, py - 50, P, 100);
            d.setColor(new Color(120, 180, 230)); double[] o = new double[8]; int lx = -1, ly = 0;
            for (int i = 0; i < P; i++) { FftSea.fetch(f, i * SPAN / P, SPAN / 2, o); int y = py - (int) Math.round(o[0] * sc * 25); if (lx >= 0) d.drawLine(ox + lx, ly, ox + i, y); lx = i; ly = y; }
            d.setColor(Color.WHITE); d.drawLine(ox + P - 8, py - 25, ox + P - 8, py + 25); d.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11)); d.drawString("2 m", ox + P - 32, py - 28);
            d.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14)); d.drawString(gn[c] + " | " + sn[r], ox, oy - 8);
            d.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12)); d.drawString(String.format("Hs %.2f m, highest crest %.2f m, foam %.1f%%", st[0], st[1], st[2] * 100), ox, py + 70);
        }
        d.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12)); d.setColor(new Color(200, 200, 210));
        d.drawString("Tides 0.13.0 FftSea fields (what the GPU draws). 160 m square, same wind and seed. Shading relief x4.", 20, H - 20);
        d.dispose(); ImageIO.write(img, "png", new File(out, "tides_planet_g.png"));
        // frames: storm sea, both gravities, 3 s at 10 fps
        for (int k = 0; k < 30; k++) { BufferedImage fr = new BufferedImage(2 * P + 60, P + 50, BufferedImage.TYPE_INT_RGB); Graphics2D q = fr.createGraphics(); q.setColor(new Color(18, 20, 26)); q.fillRect(0, 0, fr.getWidth(), fr.getHeight());
            q.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (int c = 0; c < 2; c++) { FftSea.Field f = FftSea.build(300.0 + k * 0.1, 1.0, 0.6, gs[c], null); panel(fr, f, WaveModel.heightOpen(1.0) / f.hsFull, 20 + c * (P + 20), 40);
                q.setColor(Color.WHITE); q.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14)); q.drawString(gn[c] + " | storm", 20 + c * (P + 20), 30); }
            q.dispose(); ImageIO.write(fr, "png", new File(out, String.format("frame_%02d.png", k))); }
        System.out.println("rendered");
    }
    /** Shaded surface into img at (ox, oy); returns {Hs, highest crest, foam share} over the panel. */
    static double[] panel(BufferedImage img, FftSea.Field f, double sc, int ox, int oy) {
        double[] o = new double[8], ox1 = new double[8], oz1 = new double[8]; double e = SPAN / P, v = 0, top = 0; int foam = 0;
        double lx = -0.55, ly = 0.6, lz = 0.58; double ln = Math.sqrt(lx * lx + ly * ly + lz * lz); lx /= ln; ly /= ln; lz /= ln;
        for (int j = 0; j < P; j++) for (int i = 0; i < P; i++) {
            double x = i * e, z = j * e; FftSea.fetch(f, x, z, o); FftSea.fetch(f, x + e, z, ox1); FftSea.fetch(f, x, z + e, oz1);
            double h = o[0] * sc, sx = (ox1[0] - o[0]) * sc / e, sz = (oz1[0] - o[0]) * sc / e; v += h * h; top = Math.max(top, h);
            double nx = -4 * sx, ny = 1, nz = -4 * sz, nn = Math.sqrt(nx * nx + ny * ny + nz * nz); double lit = Math.max(0, (nx * lx + ny * ly + nz * lz) / nn);
            double J = (1 + sc * o[4]) * (1 + sc * o[5]) - sc * o[6] * sc * o[6]; boolean fo = J < f.breakJ; if (fo) foam++;
            double sh = Math.pow(lit, 2.2); int r = (int) (20 + 40 * sh), gg = (int) (60 + 90 * sh), b = (int) (100 + 130 * sh);
            if (fo) { r = 235; gg = 240; b = 245; }
            img.setRGB(ox + i, oy + j, (Math.min(255, r) << 16) | (Math.min(255, gg) << 8) | Math.min(255, b));
        }
        return new double[]{4 * Math.sqrt(v / (P * P)), top, foam / (double) (P * P)};
    }
}
