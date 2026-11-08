package com.hopeless.tides;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 0.7.1: the server's settings, saved with the world (serverconfig/hopelesstides-server.toml), applied on load and reload. */
public final class TidesConfig {
    private TidesConfig() {}
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue HYDRO, NATURAL_ONLY, FFT, TIDE;
    public static ForgeConfigSpec.DoubleValue TIDE_AMP;
    public static final ForgeConfigSpec.IntValue REGION, INIT_READS;
    public static final ForgeConfigSpec.DoubleValue BUDGET_MS, MAX_SPEED;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> DIMENSIONS, WAVE_EXEMPT;
    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Column water simulation (Hopeless Hydro's water core): water moves as real water in a region round each player").push("hydro");
        HYDRO = b.comment("Run the column water simulation").define("enabled", true);
        NATURAL_ONLY = b.comment("Hold only natural water: a bed of natural ground (stone, dirt, sand, gravel, clay, mud...), no built blocks or farmland beside it.",
            "Water in channels, farms, item streams and builds stays vanilla.").define("naturalOnly", true);
        REGION = b.comment("Region size in blocks (a square round each player or group of players)").defineInRange("regionSize", 80, 32, 160);
        INIT_READS = b.comment("Columns a new region reads per tick (it starts once all are read; spreads the cost)").defineInRange("initialReadsPerTick", 1600, 200, 20000);
        BUDGET_MS = b.comment("Milliseconds a tick the simulation may use for all regions; over it, steps wait for the next tick").defineInRange("budgetMs", 12.0, 1.0, 40.0);
        MAX_SPEED = b.comment("A player moving faster than this (blocks/s: planes, fast vehicles, elytra) gets no new region").defineInRange("maxPlayerSpeed", 12.0, 1.0, 100.0);
        DIMENSIONS = b.comment("Dimensions where it runs").defineListAllowEmpty("dimensions", List.of("minecraft:overworld"), o -> o instanceof String);
        b.pop();
        b.push("waves");
        FFT = b.comment("0.9.0: Fourier waves (a 512-block ocean spectrum, JONSWAP) on top of the column water, scaled per spot by depth, shelter and current.",
            "Off: the 12-wave model. Clients follow the server.").define("fft", true);
        TIDE = b.comment("0.11.0: the tide on open water: two highs a day, spring tides at full and new moon. Moves the drawn and felt surface only;",
            "no water blocks are added or removed.").define("tide", true);
        TIDE_AMP = b.comment("Tide amplitude at spring tide (blocks)").defineInRange("tideAmplitude", 0.25, 0.0, 0.45);
        WAVE_EXEMPT = b.comment("Entities the waves do not lift or push (\"mod:*\" for a whole mod). Vehicles with their own water physics belong here.")
            .defineListAllowEmpty("exemptEntities", List.of("superbwarfare:*"), o -> o instanceof String);
        b.pop();
        SPEC = b.build();
    }
    public static volatile Set<String> exempt = Set.of("superbwarfare:*"), dims = Set.of("minecraft:overworld");
    public static void apply(ModConfigEvent e) {
        if (e.getConfig().getSpec() != SPEC) return;
        HydroSim.enabled = HYDRO.get(); HydroSim.NATURAL_ONLY = NATURAL_ONLY.get(); HydroSim.N = REGION.get(); HydroSim.INIT_READS = INIT_READS.get();
        HydroSim.BUDGET_MS = BUDGET_MS.get(); HydroSim.MAX_SPEED = MAX_SPEED.get();
        dims = new HashSet<>(DIMENSIONS.get()); Tides.fftOn = FFT.get(); Tides.tideOn = TIDE.get(); Tides.tideAmp = TIDE_AMP.get(); exempt = new HashSet<>(WAVE_EXEMPT.get());
        HopelessTides.LOG.info("[Hopeless Tides] config: hydro {} naturalOnly {} region {} budget {} ms dims {} wave-exempt {}", HydroSim.enabled, HydroSim.NATURAL_ONLY, HydroSim.N, HydroSim.BUDGET_MS, dims, exempt);
    }
    /** Do the waves leave this entity alone? */
    public static boolean exempt(net.minecraft.world.entity.Entity e) {
        Set<String> s = exempt; if (s.isEmpty()) return false;
        var k = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return s.contains(k.toString()) || s.contains(k.getNamespace() + ":*");
    }
    public static void setFft(boolean on) { Tides.fftOn = on; try { FFT.set(on); FFT.save(); } catch (Throwable t) { /* not loaded yet */ } TidesNet.sendAll(); }
    public static void setHydro(boolean on) { HydroSim.enabled = on; try { HYDRO.set(on); HYDRO.save(); } catch (Throwable t) { /* not loaded yet */ } }
}
