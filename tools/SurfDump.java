import com.hopeless.tides.core.*;
import java.io.*;
/** SurfDump seed x z out.json: a solved field packed as TidesSurfGpu uploads it, plus CPU corner values to check the shader against. Saved tool. */
public class SurfDump {
    public static void main(String[] a) throws Exception {
        int N = 128; double g = 11.53, s = 1.0, u = FftSea.u10(s), hs = WaveModel.heightOpen(s) * FftSea.heightScale(u, g); double[] p = FftSea.parameters(u, FftSea.FETCH, g);
        int x0 = Integer.parseInt(a[1]) - N / 2, z0 = Integer.parseInt(a[2]) - N / 2;
        Surf.Input in = SurfTest.input(Long.parseLong(a[0]), x0, z0, N, hs, 2 * Math.PI / p[1], -Math.PI / 2, g); in.seedX0 = Math.floorMod(x0, Surf.FRAME); in.seedY0 = Math.floorMod(z0, Surf.FRAME);
        Surf.Field f = Surf.compute(in);
        StringBuilder b = new StringBuilder("{\"tex\":[");
        for (int c = 0; c < N * N; c++) { boolean q = f.seaCol[c]; b.append(q ? f.phi[1][c] : 0).append(',').append(q ? f.shR[c] : 0).append(',').append(q ? f.shP[c] : 0).append(',').append(q ? f.hrms[c] : -1).append(','); }
        for (int c = 0; c < N * N; c++) { double[] gg = f.seaCol[c] ? f.grad(c % N, c / N) : new double[2]; b.append(f.rho[c]).append(',').append(f.depth[c]).append(',').append((float) gg[0]).append(',').append((float) gg[1]).append(','); }
        for (int c = 0; c < 2 * N * N * 4; c++) b.append("0,");
        b.setLength(b.length() - 1); double t = 1234.5, clk = Surf.clock(f.om[1], t);
        b.append("],\"clock\":").append((float) clk).append(",\"seed\":[").append(in.seedX0).append(',').append(in.seedY0).append("],\"pts\":[");
        java.util.Random r = new java.util.Random(3); int n = 0;
        while (n < 256) { int I = r.nextInt(N + 1), J = r.nextInt(N + 1); double[] e = f.corner(I, J, clk, in.seedX0 + I, in.seedY0 + J); if (e == null || e[1] <= 0) continue;
            b.append(n > 0 ? "," : "").append(String.format(java.util.Locale.ROOT, "[%d,%d,%.6f,%.6f,%.6f]", I, J, e[0], e[1], e[4])); n++; }
        b.append("]}"); try (FileWriter w = new FileWriter(a[3])) { w.write(b.toString()); } System.out.println("dumped " + n + " corners");
    }
}
