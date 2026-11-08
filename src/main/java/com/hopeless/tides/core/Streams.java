package com.hopeless.tides.core;

/**
 * Streams that come and go with the weather (0.3.0). Pure Java: the game, the previews and the tests all run this code.
 *
 * Rain on the land soaks in (the diagram: precipitation, infiltration, percolation, groundwater):
 *   soil    a fast store: fills while it rains, drains in a few hours. Gullies (ephemeral streams) run while it is high.
 *   ground  groundwater: fed slowly by the soil, drains over days. Creeks (intermittent streams) run while it is high.
 * Rivers (perennial streams) always run; after heavy rain on wet ground they rise to bank-full and then over the floodplain.
 *
 * In Hopeless Deep the rain falls on the sealed surface far above and seeps down to the caves, so the world's own weather drives it.
 * Water level in a creek or gully is LEVEL across the channel (it comes from the stream's full level, not from each column's bed):
 * as a stream dries it shrinks into its deepest pools first, as real streams do.
 */
public final class Streams {
    private Streams() {}
    // kinds (the same numbers as Hopeless Deep's Deep.ST_*)
    public static final int NONE = 0, PERENNIAL = 1, INTERMITTENT = 2, EPHEMERAL = 3, FLOODPLAIN = 4, BANK = 5, LEVEE = 6;
    public static final String[] NAMES = {"none", "perennial", "intermittent", "ephemeral", "floodplain", "bank", "levee"};

    public static final double SOIL_TICKS = 4000, GROUND_TICKS = 120000;   // how fast the soil and the groundwater follow their input
    /** The two stores, 0..about 1.5. */
    public static final class Hydro {
        public double soil, ground;
        /** Advance by dt ticks with the world's rain (0..1) and thunder (0..1) levels. Exact for a constant input over dt. */
        public void step(double dt, double rain, double thunder) {
            double in = rain * (1.0 + 0.5 * thunder);
            double a = Math.exp(-dt / SOIL_TICKS), s0 = soil;
            soil = in + (s0 - in) * a;
            // the ground follows the soil; integrate the soil's path over the step (a first-order store feeding another)
            double b = Math.exp(-dt / GROUND_TICKS), k = SOIL_TICKS / (SOIL_TICKS - GROUND_TICKS);
            ground = in + (ground - in) * b + (s0 - in) * k * (a - b);
        }
        /** How full gullies run, 0..1. */
        public double ephemeral() { return clamp((soil - 0.25) / 0.5); }
        /** How full creeks run, 0..1. */
        public double intermittent() { return clamp((ground - 0.145) / 0.12); }
        /** River stage: 0 normal, 1 bank-full, 2 over the floodplain. */
        public int flood() { return soil > 1.1 && ground > 0.22 ? 2 : soil > 0.92 && ground > 0.2 ? 1 : 0; }
    }
    static double clamp(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    /** Nominal depth of a creek and a gully below their full level (the deepest pools). */
    public static final int CREEK_DEPTH = 3, GULLY_DEPTH = 2;

    /**
     * The top water block a column should have now, or its bed (no water). bed: the top solid block; full: the top water block when
     * running full (creeks, gullies, rivers); flood1/flood2: bank-full and flood levels (river valleys). Missing levels: Integer.MIN_VALUE.
     */
    public static int target(int kind, int bed, int full, int flood1, int flood2, Hydro h) {
        int top;
        switch (kind) {
            case PERENNIAL: { int st = h.flood(); top = st == 2 ? flood2 : st == 1 ? flood1 : full; break; }
            case FLOODPLAIN: case LEVEE: top = h.flood() == 2 ? flood2 : bed; break;
            case INTERMITTENT: top = level(full, CREEK_DEPTH, h.intermittent()); break;
            case EPHEMERAL: top = level(full, GULLY_DEPTH, h.ephemeral()); break;
            default: top = bed;
        }
        if (top == Integer.MIN_VALUE) return bed;
        return Math.max(bed, top);
    }
    static int level(int full, int depth, double f) {
        if (full == Integer.MIN_VALUE || f <= 0) return Integer.MIN_VALUE;
        return full - (int) Math.round((1 - f) * depth);
    }
}
