import com.hopeless.tides.core.*;
import java.io.*; import java.util.*; import java.awt.image.BufferedImage; import javax.imageio.ImageIO;
/** WavePreview scene.bin out.png : exposure, depth and the wave surface (calm / rain / storm) over a real Hopeless Deep scene, from the mod's code. */
public class WavePreview {
    public static void main(String[] a) throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(a[0]))); byte[] m = new byte[7]; in.readFully(m);
        int n = in.readInt(), H = in.readInt(), x0 = in.readInt(), y0 = in.readInt(), z0 = in.readInt(), P = in.readInt();
        boolean[] isW = new boolean[P]; for (int i = 0; i < P; i++) { String s = in.readUTF(); isW[i] = s.startsWith("water"); }
        short[] idx = new short[n * n * H]; for (int i = 0; i < idx.length; i++) idx[i] = in.readShort();
        SeaInfo.Probe p = (x, y, z) -> { int i = x - x0, j = z - z0, k = y - y0; if (i < 0 || j < 0 || k < 0 || i >= n || j >= n || k >= H) return false; return isW[idx[(k * n + j) * n + i]]; };
        // the surface level: the most common top-water y
        Map<Integer, Integer> tops = new HashMap<>();
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) for (int k = H - 2; k >= 0; k--) if (isW[idx[(k * n + j) * n + i]] && !isW[idx[((k + 1) * n + j) * n + i]]) { tops.merge(y0 + k, 1, Integer::sum); break; }
        int Y = tops.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        Map<Long, double[]> cache = new HashMap<>(); SeaInfo.Nodes direct = SeaInfo.direct(p);
        SeaInfo.Nodes nodes = (nx, nz, y) -> cache.computeIfAbsent(((long) nx << 32) ^ (nz & 0xffffffffL), k -> direct.at(nx, nz, y));
        int S = 6, W = n * S; BufferedImage img = new BufferedImage(W * 5 + 40, W, BufferedImage.TYPE_INT_RGB);
        double[] states = {WaveModel.seaStateWeather(0, 0), WaveModel.seaStateWeather(1, 0), WaveModel.seaStateWeather(1, 1)};
        double dayTime = 6000, t = WaveModel.daySeconds(dayTime), wind = WaveModel.wind(dayTime);
        double[] maxH = new double[3]; int[] brk = new int[3]; int water = 0;
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
            int x = x0 + i, z = z0 + j; boolean wet = SeaInfo.surface(p, x, Y, z); if (wet) water++;
            int[] ed = SeaInfo.corner(nodes, x, z, Y);
            int[] cols = new int[5];
            cols[0] = wet ? gray(ed[0] / 15.0, 0x30, 0xff) : 0x3a2f20; cols[1] = wet ? gray(ed[1] / 15.0, 0x30, 0xff) : 0x3a2f20;
            for (int q = 0; q < 3; q++) {
                Surface.Point sp = Surface.at(nodes, x + 0.5, z + 0.5, Y, t, states[q], wind, null);
                if (wet) { maxH[q] = Math.max(maxH[q], Math.abs(sp.h)); if (sp.breaking) brk[q]++; }
                double v = 0.5 + sp.h / 2.4; cols[2 + q] = !wet ? 0x3a2f20 : sp.breaking ? 0xf0f0f0 : rgb(0.1 * v, 0.35 * v + 0.1, 0.55 + 0.45 * v);
            }
            for (int c = 0; c < 5; c++) for (int dy = 0; dy < S; dy++) for (int dx = 0; dx < S; dx++) img.setRGB(c * (W + 10) + i * S + dx, j * S + dy, cols[c]);
        }
        ImageIO.write(img, "png", new File(a[1]));
        System.out.printf("surface y %d, water columns %d, wind %.0f deg; peak |h| calm %.2f rain %.2f storm %.2f blocks; breaking columns %d %d %d; nodes %d%n",
            Y, water, Math.toDegrees(wind), maxH[0], maxH[1], maxH[2], brk[0], brk[1], brk[2], cache.size());
    }
    static int gray(double v, int lo, int hi) { int g = (int) (lo + (hi - lo) * Math.max(0, Math.min(1, v))); return g << 16 | g << 8 | g; }
    static int rgb(double r, double g, double b) { return ((int) (255 * Math.max(0, Math.min(1, r))) << 16) | ((int) (255 * Math.max(0, Math.min(1, g))) << 8) | (int) (255 * Math.max(0, Math.min(1, b))); }
}
