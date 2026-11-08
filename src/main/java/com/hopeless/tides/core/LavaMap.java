package com.hopeless.tides.core;

/**
 * 0.12.0: the lava map's texels from a square of columns (pure: the client fills the inputs, tools/LavaDump the harness's).
 * Per column, RGBA bytes: r = blocks from the lava's edge (0..8, x 31; the crust is older and colder there: lava cools where it
 * touches ground), g = the lava surface block's y (mod 256), b = how near lava is for ground that is not lava (255 next to it ->
 * 0 at 6 blocks: the warm light on the shore), a = 255 where the column has lava at the top.
 */
public final class LavaMap {
    private LavaMap() {}
    public static final int EDGE_MAX = 8, NEAR_MAX = 6;
    public static byte[] texels(boolean[] lava, int[] surfY, int n) {
        int[] edge = distance(lava, n, true), near = distance(lava, n, false);
        byte[] out = new byte[n * n * 4];
        for (int i = 0; i < n * n; i++) {
            out[i * 4] = (byte) (lava[i] ? Math.min(255, Math.min(EDGE_MAX, edge[i]) * 31) : 0);
            out[i * 4 + 1] = (byte) (surfY[i] & 255);
            out[i * 4 + 2] = (byte) (lava[i] ? 0 : Math.max(0, 255 - (Math.max(1, near[i]) - 1) * 255 / NEAR_MAX));
            out[i * 4 + 3] = (byte) (lava[i] ? 255 : 0);
        }
        return out;
    }
    /** Chessboard-free 4-neighbour BFS: inside = true gives each lava cell its steps to the nearest non-lava cell; false gives each
     *  other cell its steps to the nearest lava cell. Capped at 32. */
    static int[] distance(boolean[] lava, int n, boolean inside) {
        int[] d = new int[n * n]; java.util.Arrays.fill(d, 32); int[] q = new int[n * n]; int h = 0, t = 0;
        for (int i = 0; i < n * n; i++) if (lava[i] != inside) { d[i] = 0; q[t++] = i; }
        while (h < t) { int i = q[h++], x = i % n, z = i / n, nd = d[i] + 1; if (nd > 32) continue;
            int[] nb = {x > 0 ? i - 1 : -1, x < n - 1 ? i + 1 : -1, z > 0 ? i - n : -1, z < n - 1 ? i + n : -1};
            for (int j : nb) if (j >= 0 && d[j] > nd) { d[j] = nd; q[t++] = j; } }
        return d;
    }
}
