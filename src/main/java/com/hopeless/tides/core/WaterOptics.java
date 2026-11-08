package com.hopeless.tides.core;

/**
 * 0.15.0: WATER COLOUR AND CLARITY BY TYPE AND PLACE, carried over from Hopeless Water / Hydro (worlds/WaterOptics.cs, 2026-10-31; owner:
 * "the color shouldn't be a catch all it should be dependent on the type of water and the location", "natural, but not blindingly dark").
 * Pure Java (tests and previews run it offline).
 *
 * Hue: the Forel-Ule class of natural water; its chromaticity from Novoa, Wernand & van der Woerd 2013, "The Forel-Ule scale revisited
 * spectrally", J. Eur. Opt. Soc. 8, 13057, Table 5 (NWV 2013 x, y), turned into linear sRGB here (XYZ at Y = 1 -> sRGB primaries,
 * scaled to a largest channel of 1; every class used is inside the sRGB gamut).
 * Clarity: the MEDIAN Kd(490) and Secchi depth of each FU class, Pitarch, van der Woerd, Brewin & Zielinski 2019, Remote Sens. Environ.
 * 231, 111249 (Tables A2, A3). Glacial: Secchi 2 m UNSOURCED (inside the published spread), Kd = 0.93 / Secchi (Koenings & Edmundson
 * 1991, Limnol. Oceanogr. 36, 91).
 * Sight under water: black target y = 4.8 / c (Zaneveld & Pegau 2003), c = 7.5 / Secchi - Kd (Aas et al. 2014, Ocean Sci. 10, 177).
 * GAMEPLAY (the owner's choices, as in Hopeless Water): the played sight is 500 m in every type ("I like the water visibility at 500",
 * then "500 m for every type"); '/tides sight records' plays the published sights. The types still differ by colour and by how fast the
 * bed fades (Kd). Above water no type's colour is darker than luminance 0.25 (a brown lake reads as brown water, not a black hole).
 */
public final class WaterOptics {
    private WaterOptics() {}
    public enum Type { WARM_OPEN, COOL_OPEN, COASTAL, SAND_SHALLOW, MOUNTAIN, GLACIAL, EUTROPHIC, HUMIC, RIVER }
    public static final int[] FU = {2, 5, 8, 4, 4, 5, 11, 16, 14};
    public static final double[] KD = {0.037, 0.099, 0.372, 0.069, 0.069, 0.465, 0.781, 2.061, 1.345};
    public static final double[] SECCHI = {25.12, 9.95, 3.32, 13.63, 13.63, 2.0, 1.66, 0.73, 1.01};
    /** Novoa et al. 2013 Table 5 (NWV 2013) chromaticity x, y for FU 1..21. */
    public static final double[][] FU_XY = {{0.191, 0.167}, {0.199, 0.200}, {0.210, 0.240}, {0.227, 0.288}, {0.246, 0.335}, {0.266, 0.376}, {0.291, 0.412},
        {0.315, 0.440}, {0.337, 0.462}, {0.363, 0.476}, {0.386, 0.487}, {0.402, 0.481}, {0.416, 0.474}, {0.431, 0.466}, {0.446, 0.458}, {0.461, 0.449},
        {0.475, 0.441}, {0.489, 0.433}, {0.503, 0.425}, {0.516, 0.416}, {0.528, 0.408}};
    public static final double SIGHT_K = 4.8, SECCHI_GAMMA = 7.5, GAME_SIGHT = 500, MIN_LUM = 0.25;
    public static volatile boolean useRecords;

    /** Linear sRGB of FU class fu, largest channel 1. */
    public static double[] hue(int fu) {
        double x = FU_XY[fu - 1][0], y = FU_XY[fu - 1][1], X = x / y, Y = 1, Z = (1 - x - y) / y;
        double r = 3.2406 * X - 1.5372 * Y - 0.4986 * Z, g = -0.9689 * X + 1.8758 * Y + 0.0415 * Z, b = 0.0557 * X - 0.2040 * Y + 1.0570 * Z;
        double m = Math.max(r, Math.max(g, b)); return new double[]{Math.max(0, r) / m, Math.max(0, g) / m, Math.max(0, b) / m};
    }
    public static double[] hue(Type t) { return hue(FU[t.ordinal()]); }
    public static double lum(double[] c) { return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]; }
    /** The published sight (m) for a type. */
    public static double naturalSight(Type t) { double c = Math.max(0.01, SECCHI_GAMMA / SECCHI[t.ordinal()] - KD[t.ordinal()]); return SIGHT_K / c; }
    /** The played sight (m): 500 m (owner) or the published one ('/tides sight records'). */
    public static double playedSight(Type t) { return useRecords ? naturalSight(t) : GAME_SIGHT; }

    /** Which water a place holds (Hopeless Water's rules; Minecraft has no lake area or inflow, so those tests are dropped and a
     *  swampy biome counts as nutrient-rich). meanC: the water's annual mean; altitude: m above the sea. */
    public static Type classify(boolean river, boolean sea, boolean swamp, double meanC, double altitude) {
        if (river) return Type.RIVER;
        if (sea) return meanC >= 20 ? Type.WARM_OPEN : Type.COOL_OPEN;
        if (swamp) return Type.EUTROPHIC;
        if (meanC < 0 && altitude > 800) return Type.GLACIAL;          // high and freezing: meltwater (no inflow data in Minecraft)
        if (altitude > 1200 || meanC < 2) return Type.MOUNTAIN;
        if (meanC >= 16 && altitude < 600) return Type.EUTROPHIC;
        if (meanC < 10 && altitude < 500) return Type.HUMIC;           // cool lowland: forest water (lakes here are all small)
        return Type.MOUNTAIN;
    }
    /** The sea by its depth: a warm clear sea shades to sand turquoise from 30 m to 5 m, a cool sea to coastal green from 60 m to 20 m
     *  (Hopeless Water SeaAt, depths CHOSEN). {near type ordinal, blend 0..1}. */
    public static double[] seaAt(Type offshore, double depth) {
        if (offshore == Type.WARM_OPEN) return new double[]{Type.SAND_SHALLOW.ordinal(), clamp((30 - depth) / 25)};
        if (offshore == Type.COOL_OPEN) return new double[]{Type.COASTAL.ordinal(), clamp((60 - depth) / 40)};
        return new double[]{offshore.ordinal(), 0};
    }
    /** The body colour (linear, luminance at least MIN_LUM) and Kd of a place: {r, g, b, kd}. */
    public static double[] look(Type t, double depth) {
        double[] s = seaAt(t, depth); Type n = Type.values()[(int) s[0]]; double f = s[1];
        double[] a = hue(t), b = hue(n); double[] c = {a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f};
        double l = lum(c); if (l < MIN_LUM) { double k = MIN_LUM / l; c[0] *= k; c[1] *= k; c[2] *= k; }
        double kd = KD[t.ordinal()] + (KD[n.ordinal()] - KD[t.ordinal()]) * f;
        return new double[]{c[0], c[1], c[2], kd};
    }
    static double clamp(double x) { return x < 0 ? 0 : x > 1 ? 1 : x; }
}
