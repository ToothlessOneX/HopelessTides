package com.hopeless.tides.core;

/**
 * Exposure and depth of the water round a spot, read from the blocks. Pure Java (the world is reached through Probe).
 *
 * Both are worked out on a grid of nodes every STEP blocks for one water surface level (the y of the top water block), then blended
 * to any corner or point. The renderer and the physics both take them at block corners and blend inside the block, so what is drawn
 * and what moves boats and swimmers is the same surface.
 */
public final class SeaInfo {
    private SeaInfo() {}
    public interface Probe { boolean water(int x, int y, int z); }
    public static final int STEP = 4, MAX = 15;
    static final int[] RADII = {3, 7, 13, 22};
    static final int ANGLES = 12;
    static final double[][] DIRS = new double[ANGLES][2];
    static { for (int a = 0; a < ANGLES; a++) { double t = a * 2 * Math.PI / ANGLES + 0.13; DIRS[a][0] = Math.cos(t); DIRS[a][1] = Math.sin(t); } }

    /** Is (x, y, z) open water surface: water here and not water above? */
    public static boolean surface(Probe p, int x, int y, int z) { return p.water(x, y, z) && !p.water(x, y + 1, z); }

    /** Exposure 0..15 at a node: the share of open surface at the same level round it, far rings counting most (fetch). */
    public static double exposureNode(Probe p, int x, int y, int z) {
        double sum = 0, wsum = 0;
        for (int r = 0; r < RADII.length; r++) {
            double w = 1.0 + r, hits = 0;
            for (int a = 0; a < ANGLES; a++) { int sx = x + (int) Math.round(DIRS[a][0] * RADII[r]), sz = z + (int) Math.round(DIRS[a][1] * RADII[r]); if (surface(p, sx, y, sz)) hits++; }
            sum += w * hits / ANGLES; wsum += w;
        }
        return Math.min(MAX, MAX * 1.8 * sum / wsum);   // open water on one side (a beach facing the sea) counts as fully exposed
    }
    /** Depth 0..15: water blocks from the surface down. */
    public static double depthNode(Probe p, int x, int y, int z) {
        if (!p.water(x, y, z)) return 0;
        int d = 1; while (d < MAX && p.water(x, y - d, z)) d++;
        return d;
    }

    /** Node values (exposure, depth), cached by the caller if it likes. */
    public interface Nodes { double[] at(int nx, int nz, int y); }
    public static Nodes direct(Probe p) { return (nx, nz, y) -> new double[]{exposureNode(p, nx * STEP, y, nz * STEP), depthNode(p, nx * STEP, y, nz * STEP)}; }

    /** {exposure, depth} at a block corner (integer x, z) for surface level y, blended from the four nodes round it and rounded to 0..15. */
    public static int[] corner(Nodes nodes, int x, int z, int y) { int c = cornerPacked(nodes, x, z, y); return new int[]{c >> 4, c & 15}; }
    /** The same as corner, packed as exposure * 16 + depth (no allocation). */
    public static int cornerPacked(Nodes nodes, int x, int z, int y) {
        int nx = Math.floorDiv(x, STEP), nz = Math.floorDiv(z, STEP); double fx = (x - nx * STEP) / (double) STEP, fz = (z - nz * STEP) / (double) STEP;
        double[] a = nodes.at(nx, nz, y), b = nodes.at(nx + 1, nz, y), c = nodes.at(nx, nz + 1, y), d = nodes.at(nx + 1, nz + 1, y);
        double e = (a[0] * (1 - fx) + b[0] * fx) * (1 - fz) + (c[0] * (1 - fx) + d[0] * fx) * fz;
        double dp = (a[1] * (1 - fx) + b[1] * fx) * (1 - fz) + (c[1] * (1 - fx) + d[1] * fx) * fz;
        return ((int) Math.round(Math.min(MAX, Math.max(0, e))) << 4) | (int) Math.round(Math.min(MAX, Math.max(0, dp)));
    }
    /** Packs a corner's exposure and depth into one byte (exposure high nibble), as the renderer stores it in the vertex. */
    public static int pack(int[] ed) { return (ed[0] << 4) | ed[1]; }
}
