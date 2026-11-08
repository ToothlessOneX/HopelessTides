package com.hopeless.tides.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 0.10.0: THE WATER ANSWERS BACK (Hopeless Water Ripples.cs): a 32 x 32 block square round the camera (128 cells of 0.25 m) carries a
 * height field under the damped wave equation h_tt = c^2 lap h - Damp h_t, c = 0.88 m/s (deep-water speed of a 0.5 m wave), Damp 0.5/s,
 * stepped at 60 Hz (3 steps a client tick). Bodies in the water push it: a body that moves takes water from where it is now and gives it
 * back where it was (a moving dipole, so a swimmer or a boat draws a wake); a body that goes in pushes a ring. The field follows the
 * camera by whole cells. On texture unit 4 for the fragment shader (slope added to the water's detail). Probes: steps, pushes, peak.
 */
public final class TidesRipples {
    private TidesRipples() {}
    public static final int SIZE = 128, UNIT = 4;
    public static final double CELL = 0.25, SPEED = 0.88, DAMP = 0.5, STEP = 1.0 / 60.0, MAXH = 0.6;
    static float[] h = new float[SIZE * SIZE], old = new float[SIZE * SIZE], nw = new float[SIZE * SIZE];
    static float[] foam = new float[SIZE * SIZE], foamNew = new float[SIZE * SIZE];   // 0.11.0: foam trails (0..1), fading over FOAM_LIFE s
    public static final double FOAM_LIFE = 6.0;
    static int ox = Integer.MIN_VALUE, oz;
    static final Map<Integer, double[]> LAST = new HashMap<>();
    public static volatile long steps, pushes, uploads; public static volatile double peak;
    static int tex; static FloatBuffer buf; static boolean dirty;

    static void follow(double x, double z) {
        int cx = (int) Math.floor(x / CELL) - SIZE / 2, cz = (int) Math.floor(z / CELL) - SIZE / 2;
        if (ox == Integer.MIN_VALUE) { ox = cx; oz = cz; return; }
        int dx = cx - ox, dz = cz - oz; if (Math.abs(dx) < SIZE / 4 && Math.abs(dz) < SIZE / 4) return;
        if (Math.abs(dx) >= SIZE || Math.abs(dz) >= SIZE) { java.util.Arrays.fill(h, 0); java.util.Arrays.fill(old, 0); java.util.Arrays.fill(foam, 0); }
        else { shift(h, dx, dz); shift(old, dx, dz); shift(foam, dx, dz); }
        ox = cx; oz = cz;
    }
    static void shift(float[] a, int dx, int dz) {
        float[] b = new float[a.length];
        for (int y = 0; y < SIZE; y++) { int sy = y + dz; if (sy < 0 || sy >= SIZE) continue;
            for (int x = 0; x < SIZE; x++) { int sx = x + dx; if (sx < 0 || sx >= SIZE) continue; b[x + SIZE * y] = a[sx + SIZE * sy]; } }
        System.arraycopy(b, 0, a, 0, a.length);
    }
    static void step() {
        double r = SPEED * STEP / CELL, r2 = r * r, keep = 1 - DAMP * STEP; float pk = 0;
        for (int y = 1; y < SIZE - 1; y++) for (int x = 1; x < SIZE - 1; x++) {
            int i = x + SIZE * y; double lap = h[i - 1] + h[i + 1] + h[i - SIZE] + h[i + SIZE] - 4 * h[i];
            double v = h[i] + (h[i] - old[i]) * keep + r2 * lap; float f = (float) (v > MAXH ? MAXH : v < -MAXH ? -MAXH : v);
            nw[i] = f; pk = Math.max(pk, Math.abs(f));
        }
        for (int k = 0; k < SIZE; k++) { nw[k] = 0; nw[k + SIZE * (SIZE - 1)] = 0; nw[SIZE * k] = 0; nw[SIZE * k + SIZE - 1] = 0; }
        float[] t = old; old = h; h = nw; nw = t; steps++; peak = pk; dirty = true;
        // foam: fades (e-fold FOAM_LIFE) and spreads a little (a quarter of the way to its neighbours' mean in a second, CHOSEN)
        float keepF = (float) Math.exp(-STEP / FOAM_LIFE), mixF = (float) (0.25 * STEP);
        for (int y = 1; y < SIZE - 1; y++) for (int x = 1; x < SIZE - 1; x++) { int i = x + SIZE * y;
            float m = 0.25f * (foam[i - 1] + foam[i + 1] + foam[i - SIZE] + foam[i + SIZE]); foamNew[i] = (foam[i] + (m - foam[i]) * mixF) * keepF; }
        float[] tf = foam; foam = foamNew; foamNew = tf;
    }
    /** Add volume m3 (negative: take away) as a smooth bump of radius r m at (x, z): displaced, not yet moving (Ripples.Push). */
    public static void push(double x, double z, double radius, double volume) {
        if (ox == Integer.MIN_VALUE) return;
        radius = Math.max(CELL, radius);
        double fx = x / CELL - ox, fz = z / CELL - oz, rc = radius / CELL;
        int x0 = (int) Math.floor(fx - rc), x1 = (int) Math.ceil(fx + rc), z0 = (int) Math.floor(fz - rc), z1 = (int) Math.ceil(fz + rc);
        double sum = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int yy = Math.max(1, z0); yy <= Math.min(SIZE - 2, z1); yy++) for (int xx = Math.max(1, x0); xx <= Math.min(SIZE - 2, x1); xx++) {
                double d = Math.sqrt((xx - fx) * (xx - fx) + (yy - fz) * (yy - fz)) / rc; if (d >= 1) continue;
                double w = 0.5 * (1 + Math.cos(Math.PI * d));
                if (pass == 0) sum += w; else { int i = xx + SIZE * yy; float dv = (float) (volume / (CELL * CELL) * w / sum); h[i] += dv; old[i] += dv; }
            }
            if (sum <= 0) return;
        }
        pushes++;
    }
    /** Raises the foam to 'amount' (0..1) over a disc of radius r at (x, z). */
    public static void addFoam(double x, double z, double radius, double amount) {
        if (ox == Integer.MIN_VALUE || amount <= 0) return;
        double fx = x / CELL - ox, fz = z / CELL - oz, rc = Math.max(1, radius / CELL);
        for (int yy = Math.max(1, (int) (fz - rc)); yy <= Math.min(SIZE - 2, (int) (fz + rc) + 1); yy++) for (int xx = Math.max(1, (int) (fx - rc)); xx <= Math.min(SIZE - 2, (int) (fx + rc) + 1); xx++) {
            double d = Math.hypot(xx - fx, yy - fz) / rc; if (d >= 1) continue; int i = xx + SIZE * yy; foam[i] = (float) Math.max(foam[i], amount * (1 - d * d)); }
    }
    /** Client tick: follow the camera, push with the bodies in the water near it, step 3 times. */
    public static void tick(ClientLevel l, Vec3 cam) {
        follow(cam.x, cam.z);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Entity e : l.entitiesForRendering()) {
            if (Math.abs(e.getX() - cam.x) > 15 || Math.abs(e.getZ() - cam.z) > 15) continue;
            if (!e.isInWater() && !(e instanceof net.minecraft.world.entity.vehicle.Boat && inWaterBelow(l, e))) continue;   // a boat floats on it
            seen.add(e.getId());
            double r = Math.max(0.2, e.getBbWidth() * 0.5); double[] last = LAST.get(e.getId());
            if (last == null) { push(e.getX(), e.getZ(), r * 1.5, -0.6 * r * r); LAST.put(e.getId(), new double[]{e.getX(), e.getZ()}); continue; }   // went in: a ring
            double mx = e.getX() - last[0], mz = e.getZ() - last[1], d = Math.hypot(mx, mz);
            if (d > 0.01 && d < 3) {
                double vol = displaced(r);                                   // the hollow the body makes moves with it: taken here, given back there
                push(e.getX(), e.getZ(), r * 1.6, -vol); push(last[0], last[1], r * 1.6, vol);
                addFoam(last[0], last[1], r * 1.1, Math.min(1.0, (d * 20 - 1.0) / 4.0));   // foam behind a body faster than 1 m/s, full at 5 m/s (CHOSEN)
            }
            last[0] = e.getX(); last[1] = e.getZ();
        }
        LAST.keySet().retainAll(seen);
        for (int i = 0; i < 3; i++) step();
    }
    /** The water a floating or swimming body of radius r holds aside: pi r^2 x 0.25 m draft (CHOSEN: a boat's or a swimmer's depth in it). */
    public static double displaced(double r) { return Math.PI * r * r * 0.25; }
    static boolean inWaterBelow(ClientLevel l, Entity e) { return l.getFluidState(e.blockPosition().below()).is(net.minecraft.tags.FluidTags.WATER) || l.getFluidState(e.blockPosition()).is(net.minecraft.tags.FluidTags.WATER); }
    static final Map<Integer, int[]> LOC = new HashMap<>();
    /** Uploads the field (if it changed) and sets u_TidesRip (unit 4) and u_TidesRipO (world x, z of the corner, cell, 1 = on). */
    public static void setUniforms(int prog) {
        int[] loc = LOC.computeIfAbsent(prog, p -> new int[]{GL20.glGetUniformLocation(p, "u_TidesRip"), GL20.glGetUniformLocation(p, "u_TidesRipO")});
        if (loc[1] < 0) return;
        if (ox == Integer.MIN_VALUE) { GL20.glUniform4f(loc[1], 0, 0, (float) CELL, 0); return; }
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE); GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        if (tex == 0) {
            tex = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            { int[] tgl = TidesGl.begin(); GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG32F, SIZE, SIZE, 0, GL30.GL_RG, GL11.GL_FLOAT, (FloatBuffer) null); TidesGl.end(tgl); }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR); GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER); GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 0); dirty = true;
        } else GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        if (dirty) {
            if (buf == null) buf = org.lwjgl.BufferUtils.createFloatBuffer(SIZE * SIZE * 2);
            buf.clear(); for (int i = 0; i < SIZE * SIZE; i++) { buf.put(h[i]); buf.put(foam[i]); } buf.flip();
            { int[] tgl = TidesGl.begin(); GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL30.GL_RG, GL11.GL_FLOAT, buf); TidesGl.end(tgl); } dirty = false; uploads++;
        }
        GL13.glActiveTexture(active);
        if (loc[0] >= 0) GL20.glUniform1i(loc[0], UNIT);
        GL20.glUniform4f(loc[1], (float) (ox * CELL - TidesRender.OX), (float) (oz * CELL - TidesRender.OZ), (float) CELL, 1f);
    }
    public static String probe() { return String.format("ripples: steps %d pushes %d peak %.3f uploads %d", steps, pushes, peak, uploads); }
}
