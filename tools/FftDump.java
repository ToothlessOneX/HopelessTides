import com.hopeless.tides.core.*;
import java.io.*;
import java.nio.*;
/** FftDump OUTDIR t s wind : writes the Fourier pair for (t, t + SLOT) as the GPU texture (fft.bin: 256 x 1024 RGBA halves, little endian,
 *  even slot rows 0-511, odd 512-1023), the uniforms (uniforms.txt) and the physics' corners (corners.csv: x z exposure depth speed h break)
 *  so tools/fft_parity.py can hold the shader to FftCorner.fill. */
public class FftDump {
    public static void main(String[] a) throws Exception {
        File out = new File(a[0]); out.mkdirs();
        double t = Double.parseDouble(a[1]), s = Double.parseDouble(a[2]), w = Double.parseDouble(a[3]), frac = 0.37;
        long k = (long) Math.floor(t / FftSea.SLOT); double sq = FftSea.quantize(s);
        FftSea.Field p = FftSea.build(k * FftSea.SLOT, sq, w, null), c = FftSea.build((k + 1) * FftSea.SLOT, sq, w, null); p.slot = k; c.slot = k + 1;
        int N = FftSea.N; ByteBuffer bb = ByteBuffer.allocate(N * 4 * N * 4 * 2).order(ByteOrder.LITTLE_ENDIAN);
        FftSea.Field[] byPar = new FftSea.Field[2]; byPar[(int) (p.slot & 1)] = p; byPar[(int) (c.slot & 1)] = c;
        for (FftSea.Field f : byPar) { for (short v : f.a) bb.putShort(v); for (short v : f.b) bb.putShort(v); }
        try (FileOutputStream o = new FileOutputStream(new File(out, "fft.bin"))) { o.write(bb.array()); }
        try (PrintWriter o = new PrintWriter(new File(out, "uniforms.txt"))) {
            o.printf(java.util.Locale.ROOT, "%.9f %.9f %.9f %.1f%n%.1f %.9f 0 0%n", frac, WaveModel.heightOpen(s) / c.hsFull, c.breakJ, (p.slot & 1) * 512.0, 1.0, w);
        }
        double[] v = new double[7], o8 = new double[8], tmp = new double[8]; java.util.Random r = new java.util.Random(5);
        try (PrintWriter o = new PrintWriter(new File(out, "corners.csv"))) {
            for (int i = 0; i < 4000; i++) {
                int x = r.nextInt(2000) - 1000 + (i % 7 == 0 ? 1000000 : 0), z = r.nextInt(2000) - 1000, e = 1 + r.nextInt(15), d = 1 + r.nextInt(15), sc = r.nextInt(6);
                double speed = sc <= 1 ? 0 : (sc - 1) * 0.3;
                FftCorner.fill(x, z, e, d, s, w, speed, p, c, frac, FftSea.SLOT, v, 0, o8, tmp);
                o.printf(java.util.Locale.ROOT, "%d %d %d %d %d %.7f %d%n", x, z, e, d, sc, v[Surface.CH], (int) v[Surface.CBREAK]);
            }
        }
        if (a.length > 4) for (int m = 0; m <= Integer.parseInt(a[4]); m++) {   // a run of single fields for the look harness: seq_<slot>.bin (A then B) + seq.txt
            FftSea.Field f = FftSea.build((k + m) * FftSea.SLOT, sq, w, null); ByteBuffer b1 = ByteBuffer.allocate(N * N * 4 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (short q : f.a) b1.putShort(q); for (short q : f.b) b1.putShort(q);
            try (FileOutputStream o = new FileOutputStream(new File(out, "seq_" + (k + m) + ".bin"))) { o.write(b1.array()); }
            try (FileWriter o = new FileWriter(new File(out, "seq.txt"), m > 0)) { o.write(String.format(java.util.Locale.ROOT, "%d %.9f %.9f %.9f%n", k + m, f.hsFull, f.breakJ, WaveModel.heightOpen(s) / f.hsFull)); }
        }
        System.out.printf("dumped slot %d/%d hsFull %.3f breakJ %.3f%n", p.slot, c.slot, c.hsFull, c.breakJ);
    }
}
