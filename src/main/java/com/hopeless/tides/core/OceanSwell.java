package com.hopeless.tides.core;

/**
 * 0.16.1: where the swell comes from at a place. Swell is made by storms far out at sea and reaches every coast that faces open water; it
 * does not come off the land. RAYS directions from the place over a water map, STEP m at a time to REACH m; the open ones (water all the
 * way) are the ocean's sector; the swell's wandering heading picks the nearest open direction, smoothed with its open neighbours (weights
 * 3-2-1). Pure Java: TidesSurf (the game, over the world mod's map) and tools/SteerPreview (offline) run the same code.
 */
public final class OceanSwell {
    private OceanSwell() {}
    public static final int RAYS = 36; public static final double STEP = 32, REACH = 8000;
    public interface Water { int at(double x, double z); }   // 1 water, 0 land, < 0 unknown
    /** {toward rad, open directions, from rad}; null: no open ocean within REACH in any direction. 0.16.2: the swell comes from the middle
     *  of the widest open arc (straight in from the open sea, as swell meets an exposed coast) and wanders within the middle half of that arc
     *  (sin of the wandering heading: -1..1); open all round (open sea): the wandering heading itself. */
    public static double[] from(Water w, double cx, double cz, double wanderToward) {
        boolean[] open = new boolean[RAYS]; int nOpen = 0;
        for (int r = 0; r < RAYS; r++) { double a = 2 * Math.PI * r / RAYS, ca = Math.cos(a), sa = Math.sin(a); boolean o = true;
            for (double d = STEP; d <= REACH; d += STEP) if (w.at(cx + ca * d, cz + sa * d) == 0) { o = false; break; }
            open[r] = o; if (o) nOpen++; }
        if (nOpen == 0) return null;
        double step = 2 * Math.PI / RAYS, fr;
        if (nOpen == RAYS) fr = wanderToward + Math.PI;
        else { int bestStart = -1, bestLen = 0; for (int r = 0; r < RAYS; r++) { if (!open[r] || open[Math.floorMod(r - 1, RAYS)]) continue; int len = 0; while (len < RAYS && open[(r + len) % RAYS]) len++; if (len > bestLen) { bestLen = len; bestStart = r; } }
            double mid = (bestStart + (bestLen - 1) / 2.0) * step, half = (bestLen - 1) / 2.0 * step; fr = mid + Math.sin(wanderToward) * 0.5 * half; }
        fr = Math.atan2(Math.sin(fr), Math.cos(fr)); return new double[]{Math.atan2(Math.sin(fr + Math.PI), Math.cos(fr + Math.PI)), nOpen, fr};
    }
}
