package com.hopeless.tides.client;

import com.hopeless.tides.TidesSurf;
import com.hopeless.tides.core.Surf;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/**
 * 0.16.0: the spray the surf throws (client only; Tides' own drop and mist particles, Hydro's splash layers):
 *  - at a wall foot (cliff, rock, sea wall, dock: Surf walls), each wave's front throws a jet up the face: its drops leave at
 *    sqrt(2 g R) for that wave's run-up R (the wall's 2 % run-up R2, EurOtop 2018, times the wave's own height over the 2 % one) and
 *    fall back; a little mist hangs over it;
 *  - on a breaking bore's front (foam over 0.5), a few drops and mist where the crest spills.
 * Within 48 blocks of the camera, at most CAP particles a tick. Probes: wall jets, bore sprays, particles.
 */
public final class TidesSurfSpray {
    private TidesSurfSpray() {}
    public static final int CAP = 240; public static final double REACH = 48;
    public static volatile long jets, boreSprays, particles;
    static float[] lastFr; static Surf.Field lastField;
    public static void tick(ClientLevel l, Vec3 cam) {
        TidesSurf.Host h = TidesSurf.at(l, cam.x, cam.z); Surf.Field f = h == null ? null : h.field; if (f == null || !TidesSurf.on) return;
        int N = TidesSurf.N; if (f != lastField) { lastFr = new float[N * N]; java.util.Arrays.fill(lastFr, -1); lastField = f; }
        double t = TidesSurf.seconds(l, 0f), clk = Surf.clock(f.om[Surf.DOM], t), g = f.g; var r = l.random; int budget = CAP;
        int ci0 = (int) Math.floor(cam.x - h.ox), cj0 = (int) Math.floor(cam.z - h.oz), R = (int) REACH;
        // wall jets: every wall-foot column near the camera, when its wave's front arrives (the front fraction wraps)
        for (int j = Math.max(0, cj0 - R); j < Math.min(N, cj0 + R) && budget > 0; j++) for (int i = Math.max(0, ci0 - R); i < Math.min(N, ci0 + R) && budget > 0; i++) {
            int c = i + N * j; if (!f.seaCol[c] || Float.isNaN(f.wallDist[c]) || f.wallDist[c] > 0.5f) continue;
            double thD = f.phi[Surf.DOM][c] - clk + Surf.PHASE0[Surf.DOM], cyc = -(thD - f.shP[c]) / (2 * Math.PI), fr = cyc - Math.floor(cyc);
            float last = lastFr[c]; lastFr[c] = (float) fr; if (last < 0 || fr >= last) continue;   // the front came in since the last tick
            int n = (int) Math.floor(cyc); double q = Surf.waveQ(n, h.seedX0 + i, h.seedY0 + j) / Surf.Q2, run = f.wallR2[c] * q; if (run < 0.15) continue;
            double v0 = Math.sqrt(2 * g * run) / 20.0, y = h.seaTop + 0.1; int drops = (int) Math.min(18, 4 + run * 8);
            double x = h.ox + i + 0.5 - f.wallNx[c] * 0.45, z = h.oz + j + 0.5 - f.wallNy[c] * 0.45;   // at the wall's face
            for (int k = 0; k < drops && budget > 0; k++) { double up = v0 * (0.55 + 0.45 * r.nextDouble()), out = up * 0.12 * r.nextDouble();
                l.addParticle(TidesParticles.DROP.get(), x + (r.nextDouble() - 0.5) * 0.9, y, z + (r.nextDouble() - 0.5) * 0.9, f.wallNx[c] * out, up, f.wallNy[c] * out); budget--; particles++; }
            if (budget > 0) { l.addParticle(TidesParticles.MIST.get(), x, y + run * 0.5, z, f.wallNx[c] * 0.02, 0.02, f.wallNy[c] * 0.02); budget--; particles++; }
            jets++;
        }
        // bore spray: random sea columns near the camera whose bore is white at its front
        for (int s = 0; s < 64 && budget > 0; s++) {
            int i = ci0 + r.nextInt(2 * R) - R, j = cj0 + r.nextInt(2 * R) - R; if (i < 1 || j < 1 || i >= N || j >= N) continue; int c = i + N * j; if (!f.seaCol[c] || Surf.blend(f.rho[c]) <= 0) continue;
            double[] b = f.corner(i, j, clk, h.seedX0 + i, h.seedY0 + j); if (b == null || b[4] < 0.5) continue;
            double[] gr = f.grad(i, j); double gl = Math.hypot(gr[0], gr[1]); if (gl <= 0) continue; double dx = gr[0] / gl, dz = gr[1] / gl, sp = b[5] / 20.0;
            double x = h.ox + i, z = h.oz + j, y = h.seaTop + Math.max(0, b[0]) + 0.05;
            for (int k = 0; k < 2 && budget > 0; k++) { l.addParticle(TidesParticles.DROP.get(), x + r.nextDouble() - 0.5, y, z + r.nextDouble() - 0.5, dx * sp * 0.6, 0.05 + 0.08 * r.nextDouble(), dz * sp * 0.6); budget--; particles++; }
            if (budget > 0 && r.nextInt(3) == 0) { l.addParticle(TidesParticles.MIST.get(), x, y + 0.2, z, dx * 0.03, 0.01, dz * 0.03); budget--; particles++; }
            boreSprays++;
        }
    }
    public static String probe() { return "surf spray: wall jets " + jets + ", bore sprays " + boreSprays + ", particles " + particles; }
}
