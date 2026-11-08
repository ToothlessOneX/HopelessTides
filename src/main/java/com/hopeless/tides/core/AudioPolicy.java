package com.hopeless.tides.core;

/**
 * 0.8.0: the water's sound policy. Ported from Hopeless Hydro 0.10.0 AudioModel.cs (Revale's water audio, Hopeless Water visual8/9):
 * crossfaded beds and one-shot wading steps, with these changes for Minecraft:
 *  - no under-water bed and no entry/exit splashes: Minecraft plays its own (ambient.underwater.*, entity.player.splash);
 *  - no jetpack skim;
 *  - the open-water and shore beds follow the WAVE HEIGHT (Star Citizen's ocean audio has a wave-height input it leaves empty;
 *    Hydro's beds are fixed). wave = 0.55 + 0.9 x sea state (0..1): a calm sea 0.64, rain 1.0, a storm 1.45 (CHOSEN).
 * Pure Java: the client feeds it, tests run it offline.
 */
public final class AudioPolicy {
    public float ocean, shore; public boolean step; double stride;
    public static float clamp(double x) { return (float) Math.max(0, Math.min(1, x)); }
    public static double wave(double seaState) { return 0.55 + 0.9 * Math.max(0, Math.min(1, seaState)); }
    /**
     * dt seconds (clamped to 0.1); submerged 0..1 (eye under water: beds fade); nearWater, nearShore 0..1 from the probes (scan);
     * seaState 0..1; feetDepth m (water above the feet, negative = dry); horizontal speed m/s; grounded.
     */
    public void tick(double dt, double submerged, double nearWater, double nearShore, double seaState, double feetDepth, double speed, boolean grounded) {
        dt = Math.max(0, Math.min(0.1, dt)); step = false;
        float blend = clamp(1 - Math.exp(-dt * 4));
        double w = wave(seaState), sub = clamp(submerged);
        ocean += (float) ((nearWater * (1 - sub) * 0.30 * w) - ocean) * blend;   // Revale's gains 0.30 / 0.65, x the wave height (Tides)
        shore += (float) ((nearShore * (1 - sub) * 0.65 * w) - shore) * blend;
        if (grounded && feetDepth > 0.025 && feetDepth < 1.1 && speed > 0.25) { stride += speed * dt; if (stride >= 1.25) { step = true; stride %= 1.25; } }
        else stride = 0;
    }
    /** Revale's summed-gain budget: beds and steps share a total of 1. Returns the scale for all of them. */
    public static float budget(float sum) { return sum > 1 ? 1 / sum : 1; }
    /**
     * Revale's probe gain for one probe (Hydro WaterAudio.Scan): (1 - radius/45)(1 - height over the water/45); 0 past 45 m.
     */
    public static float probeGain(double radius, double altitude) {
        if (Double.isNaN(altitude) || altitude > 45) return 0;
        return (float) ((1 - radius / 45) * (1 - altitude / 45));
    }
    /** The rings Hydro probes: 8 directions x 10, 20, 30 m. */
    public static final double[] RADII = {10, 20, 30};
    public static final int DIRS = 8;
}
