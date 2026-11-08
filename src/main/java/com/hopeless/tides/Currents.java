package com.hopeless.tides;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 0.4.0: river currents. In a Hopeless Deep stream, the water flows downstream at the stream's speed (from its bed slope: Deep's
 * StreamApi v2, after Star Citizen's river nodes, where flow speed follows slope). Dropped items and mobs in the water are carried on
 * the server; a player's own movement is worked out by their client, so the server sends each player the current where they swim.
 */
public final class Currents {
    private Currents() {}
    public static volatile long lookups, cached, pushes, sent, apiErrors;   // probes
    public static volatile String state = "not looked up";
    static MethodHandle FLOW; static boolean looked;
    static final float[] NONE = new float[0];
    static final ConcurrentHashMap<Long, float[]> CACHE = new ConcurrentHashMap<>();   // the stream layout never changes: kept (capped)
    /** The current a client was last sent for its own player: {fx, fz, speed}, or null. */
    public static volatile float[] clientFlow;

    static boolean api() {
        if (!looked) { looked = true;
            try { Class<?> c = Class.forName("com.hopeless.deep.StreamApi", true, Currents.class.getClassLoader());
                if (c.getField("VERSION").getInt(null) < 2) throw new IllegalStateException("StreamApi version < 2");
                FLOW = MethodHandles.publicLookup().findStatic(c, "flow", MethodType.methodType(float[].class, Object.class, int.class, int.class)); state = "Hopeless Deep stream flow found";
            } catch (Throwable t) { FLOW = null; state = "no stream flow (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")"; }
            HopelessTides.LOG.info("[Hopeless Tides] currents: {}", state);
        }
        return FLOW != null;
    }
    /** {fx, fz, speed (blocks/s), stage} at a column, or null. */
    public static float[] at(ServerLevel l, int x, int z) {
        float[] h = HydroSim.flow(l, x, z); if (h != null) return h;   // 0.7.0: the column simulation's own current first
        if (!api()) return null;
        long k = ((long) x << 32) ^ (z & 0xFFFFFFFFL); float[] v = CACHE.get(k);
        if (v != null) { cached++; return v.length == 0 ? null : v; }
        lookups++;
        try { v = (float[]) FLOW.invokeExact((Object) l, x, z); } catch (Throwable t) { apiErrors++; if (apiErrors < 5) HopelessTides.LOG.error("[Hopeless Tides] flow lookup failed", t); v = null; }
        if (CACHE.size() > 200_000) CACHE.clear();
        CACHE.put(k, v == null ? NONE : v); return v;
    }
    /** Every 10 ticks: each player in a stream's water is sent its current (and a stop when they leave it). */
    static final java.util.Map<java.util.UUID, Boolean> IN = new java.util.HashMap<>();
    static void tickPlayers(ServerLevel l) {
        for (ServerPlayer p : l.players()) {
            float[] f = p.isInWater() ? at(l, (int) Math.floor(p.getX()), (int) Math.floor(p.getZ())) : null;
            boolean was = IN.getOrDefault(p.getUUID(), false);
            if (f != null) { TidesNet.current(p, f[0], f[1], f[2]); IN.put(p.getUUID(), true); sent++; }
            else if (was) { TidesNet.current(p, 0, 0, 0); IN.put(p.getUUID(), false); sent++; }
        }
    }
    /** How hard the current pushes a swimmer or mob each tick, as a share of its speed (set from the drift measured in the tests). */
    public static double SWIM = 0.25, BOAT = 0.25;
    /** The current for an entity: on the server from the stream itself; on a client only for its own player (sent by the server). */
    public static float[] forEntity(net.minecraft.world.entity.Entity e, int x, int z) {
        if (e.level() instanceof ServerLevel sl) return e instanceof net.minecraft.world.entity.player.Player ? null : at(sl, x, z);
        return e.isControlledByLocalInstance() && e instanceof net.minecraft.world.entity.player.Player ? clientFlow : null;
    }
    public static String probe() { return "currents: " + state + "; lookups " + lookups + " cached " + cached + " pushes " + pushes + " sent " + sent + " errors " + apiErrors; }
}
