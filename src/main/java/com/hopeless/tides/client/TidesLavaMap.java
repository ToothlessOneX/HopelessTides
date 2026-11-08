package com.hopeless.tides.client;

import com.hopeless.tides.core.LavaMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 0.12.0: where the lava is round the camera, for the lava look and the warm light on the ground beside it. 64 x 64 columns (one
 * block each), re-centred by 16; each column is read from 16 above the eye to 32 below it (so a Nether roof does not hide the lava),
 * 256 columns a tick (a full pass in 16 ticks). Unit 3 (u_TidesLavaMap, u_TidesLavaO). Also spawns the embers from lava near the eye.
 * Probes: passes, lava columns, uploads, embers.
 */
public final class TidesLavaMap {
    private TidesLavaMap() {}
    public static final int N = 64, UNIT = 3, PER_TICK = 256;
    static final boolean[] LAVA = new boolean[N * N]; static final int[] SY = new int[N * N];
    static int ox = Integer.MIN_VALUE, oz, cursor; static boolean dirty; static int tex; static ByteBuffer buf;
    public static volatile long passes, lavaColumns, uploads, embers;
    public static void tick(ClientLevel l, Vec3 cam) {
        int nx = ((int) Math.floor(cam.x) >> 4 << 4) - N / 2, nz = ((int) Math.floor(cam.z) >> 4 << 4) - N / 2;
        if (nx != ox || nz != oz) { java.util.Arrays.fill(LAVA, false); ox = nx; oz = nz; cursor = 0; dirty = true; }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(); int ey = (int) Math.floor(cam.y);
        for (int k = 0; k < PER_TICK; k++) {
            int i = cursor; cursor = (cursor + 1) % (N * N); if (cursor == 0) passes++;
            int x = ox + i % N, z = oz + i / N; boolean found = false; int sy = 0;
            for (int y = ey + 16; y >= ey - 32; y--) {   // the highest lava block with open space (not lava, not a full block) over it
                m.set(x, y, z); if (!l.getFluidState(m).is(FluidTags.LAVA)) continue;
                m.set(x, y + 1, z); if (l.getFluidState(m).is(FluidTags.LAVA) || l.getBlockState(m).isSolidRender(l, m)) continue;
                found = true; sy = y; break;
            }
            if (LAVA[i] != found || SY[i] != sy) { LAVA[i] = found; SY[i] = sy; dirty = true; }
        }
        // embers: a few from lava columns near the eye (rising, glowing, gone in 1-2 s)
        var r = l.random;
        for (int k = 0; k < 6; k++) {
            int i = r.nextInt(N * N); if (!LAVA[i]) continue; int x = ox + i % N, z = oz + i / N;
            double d2 = (x + 0.5 - cam.x) * (x + 0.5 - cam.x) + (z + 0.5 - cam.z) * (z + 0.5 - cam.z); if (d2 > 24 * 24 || r.nextInt(3) != 0) continue;
            l.addParticle(TidesParticles.EMBER.get(), x + r.nextDouble(), SY[i] + 0.95, z + r.nextDouble(), (r.nextDouble() - 0.5) * 0.02, 0.03 + r.nextDouble() * 0.05, (r.nextDouble() - 0.5) * 0.02);
            embers++;
        }
    }
    static final Map<Integer, int[]> LOC = new HashMap<>();
    public static void setUniforms(int prog) {
        int[] loc = LOC.computeIfAbsent(prog, p -> new int[]{GL20.glGetUniformLocation(p, "u_TidesLavaMap"), GL20.glGetUniformLocation(p, "u_TidesLavaO")});
        if (loc[1] < 0) return;
        if (ox == Integer.MIN_VALUE) { GL20.glUniform4f(loc[1], 0, 0, N, 0); return; }
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE); GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        if (tex == 0) {
            tex = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            { int[] tgl = TidesGl.begin(); GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, N, N, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null); TidesGl.end(tgl); }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR); GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER); GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
            dirty = true;
        } else GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        if (dirty) {
            byte[] t = LavaMap.texels(LAVA, SY, N); if (buf == null) buf = org.lwjgl.BufferUtils.createByteBuffer(N * N * 4);
            buf.clear(); buf.put(t); buf.flip(); { int[] tgl = TidesGl.begin(); GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, N, N, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf); TidesGl.end(tgl); }
            int c = 0; for (boolean b : LAVA) if (b) c++; lavaColumns = c; dirty = false; uploads++;
        }
        GL13.glActiveTexture(active);
        if (loc[0] >= 0) GL20.glUniform1i(loc[0], UNIT);
        GL20.glUniform4f(loc[1], (float) (ox - TidesRender.OX), (float) (oz - TidesRender.OZ), N, 1f);
    }
    public static String probe() { return "lava map: passes " + passes + " lava columns " + lavaColumns + " uploads " + uploads + " embers " + embers; }
}
