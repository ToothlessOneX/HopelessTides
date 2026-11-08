package com.hopeless.tides;

import com.hopeless.tides.core.Basin;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 0.7.0: Hopeless Hydro's column water in Minecraft (the owner chose: "do 1 and 2").
 * 1. The simulation writes blocks. Round each player a REGION of N x N columns runs Hydro's water core (core.Basin, ported bit-exact).
 *    Each column's water is read from the world: the bed is the solid block under the open space at the player's level, the water is
 *    the still or flowing (not falling) water stacked on it, a source block = 1 m, a flowing block of level k = k/8 m. The core moves
 *    it; a column's blocks are rewritten only when its depth changes by a whole eighth (hysteresis 0.7 of an eighth), at most
 *    WRITE_CAP block changes a tick, biggest change first. Inside a region vanilla's water spreading is off for the columns the
 *    simulation holds (FlowingFluidMixin); everything else is vanilla.
 *    - The region's rim holds its read level (water flows in at an upstream edge and out at a downstream one), so a river runs
 *      through a region and a lake at rest stays at rest; only what changes inside (a dug bank, a bucket, a broken dam) moves.
 *    - Water the world adds or takes (buckets, vanilla water flowing in from outside, Hopeless Deep's streams) is found by re-reading
 *      columns (all of them in turn, and at once where a block changed) and goes in through Basin.change, so the ledger stays exact.
 *    - Falling water, water over something not solid, and columns with no open space are left to vanilla (not "known").
 *    - When a region is dropped, a partial top of 4/8 or more becomes a source and less is removed, so vanilla does not drain it.
 * 2. Its speeds drive the rest: the river currents that push swimmers, items and boats (Currents.at asks here first), and the
 *    river look in the shader (sent to the clients as a speed and direction per column, TidesNet.Flow).
 */
public final class HydroSim {
    private HydroSim() {}
    public static volatile boolean enabled = true;
    public static int N = 80, RIM = 1, WRITE_CAP = 600, READS_PER_TICK = 500, MAX_SUB = 8;
    public static double BUDGET_MS = 12, FILM = 0.8, MAX_SPEED = 12;
    public static boolean NATURAL_ONLY = true; public static int INIT_READS = 1600;
    public static volatile long notNatural, farmSkips, noRegionFast, noRegionDry;
    // probes
    public static volatile long steps, skipped, writes, writesDeferred, reads, externalPours, externalDrains, rimIn, rimOut, regionsMade, regionsDropped, vanillaTicksStopped, finalSources, finalRemoved;
    public static volatile long springs, springIn;
    public static volatile double lastMs, maxMs;

    static final List<Region> REGIONS = new ArrayList<>();
    static boolean writing;

    public static final class Region {
        final ServerLevel level; final int ox, oz, refY; final Basin b;
        final int[] bedY, ceilY, written; final boolean[] mine, spring; final long[] rimTicks;
        int rr, initRead; boolean ready; long born; double owedDt; final ArrayList<Integer> dirtyCols = new ArrayList<>();
        Region(ServerLevel l, int ox, int oz, int refY) {
            level = l; this.ox = ox; this.oz = oz; this.refY = refY; b = new Basin(N, 1.0); b.G = Tides.gravity(l);   // 0.13.0: the planet's gravity (shallow-water wave speed sqrt(g h))
            int c = N * N; bedY = new int[c]; ceilY = new int[c]; written = new int[c]; mine = new boolean[c]; spring = new boolean[c]; rimTicks = new long[c];
            Arrays.fill(written, -1);
        }
        boolean contains(int x, int z) { return x >= ox && z >= oz && x < ox + N && z < oz + N; }
        int col(int x, int z) { return (x - ox) + N * (z - oz); }
        boolean rim(int i, int j) { return i < RIM || j < RIM || i >= N - RIM || j >= N - RIM; }
    }

    // ------------------------------------------------------------------ reading a column
    static boolean open(BlockState s) { return s.isAir() || (s.getBlock() instanceof LiquidBlock && s.getFluidState().is(FluidTags.WATER)); }
    /** {bedY, ceilY, eighths of water} for a column at the region's level, or null when the simulation must not hold it. */
    static int[] readColumn(ServerLevel l, int x, int z, int refY) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, refY + 6, z);
        if (!l.hasChunkAt(m)) return null;
        int y = refY + 6, lo = Math.max(l.getMinBuildHeight(), refY - 48);
        while (y > lo && !open(l.getBlockState(m.setY(y)))) y--;                 // out of a ceiling
        if (y <= lo) return null;
        while (y > lo && open(l.getBlockState(m.setY(y)))) y--;                  // down through air and water to the bed
        if (y <= lo) return null;
        BlockState bed = l.getBlockState(m.setY(y));
        if (!bed.isSolidRender(l, m) && !bed.isFaceSturdy(l, m, net.minecraft.core.Direction.UP)) return null;
        int bedY = y, top = bedY + 1, eighths = 0; boolean partial = false;
        for (int k = top; k < top + 64; k++) {
            BlockState s = l.getBlockState(m.setY(k)); FluidState f = s.getFluidState();
            if (!(s.getBlock() instanceof LiquidBlock) || !f.is(FluidTags.WATER)) break;
            if (partial) return null;                                             // water over a partial block: not a column
            if (f.isSource()) eighths += 8;
            else { if (f.getValue(FlowingFluid.FALLING)) return null; eighths += f.getAmount(); partial = true; }
        }
        // 0.15.1: the column water is the RIVER model (Hydro's rivers and streams); seas and lakes are the wave model's (Tides' surface:
        // FFT sea, surf, tide), as in Hopeless Hydro and Water (owner: Hydro handles rivers and streams, Water the sea and all lakes).
        // A body of still water is left alone: source water outside a river biome whose surface reaches water 3 or more blocks deep
        // within 8 blocks at the same level (a lake, the sea, a cave sea). Streams, flowing water and river biomes stay simulated.
        if (!partial && eighths > 0 && bodyOfWater(l, x, z, top + eighths / 8 - 1)) { bodies++; return null; }
        int ceil = top; while (ceil < top + 64 && open(l.getBlockState(m.setY(ceil)))) ceil++;
        if (ceil - top < 1) return null;
        if (NATURAL_ONLY) {   // 0.7.1: only natural water - the bed natural ground, no built block or farmland beside the water (or beside the bed's top if dry)
            if (!natural(bed)) { notNatural++; return null; }
            int levels = Math.max(1, (eighths + 7) / 8);
            BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
            for (int k = top - 1; k < top + levels; k++) for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                n.set(x + d.getStepX(), k, z + d.getStepZ()); BlockState s = l.getBlockState(n);
                if (s.getBlock() instanceof net.minecraft.world.level.block.FarmBlock) { farmSkips++; return null; }
                if (k >= top && s.isSolidRender(l, n) && !natural(s)) { notNatural++; return null; }
            }
        }
        return new int[]{bedY, ceil, eighths};
    }

    public static volatile long bodies;
    static boolean bodyOfWater(ServerLevel l, int x, int z, int topY) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, topY, z);
        if (l.getBiome(m).is(net.minecraft.tags.BiomeTags.IS_RIVER)) return false;
        for (int r : new int[]{0, 4, 8}) for (int d = 0; d < (r == 0 ? 1 : 8); d++) {
            int dx = r == 0 ? 0 : (int) Math.round(r * Math.cos(d * Math.PI / 4)), dz = r == 0 ? 0 : (int) Math.round(r * Math.sin(d * Math.PI / 4));
            int xx = x + dx, zz = z + dz; if (!l.hasChunk(xx >> 4, zz >> 4)) continue;
            int surf = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, xx, zz) - 1, floor = l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, xx, zz);
            if (surf == topY && surf - floor + 1 >= 3 && l.getFluidState(m.set(xx, surf, zz)).isSource()) return true;
        }
        return false;
    }
    /** Natural ground: what a real lake, river or sea bed is made of (tags, so other mods' dirt, sand and stone count too). */
    static boolean natural(BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.DIRT) || s.is(net.minecraft.tags.BlockTags.SAND) || s.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)
            || s.is(net.minecraft.tags.BlockTags.BASE_STONE_NETHER) || s.is(net.minecraftforge.common.Tags.Blocks.GRAVEL) || s.is(net.minecraftforge.common.Tags.Blocks.STONE)
            || s.is(net.minecraftforge.common.Tags.Blocks.SANDSTONE) || s.is(net.minecraftforge.common.Tags.Blocks.ORES) || s.is(net.minecraft.tags.BlockTags.TERRACOTTA)
            || s.is(net.minecraft.tags.BlockTags.CORAL_BLOCKS) || s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.ICE) || s.is(net.minecraft.tags.BlockTags.SNOW)
            || s.is(Blocks.CLAY) || s.is(Blocks.MUD) || s.is(Blocks.PACKED_MUD) || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.CALCITE) || s.is(Blocks.TUFF) || s.is(Blocks.DRIPSTONE_BLOCK)
            || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.SOUL_SAND) || s.is(Blocks.SOUL_SOIL) || s.is(Blocks.OBSIDIAN) || s.is(Blocks.SUSPICIOUS_SAND) || s.is(Blocks.SUSPICIOUS_GRAVEL);
    }
    /** Is there water near this point (a region is made only near water)? Sparse: every 4 blocks out to 24, from 8 below to 4 above. */
    static boolean nearWater(ServerLevel l, double px, double py, double pz) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(); int x0 = (int) Math.floor(px), y0 = (int) Math.floor(py), z0 = (int) Math.floor(pz);
        for (int dz = -24; dz <= 24; dz += 4) for (int dx = -24; dx <= 24; dx += 4) { m.set(x0 + dx, y0, z0 + dz); if (!l.hasChunkAt(m)) continue;
            for (int dy = -8; dy <= 4; dy += 2) if (l.getFluidState(m.setY(y0 + dy)).is(FluidTags.WATER)) return true; }
        return false;
    }

    // ------------------------------------------------------------------ regions
    /** Test hook (a dedicated server has no player): regions also follow these pinned points. */
    public static final List<double[]> PINS = new ArrayList<>();
    static Region make(ServerLevel l, double px, double py, double pz) {
        int cx = ((int) Math.floor(px) >> 4 << 4) + 8, cz = ((int) Math.floor(pz) >> 4 << 4) + 8;
        Region r = new Region(l, cx - N / 2, cz - N / 2, (int) Math.floor(py));
        for (int c = 0; c < N * N; c++) r.b.setKnown(c, false);                 // 0.7.1: read over the next ticks (INIT_READS a tick), stepped once all are read
        r.born = l.getGameTime(); regionsMade++;
        return r;
    }
    /** The first read of a region, a slice a tick; when it is done, springs are found and the region starts. */
    static void initialRead(Region r) {
        int n = N * N, end = Math.min(n, r.initRead + INIT_READS);
        for (; r.initRead < end; r.initRead++) readInto(r, r.initRead % N, r.initRead / N, true);
        if (r.initRead < n) return;
        // SPRINGS: in vanilla a source block that feeds lower flowing water never runs dry; here it holds its level, as Hydro's level
        // sources do (and as the rim does), or every stream whose head is in the region would drain away
        for (int j = 1; j < N - 1; j++) for (int i = 1; i < N - 1; i++) {
            int c = i + N * j; if (!r.mine[c] || r.written[c] <= 0 || r.written[c] % 8 != 0) continue;
            double top = r.bedY[c] + 1 + r.written[c] / 8.0;
            for (int d : new int[]{-1, 1, -N, N}) { int o = c + d; if (!r.mine[o] || r.written[o] <= 0 || r.written[o] % 8 == 0) continue;
                if (r.bedY[o] + 1 + r.written[o] / 8.0 < top) { r.spring[c] = true; r.rimTicks[c] = r.b.D[c]; springs++; break; } }
        }
        r.b.rescan(); r.ready = true;
    }
    /** Read one column into the region. first: the region's first read (water already there is its starting state). */
    static void readInto(Region r, int i, int j, boolean first) {
        int c = i + N * j; int[] v = readColumn(r.level, r.ox + i, r.oz + j, r.refY); reads++;
        Basin b = r.b;
        if (v == null) {
            if (r.mine[c]) { b.change(c, -b.D[c]); }                              // no longer ours: the water there is vanilla's again
            r.mine[c] = false; r.spring[c] = false; b.setKnown(c, false); r.written[c] = -1; return;
        }
        double bedTop = v[0] + 1;
        if (!r.mine[c] || b.Bed[c] != bedTop) b.setBed(c, bedTop);
        r.bedY[c] = v[0]; r.ceilY[c] = v[1];
        if (!b.Known[c]) b.setKnown(c, true);
        long want = Basin.ticks(v[2] / 8.0);
        if (first || !r.mine[c] || r.written[c] < 0) {
            b.change(c, want - b.D[c]);
            r.written[c] = v[2];
        } else if (v[2] != r.written[c]) {                                         // the world changed it, not us
            long d = Basin.ticks((v[2] - r.written[c]) / 8.0);
            long moved = b.change(c, d); if (moved > 0) externalPours++; else externalDrains++;
            r.written[c] = v[2];
        }
        r.mine[c] = true;
        if (r.rim(i, j) || r.spring[c]) { if (r.spring[c] && v[2] == 0) r.spring[c] = false; r.rimTicks[c] = b.D[c]; }
    }
    static void drop(Region r) {
        // a partial top is either made whole (>= 4/8) or removed, so vanilla's rules leave the water as it is
        writing = true;
        try {
            for (int c = 0; c < N * N; c++) {
                if (!r.mine[c] || r.written[c] <= 0) continue;
                int e = r.written[c], full = e / 8, rem = e % 8; if (rem == 0) continue;
                BlockPos p = new BlockPos(r.ox + c % N, r.bedY[c] + 1 + full, r.oz + c / N);
                if (rem >= 4) { r.level.setBlock(p, Blocks.WATER.defaultBlockState(), 3); finalSources++; }
                else { r.level.setBlock(p, Blocks.AIR.defaultBlockState(), 3); finalRemoved++; }
            }
        } finally { writing = false; }
        regionsDropped++;
    }

    // ------------------------------------------------------------------ the tick
    static long tickNo;
    static void tick(ServerLevel l) {
        if (!enabled) { if (!REGIONS.isEmpty()) { for (Region r : REGIONS) drop(r); REGIONS.clear(); } return; }
        tickNo++;
        // one region per group of players: a player more than 24 blocks from his region's middle gets a new one
        List<Region> keep = new ArrayList<>();
        if (!TidesConfig.dims.contains(l.dimension().location().toString()) && Tides.planet(l) == null) return;   // 0.13.0: planet dimensions (TidesPlanets) too
        List<double[]> at = new ArrayList<>();
        for (ServerPlayer p : l.players()) if (!p.isSpectator()) {
            double sp = (p.getVehicle() != null ? p.getVehicle().getDeltaMovement() : p.getDeltaMovement()).length() * 20;
            at.add(new double[]{p.getX(), p.getY(), p.getZ(), sp > MAX_SPEED || p.isFallFlying() ? 1 : 0});
        }
        if (l.dimension() == net.minecraft.world.level.Level.OVERWORLD) at.addAll(PINS);
        for (double[] p : at) {
            Region best = null;
            for (Region r : REGIONS) if (r.level == l && Math.abs(p[0] - (r.ox + N / 2.0)) < 24 && Math.abs(p[2] - (r.oz + N / 2.0)) < 24 && Math.abs(p[1] - r.refY) < 12) best = r;
            for (Region r : keep) if (best == null && Math.abs(p[0] - (r.ox + N / 2.0)) < 24 && Math.abs(p[2] - (r.oz + N / 2.0)) < 24 && Math.abs(p[1] - r.refY) < 12) best = r;
            if (best == null) {
                if (p.length > 3 && p[3] > 0) { noRegionFast++; continue; }          // 0.7.1: no new region for a player going fast (plane, vehicle, elytra)
                if (!nearWater(l, p[0], p[1], p[2])) { noRegionDry++; continue; }    // 0.7.1: and none away from water
                best = make(l, p[0], p[1], p[2]);
            }
            if (!keep.contains(best)) keep.add(best);
        }
        for (Region r : new ArrayList<>(REGIONS)) if (r.level == l && !keep.contains(r)) { drop(r); REGIONS.remove(r); }
        for (Region r : keep) if (!REGIONS.contains(r)) REGIONS.add(r);
        long t0 = System.nanoTime();
        for (Region r : REGIONS) {
            if (r.level != l) continue;
            if (!r.ready) { initialRead(r); continue; }
            // re-read: changed columns now, every column in turn
            for (Integer c : r.dirtyCols) readInto(r, c % N, c / N, false);
            r.dirtyCols.clear();
            for (int k = 0; k < READS_PER_TICK; k++) { int c = r.rr; r.rr = (r.rr + 1) % (N * N); readInto(r, c % N, c / N, false); }
            // the rim holds its level
            for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) { int c = i + N * j; if (!r.mine[c]) continue;
                if (r.rim(i, j)) { long d = r.rimTicks[c] - r.b.D[c]; if (d != 0) { r.b.change(c, d); if (d > 0) rimIn += d; else rimOut -= d; } }
                else if (r.spring[c]) { long d = r.rimTicks[c] - r.b.D[c]; if (d > 0) { r.b.change(c, d); springIn += d; } } }   // a spring only gives
            // step, unless the tick is over budget (then the time is owed and the next step takes it, up to 0.2 s)
            r.owedDt = Math.min(0.2, r.owedDt + 0.05);
            if ((System.nanoTime() - t0) / 1e6 > BUDGET_MS) { skipped++; continue; }
            r.b.step(r.owedDt, MAX_SUB); r.owedDt = 0; steps++;
            write(r);
        }
        lastMs = (System.nanoTime() - t0) / 1e6; maxMs = Math.max(maxMs, lastMs);
        if (tickNo % 20 == 0) for (Region r : REGIONS) if (r.level == l) TidesNet.flow(r);
    }
    /** Rewrites the columns whose depth moved by a whole eighth, biggest change first, at most WRITE_CAP blocks. */
    static void write(Region r) {
        Basin b = r.b; int n = N * N;
        List<int[]> todo = new ArrayList<>();
        for (int c = 0; c < n; c++) {
            if (!r.mine[c] || r.rim(c % N, c / N)) continue;
            double e = b.D[c] * Basin.TICK * 8; int w = r.written[c];
            if (Math.abs(e - w) < 0.7) continue;
            int want = e < FILM ? 0 : (int) Math.round(e), cap = (r.ceilY[c] - r.bedY[c] - 1) * 8; want = Math.max(0, Math.min(want, cap));   // a film under FILM eighths is not drawn (Hydro: films under 2 cm)
            if (want != w) todo.add(new int[]{c, want, Math.abs(want - w)});
        }
        todo.sort((x, y) -> y[2] - x[2]);
        int budget = WRITE_CAP;
        writing = true;
        try {
            for (int[] t : todo) {
                int c = t[0], want = t[1], was = Math.max(0, r.written[c]);
                int x = r.ox + c % N, z = r.oz + c / N, base = r.bedY[c] + 1;
                int topOld = (was + 7) / 8, topNew = (want + 7) / 8, span = Math.max(topOld, topNew);
                if (span > budget) { writesDeferred++; continue; }
                BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                for (int k = 0; k < span; k++) {
                    int lvl = Math.min(8, Math.max(0, want - 8 * k));                  // eighths in block k
                    BlockState s = lvl == 8 ? Blocks.WATER.defaultBlockState() : lvl == 0 ? Blocks.AIR.defaultBlockState() : Fluids.WATER.getFlowing(lvl, false).createLegacyBlock();
                    m.set(x, base + k, z); BlockState cur = r.level.getBlockState(m);
                    if (cur == s) continue;
                    if (!open(cur)) break;                                             // something was built there since: stop
                    r.level.setBlock(m, s, 2 | 16); writes++; budget--;               // clients told; no neighbour updates (no vanilla flow ticks)
                }
                r.written[c] = want;
                if (budget <= 0) break;
            }
        } finally { writing = false; }
    }

    // ------------------------------------------------------------------ the world's side
    /** A block changed (not by us): re-read its column, and its neighbours (a dug bank opens a face). */
    public static void blockChanged(ServerLevel l, BlockPos p) {
        if (writing || !enabled) return;
        for (Region r : REGIONS) {
            if (r.level != l) continue;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { int x = p.getX() + dx, z = p.getZ() + dz; if (r.contains(x, z)) r.dirtyCols.add(r.col(x, z)); }
        }
    }
    /** Vanilla's fluid tick at this water block is not run: the simulation holds this column. */
    public static boolean owns(net.minecraft.world.level.Level l, BlockPos p) {
        if (!enabled || !(l instanceof ServerLevel)) return false;
        for (Region r : REGIONS) {
            if (r.level != l || !r.contains(p.getX(), p.getZ())) continue;
            int c = r.col(p.getX(), p.getZ());
            if (r.mine[c] && p.getY() > r.bedY[c] && p.getY() < r.ceilY[c]) { vanillaTicksStopped++; return true; }
        }
        return false;
    }
    /** The current at a column the simulation holds: {fx, fz, speed m/s, stage}, or null. */
    public static float[] flow(ServerLevel l, int x, int z) {
        for (Region r : REGIONS) {
            if (r.level != l || !r.contains(x, z)) continue;
            int c = r.col(x, z); if (!r.mine[c] || r.b.D[c] * Basin.TICK < 0.05) return null;
            double[] v = r.b.velocity(x - r.ox, z - r.oz); double s = Math.hypot(v[0], v[1]);
            if (s < 0.05) return null;
            return new float[]{(float) (v[0] / s), (float) (v[1] / s), (float) s, s > 1.5 ? 5 : 3};
        }
        return null;
    }
    /** Per column, for the clients: speed class 0 (none / not ours), 1..15 = (k-1) x 0.3 m/s; direction class 0..15 x 22.5 deg. */
    static byte[][] flowGrid(Region r) {
        int n = N * N; byte[] sp = new byte[n], dir = new byte[n];
        for (int c = 0; c < n; c++) {
            if (!r.mine[c] || r.b.D[c] * Basin.TICK < 0.05) continue;
            double[] v = r.b.velocity(c % N, c / N); double s = Math.hypot(v[0], v[1]);
            sp[c] = (byte) Math.min(15, 1 + (int) Math.round(s / 0.3));
            double a = Math.atan2(v[1], v[0]); if (a < 0) a += 2 * Math.PI; dir[c] = (byte) (((int) Math.round(a / (Math.PI / 8))) & 15);
        }
        return new byte[][]{sp, dir};
    }

    /** One column, for tests: what the world holds there and what the simulation holds. */
    public static String probe(ServerLevel l, int x, int z) {
        for (Region r : REGIONS) {
            if (r.level != l || !r.contains(x, z)) continue;
            int c = r.col(x, z); double[] v = r.b.velocity(x - r.ox, z - r.oz);
            return String.format("hydro column %d,%d: held %s bed y %d ceiling y %d, sim depth %.3f m (%.2f eighths), written %d eighths, velocity %.3f %.3f m/s, world read now %s",
                x, z, r.mine[c], r.bedY[c], r.ceilY[c], r.b.D[c] * Basin.TICK, r.b.D[c] * Basin.TICK * 8, r.written[c], v[0], v[1], java.util.Arrays.toString(readColumn(l, x, z, r.refY)));
        }
        return "hydro column " + x + "," + z + ": in no region";
    }
    /** Test: the water the world holds in each region's held columns (read now) against what the simulation holds, in m3. */
    public static String count() {
        StringBuilder sb = new StringBuilder();
        for (Region r : REGIONS) {
            long world = 0, written = 0; int cols = 0, differ = 0;
            for (int c = 0; c < N * N; c++) { if (!r.mine[c]) continue; int[] v = readColumn(r.level, r.ox + c % N, r.oz + c / N, r.refY); if (v == null) continue;
                cols++; world += v[2]; written += Math.max(0, r.written[c]); if (v[2] != r.written[c]) differ++; }
            sb.append(String.format("region %d,%d: world %.3f m3 in %d columns, written %.3f m3, simulation %.3f m3, columns where world != written %d | ", r.ox, r.oz, world / 8.0, cols, written / 8.0, r.b.held() * Basin.TICK, differ));
        }
        return sb.length() == 0 ? "no regions" : sb.toString();
    }
    public static String status() {
        StringBuilder s = new StringBuilder(String.format("hydro %s (natural only %s, skipped: not natural %d, by farmland %d, seas and lakes (wave model) %d, no region fast %d dry %d): %d region(s), last %.2f ms max %.2f ms, steps %d skipped %d, writes %d deferred %d, reads %d, external pours %d drains %d, rim in %.1f m3 out %.1f m3, springs %d gave %.1f m3, vanilla ticks stopped %d, dropped regions %d (made whole %d, removed %d)",
            enabled ? "on" : "off", NATURAL_ONLY, notNatural, farmSkips, bodies, noRegionFast, noRegionDry, REGIONS.size(), lastMs, maxMs, steps, skipped, writes, writesDeferred, reads, externalPours, externalDrains, rimIn * Basin.TICK, rimOut * Basin.TICK, springs, springIn * Basin.TICK, vanillaTicksStopped, regionsDropped, finalSources, finalRemoved));
        for (Region r : REGIONS) {
            int mine = 0; for (boolean m : r.mine) if (m) mine++;
            s.append(String.format(" | region %d,%d y%d: columns held %d, wet %d, awake blocks %d asleep %d, substeps %d, max speed %.2f m/s, max depth %.2f m, %.1f m3, ledger %s, step %.2f ms",
                r.ox, r.oz, r.refY, mine, r.b.WetColumns, r.b.ActiveBlocks, r.b.sleepingBlocks(), r.b.SubstepsLast, r.b.MaxSpeed, r.b.MaxDepth, r.b.held() * Basin.TICK, r.b.ledgerError() == null ? "exact" : r.b.ledgerError(), r.b.LastMs));
        }
        return s.toString();
    }
}
