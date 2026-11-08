import com.hopeless.tides.core.*;
import com.hopeless.infinity.planet.*;

import java.io.*;
import java.nio.*;

/**
 * ShoreView seed x z outDir [frames] [windDeg] [skyState]: everything tools/shore_view.js needs to draw the water meeting the shore OUT OF
 * THE GAME with the mod's own shader code (tides_waves.glsl, tides_fft.glsl, tides_surf.glsl, the files the game loads). Owner, 2026-11-02:
 * "create a new tool to be able to check this outside of the game so this doesnt happen again".
 *
 * From the mod's code, as the game does it beyond the 128-block surf field:
 *  - the terrain: Hopeless Infinity's real planet generator (seed, face 0), 512 x 512 columns round (x, z), the map the client scans;
 *  - the sea: the planet's gravity 11.53 m/s2; the wind sea from FftSea (u10 from the sky state, the fetch measured as TidesSurf does);
 *    the swell from TidesSurf's buoy values (Hs 0.93 m, Tp 9.09 s, x 9.81 / g) coming from the open ocean (OceanSwell over the same map);
 *  - the shore map: core/ShoreMap.build on those depths (the same call TidesShore makes);
 *  - the Fourier fields: FftSea.build per quarter-second slot, packed as TidesFftGpu uploads them.
 * NOT reproduced (stated on the render): the mesher's per-vertex exposure (taken as 15, open) and the look shader's lighting (a plain sky
 * reflection and foam from the shader's own foam value instead). Saved tool.
 */
public class ShoreView {
    public static void main(String[] a) throws Exception {
        long seed = Long.parseLong(a[0]); int cx = Integer.parseInt(a[1]), cz = Integer.parseInt(a[2]); File out = new File(a[3]); out.mkdirs();
        int frames = a.length > 4 ? Integer.parseInt(a[4]) : 24; double windDeg = a.length > 5 ? Double.parseDouble(a[5]) : 20, s = a.length > 6 ? Double.parseDouble(a[6]) : 0.5;
        int N = ShoreMap.N_GAME, ox = cx - N / 2, oz = cz - N / 2; double g = 11.53, wind = Math.toRadians(windDeg), u = FftSea.u10(s);
        PlanetWorld pw = new PlanetWorld(seed);
        float[] depth = new float[N * N]; short[] top = new short[N * N]; byte[] mat = new byte[N * N];
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) { Column c = Column.of(pw, 0, ox + i, oz + j); int k = i + N * j;
            top[k] = (short) c.surfaceY; mat[k] = (byte) c.top.ordinal(); int cnt = c.surfaceY < Terrain.SEA ? Terrain.SEA - c.surfaceY : 0; depth[k] = cnt == 0 ? 0f : cnt >= 48 ? Float.NaN : cnt - 0.11f; }
        // the fetch as TidesSurf measures it (Saville: 15 radials within 42 deg of upwind, to 8 km over the world map), from the deepest
        // water column round the view (TidesSurf 0.16.3: from the field's deepest sea column, not the viewer standing on land)
        int deep = -1; for (int k = 0; k < N * N; k++) { int i = k % N, j = k / N; if (Math.abs(i - N / 2) > 64 || Math.abs(j - N / 2) > 64) continue; if (depth[k] > 0 && (deep < 0 || depth[k] > depth[deep] || Float.isNaN(depth[k]))) deep = k; }
        final int mx = deep < 0 ? cx : ox + deep % N, mz = deep < 0 ? cz : oz + deep / N; System.out.printf("sea measured from %d %d%n", mx, mz);
        double num = 0, den = 0; for (int r = 0; r < 15; r++) { double an = wind + Math.PI + Math.toRadians(-42 + 6 * r), d = 8; for (; d <= 8000; d += 8) { Column c = Column.of(pw, 0, (int) Math.floor(mx + Math.cos(an) * d), (int) Math.floor(mz + Math.sin(an) * d)); if (c.surfaceY >= Terrain.SEA) break; }
            double cw = Math.cos(Math.toRadians(-42 + 6 * r)); num += Math.min(d, 8000) * cw * cw; den += cw; }
        double fetch = FftSea.quantFetch(num / den >= 8000 * 0.98 ? FftSea.FETCH : num / den);
        double swH = 0.93 * 9.81 / g, swT = 9.09 * 9.81 / g;
        double[] os = OceanSwell.from((x, z) -> { Column c = Column.of(pw, 0, (int) Math.floor(x), (int) Math.floor(z)); return c.voidCol ? -1 : c.surfaceY < Terrain.SEA ? 1 : 0; }, mx, mz, wind + Math.toRadians(25));
        double swDir = os == null ? wind : os[0]; if (os == null) swH = 0;
        double hw = FftSea.hsFullCached(u, g, fetch); double[] fp = FftSea.parameters(u, fetch, g); double tw = 2 * Math.PI / fp[1], tp = swH > 0 ? swT : tw;
        ShoreMap sm = ShoreMap.build(depth, N, g, tp, swDir, swH, wind, u, hw, fetch);
        System.out.println(sm.describe());
        System.out.printf("sea: wind %.0f deg %.1f m/s, fetch %.0f m, wind sea Hs %.2f m Tp %.1f s; swell %s%n", windDeg, u, fetch, hw, tw, swH > 0 ? String.format("Hs %.2f m Tp %.1f s toward %.0f deg (from the open ocean)", swH, swT, Math.toDegrees(swDir)) : "none (no open ocean)");
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(out, "terrain.bin"))))) { ByteBuffer b = ByteBuffer.allocate(N * N * 3).order(ByteOrder.LITTLE_ENDIAN); for (int k = 0; k < N * N; k++) b.putShort(top[k]); b.put(mat); o.write(b.array()); }
        { java.nio.FloatBuffer fb = java.nio.FloatBuffer.allocate(N * N * 12); fillShore(sm, fb); ByteBuffer b = ByteBuffer.allocate(N * N * 12 * 4).order(ByteOrder.LITTLE_ENDIAN); for (int k = 0; k < N * N * 12; k++) b.putFloat(fb.get(k)); try (FileOutputStream o = new FileOutputStream(new File(out, "shore.bin"))) { o.write(b.array()); } }
        // the Fourier fields, one per slot (the wind sea only: with a swell the game builds the drive with swellHs = 0)
        double t0 = 1000; long k0 = (long) Math.floor(t0 / FftSea.SLOT); FftSea.Field f = null; StringBuilder fs = new StringBuilder();
        for (int m = 0; m <= frames; m++) { long k = k0 + m; f = FftSea.build(k * FftSea.SLOT, s, wind, g, fetch, 0, 0, 0, null);
            ByteBuffer b = ByteBuffer.allocate(FftSea.N * FftSea.N * 4 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN); for (short q : f.a) b.putShort(q); for (short q : f.b) b.putShort(q);
            try (FileOutputStream o = new FileOutputStream(new File(out, "fft_" + m + ".bin"))) { o.write(b.array()); }
            fs.append(String.format(java.util.Locale.ROOT, "%s{\"hsFull\":%.9f,\"breakJ\":%.9f}", m == 0 ? "" : ",", f.hsFull, f.breakJ)); }
        // per frame: the clocks (shore train, swell components)
        StringBuilder fr = new StringBuilder(); double[] om = new double[Surf.K];
        for (int q = 0; q < Surf.K; q++) om[q] = 2 * Math.PI / Math.max(0.1, swT * Surf.PER_MUL[q]);
        for (int m = 0; m < frames; m++) { double t = (k0 + m) * FftSea.SLOT; fr.append(m == 0 ? "" : ",").append(String.format(java.util.Locale.ROOT, "{\"t\":%.3f,\"shClk\":%.7f,\"swC\":[%.7f,%.7f,%.7f,%.7f]}", t, Surf.clock(sm.om, t), Surf.clock(om[0], t), Surf.clock(om[1], t), Surf.clock(om[2], t), Surf.clock(om[3], t))); }
        String meta = String.format(java.util.Locale.ROOT, "{\"seed\":%d,\"cx\":%d,\"cz\":%d,\"n\":%d,\"ox\":%d,\"oz\":%d,\"sea\":%d,\"g\":%.4f,\"s\":%.4f,\"wind\":%.7f,\"fetch\":%.1f,\"hw\":%.4f,\"tw\":%.4f,\"swH\":%.4f,\"swT\":%.4f,\"swDir\":%.7f,"
                + "\"L0\":%.6f,\"heightOpen\":%.9f,\"swK\":[%.8f,%.8f,%.8f,%.8f],\"swD\":[%.8f,%.8f,%.8f,%.8f],\"swA\":%.8f,\"frameMod\":[%d,%d],\"mats\":%s,\"fft\":[%s],\"frames\":[%s],\"describe\":\"%s\"}",
                seed, cx, cz, N, ox, oz, Terrain.SEA, g, s, wind, fetch, hw, tw, swH, swT, swDir, sm.L0, WaveModel.heightOpen(s),
                om[0] * om[0] / g, om[1] * om[1] / g, om[2] * om[2] / g, om[3] * om[3] / g, swDir + Surf.DIR_OFF[0], swDir + Surf.DIR_OFF[1], swDir + Surf.DIR_OFF[2], swDir + Surf.DIR_OFF[3], Surf.UNIT * swH,
                Math.floorMod(ox, Surf.FRAME), Math.floorMod(oz, Surf.FRAME), matNames(), fs, fr, sm.describe());
        try (PrintWriter o = new PrintWriter(new File(out, "meta.json"))) { o.print(meta); }
        System.out.println("written to " + out);
    }
    static String matNames() { StringBuilder b = new StringBuilder("["); for (Column.Mat m : Column.Mat.values()) b.append(b.length() > 1 ? "," : "").append('"').append(m.name()).append('"'); return b.append(']').toString(); }
    /** As TidesShore.fill (client class; copied so this needs no game classes): the texture's three blocks. */
    static void fillShore(ShoreMap s, java.nio.FloatBuffer b) {
        int n2 = s.n * s.n;
        for (int c = 0; c < n2; c++) { boolean ok = !Float.isNaN(s.phi[c]); b.put(ok ? s.phi[c] : -1).put(s.h[c]).put(s.gx[c]).put(s.gz[c]); }
        for (int c = 0; c < n2; c++) { boolean ok = !Float.isNaN(s.phi[c]); b.put(s.lee[c]).put(s.fk[c]).put(s.kr[c]).put(ok ? 1 : 0); }
        for (int c = 0; c < n2; c++) b.put(s.ar[c]).put(s.ap[c]).put(s.hr[c]).put(0);
    }
}
