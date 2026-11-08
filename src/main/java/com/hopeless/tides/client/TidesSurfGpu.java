package com.hopeless.tides.client;

import com.hopeless.tides.TidesSurf;
import com.hopeless.tides.core.Surf;
import net.minecraft.client.multiplayer.ClientLevel;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 0.14.0: the client's surf field on the GPU (tides_surf.glsl): uploaded when a new field is solved (every few seconds at most), on
 * texture unit 8, read by the water's vertices (the bores) and the beach's pixels (the swash). Probes: uploads, frames on/off.
 */
public final class TidesSurfGpu {
    private TidesSurfGpu() {}
    public static final int UNIT = 8, N = TidesSurf.N, ROWS = 10;
    static int tex; static FloatBuffer buf; static Surf.Field uploaded, chopUploaded; static TidesSurf.Host host;
    public static volatile long uploads, framesOn, framesOff, trainFrames, chopFrames;
    static final Map<Integer, int[]> LOC = new HashMap<>();
    public static void setUniforms(int prog, ClientLevel l, float pt) {
        int[] loc = LOC.computeIfAbsent(prog, p -> new int[]{GL20.glGetUniformLocation(p, "u_TidesSurf"), GL20.glGetUniformLocation(p, "u_TidesSurfO"), GL20.glGetUniformLocation(p, "u_TidesSurfT"), GL20.glGetUniformLocation(p, "u_TidesSwO"), GL20.glGetUniformLocation(p, "u_TidesSwC"), GL20.glGetUniformLocation(p, "u_TidesSwK"), GL20.glGetUniformLocation(p, "u_TidesSwD"), GL20.glGetUniformLocation(p, "u_TidesSwA"), GL20.glGetUniformLocation(p, "u_TidesChC"), GL20.glGetUniformLocation(p, "u_TidesChK"), GL20.glGetUniformLocation(p, "u_TidesChA")});
        if (loc[2] < 0) return;
        var cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        TidesSurf.Host h = TidesSurf.at(l, cam.x, cam.z); Surf.Field f = h == null ? null : h.field;
        if (f == null || !TidesSurf.on) { GL20.glUniform4f(loc[2], 0, 0, 0, 0); if (loc[3] >= 0) GL20.glUniform4f(loc[3], 0, 0, 0, 0); framesOff++; return; }   // no train either
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE); GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        if (tex == 0) {
            tex = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            { int[] tgl = TidesGl.begin(); GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, N, ROWS * N, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null); TidesGl.end(tgl); }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST); GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 0);
        } else GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        if (f != uploaded || h.chop != chopUploaded) {
            if (buf == null) buf = org.lwjgl.BufferUtils.createFloatBuffer(N * N * 4 * ROWS);
            buf.clear();
            for (int c = 0; c < N * N; c++) { boolean s = f.seaCol[c]; buf.put(s ? f.phi[Surf.DOM][c] : 0).put(s ? f.shR[c] : 0).put(s ? f.shP[c] : 0).put(s ? f.hrms[c] : -1); }
            for (int c = 0; c < N * N; c++) { double[] g = f.seaCol[c] ? f.grad(c % N, c / N) : new double[2]; buf.put(f.rho[c]).put(f.depth[c]).put((float) g[0]).put((float) g[1]); }
            // rows 2, 3: the swash on a dry column; 0.16.0 on a sea column near a wall: (distance to the wall foot, Kr, R2, 0) and (out-to-sea x, z, -1e6, 1)
            for (int c = 0; c < N * N; c++) { boolean w = !Float.isNaN(f.shoreX[c]), wl = f.seaCol[c] && !Float.isNaN(f.wallDist[c]);
                if (wl) buf.put(f.wallDist[c]).put(f.wallKr[c]).put(f.wallR2[c]).put(0); else buf.put(w ? f.shoreTheta[c] : 0).put(w ? f.shoreX[c] : 0).put(w ? f.beta[c] : 0).put(w ? f.run2[c] : 0); }
            for (int c = 0; c < N * N; c++) { boolean w = !Float.isNaN(f.shoreX[c]), wl = f.seaCol[c] && !Float.isNaN(f.wallDist[c]);
                if (wl) buf.put(f.wallNx[c]).put(f.wallNy[c]).put(-1e6f).put(1); else buf.put(w ? f.upX[c] : 0).put(w ? f.upY[c] : 0).put(w ? (float) bedTop(h, c) : -1e6f).put(0); }
            // rows 4, 5 (0.16.1 the swell train): the K components' phase and height; height -1 where the wave does not reach (or no sea)
            for (int c = 0; c < N * N; c++) { boolean s = f.seaCol[c]; for (int q = 0; q < Surf.K; q++) buf.put(s ? f.phi[q][c] : 0); }
            for (int c = 0; c < N * N; c++) { boolean s = f.seaCol[c] && f.steerK != null && !Float.isNaN(f.steerK[c]); for (int q = 0; q < Surf.K; q++) buf.put(s ? f.hc[q][c] : -1f); }
            // row 6 (0.16.2 the local fetch): the wind sea's height factor, 1 sea / 0 not
            for (int c = 0; c < N * N; c++) buf.put(f.fetchK == null || !f.seaCol[c] ? 1f : f.fetchK[c]).put(0).put(0).put(f.seaCol[c] ? 1 : 0);
            // rows 7, 8, 9 (0.16.3 the wind sea in shallow water, the second solve): phases, heights (-1: not reached / no sea), (Abreu r, phi, phase gradient x, z)
            Surf.Field ch = h.chop;
            for (int c = 0; c < N * N; c++) { boolean s = ch != null && ch.seaCol[c]; for (int q = 0; q < Surf.K; q++) buf.put(s ? ch.phi[q][c] : 0); }
            for (int c = 0; c < N * N; c++) { boolean s = ch != null && ch.seaCol[c] && !Float.isNaN(ch.steerK[c]); for (int q = 0; q < Surf.K; q++) buf.put(s ? ch.hc[q][c] : -1f); }
            for (int c = 0; c < N * N; c++) { boolean s = ch != null && ch.seaCol[c]; double[] g = s ? ch.grad(c % N, c / N) : new double[2]; buf.put(s ? ch.shR[c] : 0).put(s ? ch.shP[c] : 0).put((float) g[0]).put((float) g[1]); }
            chopUploaded = ch;
            buf.flip();
            { int[] tgl = TidesGl.begin(); GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, N, ROWS * N, GL11.GL_RGBA, GL11.GL_FLOAT, buf); TidesGl.end(tgl); }
            uploaded = f; host = h; uploads++;
        }
        GL13.glActiveTexture(active);
        if (loc[0] >= 0) GL20.glUniform1i(loc[0], UNIT);
        if (loc[1] >= 0) GL20.glUniform4f(loc[1], (float) (h.ox - TidesRender.OX), (float) (h.oz - TidesRender.OZ), (float) h.seedX0, (float) h.seedY0);
        double t = TidesSurf.seconds(l, pt);
        GL20.glUniform4f(loc[2], (float) Surf.clock(f.om[Surf.DOM], t), (float) Surf.PHASE0[Surf.DOM], (float) (2 * Math.PI / f.om[Surf.DOM]), 1f);
        // 0.16.3 the wind sea in shallow water: the second solve's clocks, deep wavenumbers, its deep wavelength and on
        Surf.Field ch = h.chop;
        if (ch != null && TidesSurf.chopOn && h.chop == chopUploaded) { float[] c4 = new float[4], k4 = new float[4]; for (int q = 0; q < Surf.K; q++) { c4[q] = (float) Surf.clock(ch.om[q], t); k4[q] = (float) (ch.om[q] * ch.om[q] / ch.g); }
            if (loc[8] >= 0) GL20.glUniform4f(loc[8], c4[0], c4[1], c4[2], c4[3]); if (loc[9] >= 0) GL20.glUniform4f(loc[9], k4[0], k4[1], k4[2], k4[3]); if (loc[10] >= 0) GL20.glUniform4f(loc[10], (float) h.chopL0, 1f, 0f, 0f); chopFrames++; }
        else if (loc[10] >= 0) GL20.glUniform4f(loc[10], 1f, 0f, 0f, 0f);
        // 0.16.1 the swell train: on, its share, the K clocks, deep wavenumbers and headings; the world's position mod FRAME at the shader origin
        double sh = TidesSurf.share(h);
        if (loc[3] >= 0) GL20.glUniform4f(loc[3], sh > 0 ? 1f : 0f, (float) sh, (float) Math.floorMod((long) TidesRender.OX, (long) Surf.FRAME), (float) Math.floorMod((long) TidesRender.OZ, (long) Surf.FRAME));
        if (sh > 0) { float[] c4 = new float[4], k4 = new float[4], d4 = new float[4]; for (int q = 0; q < Surf.K; q++) { c4[q] = (float) Surf.clock(f.om[q], t); k4[q] = (float) (f.om[q] * f.om[q] / f.g); d4[q] = (float) (f.heading + Surf.DIR_OFF[q]); }
            if (loc[4] >= 0) GL20.glUniform4f(loc[4], c4[0], c4[1], c4[2], c4[3]); if (loc[5] >= 0) GL20.glUniform4f(loc[5], k4[0], k4[1], k4[2], k4[3]); if (loc[6] >= 0) GL20.glUniform4f(loc[6], d4[0], d4[1], d4[2], d4[3]);
            if (loc[7] >= 0) GL20.glUniform4f(loc[7], (float) (Surf.UNIT * f.hs0 * sh), (float) Surf.UNIT, (float) h.seaTop, 0f); trainFrames++; }
        framesOn++;
    }
    /** The ground's top (world y) of a field column: the still water's level plus the beach's rise (bed kept in the field). */
    static double bedTop(TidesSurf.Host h, int c) { return h.field.bed == null ? -1e6 : h.field.bed[c]; }
    public static String probe() { return "surf gpu: uploads " + uploads + ", frames on " + framesOn + " off " + framesOff + ", swell train frames " + trainFrames + ", shallow wind-sea frames " + chopFrames; }
}
