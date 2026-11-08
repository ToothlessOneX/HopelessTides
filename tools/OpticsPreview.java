import com.hopeless.tides.core.*;
import com.hopeless.infinity.planet.*;
import java.awt.*; import java.awt.image.BufferedImage; import javax.imageio.ImageIO; import java.io.File;
/** OpticsPreview seed x z out.png: the water types and body colours (core/WaterOptics) over Infinity's real terrain round (x, z), as
 *  TidesOptics classifies them (sea from the sea level, temperature from the planet's climate, depth from the bed), and the nine types'
 *  colours with their published and played sights. Saved tool. */
public class OpticsPreview {
    public static void main(String[] a) throws Exception {
        long seed = Long.parseLong(a[0]); int cx = Integer.parseInt(a[1]), cz = Integer.parseInt(a[2]); PlanetWorld w = new PlanetWorld(seed);
        int N = 256, S = 3, W = N * S; BufferedImage img = new BufferedImage(W + 420, W + 40, BufferedImage.TYPE_INT_RGB); Graphics2D d = img.createGraphics();
        d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON); d.setColor(new Color(18, 20, 26)); d.fillRect(0, 0, img.getWidth(), img.getHeight());
        int[] count = new int[9];
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) { Column c = Column.of(w, 0, cx - N / 2 + i, cz - N / 2 + j); int rgb;
            if (c.surfaceY < Terrain.SEA) { double depth = Terrain.SEA + 0.889 - (c.surfaceY + 1); WaterOptics.Type t = WaterOptics.classify(false, true, false, c.tempC, 0); count[t.ordinal()]++;
                double[] lk = WaterOptics.look(t, depth); double sh = 0.55 + 0.45 * Math.exp(-depth / 12); rgb = srgb(lk[0] * sh, lk[1] * sh, lk[2] * sh); }
            else rgb = new Color(205, 190, 140).getRGB();
            d.setColor(new Color(rgb)); d.fillRect(i * S, j * S, S, S); }
        d.setColor(Color.WHITE); d.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13)); d.drawString("Seed 1 spawn shore: the water's type and body colour (256 x 256 blocks)", 8, W + 26);
        int y = 20; d.drawString("The nine types (Forel-Ule hue, Novoa 2013)", W + 16, y); y += 18; d.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        for (WaterOptics.Type t : WaterOptics.Type.values()) { double[] lk = WaterOptics.look(t, 1000); d.setColor(new Color(srgb(lk[0], lk[1], lk[2]))); d.fillRect(W + 16, y, 40, 30);
            d.setColor(Color.WHITE); d.drawString(String.format("%s  FU %d  Kd %.3f/m", t, WaterOptics.FU[t.ordinal()], WaterOptics.KD[t.ordinal()]), W + 64, y + 12);
            d.drawString(String.format("sight: published %.1f m, played %.0f m%s", WaterOptics.naturalSight(t), WaterOptics.GAME_SIGHT, count[t.ordinal()] > 0 ? "  (" + count[t.ordinal()] + " columns here)" : ""), W + 64, y + 27); y += 42; }
        d.setColor(new Color(190, 190, 200)); d.drawString("Sea: cool open sea (water under 20 C) shading to coastal green from 60 m to 20 m deep.", W + 16, y + 10);
        d.dispose(); ImageIO.write(img, "png", new File(a[3])); System.out.println(java.util.Arrays.toString(count));
    }
    static int srgb(double r, double g, double b) { return (enc(r) << 16) | (enc(g) << 8) | enc(b); }
    static int enc(double c) { c = Math.max(0, Math.min(1, c)); return (int) Math.round(255 * (c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055)); }
}
