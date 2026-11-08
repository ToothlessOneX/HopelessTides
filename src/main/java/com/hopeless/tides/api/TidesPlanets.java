package com.hopeless.tides.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 0.13.0: what a world mod tells Tides about the planet a dimension is on (Hopeless Infinity calls this; no compile dependency either
 * way: callers use reflection and Tides only reads the table). Every dimension in the table gets Tides' water (as if listed in the
 * config's dimensions), with:
 *  - g: surface gravity (m/s2). Wave speeds (deep water: w^2 = g k) and heights at a given wind (Hs = 0.21 U^2 / g) follow it.
 *  - tideRatio: the planet's equilibrium tide over Earth's (Moon + Sun). The tide drawn is the config's spring amplitude x this ratio.
 *  - lunarShare: the moons' part of that tide, 0..1 (Earth 0.69). Spring/neap swing = (L - S) / (L + S) = |2 lunarShare - 1|.
 * Server side: set by the world mod; client side: sent by the server (TidesNet.Planets).
 */
public final class TidesPlanets {
    private TidesPlanets() {}
    public record Planet(double g, double tideRatio, double lunarShare, String name) {}
    static final Map<String, Planet> MAP = new ConcurrentHashMap<>();
    /** Called by world mods (reflection: set(String, double, double, double, String)). dimension: "namespace:path". */
    public static void set(String dimension, double g, double tideRatio, double lunarShare, String name) {
        Planet p = new Planet(g > 0.01 ? g : 9.81, Math.max(0, tideRatio), Math.max(0, Math.min(1, lunarShare)), name == null ? "" : name);
        Planet old = MAP.put(dimension, p);
        if (!p.equals(old)) { com.hopeless.tides.HopelessTides.LOG.info("[Hopeless Tides] planet {}: {} g {} m/s2, tide x{} of Earth's (lunar share {})", dimension, p.name, String.format("%.2f", p.g), String.format("%.3f", p.tideRatio), String.format("%.3f", p.lunarShare)); changed = true; }
    }
    public static void clear(String dimension) { if (MAP.remove(dimension) != null) changed = true; }
    public static void clearAll() { if (!MAP.isEmpty()) { MAP.clear(); changed = true; } }
    public static Planet get(String dimension) { return MAP.get(dimension); }
    public static Map<String, Planet> all() { return Map.copyOf(MAP); }
    // ---- 0.15.0: the water's temperature where the world mod knows it (the water type and colour: core/WaterOptics). No compile
    // dependency: the caller passes a JDK function (world x, z) -> annual mean surface temperature (C) at the water's own level.
    static final Map<String, java.util.function.ToDoubleBiFunction<Double, Double>> CLIMATE = new ConcurrentHashMap<>();
    /** Called by world mods (reflection: setClimate(String, ToDoubleBiFunction)). Each side registers its own (client and server). */
    public static void setClimate(String dimension, java.util.function.ToDoubleBiFunction<Double, Double> meanC) { if (meanC == null) CLIMATE.remove(dimension); else CLIMATE.put(dimension, meanC); }
    // ---- 0.16.0: is there sea at (x, z) beyond the loaded chunks? (the fetch: how far the wind blows over open water). A JDK predicate.
    static final Map<String, java.util.function.BiPredicate<Double, Double>> WATER = new ConcurrentHashMap<>();
    public static void setWater(String dimension, java.util.function.BiPredicate<Double, Double> isSea) { if (isSea == null) WATER.remove(dimension); else WATER.put(dimension, isSea); }
    /** 1 sea, 0 land, -1 not known (no world mod said). */
    public static int waterAt(String dimension, double x, double z) { var f = WATER.get(dimension); if (f == null) return -1; try { return f.test(x, z) ? 1 : 0; } catch (Throwable t) { return -1; } }
    /** The water's annual mean (C) at (x, z), or NaN when no world mod said. */
    public static double meanC(String dimension, double x, double z) { var f = CLIMATE.get(dimension); if (f == null) return Double.NaN; try { return f.applyAsDouble(x, z); } catch (Throwable t) { return Double.NaN; } }
    /** Set when the table changes (the server resends it to every player). */
    /** 0.17.3: the tide from a planet mod's real sky: per dimension, a function of the level giving the equilibrium tide now as a share of
     *  its spring maximum (-0.5..1: the bodies' sum of (cos^2 zenith - 1/3), over 2/3 of their sum). Tides then draws amplitude x tide ratio
     *  x this instead of its Minecraft-day model (two highs a Minecraft day, spring/neap from Minecraft's moon phase). */
    static final Map<String, java.util.function.ToDoubleFunction<Object>> TIDE = new java.util.concurrent.ConcurrentHashMap<>();
    public static void setTide(String dimension, java.util.function.ToDoubleFunction<Object> tide) { if (tide == null) TIDE.remove(dimension); else TIDE.put(dimension, tide); }
    public static java.util.function.ToDoubleFunction<Object> tide(String dimension) { return TIDE.get(dimension); }
    public static volatile long tideCalls, tideErrors;
    public static volatile boolean changed;
}
