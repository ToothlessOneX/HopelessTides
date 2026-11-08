package com.hopeless.tides;

import com.hopeless.tides.core.WaveModel;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

@Mod(HopelessTides.MODID)
public class HopelessTides {
    public static final String MODID = "hopelesstides", VERSION = "0.17.3";
    public static final Logger LOG = LogManager.getLogger("HopelessTides");

    public HopelessTides() {
        LOG.info("[Hopeless Tides] {}", Credit.line());
        MinecraftForge.EVENT_BUS.addListener(this::commands);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.level.BlockEvent.NeighborNotifyEvent ev) -> { if (ev.getLevel() instanceof ServerLevel sl) HydroSim.blockChanged(sl, ev.getPos()); });   // 0.7.0
        TidesNet.init();
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent ev) -> { if (ev.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) { TidesNet.send(sp); TidesNet.planets(sp); } });
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppedEvent ev) -> com.hopeless.tides.api.TidesPlanets.clearAll());   // 0.13.0: a new world sets its own
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            com.hopeless.tides.client.TidesClientLog.init(); com.hopeless.tides.client.TidesLook.init(); com.hopeless.tides.client.TidesLens.init();   // 0.7.0 / 0.10.0 lens: Hydro's look and body reactions
            net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus().addListener(com.hopeless.tides.client.TidesParticles::providers);
        }
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.SERVER, TidesConfig.SPEC);   // 0.7.1
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus().addListener(TidesConfig::apply);
        TidesParticleTypes.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        TidesSounds.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());   // 0.8.0
    }

    // ------------------------------------------------------------------ test tracking: entities followed tick by tick against the wave model
    static final class Track { Entity e; String what; int ticks; double sumErr, sumAbsDev, minY = 1e9, maxY = -1e9, base; int inWater; double x0, z0, x40 = Double.NaN, z40, x10 = Double.NaN, z10; List<double[]> s = new ArrayList<>(); StringBuilder early = new StringBuilder(); }
    static final List<Track> TRACKS = new ArrayList<>();
    static long currentTicks;
    void tick(TickEvent.LevelTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END || !(ev.level instanceof ServerLevel l)) return;
        if (com.hopeless.tides.api.TidesPlanets.changed) { com.hopeless.tides.api.TidesPlanets.changed = false; TidesNet.planetsAll(); }   // 0.13.0
        if (l.getGameTime() % 10 == 0) for (net.minecraft.server.level.ServerPlayer p : l.players()) if (!p.isSpectator()) try { TidesSurf.tick(l, p.getStringUUID(), p.getX(), p.getZ()); } catch (Throwable t) { TidesSurf.errors++; }   // 0.14.0
        try { if (++currentTicks % 10 == 0) Currents.tickPlayers(l); } catch (Throwable t) { Currents.apiErrors++; }
        try { CaveWind.tick(l); } catch (Throwable t) { if (CaveWind.runs < 3) LOG.error("[Hopeless Tides] cave wind failed", t); }
        try { HydroSim.tick(l); } catch (Throwable t) { HydroErrors.n++; if (HydroErrors.n < 5) LOG.error("[Hopeless Tides] hydro failed", t); }
        try { StreamWorks.tick(l); } catch (Throwable t) { StreamWorks.apiErrors++; if (StreamWorks.apiErrors < 5) LOG.error("[Hopeless Tides] streams failed", t); }
        if (l.dimension() == net.minecraft.world.level.Level.OVERWORLD && l.getGameTime() % 1200 == 0 && StreamWorks.chunksRead > 0) LOG.info("[Hopeless Tides] streams: {}", StreamWorks.probe(l));
        if (TRACKS.isEmpty()) return;
        for (Track t : new ArrayList<>(TRACKS)) {
            if (t.e.level() != l) continue;
            if (t.e.isRemoved()) { TRACKS.remove(t); continue; }
            t.ticks++; if (t.ticks == 10) { t.x10 = t.e.getX(); t.z10 = t.e.getZ(); } if (t.ticks == 50) { t.x40 = t.e.getX(); t.z40 = t.e.getZ(); }
            BlockPos top = Tides.surfaceBlock(l, (int) Math.floor(t.e.getX()), (int) Math.floor(t.e.getY()), (int) Math.floor(t.e.getZ()), 2, 12);
            if (t.ticks <= 60 && t.ticks % 6 == 0 && top == null) t.early.append(String.format(" [y %.2f v %.3f no surface]", t.e.getY(), t.e.getDeltaMovement().y));
            if (top == null) continue;
            Tides.Wave w = Tides.wave(l, t.e.getX(), t.e.getZ(), top, 0f);
            double y = t.e.getY(); t.base = top.getY() + l.getFluidState(top).getHeight(l, top);
            if (t.ticks <= 60 && t.ticks % 6 == 0) t.early.append(String.format(" %.2f/%.2f", y - t.base, w.surfaceY - t.base));
            if (t.ticks > 60) { t.minY = Math.min(t.minY, y); t.maxY = Math.max(t.maxY, y); t.s.add(new double[]{y, w.surfaceY}); t.inWater++; }
        }
    }
    /** After settling: how far the entity rises and falls, how well its height follows the wave surface under it (correlation and
     *  the spread left after its mean offset), and how far it drifted. */
    static String report(Track t) {
        int n = t.s.size(); double my = 0, ms = 0; for (double[] v : t.s) { my += v[0]; ms += v[1]; } my /= Math.max(1, n); ms /= Math.max(1, n);
        double cyy = 0, css = 0, cys = 0, res = 0; for (double[] v : t.s) { double a = v[0] - my, b = v[1] - ms; cyy += a * a; css += b * b; cys += a * b; res += (a - b) * (a - b); }
        double corr = cys / Math.sqrt(Math.max(1e-12, cyy * css));
        double sec = Math.max(1, t.ticks) / 20.0, vx = (t.e.getX() - t.x0) / sec, vz = (t.e.getZ() - t.z0) / sec;   // 0.4.0: mean drift velocity
        double ex = Double.isNaN(t.x40) ? 0 : (t.x40 - t.x10) / 2.0, ez = Double.isNaN(t.x40) ? 0 : (t.z40 - t.z10) / 2.0;   // ticks 10..50: before it reaches a bank
        return String.format("%s y now %.2f | ", t.what, t.e.getY()) + String.format("early drift %.2f %.2f (%.2f b/s, ticks 10-50) | drift velocity %.2f %.2f (%.2f b/s over %.1f s) | ", ex, ez, Math.hypot(ex, ez), vx, vz, Math.hypot(vx, vz), sec) + String.format("%s settled ticks %d: y swing %.2f, wave swing under it %.2f, correlation %.2f, spread after offset %.3f, mean offset %.2f, drift %.2f | first 3 s (y/wave above still water):%s",
            t.what, n, t.maxY - t.minY, 2 * Math.sqrt(css / Math.max(1, n)) * 1.414, corr, Math.sqrt(res / Math.max(1, n)), my - ms, Math.hypot(t.e.getX() - t.x0, t.e.getZ() - t.z0), t.early);
    }

    static void say(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c, String s) { c.getSource().sendSuccess(() -> Component.literal(s), false); LOG.info(s); }
    void commands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("tides").requires(s -> s.hasPermission(2))
            .then(Commands.literal("info").executes(c -> {
                ServerLevel l = c.getSource().getLevel(); var o = c.getSource().getPosition();
                BlockPos top = Tides.surfaceBlock(l, (int) Math.floor(o.x), (int) Math.floor(o.y), (int) Math.floor(o.z), 4, 8);
                if (top == null) { say(c, "[tides] no water surface here"); return 0; }
                Tides.Wave w = Tides.wave(l, o.x, o.z, top, 0f);
                say(c, String.format("[tides] surface block %s exposure %d depth %d | sea state %.2f wind %.0f deg | h %.2f (surface y %.2f) rising %.2f/s current %.2f %.2f breaking %s | %s",
                    top.toShortString(), w.p.exposure, w.p.depth, Tides.seaState(l, top), Math.toDegrees(WaveModel.wind(l.getDayTime())), w.p.h, w.surfaceY, w.p.dhdt, w.p.ux, w.p.uz, w.p.breaking, probe()));
                return 1; }))
            .then(Commands.literal("sea").then(Commands.literal("auto").executes(c -> { Tides.overrideState = -1; TidesNet.sendAll(); say(c, "[tides] sea state from the weather"); return 1; }))
                .then(Commands.argument("state", DoubleArgumentType.doubleArg(0, 1)).executes(c -> { Tides.overrideState = DoubleArgumentType.getDouble(c, "state"); TidesNet.sendAll(); say(c, "[tides] sea state fixed at " + Tides.overrideState + " (test)"); return 1; })))
            .then(Commands.literal("track").then(Commands.argument("what", StringArgumentType.word()).executes(c -> {
                // drop a boat / an item / a mob on the water here and follow it against the wave model
                ServerLevel l = c.getSource().getLevel(); var o = c.getSource().getPosition(); String w = StringArgumentType.getString(c, "what");
                BlockPos top = Tides.surfaceBlock(l, (int) Math.floor(o.x), (int) Math.floor(o.y), (int) Math.floor(o.z), 4, 8);
                if (top == null) { say(c, "[tides] no water surface here"); return 0; }
                net.minecraft.world.item.Item named = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(net.minecraft.resources.ResourceLocation.tryParse(w.contains(":") ? w : "minecraft:" + w));
                Entity en = switch (w) { case "boat" -> EntityType.BOAT.create(l); case "item" -> new net.minecraft.world.entity.item.ItemEntity(l, 0, 0, 0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG)); case "cod" -> EntityType.COD.create(l);
                    default -> named != null && named != net.minecraft.world.item.Items.AIR ? new net.minecraft.world.entity.item.ItemEntity(l, 0, 0, 0, new net.minecraft.world.item.ItemStack(named)) : EntityType.COD.create(l); };   // 0.5.0: any item by id
                if (en == null) return 0;
                en.moveTo(o.x, top.getY() + 1.0, o.z, 0, 0); l.addFreshEntity(en);
                Track t = new Track(); t.e = en; t.what = w; t.x0 = o.x; t.z0 = o.z; TRACKS.add(t); say(c, "[tides] tracking " + w + " at " + en.blockPosition().toShortString()); return 1; })))
            .then(Commands.literal("hydro")   // 0.7.0: the column water simulation
                .executes(c -> { say(c, "[tides] " + HydroSim.status()); return 1; })
                .then(Commands.literal("on").executes(c -> { TidesConfig.setHydro(true); say(c, "[tides] hydro on (saved)"); return 1; }))
                .then(Commands.literal("off").executes(c -> { TidesConfig.setHydro(false); say(c, "[tides] hydro off (saved; regions dropped next tick)"); return 1; }))
                .then(Commands.literal("count").executes(c -> { say(c, "[tides] hydro count: " + HydroSim.count()); return 1; }))
                .then(Commands.literal("pin").then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer()).executes(c -> {
                    HydroSim.PINS.add(new double[]{IntegerArgumentType.getInteger(c, "x") + 0.5, IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z") + 0.5}); say(c, "[tides] hydro pinned (test)"); return 1; })))))
                .then(Commands.literal("unpin").executes(c -> { HydroSim.PINS.clear(); say(c, "[tides] hydro pins cleared"); return 1; }))
                .then(Commands.literal("probe").then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer()).executes(c -> {
                    say(c, "[tides] " + HydroSim.probe(c.getSource().getLevel(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"))); return 1; })))))
            .then(Commands.literal("fft")   // 0.9.0: the Fourier waves over the column water
                .executes(c -> { var o = c.getSource().getPosition(); BlockPos bp = BlockPos.containing(o.x, o.y, o.z); ServerLevel l = c.getSource().getLevel();
                    say(c, "[tides] " + Tides.fftProbe() + String.format(" | here: sky light %d raw %d block %d, skyState %.2f", l.getBrightness(net.minecraft.world.level.LightLayer.SKY, bp), l.getLightEngine().getLayerListener(net.minecraft.world.level.LightLayer.SKY).getLightValue(bp), l.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, bp), Tides.skyState(l))); return 1; })
                .then(Commands.literal("on").executes(c -> { TidesConfig.setFft(true); say(c, "[tides] fft on (saved, sent to clients)"); return 1; }))
                .then(Commands.literal("off").executes(c -> { TidesConfig.setFft(false); say(c, "[tides] fft off (saved, sent to clients): the 12-wave model"); return 1; })))
            .then(Commands.literal("tide").executes(c -> { ServerLevel l = c.getSource().getLevel();   // 0.11.0
                var pl = Tides.planet(l);
                say(c, String.format("[tides] tide %s, amplitude %.2f%s, now %+.3f blocks (moon phase %d, day time %d)", Tides.tideOn ? "on" : "off", Tides.tideAmp,
                    pl == null ? " (Earth)" : String.format(" x %.3f = %.3f at spring, neap x %.2f (planet %s, lunar share %.3f)", pl.tideRatio(), Tides.tideAmp * pl.tideRatio(), Math.abs(2 * pl.lunarShare() - 1), pl.name(), pl.lunarShare()),
                    Tides.tideNow(l), l.getMoonPhase(), l.getDayTime() % 24000) + (com.hopeless.tides.api.TidesPlanets.tide(l.dimension().location().toString()) != null ? String.format(" | from the real sky (Hopeless Infinity): calls %d, errors %d", com.hopeless.tides.api.TidesPlanets.tideCalls, com.hopeless.tides.api.TidesPlanets.tideErrors) : "")); return 1; }))
            .then(Commands.literal("surf").executes(c -> { var o = c.getSource().getPosition(); ServerLevel l = c.getSource().getLevel(); double t = TidesSurf.seconds(l, 0f);   // 0.14.0
                    double[] b = TidesSurf.bore(l, o.x, o.z, t), sw = TidesSurf.swash(l, o.x, o.z, t);
                    say(c, "[tides] " + TidesSurf.probe() + (b == null ? " | here: no bores" : String.format(" | here: bore eta %+.2f weight %.2f foam %.2f flow %.2f m/s", b[0], b[1], b[4], b[5])) + (sw == null ? "" : String.format(" | swash sheet %.2f speed %+.2f wet %.2f", sw[0], sw[1], sw[4]))
                        + swellHere(l, TidesSurf.swell(l, o.x, o.z, t)) + " | swell train samples " + Tides.swellSamples); return 1; })
                .then(Commands.literal("chop").executes(c -> { say(c, "[tides] shallow wind sea " + (TidesSurf.chopOn ? "on" : "off") + ", solves " + TidesSurf.chops + ", corners " + Tides.chopCorners); return 1; })   // 0.16.3
                    .then(Commands.literal("on").executes(c -> { TidesSurf.chopOn = true; say(c, "[tides] shallow wind sea on: near shores the short waves turn to the shore, steepen and break"); return 1; }))
                    .then(Commands.literal("off").executes(c -> { TidesSurf.chopOn = false; say(c, "[tides] shallow wind sea off: the short waves run with the wind right up to the beach (0.16.2); the swell train, bores and swash stay"); return 1; })))
                .then(Commands.literal("shore").executes(c -> { say(c, "[tides] shore map " + (com.hopeless.tides.core.ShoreMap.on ? "on" : "off") + " (the client log line shows its builds and frames)"); return 1; })   // 0.17.0
                    .then(Commands.literal("on").executes(c -> { com.hopeless.tides.core.ShoreMap.on = true; say(c, "[tides] shore map on: every wave knows where the shore is; in shallow water the crests run along the shore, shoal, break and reflect off steep banks; land blocks the swell and shortens the fetch"); return 1; }))
                    .then(Commands.literal("off").executes(c -> { com.hopeless.tides.core.ShoreMap.on = false; say(c, "[tides] shore map off: beyond the 128-block surf field the sea runs through land again (0.16.3): no lee, no turning, no breaking there"); return 1; })))
                .then(Commands.literal("train").executes(c -> { say(c, "[tides] swell train " + (TidesSurf.trainOn ? "on" : "off")); return 1; })   // 0.16.1
                    .then(Commands.literal("on").executes(c -> { TidesSurf.trainOn = true; say(c, "[tides] swell train on: the swell comes in from the open ocean and turns to the shore; the wind sea runs with the wind"); return 1; }))
                    .then(Commands.literal("off").executes(c -> { TidesSurf.trainOn = false; say(c, "[tides] swell train off: the swell is in the Fourier sea and runs one way everywhere (0.16.0); the surf is solved for the bigger sea"); return 1; })))
                .then(Commands.literal("on").executes(c -> { TidesSurf.on = true; say(c, "[tides] surf on"); return 1; }))
                .then(Commands.literal("off").executes(c -> { TidesSurf.on = false; say(c, "[tides] surf off: the sea meets the shore as in 0.13 (no bores, no swash; the old shore push comes back)"); return 1; })))
            .then(Commands.literal("sight").executes(c -> { say(c, "[tides] sight under water: " + (com.hopeless.tides.core.WaterOptics.useRecords ? "the published records" : "500 m in every type (game)")); return 1; })   // 0.15.0
                .then(Commands.literal("records").executes(c -> { com.hopeless.tides.core.WaterOptics.useRecords = true; say(c, "[tides] sight: published records (warm sea 18 m ... humic 0.6 m)"); return 1; }))
                .then(Commands.literal("game").executes(c -> { com.hopeless.tides.core.WaterOptics.useRecords = false; say(c, "[tides] sight: 500 m in every type"); return 1; })))
            .then(Commands.literal("planet").executes(c -> { ServerLevel l = c.getSource().getLevel();   // 0.13.0: what the world mod told Tides
                var pl = Tides.planet(l);
                say(c, "[tides] planet here (" + l.dimension().location() + "): " + (pl == null ? "none (Earth values)" : String.format("%s g %.2f m/s2, wave height x %.3f, wave speed x %.3f, tide x %.3f, lunar share %.3f, column water %s",
                    pl.name(), pl.g(), Tides.heightScale(l), Math.sqrt(pl.g() / 9.81), pl.tideRatio(), pl.lunarShare(), "on")) + " | planets known " + com.hopeless.tides.api.TidesPlanets.all().size() + " | sent " + TidesNet.planetsSent); return 1; }))
            .then(Commands.literal("wind").executes(c -> {
                // 0.6.0: the cave wind at the caller, worked out now, with its parts
                var o = c.getSource().getPosition(); double[] w = CaveWind.at(c.getSource().getLevel(), o.x, o.y, o.z, 0);
                say(c, "[tides] " + CaveWind.describe(w) + " | " + CaveWind.probe()); return 1; }))
            .then(Commands.literal("flow").executes(c -> {
                // 0.4.0: the river current at the caller's column
                var o = c.getSource().getPosition(); float[] f = Currents.at(c.getSource().getLevel(), (int) Math.floor(o.x), (int) Math.floor(o.z));
                say(c, f == null ? "[tides] flow: none here | " + Currents.probe() : String.format("[tides] flow: dir %.2f %.2f speed %.2f b/s stage %d", f[0], f[1], f[2], (int) f[3])); return 1; }))
            .then(Commands.literal("report").executes(c -> { for (Track t : TRACKS) say(c, "[tides] " + report(t)); if (TRACKS.isEmpty()) say(c, "[tides] nothing tracked"); return 1; }))
            .then(Commands.literal("streams")
                .executes(c -> { say(c, "[tides] streams: " + StreamWorks.probe(c.getSource().getLevel())); return 1; })
                .then(Commands.literal("scan").executes(c -> { say(c, "[tides] streams near: " + StreamWorks.scan(c.getSource().getLevel())); return 1; }))
                .then(Commands.literal("col").then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer()).executes(c -> {
                    say(c, "[tides] " + StreamWorks.column(c.getSource().getLevel(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"))); return 1; }))))
                .then(Commands.literal("set").then(Commands.argument("soil", DoubleArgumentType.doubleArg(0, 2)).then(Commands.argument("ground", DoubleArgumentType.doubleArg(0, 2)).executes(c -> {
                    StreamWorks.Store s = StreamWorks.store(c.getSource().getLevel()); s.h.soil = DoubleArgumentType.getDouble(c, "soil"); s.h.ground = DoubleArgumentType.getDouble(c, "ground"); s.setDirty();
                    say(c, String.format("[tides] streams set: soil %.2f ground %.2f -> gullies %.0f%% creeks %.0f%% river stage %d", s.h.soil, s.h.ground, 100 * s.h.ephemeral(), 100 * s.h.intermittent(), s.h.flood())); return 1; }))))
                .then(Commands.literal("freeze").executes(c -> { StreamWorks.Store s = StreamWorks.store(c.getSource().getLevel()); s.frozen = true; s.setDirty(); say(c, "[tides] streams frozen (tests)"); return 1; }))
                .then(Commands.literal("unfreeze").executes(c -> { StreamWorks.Store s = StreamWorks.store(c.getSource().getLevel()); s.frozen = false; s.setDirty(); say(c, "[tides] streams follow the weather"); return 1; })))
            .then(Commands.literal("clear").executes(c -> { for (Track t : TRACKS) t.e.discard(); TRACKS.clear(); say(c, "[tides] cleared"); return 1; })));
    }
    static final class HydroErrors { static int n; }
    public static String probe() { return HydroSim.status().split(" \\| ")[0] + " | " + CaveWind.probe() + " | " + Currents.probe() + " | buoyancy: light " + Buoyancy.light + " wood " + Buoyancy.wood + " ice " + Buoyancy.ice + " plain " + Buoyancy.plain + " stone " + Buoyancy.stone + " metal " + Buoyancy.metal + " sinking " + Buoyancy.sinking + " | " + Tides.fftProbe() + " | iced " + Tides.iced + " | samples " + Tides.samples + " node misses " + Tides.nodeMisses + " corner misses " + Tides.cornerMisses + " tables " + com.hopeless.tides.core.WaveModel.tablesBuilt + " entity hits " + Tides.surfaceHits + " surf checks " + Tides.surfChecks + " surf pushes " + Tides.surfPushes + " | " + TidesSurf.probe() + " | surf samples " + Tides.surfSamples + " swash pushes " + Tides.swashPushes + " wall samples " + Tides.wallSamples + " wall pushes " + Tides.wallPushes; }
    static String swellHere(net.minecraft.world.level.Level l, double[] sw) { TidesSurf.Host h = TidesSurf.viewer(l); double sh = TidesSurf.share(h);
        return sw == null ? " | swell train: none here" + (h == null ? "" : String.format(" (swell %.2f m, share %.2f)", h.swellHs, sh)) : String.format(" | swell train here: eta %+.2f m, heading %.0f deg, share %.2f", sw[0], Math.toDegrees(h.field.heading), sh); }
}
