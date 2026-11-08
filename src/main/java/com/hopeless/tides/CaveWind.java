package com.hopeless.tides;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 0.6.0: wind in the caves, made by the water (after the user: rushing water pressurises a cave and forces the air out). In a
 * Hopeless Deep world there is no sky and no barometric breathing through the sealed shafts, so the air moves for three reasons:
 *
 *  1. FLOOD PISTON - water that fills a cavern pushes out the same volume of air (conservation of volume: the air leaving equals the
 *     water arriving). Air speed = water volume per second / the cavern's cross-section, measured round the player with rays. In our
 *     large caverns this is slow (a flood adding 20 m3/s to a 60 x 40 m section moves the air at 0.008 m/s); in a narrow passage it
 *     is fast - the reason real caves roar at their constrictions (Cueva del Nacimiento, Picos de Europa: 5.4-7.0 m/s measured at the
 *     Hole in the Wall; Wind Cave SD, a barometric cave: over 70 mph at its small entrance).
 *  2. FALLING WATER - a waterfall drags air down with it and the downdraft spreads out from the plunge pool. Water falling h metres
 *     lands at sqrt(2 g h); the spreading downdraft is taken as a quarter of that at 4 m from the fall, falling off as 4 / (4 + r).
 *     (Estimate: no measured cave numbers were found; it gives 3-5 m/s near a 10-20 m fall, a strong breeze.)
 *  3. MOVING WATER - a stream drags the air just above it along. Taken as 4% of the surface speed over a stream, 12% over rapids
 *     (spray and broken water), averaged over the ground round the player, so it is the share of moving water that counts. (Estimate, as 2.)
 * Each source counts with weight exp(-r / 12) by its horizontal distance and only within 24 blocks up or down. The sum is the wind
 * where the player is; it sets the cave sea state used by the drawn waves and the physics, and the ripple strength on the water.
 * Waves from a wind (fetch-limited growth, US Army Shore Protection Manual 1984): Hs = 1.6e-3 U sqrt(F / g). Cave winds make small
 * waves - ripples you see, not swell - which is what real cave lakes do.
 */
public final class CaveWind {
    private CaveWind() {}
    public static volatile long runs, samples, sent;   // probes
    public static final Map<UUID, double[]> LAST = new ConcurrentHashMap<>();   // player -> {U, dirRad, flood, falls, streams, section, x, y, z}
    static long lastNet = Long.MIN_VALUE; static long lastTick;

    /** Every 40 ticks: the wind at each player in a cave, sent to them. */
    static void tick(ServerLevel l) {
        if (l.getGameTime() % 40 != 0) return;
        long wNet = StreamWorks.placed - StreamWorks.removed; double q = 0;
        if (lastNet != Long.MIN_VALUE) q = Math.max(0, wNet - lastNet) / 2.0;            // m3 of stream water added per second (rising floods)
        lastNet = wNet;
        int players = Math.max(1, l.players().size());
        for (ServerPlayer p : l.players()) {
            BlockPos at = p.blockPosition();
            boolean sky = l.canSeeSky(at.above()) || l.getBrightness(net.minecraft.world.level.LightLayer.SKY, at) >= 8;
            if (sky) { LAST.remove(p.getUUID()); TidesNet.wind(p, -1, 0); continue; }   // open sky: the weather's wind
            double[] w = at(l, p.getX(), p.getY(), p.getZ(), q / players);
            LAST.put(p.getUUID(), w); TidesNet.wind(p, (float) w[0], (float) w[1]); sent++;
        }
        runs++;
    }

    /** {U m/s, direction rad (the way the air goes, same convention as WaveModel.wind), flood m/s, falls m/s, streams m/s, section m2, x, y, z}. */
    public static double[] at(ServerLevel l, double px, double py, double pz, double floodQ) {
        double vx = 0, vz = 0, falls = 0, streams = 0, sx = 0, sz = 0, wsum = 0; int R = 40, S = 3;
        for (int dz = -R; dz <= R; dz += S) for (int dx = -R; dx <= R; dx += S) {
            int x = (int) Math.floor(px) + dx, z = (int) Math.floor(pz) + dz; double r = Math.hypot(dx, dz); if (r > R) continue;
            double wgt = Math.exp(-r / 12.0); wsum += wgt;                                   // every column counts in the stream drag average, dry or wet
            float[] f = Currents.at(l, x, z); samples++; if (f == null) continue;
            int stage = (int) f[3]; double spd = f[2];
            if (stage == 7) {                                                                // waterfall: a downdraft spreading from it
                double h = fallHeight(l, x, z, py), vj = Math.sqrt(2 * 9.81 * h), u = 0.25 * vj * 4.0 / (4.0 + r) * wgt;
                double ox = px - (x + 0.5), oz = pz - (z + 0.5), ol = Math.max(0.5, Math.hypot(ox, oz)); vx += u * ox / ol; vz += u * oz / ol; falls += u;
            } else {
                double k = stage == 5 ? 0.12 : 0.04, u = k * spd * wgt; sx += u * f[0]; sz += u * f[1];
            }
        }
        // stream drag: the weighted mean over the area round the player (a wide river drags the air above it, not the sum of its parts)
        if (wsum > 0) { sx /= wsum; sz /= wsum; } streams = Math.hypot(sx, sz); vx += sx; vz += sz;
        // flood piston through the cavern section round the player
        double[] sec = section(l, px, py, pz); double flood = floodQ > 0 ? floodQ / Math.max(4, sec[0]) : 0;
        vx += flood * Math.cos(sec[1]); vz += flood * Math.sin(sec[1]);
        double U = Math.hypot(vx, vz), dir = Math.atan2(vz, vx);
        return new double[]{U, dir, flood, falls, streams, sec[0], px, py, pz};
    }
    /** A waterfall's drop: the falling water stacked under the first water found from 32 above the player down to 48 below (6 when none is found). */
    static double fallHeight(ServerLevel l, int x, int z, double py) {
        for (int y0 = (int) py + 32; y0 > (int) py - 48; y0--) { BlockPos b = new BlockPos(x, y0, z);
            if (!l.getFluidState(b).isEmpty()) { int d = 0; while (d < 32 && !l.getFluidState(b.below(d + 1)).isEmpty()) d++; return Math.max(2, Math.min(32, d)); } }
        return 6;
    }
    /** The cavern's cross-section (m2) round a point and the direction of its longer axis (rad): rays to the walls (to 96). */
    static double[] section(ServerLevel l, double px, double py, double pz) {
        double[] d = new double[4]; int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int i = 0; i < 4; i++) { int k = 1; while (k < 96 && !solid(l, px + dirs[i][0] * k, py + 1, pz + dirs[i][1] * k)) k++; d[i] = k; }
        int up = 1; while (up < 96 && !solid(l, px, py + 1 + up, pz)) up++;
        int dn = 0; while (dn < 96 && !solid(l, px, py - dn - 0.1, pz)) dn++;
        double wx = d[0] + d[1], wz = d[2] + d[3], height = up + dn;
        // the air leaves along the passage (the longer axis), toward its farther end
        double axis = wx >= wz ? (d[0] >= d[1] ? 0 : Math.PI) : (d[2] >= d[3] ? Math.PI / 2 : -Math.PI / 2);
        return new double[]{Math.min(wx, wz) * height, axis};
    }
    static boolean solid(ServerLevel l, double x, double y, double z) {
        BlockPos b = BlockPos.containing(x, y, z); if (!l.isLoaded(b)) return true;
        var s = l.getBlockState(b); return !s.isAir() && s.getFluidState().isEmpty() && s.isSolid();
    }
    /** The cave wind nearest a point among the players' last readings (within 64), or null. */
    public static double[] near(double x, double y, double z) {
        double[] best = null; double bd = 64 * 64;
        for (double[] w : LAST.values()) { double d = (w[6] - x) * (w[6] - x) + (w[8] - z) * (w[8] - z); if (d < bd && Math.abs(w[7] - y) < 48) { bd = d; best = w; } }
        return best;
    }
    public static String describe(double[] w) {
        return w == null ? "open sky (weather wind)" : String.format("wind %.2f m/s toward %.0f deg (falls %.2f, streams %.2f, flood piston %.3f through %.0f m2) -> cave sea state %.3f, ripples %.2f",
            w[0], Math.toDegrees(w[1]), w[3], w[4], w[2], w[5], com.hopeless.tides.core.WaveModel.caveStateFromWind(w[0]), com.hopeless.tides.core.WaveModel.rippleFromWind(w[0]));
    }
    public static String probe() { return "cave wind: runs " + runs + " samples " + samples + " sent " + sent + " players with a reading " + LAST.size(); }
}
