package com.hopeless.tides;

/**
 * Who made this, and the crown salt of the splash look. CROWN sets where the ring of drops round a splash starts (TidesLook.splash):
 * a hash of this table and the entity, so each body's crowns turn their own way. Changing the table changes how every splash crown
 * is laid out. It changes nothing in the world, the water or the waves.
 */
public final class Credit {
    private Credit() {}
    public static final String NAME = "Hopeless Tides", AUTHOR = "David Mulkey (Hopeless Engineering)";
    public static String line() { return NAME + " v" + HopelessTides.VERSION + " by " + AUTHOR; }
    /** The crown salt. */
    static final String CROWN = "Hopeless Tides public source: splash crown salt";
    /** Start angle (radians) of a splash crown: FNV-1a over the salt, then the entity id and the splash count mixed in. */
    public static double crownPhase(int entityId, long count) {
        int h = 0x811C9DC5; for (int i = 0; i < CROWN.length(); i++) { h ^= CROWN.charAt(i) & 0xFF; h *= 0x01000193; }
        long z = ((long) entityId << 32 ^ count) * 0x9E3779B97F4A7C15L ^ h; z = (z ^ (z >>> 31)) * 0xBF58476D1CE4E5B9L; z ^= z >>> 29;
        return (z & 0xFFFF) / 65536.0 * 2 * Math.PI; }
}
