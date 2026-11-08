import com.hopeless.tides.core.*;
import java.awt.*; import java.awt.image.BufferedImage; import javax.imageio.ImageIO; import java.io.File;
/** ClimatePreview g out.png: Tides 0.16.0's Fourier sea (FftSea.build, what the GPU draws) for fair / rain / storm wind on the open sea
 *  with the measured swell, a 10 km lake and a 2 km lake: shaded surface and a 160 m profile with Hs. Saved tool. */
public class ClimatePreview {
    public static void main(String[] a) throws Exception {
        double g = Double.parseDouble(a[0]); int P = PlanetWaves.P; double[] st = {0.0, 0.5, 1.0}; String[] sn = {"fair (5 m/s)", "rain (7.5 m/s)", "storm (10 m/s)"};
        double[][] body = {{100000, 0.93 * 9.81 / g}, {10000, 0}, {2000, 0}}; String[] bn = {"open sea + swell", "10 km lake", "2 km lake"};
        int W = 3 * (P + 20) + 20, H = 3 * (P + 140) + 40; BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB); Graphics2D d = img.createGraphics();
        d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON); d.setColor(new Color(18, 20, 26)); d.fillRect(0, 0, W, H);
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
            FftSea.Field f = FftSea.build(300, st[r], 0.6, g, body[c][0], body[c][1], 9.09 * 9.81 / g, 1.0, null); int ox = 20 + c * (P + 20), oy = 40 + r * (P + 140);
            double sc = WaveModel.heightOpen(st[r]) / f.hsFull; double[] s = PlanetWaves.panel(img, f, sc, ox, oy);
            int py = oy + P + 60; d.setColor(new Color(40, 44, 54)); d.fillRect(ox, py - 45, P, 90); d.setColor(new Color(120, 180, 230)); double[] o = new double[8]; int lx = -1, ly = 0;
            for (int i = 0; i < P; i++) { FftSea.fetch(f, i * PlanetWaves.SPAN / P, 80, o); int y = py - (int) Math.round(o[0] * sc * 15); if (lx >= 0) d.drawLine(ox + lx, ly, ox + i, y); lx = i; ly = y; }
            d.setColor(Color.WHITE); d.drawLine(ox + P - 6, py - 15, ox + P - 6, py + 15); d.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13)); d.drawString(bn[c] + " | " + sn[r], ox, oy - 6);
            d.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12)); d.drawString(String.format("Hs %.2f m (wind %.2f m + swell %.2f m); 2 m bar", s[0], f.windHs, f.swellHs), ox, py + 60);
        }
        d.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12)); d.setColor(new Color(200, 200, 210)); d.drawString(String.format("Tides 0.16.0 FftSea fields, g %.2f: JONSWAP by fetch; swell from NOAA buoys (calm-hour median, Diamond Shoals). 160 m squares, relief x4.", g), 20, H - 12);
        d.dispose(); ImageIO.write(img, "png", new File(a[1]));
    }
}
