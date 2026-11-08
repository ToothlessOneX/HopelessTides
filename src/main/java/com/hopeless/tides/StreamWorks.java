package com.hopeless.tides;

import com.hopeless.tides.core.Streams;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.SavedData;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Streams in a running world (0.3.0): the soil and groundwater stores follow the world's weather, and the creeks, gullies and river
 * floods near players fill and drain to match, one block of water level at a time.
 *
 * The stream layout comes from Hopeless Deep (com.hopeless.deep.StreamApi, found by name: neither mod needs the other). Only columns
 * whose bed is still natural ground at the height the generator made it are touched, so towns, paths and anything a player built in
 * a stream bed are left alone. Water is only placed into air or small plants, and only water is removed.
 */
public final class StreamWorks {
    private StreamWorks() {}
    public static final int RADIUS = 6;              // chunks round each player that are kept up to date
    static final int STEP_TICKS = 10;                // a column's water moves one block per this many ticks at most
    static final int BUDGET = 384;                   // block changes per tick, at most
    static final long BUILD_NANOS = 2_000_000;       // time per tick for reading new chunks' streams

    // probes
    public static volatile long chunksRead, chunksWithStreams, readNanos, columns, guarded, placed, removed, blocked, tidied, apiErrors, hydroSteps, drainSteps, maxDrain, spillDrained;
    public static volatile String apiState = "not looked up";
    public static volatile double lastRain, lastThunder;

    // ------------------------------------------------------------------ the link to Hopeless Deep
    static MethodHandle AT, DEEP_LEVEL; static boolean looked;
    static boolean api() {
        if (!looked) { looked = true;
            try {
                Class<?> c = Class.forName("com.hopeless.deep.StreamApi", true, StreamWorks.class.getClassLoader());
                MethodHandles.Lookup lk = MethodHandles.publicLookup();
                AT = lk.findStatic(c, "at", MethodType.methodType(int[].class, Object.class, int.class, int.class));
                DEEP_LEVEL = lk.findStatic(c, "deepLevel", MethodType.methodType(boolean.class, Object.class));
                apiState = "Hopeless Deep found (StreamApi version " + c.getField("VERSION").getInt(null) + ")";
            } catch (Throwable t) { AT = null; apiState = "Hopeless Deep not found: streams off (" + t.getClass().getSimpleName() + ")"; }
            HopelessTides.LOG.info("[Hopeless Tides] streams: {}", apiState);
        }
        return AT != null;
    }
    static int[] at(ServerLevel l, int x, int z) {
        try { return (int[]) AT.invokeExact((Object) l, x, z); } catch (Throwable t) { apiErrors++; if (apiErrors < 5) HopelessTides.LOG.error("[Hopeless Tides] stream lookup failed", t); return null; }
    }
    static boolean deepLevel(ServerLevel l) { try { return (boolean) DEEP_LEVEL.invokeExact((Object) l); } catch (Throwable t) { apiErrors++; return false; } }

    // ------------------------------------------------------------------ saved state: the two stores
    public static final class Store extends SavedData {
        public final Streams.Hydro h = new Streams.Hydro(); public boolean frozen;
        // the weather on the sealed surface: a world with no sky (Hopeless Deep) has no weather cycle of its own, so it is run here,
        // with vanilla's timings (ServerLevel.advanceWeatherCycle)
        public int rainTime, thunderTime; public boolean raining, thundering; public double rainL, thunderL;
        static Store load(CompoundTag t) { Store s = new Store(); s.h.soil = t.getDouble("soil"); s.h.ground = t.getDouble("ground"); s.frozen = t.getBoolean("frozen");
            s.rainTime = t.getInt("rainTime"); s.thunderTime = t.getInt("thunderTime"); s.raining = t.getBoolean("raining"); s.thundering = t.getBoolean("thundering"); s.rainL = t.getDouble("rainL"); s.thunderL = t.getDouble("thunderL"); return s; }
        @Override public CompoundTag save(CompoundTag t) { t.putDouble("soil", h.soil); t.putDouble("ground", h.ground); t.putBoolean("frozen", frozen);
            t.putInt("rainTime", rainTime); t.putInt("thunderTime", thunderTime); t.putBoolean("raining", raining); t.putBoolean("thundering", thundering); t.putDouble("rainL", rainL); t.putDouble("thunderL", thunderL); return t; }
        void ramp(int n) { for (int k = 0; k < n; k++) { rainL = Math.max(0, Math.min(1, rainL + (raining ? 0.01 : -0.01))); thunderL = Math.max(0, Math.min(1, thunderL + (thundering && raining ? 0.01 : -0.01))); } }
        /** Advance the surface weather by n ticks. */
        void weather(int n, net.minecraft.util.RandomSource r) {
            for (int k = 0; k < n; k++) {
                if (thunderTime > 0) { if (--thunderTime == 0) thundering = !thundering; } else thunderTime = thundering ? 3600 + r.nextInt(12000) : 12000 + r.nextInt(168000);
                if (rainTime > 0) { if (--rainTime == 0) raining = !raining; } else rainTime = raining ? 12000 + r.nextInt(12000) : 12000 + r.nextInt(168000);
                rainL = Math.max(0, Math.min(1, rainL + (raining ? 0.01 : -0.01))); thunderL = Math.max(0, Math.min(1, thunderL + (thundering && raining ? 0.01 : -0.01)));
            }
        }
    }
    public static Store store(ServerLevel l) { return l.getDataStorage().computeIfAbsent(Store::load, Store::new, "hopelesstides_streams"); }

    // ------------------------------------------------------------------ per chunk: its stream columns
    static final class Cols {
        int n; int[] x = new int[16], z = new int[16], kind = new int[16], bed = new int[16], full = new int[16], f1 = new int[16], f2 = new int[16], cur = new int[16], top = new int[16];
        boolean[] ok = new boolean[16]; byte[] tidy = new byte[16]; long seen; int next;
        void add(int[] s, int xx, int zz) {
            if (n == x.length) { int m = n * 2; x = java.util.Arrays.copyOf(x, m); z = java.util.Arrays.copyOf(z, m); kind = java.util.Arrays.copyOf(kind, m); bed = java.util.Arrays.copyOf(bed, m);
                full = java.util.Arrays.copyOf(full, m); f1 = java.util.Arrays.copyOf(f1, m); f2 = java.util.Arrays.copyOf(f2, m); cur = java.util.Arrays.copyOf(cur, m); top = java.util.Arrays.copyOf(top, m); ok = java.util.Arrays.copyOf(ok, m); tidy = java.util.Arrays.copyOf(tidy, m); }
            x[n] = xx; z[n] = zz; kind[n] = s[0]; bed[n] = s[1]; full[n] = s[2]; f1[n] = s[3]; f2[n] = s[4];
            top[n] = Math.max(s[2], Math.max(s[3], s[4])); n++;
        }
    }
    static final class LevelState { final Map<Long, Cols> chunks = new HashMap<>(); final ArrayDeque<Long> queue = new ArrayDeque<>(); final HashSet<Long> queued = new HashSet<>(); long[] active = new long[0]; int rr; Boolean deep; }
    static final Map<Level, LevelState> STATES = new java.util.WeakHashMap<>();

    /** Natural ground a stream bed can be: what the generator lays in beds, banks and floodplains. */
    static boolean naturalBed(BlockState b) {
        return b.is(BlockTags.DIRT) || b.is(BlockTags.SAND) || b.is(BlockTags.BASE_STONE_OVERWORLD) || b.is(Blocks.GRAVEL) || b.is(Blocks.COBBLESTONE) || b.is(Blocks.MOSSY_COBBLESTONE)
            || b.is(Blocks.CLAY) || b.is(Blocks.SANDSTONE) || b.is(Blocks.CALCITE) || b.is(Blocks.DRIPSTONE_BLOCK) || b.is(Blocks.PACKED_MUD);
    }
    /** Open to water: air, or a small plant water can take the place of (it is lost). */
    static boolean open(BlockState b) {
        if (b.isAir()) return true;
        if (!b.getFluidState().isEmpty()) return false;
        return b.canBeReplaced() && !b.is(BlockTags.SNOW) && b.getBlock() != Blocks.LIGHT;
    }
    /** A water block (still or flowing; not a waterlogged block). */
    static boolean isWater(BlockState b) { return b.getBlock() == Blocks.WATER; }
    /** Set without telling the neighbours (16: no shape updates): a neighbouring source told of a gap would flow in and, beside another
     *  source, make a new one, so a stream would never drain (measured: 5015 removals for 3081 placements, water left behind). */
    static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** Remove water here, and let flowing water beside it (fed by this one) run dry the normal way. True if anything was removed. */
    static final int[] drainX = new int[16384], drainY = new int[16384], drainZ = new int[16384];
    static final int SPILL = 8;   // how far from a stream column water that spilled out of it is followed and drained with it
    /** Remove the given blocks and all water joined to them at the same height (spilled beside the channel, or a flood sheet), in one
     *  tick. Water in another stream column whose own level is at or above that height is not touched (its water is meant to be there). */
    static void drainLayer(ServerLevel l, LevelState st, Store store, int nDrain) {
        HashMap<Long, Integer> want = new HashMap<>();
        for (long cp : st.active) { Cols c = st.chunks.get(cp); if (c == null) continue;
            for (int i = 0; i < c.n; i++) want.put(BlockPos.asLong(c.x[i], 0, c.z[i]), Streams.target(c.kind[i], c.bed[i], c.full[i], c.f1[i], c.f2[i], store.h)); }   // (also guarded columns: flood water that ran into them goes too)
        ArrayDeque<long[]> q = new ArrayDeque<>(); HashSet<Long> seen = new HashSet<>(); java.util.ArrayList<BlockPos> gone = new java.util.ArrayList<>();
        for (int i = 0; i < nDrain; i++) { long k = BlockPos.asLong(drainX[i], drainY[i], drainZ[i]); if (seen.add(k)) q.add(new long[]{k, 0}); }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(); int spilled = 0;
        while (!q.isEmpty() && gone.size() < 60000) {
            long[] e = q.poll(); m.set(BlockPos.getX(e[0]), BlockPos.getY(e[0]), BlockPos.getZ(e[0]));
            if (!l.hasChunk(m.getX() >> 4, m.getZ() >> 4) || !isWater(l.getBlockState(m))) continue;
            gone.add(m.immutable()); if (e[1] > 0) spilled++;
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                int nx = m.getX() + d.getStepX(), nz = m.getZ() + d.getStepZ(); long nk = BlockPos.asLong(nx, m.getY(), nz); if (seen.contains(nk)) continue;
                Integer w = want.get(BlockPos.asLong(nx, 0, nz)); long depth;
                if (w != null) { if (w >= m.getY()) continue; depth = 0; }                     // a stream column: only if its level is below this height
                else { depth = e[1] + 1; if (depth > SPILL) continue; }                        // spilled water: followed a few blocks out
                seen.add(nk); q.add(new long[]{nk, depth}); }
        }
        for (BlockPos p : gone) l.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
        for (BlockPos p : gone) wake(l, p);
        removed += gone.size(); spillDrained += spilled; maxDrain = Math.max(maxDrain, gone.size()); drainSteps++;
    }
    static boolean drain(ServerLevel l, BlockPos.MutableBlockPos m) {
        BlockState b = l.getBlockState(m); if (!isWater(b)) return false;
        l.setBlock(m, Blocks.AIR.defaultBlockState(), FLAGS); removed++; wake(l, m); return true;
    }
    static void wake(ServerLevel l, BlockPos m) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) { BlockPos n = m.relative(d); FluidState f = l.getFluidState(n);
            if (f.getType() == Fluids.FLOWING_WATER) l.scheduleTick(n, Fluids.FLOWING_WATER, 5); }
        BlockPos dn = m.below(); FluidState f = l.getFluidState(dn); if (f.getType() == Fluids.FLOWING_WATER) l.scheduleTick(dn, Fluids.FLOWING_WATER, 5);
    }

    // ------------------------------------------------------------------ the tick
    public static void tick(ServerLevel l) {
        if (l.dimension() != Level.OVERWORLD || !api()) return;
        LevelState st = STATES.computeIfAbsent(l, k -> new LevelState());
        if (st.deep == null) st.deep = deepLevel(l);
        if (!st.deep) return;
        long now = l.getGameTime();
        Store store = store(l);
        if (now % 20 == 0) {
            if (!store.frozen) {
                double rain = l.getRainLevel(1f), thunder = l.getThunderLevel(1f);
                if (!l.dimensionType().hasSkyLight()) {   // no sky: our own surface weather. /weather still sets the world's flags here (vanilla's cycle
                    boolean cycle = l.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE);   // does not run, so they stay as set): they win
                    if (l.getLevelData() instanceof net.minecraft.world.level.storage.ServerLevelData sd && sd.getClearWeatherTime() > 0 && !sd.isRaining()) { store.raining = false; store.thundering = false; store.rainTime = Math.max(store.rainTime, 40); store.thunderTime = Math.max(store.thunderTime, 40); }   // /weather clear
                    if (l.getLevelData().isRaining()) { store.raining = true; store.rainTime = Math.max(store.rainTime, 40); }
                    if (l.getLevelData().isThundering()) { store.thundering = true; store.thunderTime = Math.max(store.thunderTime, 40); }
                    if (cycle) store.weather(20, l.random); else store.ramp(20);
                    rain = Math.max(rain, store.rainL); thunder = Math.max(thunder, store.thunderL);
                }
                lastRain = rain; lastThunder = thunder;
                store.h.step(20, rain, thunder); store.setDirty(); hydroSteps++; }
            gather(l, st, now);
        }
        // read new chunks' streams, a little each tick
        long t0 = System.nanoTime();
        while (!st.queue.isEmpty() && System.nanoTime() - t0 < BUILD_NANOS) {
            long cp = st.queue.poll(); st.queued.remove(cp);
            int cx = ChunkPos.getX(cp), cz = ChunkPos.getZ(cp);
            if (!l.hasChunk(cx, cz)) continue;
            st.chunks.put(cp, read(l, cx, cz, now));
        }
        readNanos += System.nanoTime() - t0;
        if (now % STEP_TICKS != 0 || st.active.length == 0) return;
        // move each column one block toward its target. Rising: a budget of blocks per step. Falling: every column that falls drops its
        // top block in the same tick - water left beside a gap for even a tick flows in and, between two sources, becomes a source again,
        // so a stream drained a block at a time never empties (measured: 1351 still blocks left in a river after a flood went down).
        int budget = BUDGET; BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(); int nDrain = 0;
        for (int k = 0; k < st.active.length; k++) {
            Cols c = st.chunks.get(st.active[(st.rr + k) % st.active.length]); if (c == null || c.n == 0) continue;
            if (!l.hasChunk(c.x[0] >> 4, c.z[0] >> 4)) continue;
            for (int q = 0; q < c.n; q++) { int i = (c.next + q) % c.n; if (!c.ok[i]) continue;
                int want = Streams.target(c.kind[i], c.bed[i], c.full[i], c.f1[i], c.f2[i], store.h);
                if (want > c.cur[i]) { if (budget <= 0) continue; int y = c.cur[i] + 1; m.set(c.x[i], y, c.z[i]); BlockState b = l.getBlockState(m);
                    if (isWater(b)) { if (!b.getFluidState().isSource()) { l.setBlock(m, Blocks.WATER.defaultBlockState(), FLAGS); placed++; budget--; } }   // flowing water ahead of the rise: made still
                    else if (open(b)) {
                        if (b.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock) { BlockPos up = m.above(); if (l.getBlockState(up).getBlock() == b.getBlock()) l.setBlock(up, Blocks.AIR.defaultBlockState(), FLAGS); }
                        l.setBlock(m, Blocks.WATER.defaultBlockState(), FLAGS); placed++; budget--; }
                    else blocked++;                                                       // a boulder or a block in the way: water stands round it
                    c.cur[i] = y; }
                else if (want < c.cur[i]) { if (nDrain < drainX.length) { drainX[nDrain] = c.x[i]; drainZ[nDrain] = c.z[i]; drainY[nDrain++] = c.cur[i]; c.cur[i]--; } }   // (all together, below)
                else if (c.tidy[i] < 3 && c.cur[i] < c.top[i] + 1) {   // at its level: still water above it (formed anew from water left beside it) is drained with
                    boolean any = false;                                // everything joined to it, like a fall in level (three times at most: then it is fed, and stays);
                    for (int y = c.cur[i] + 1; y <= c.top[i] + 1; y++) { m.set(c.x[i], y, c.z[i]); BlockState b = l.getBlockState(m); if (!isWater(b)) continue;   // flowing water is woken
                        if (b.getFluidState().isSource()) { if (nDrain < drainX.length) { drainX[nDrain] = c.x[i]; drainZ[nDrain] = c.z[i]; drainY[nDrain++] = y; any = true; } }
                        else l.scheduleTick(m.immutable(), Fluids.FLOWING_WATER, 5); }
                    if (any) { c.tidy[i]++; tidied++; } }
                if (want != c.cur[i]) c.tidy[i] = 0;
            }
            c.next = (c.next + 1) % c.n;
        }
        if (nDrain > 0) drainLayer(l, st, store, nDrain);
        st.rr = (st.rr + 1) % Math.max(1, st.active.length);
    }
    /** The chunks round players and force-loaded ones; queue the ones not read yet; forget the ones nobody has been near for a while. */
    static void gather(ServerLevel l, LevelState st, long now) {
        HashSet<Long> want = new HashSet<>();
        int R = Math.max(RADIUS, l.getServer().getPlayerList().getSimulationDistance() + 1);   // every chunk where water ticks: a stream half drained at the edge would refill
        for (ServerPlayer p : l.players()) { int pcx = p.blockPosition().getX() >> 4, pcz = p.blockPosition().getZ() >> 4;
            for (int dz = -R; dz <= R; dz++) for (int dx = -R; dx <= R; dx++) want.add(ChunkPos.asLong(pcx + dx, pcz + dz)); }
        for (long cp : l.getForcedChunks()) want.add(cp);
        long[] act = new long[want.size()]; int n = 0;
        for (long cp : want) {
            if (!l.hasChunk(ChunkPos.getX(cp), ChunkPos.getZ(cp))) continue;
            Cols c = st.chunks.get(cp);
            if (c == null) { if (st.queued.add(cp)) st.queue.add(cp); continue; }
            c.seen = now; if (c.n > 0) act[n++] = cp;
        }
        st.active = java.util.Arrays.copyOf(act, n);
        if (now % 600 == 0) st.chunks.entrySet().removeIf(e -> now - e.getValue().seen > 1200 || !l.hasChunk(ChunkPos.getX(e.getKey()), ChunkPos.getZ(e.getKey())));
    }
    /** A chunk's stream columns, with each one checked against the world and its present water level found. */
    static Cols read(ServerLevel l, int cx, int cz, long now) {
        Cols c = new Cols(); c.seen = now; chunksRead++;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            int x = (cx << 4) + dx, z = (cz << 4) + dz; int[] s = at(l, x, z); if (s == null) continue;
            int topLevel = Math.max(s[2], Math.max(s[3], s[4])); if (topLevel <= s[1]) continue;   // nothing could stand above this bed
            c.add(s, x, z); int i = c.n - 1; columns++;
            BlockState bed = l.getBlockState(m.set(x, s[1], z)), above = l.getBlockState(m.set(x, s[1] + 1, z));
            c.ok[i] = naturalBed(bed) && (open(above) || above.getBlock() == Blocks.WATER || naturalBed(above));   // (a boulder can sit on the bed)
            if (!c.ok[i]) { guarded++; continue; }
            int cur = s[1];
            for (int y = s[1] + 1; y <= topLevel + 1; y++) { BlockState b = l.getBlockState(m.set(x, y, z));
                if (b.getBlock() == Blocks.WATER) cur = y; else if (b.isAir()) break; }
            c.cur[i] = cur;
        }
        if (c.n > 0) chunksWithStreams++;
        return c;
    }

    // ------------------------------------------------------------------ for commands and probes
    public static String probe(ServerLevel l) {
        Store s = store(l); LevelState st = STATES.get(l);
        return String.format("rain %.2f thunder %.2f%s | soil %.3f ground %.3f%s | gullies %.0f%% creeks %.0f%% river stage %d | chunks read %d (%d with streams, %.0f us each) active %d queued %d | columns %d guarded %d | water placed %d removed %d (tidied %d; drain steps %d, at most %d blocks at once, spilled water drained %d) blocked %d | api errors %d | %s",
            lastRain, lastThunder, l.dimensionType().hasSkyLight() ? "" : String.format(" (surface weather: %s, next change in %.1f days)", s.raining ? (s.thundering ? "thunder" : "rain") : "clear", s.rainTime / 24000.0),
            s.h.soil, s.h.ground, s.frozen ? " (frozen)" : "", 100 * s.h.ephemeral(), 100 * s.h.intermittent(), s.h.flood(), chunksRead, chunksWithStreams, chunksRead == 0 ? 0 : readNanos / 1000.0 / chunksRead,
            st == null ? 0 : st.active.length, st == null ? 0 : st.queue.size(), columns, guarded, placed, removed, tidied, drainSteps, maxDrain, spillDrained, blocked, apiErrors, apiState);
    }
    /** Counts over the active chunks: columns by kind, and how many are at their target / wet now. */
    public static String scan(ServerLevel l) {
        LevelState st = STATES.get(l); if (st == null) return "no stream state";
        Store s = store(l); int[] n = new int[7], wet = new int[7], at = new int[7], ok = new int[7], extraS = new int[7], extraF = new int[7], missing = new int[7], hiFlood = new int[7]; int[][] hist = new int[7][4]; String[] sample = new String[7];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (long cp : st.active) { Cols c = st.chunks.get(cp); if (c == null) continue;
            for (int i = 0; i < c.n; i++) { int k = c.kind[i]; n[k]++; if (!c.ok[i]) continue; ok[k]++; if (c.cur[i] > c.bed[i]) wet[k]++;
                int want = Streams.target(k, c.bed[i], c.full[i], c.f1[i], c.f2[i], s.h); if (c.cur[i] == want) at[k]++;
                // the world itself: water above the target (still / flowing), and open blocks below it that should hold water
                for (int y = want + 1; y <= c.top[i] + 1; y++) { BlockState b = l.getBlockState(m.set(c.x[i], y, c.z[i])); if (isWater(b)) { if (b.getFluidState().isSource()) { extraS[k]++; hist[k][Math.min(3, y - want - 1)]++; if (y > c.f1[i] - 1 && c.f1[i] != Integer.MIN_VALUE) hiFlood[k]++; if (sample[k] == null) sample[k] = c.x[i] + " " + y + " " + c.z[i] + " (want " + want + " full " + c.full[i] + " f1 " + c.f1[i] + ")"; } else extraF[k]++; } }
                for (int y = c.bed[i] + 1; y <= want; y++) { BlockState b = l.getBlockState(m.set(c.x[i], y, c.z[i])); if (b.isAir()) missing[k]++; } } }
        StringBuilder b = new StringBuilder();
        for (int k = 1; k < 7; k++) if (n[k] > 0) b.append(String.format("%s %d (usable %d, wet %d, at target %d; world: still water above target %d (1/2/3/4+ above: %d/%d/%d/%d, at flood levels %d, e.g. %s), flowing %d, air below target %d) ", Streams.NAMES[k], n[k], ok[k], wet[k], at[k], extraS[k], hist[k][0], hist[k][1], hist[k][2], hist[k][3], hiFlood[k], sample[k], extraF[k], missing[k]));
        return b.length() == 0 ? "no stream columns near" : b.toString();
    }
    /** One column: what Deep says and what is there. */
    public static String column(ServerLevel l, int x, int z) {
        if (!api()) return apiState;
        int[] s = at(l, x, z); if (s == null) return "no stream at " + x + " " + z;
        Store st = store(l); int want = Streams.target(s[0], s[1], s[2], s[3], s[4], st.h);
        StringBuilder b = new StringBuilder(String.format("%s at %d %d: bed %d full %s flood %s/%s target %d | blocks:", Streams.NAMES[s[0]], x, z, s[1], lv(s[2]), lv(s[3]), lv(s[4]), want));
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = s[1]; y <= Math.max(want, s[1]) + 2; y++) { BlockState bs = l.getBlockState(m.set(x, y, z)); b.append(' ').append(y).append('=').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bs.getBlock()).getPath()); }
        return b.toString();
    }
    static String lv(int v) { return v == Integer.MIN_VALUE ? "-" : Integer.toString(v); }
}
