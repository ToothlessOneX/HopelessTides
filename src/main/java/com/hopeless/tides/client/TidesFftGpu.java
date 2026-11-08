package com.hopeless.tides.client;

import com.hopeless.tides.Tides;
import com.hopeless.tides.core.FftDrive;
import com.hopeless.tides.core.FftSea;
import com.hopeless.tides.core.WaveModel;
import net.minecraft.client.multiplayer.ClientLevel;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 0.9.0: puts the client's Fourier pair (FftDrive) on texture unit 5 for the terrain vertex shader (tides_fft.glsl): one RGBA16F texture
 * 256 x 1024, the even slot's field in rows 0-511 (A then B), the odd slot's in rows 512-1023, so each new field (every quarter second)
 * is one 1 MB upload and the other half stays. Probes: uploads, last upload ms, frames on / off.
 */
public final class TidesFftGpu {
    private TidesFftGpu() {}
    public static final int UNIT = 5;
    static int tex; static final FftSea.Field[] held = new FftSea.Field[2];
    static ShortBuffer buf;
    static final FftDrive.Pair pair = new FftDrive.Pair();
    public static volatile long uploads, framesOn, framesOff; public static volatile double uploadMs;
    static final Map<Integer, int[]> LOC = new HashMap<>();

    static void upload(FftSea.Field f, int parity) {
        long t0 = System.nanoTime(); int N = FftSea.N;
        if (buf == null) buf = org.lwjgl.BufferUtils.createShortBuffer(N * N * 4 * 2);
        buf.clear(); buf.put(f.a); buf.put(f.b); buf.flip();
        { int[] tgl = TidesGl.begin(); GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, parity * 2 * N, N, 2 * N, GL11.GL_RGBA, GL30.GL_HALF_FLOAT, buf); TidesGl.end(tgl); }
        held[parity] = f; uploads++; uploadMs = (System.nanoTime() - t0) / 1e6;
    }
    /** Sets u_TidesFft* on the bound terrain program. t: tSec at the frame; dayTime with the partial tick; s: the open-sky sea state. */
    public static void setUniforms(int prog, ClientLevel l, double t, double dayTime, double s) {
        int[] loc = LOC.computeIfAbsent(prog, p -> new int[]{GL20.glGetUniformLocation(p, "u_TidesFft"), GL20.glGetUniformLocation(p, "u_TidesFft0"), GL20.glGetUniformLocation(p, "u_TidesFft1")});
        if (loc[1] < 0 || loc[2] < 0) return;
        FftDrive d = Tides.fftOn && l.dimensionType().hasSkyLight() ? Tides.drive(l) : null;
        FftDrive.Pair p = null;
        if (d != null) {
            double dayStart = Math.floor(dayTime / 24000.0) * 24000.0;
            d.want(t, Tides.skyState(l), ts -> WaveModel.wind(dayStart + ts * 20.0));
            p = d.pair(t, pair);
        }
        if (p == null || p.cur.hsFull <= 0) { GL20.glUniform4f(loc[2], 0, 0, 0, 0); framesOff++; return; }
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        if (tex == 0) {
            tex = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            { int[] tgl = TidesGl.begin(); GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, FftSea.N, 4 * FftSea.N, 0, GL11.GL_RGBA, GL30.GL_HALF_FLOAT, (ShortBuffer) null); TidesGl.end(tgl); }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST); GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 0);
        } else GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        int pp = (int) (p.prev.slot & 1), cp = (int) (p.cur.slot & 1);
        if (held[pp] != p.prev) upload(p.prev, pp);
        if (held[cp] != p.cur) upload(p.cur, cp);
        GL13.glActiveTexture(active);
        if (loc[0] >= 0) GL20.glUniform1i(loc[0], UNIT);
        GL20.glUniform4f(loc[1], (float) p.frac, (float) (WaveModel.heightOpen(s) / p.cur.hsFull), (float) p.cur.breakJ, pp * 512f);
        GL20.glUniform4f(loc[2], 1f, (float) WaveModel.wind(dayTime), 0, 0);
        framesOn++;
    }
    public static String probe() { return String.format("fft gpu: uploads %d (last %.2f ms), frames on %d off %d", uploads, uploadMs, framesOn, framesOff); }
}
