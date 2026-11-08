package com.hopeless.tides.client;

import com.hopeless.tides.TidesSurf;
import com.hopeless.tides.core.WaterOptics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 0.15.0: the water's type round the camera (core/WaterOptics, from Hopeless Water): one byte set per column of a 128 x 128 square,
 * rebuilt every 2 s on the game thread from the heightmaps (cheap), uploaded to texture unit 9 for the water's look (tides_look.glsl
 * tides_optic): r, g, b = the body's hue (linear, x 255), a = Kd x 100 (1/m). The camera's own column also sets the underwater fog
 * (TidesLook.wash, .fog). Temperature: the world mod's (TidesPlanets.meanC, Hopeless Infinity) or else the biome's temperature by a
 * CHOSEN line (0 -> -10 C, 0.8 -> 15 C, 2 -> 30 C at most). Probes: builds, ms, columns by type, the camera's type.
 */
public final class TidesOptics {
    private TidesOptics() {}
    public static final int N = 128, UNIT = 9, PERIOD = 40;
    public static volatile boolean on = true;
    static final ByteBuffer BUF = BufferUtils.createByteBuffer(N * N * 4); static int tex; static boolean dirty; static int ox = Integer.MIN_VALUE, oz;
    static long lastTick = Long.MIN_VALUE; public static volatile long builds; public static volatile double lastMs;
    public static final int[] COUNT = new int[WaterOptics.Type.values().length];
    public static volatile WaterOptics.Type cameraType = WaterOptics.Type.COOL_OPEN; public static volatile double cameraMeanC = Double.NaN;

    static double meanC(ClientLevel l, double x, double z, BlockPos.MutableBlockPos m) {
        double c = com.hopeless.tides.api.TidesPlanets.meanC(l.dimension().location().toString(), x, z);
        if (!Double.isNaN(c)) return c;
        float t = l.getBiome(m.set(x, 64, z)).value().getBaseTemperature();
        return Math.min(30, t <= 0.8 ? -10 + 25 * t / 0.8 : 15 + 15 * (t - 0.8) / 1.2);   // CHOSEN (Minecraft has no degrees)
    }
    public static void tick(ClientLevel l, Vec3 cam) {
        if (!on) return; long now = l.getGameTime(); if (now - lastTick < PERIOD) return; lastTick = now; long t0 = System.nanoTime();
        int nx = (int) Math.floor(cam.x) - N / 2, nz = (int) Math.floor(cam.z) - N / 2;
        TidesSurf.Host host = TidesSurf.at(l, cam.x, cam.z); var sf = host == null ? null : host.field; double seaTop = host == null ? Double.NaN : host.seaTop;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(); java.util.Arrays.fill(COUNT, 0); BUF.clear();
        double[] cellC = new double[(N / 16 + 1) * (N / 16 + 1)]; java.util.Arrays.fill(cellC, Double.NaN);
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) {
            int x = nx + i, z = nz + j; byte r = 0, g = 0, b = 0, a = 0;
            if (l.hasChunk(x >> 4, z >> 4)) {
                int top = l.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1; FluidState fs = l.getFluidState(m.set(x, top, z));
                if (fs.is(FluidTags.WATER)) {
                    int bed = l.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z); double surfY = top + fs.getHeight(l, m);
                    boolean river = !fs.isSource() || l.getBiome(m).is(net.minecraft.tags.BiomeTags.IS_RIVER);
                    boolean sea = false; double depth = surfY - bed;
                    if (sf != null && host.covers(x, z)) { int c = (x - host.ox) + TidesSurf.N * (z - host.oz); sea = sf.seaCol[c]; }
                    else if (!Double.isNaN(seaTop)) sea = Math.abs(surfY - seaTop) < 0.2 && depth > 3;
                    String key = l.getBiome(m).unwrapKey().map(k -> k.location().getPath()).orElse("");
                    boolean swamp = key.contains("swamp") || key.contains("mangrove");
                    int ci = (i >> 4) + (N / 16 + 1) * (j >> 4); if (Double.isNaN(cellC[ci])) cellC[ci] = meanC(l, x, z, m);
                    double alt = Double.isNaN(seaTop) ? 0 : surfY - seaTop;
                    WaterOptics.Type ty = WaterOptics.classify(river, sea, swamp, cellC[ci], alt); COUNT[ty.ordinal()]++;
                    double[] lk = WaterOptics.look(ty, depth);
                    r = (byte) Math.round(Math.min(1, lk[0]) * 255); g = (byte) Math.round(Math.min(1, lk[1]) * 255); b = (byte) Math.round(Math.min(1, lk[2]) * 255); a = (byte) Math.max(1, Math.min(255, Math.round(lk[3] * 100)));
                    if (i == N / 2 && j == N / 2) { cameraType = ty; cameraMeanC = cellC[ci]; }
                }
            }
            BUF.put(r).put(g).put(b).put(a);
        }
        BUF.flip(); ox = nx; oz = nz; dirty = true; builds++; lastMs = (System.nanoTime() - t0) / 1e6;
        // the camera's water when it is not over a water column itself (under a bridge, in a boat): the nearest classified column wins (kept)
    }
    static final Map<Integer, int[]> LOC = new HashMap<>();
    public static void setUniforms(int prog) {
        int[] loc = LOC.computeIfAbsent(prog, p -> new int[]{GL20.glGetUniformLocation(p, "u_TidesOptic"), GL20.glGetUniformLocation(p, "u_TidesOpticO")});
        if (loc[1] < 0) return;
        if (!on || ox == Integer.MIN_VALUE) { GL20.glUniform4f(loc[1], 0, 0, N, 0); return; }
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE); GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        if (tex == 0) { tex = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            { int[] tgl = TidesGl.begin(); GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, N, N, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null); TidesGl.end(tgl); }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR); GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE); GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL30.GL_TEXTURE_MAX_LEVEL, 0); }
        else GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        if (dirty) { { int[] tgl = TidesGl.begin(); GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, N, N, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, BUF); TidesGl.end(tgl); } dirty = false; }
        GL13.glActiveTexture(active);
        if (loc[0] >= 0) GL20.glUniform1i(loc[0], UNIT);
        GL20.glUniform4f(loc[1], (float) (ox - TidesRender.OX), (float) (oz - TidesRender.OZ), N, 1f);
    }
    public static String probe() {
        StringBuilder b = new StringBuilder(String.format("optics %s: builds %d (%.1f ms), camera %s %s, sight %.0f m (%s), columns", on ? "on" : "off", builds, lastMs, cameraType,
            Double.isNaN(cameraMeanC) ? "?" : String.format("%.1f C", cameraMeanC), WaterOptics.playedSight(cameraType), WaterOptics.useRecords ? "records" : "game"));
        for (WaterOptics.Type t : WaterOptics.Type.values()) if (COUNT[t.ordinal()] > 0) b.append(' ').append(t).append(' ').append(COUNT[t.ordinal()]);
        return b.toString();
    }
}
