package com.hopeless.tides;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/** The one thing the client cannot work out for itself: a sea state fixed by /tides sea (tests). Weather and time are synced by vanilla. */
public final class TidesNet {
    private TidesNet() {}
    static final String V = "7";   // 0.13.0: Planets   // 0.9.0: Sea carries the FFT switch
    public static final SimpleChannel CH = NetworkRegistry.newSimpleChannel(new ResourceLocation(HopelessTides.MODID, "main"), () -> V, v -> true, v -> true);
    public record Sea(double state, boolean fft, boolean tide, double tideAmp) {
        void enc(FriendlyByteBuf b) { b.writeDouble(state); b.writeBoolean(fft); b.writeBoolean(tide); b.writeDouble(tideAmp); }
        static Sea dec(FriendlyByteBuf b) { return new Sea(b.readDouble(), b.readBoolean(), b.readBoolean(), b.readDouble()); }
        void handle(Supplier<NetworkEvent.Context> c) { c.get().enqueueWork(() -> { Tides.overrideState = state; Tides.fftOn = fft; Tides.tideOn = tide; Tides.tideAmp = tideAmp; }); c.get().setPacketHandled(true); }
    }
    /** 0.4.0: the river current where this player swims (speed 0: none). */
    public record Current(float fx, float fz, float speed) {
        void enc(FriendlyByteBuf b) { b.writeFloat(fx); b.writeFloat(fz); b.writeFloat(speed); }
        static Current dec(FriendlyByteBuf b) { return new Current(b.readFloat(), b.readFloat(), b.readFloat()); }
        void handle(Supplier<NetworkEvent.Context> c) { c.get().enqueueWork(() -> Currents.clientFlow = speed > 0 ? new float[]{fx, fz, speed} : null); c.get().setPacketHandled(true); }
    }
    /** 0.6.0: the cave wind where this player is (u < 0: under the open sky, use the weather). */
    public record Wind(float u, float dir) {
        void enc(FriendlyByteBuf b) { b.writeFloat(u); b.writeFloat(dir); }
        static Wind dec(FriendlyByteBuf b) { return new Wind(b.readFloat(), b.readFloat()); }
        void handle(Supplier<NetworkEvent.Context> c) { c.get().enqueueWork(() -> Tides.caveWindClient = u < 0 ? null : new float[]{u, dir}); c.get().setPacketHandled(true); }
    }
    /** 0.7.0: the column simulation's speed and direction per column of a region, for the river look (classes, see HydroSim.flowGrid). */
    public record Flow(int ox, int oz, int refY, int n, byte[] sp, byte[] dir) {
        void enc(FriendlyByteBuf b) { b.writeInt(ox); b.writeInt(oz); b.writeInt(refY); b.writeShort(n); b.writeByteArray(sp); b.writeByteArray(dir); }
        static Flow dec(FriendlyByteBuf b) { return new Flow(b.readInt(), b.readInt(), b.readInt(), b.readShort(), b.readByteArray(), b.readByteArray()); }
        void handle(Supplier<NetworkEvent.Context> c) { c.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> com.hopeless.tides.client.FlowClient.accept(ox, oz, refY, n, sp, dir))); c.get().setPacketHandled(true); }
    }
    /** 0.13.0: the planet table (TidesPlanets): dimension, g, tide ratio, lunar share, name. The client replaces its table with it. */
    public record Planets(java.util.List<String> dims, double[] g, double[] tide, double[] lunar, java.util.List<String> names) {
        void enc(FriendlyByteBuf b) { b.writeVarInt(dims.size()); for (int i = 0; i < dims.size(); i++) { b.writeUtf(dims.get(i)); b.writeDouble(g[i]); b.writeDouble(tide[i]); b.writeDouble(lunar[i]); b.writeUtf(names.get(i)); } }
        static Planets dec(FriendlyByteBuf b) { int n = b.readVarInt(); java.util.List<String> d = new java.util.ArrayList<>(), nm = new java.util.ArrayList<>(); double[] g = new double[n], t = new double[n], u = new double[n];
            for (int i = 0; i < n; i++) { d.add(b.readUtf()); g[i] = b.readDouble(); t[i] = b.readDouble(); u[i] = b.readDouble(); nm.add(b.readUtf()); } return new Planets(d, g, t, u, nm); }
        void handle(Supplier<NetworkEvent.Context> c) { c.get().enqueueWork(() -> { if (net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer() == null) com.hopeless.tides.api.TidesPlanets.clearAll();   // a remote server: take its table (single player shares the server's)
            for (int i = 0; i < dims.size(); i++) com.hopeless.tides.api.TidesPlanets.set(dims.get(i), g[i], tide[i], lunar[i], names.get(i)); planetsReceived++; }); c.get().setPacketHandled(true); }
        static Planets now() { var m = com.hopeless.tides.api.TidesPlanets.all(); java.util.List<String> d = new java.util.ArrayList<>(m.keySet()), nm = new java.util.ArrayList<>(); int n = d.size(); double[] g = new double[n], t = new double[n], u = new double[n];
            for (int i = 0; i < n; i++) { var p = m.get(d.get(i)); g[i] = p.g(); t[i] = p.tideRatio(); u[i] = p.lunarShare(); nm.add(p.name()); } return new Planets(d, g, t, u, nm); }
    }
    public static volatile long planetsSent, planetsReceived;
    public static void planets(ServerPlayer p) { CH.send(PacketDistributor.PLAYER.with(() -> p), Planets.now()); planetsSent++; }
    public static void planetsAll() { CH.send(PacketDistributor.ALL.noArg(), Planets.now()); planetsSent++; }
    public static volatile long flowSent;
    static void flow(HydroSim.Region r) {
        byte[][] g = HydroSim.flowGrid(r); Flow f = new Flow(r.ox, r.oz, r.refY, HydroSim.N, g[0], g[1]);
        for (ServerPlayer p : r.level.players()) if (Math.abs(p.getX() - (r.ox + HydroSim.N / 2.0)) < HydroSim.N && Math.abs(p.getZ() - (r.oz + HydroSim.N / 2.0)) < HydroSim.N) { CH.send(PacketDistributor.PLAYER.with(() -> p), f); flowSent++; }
    }
    public static void wind(ServerPlayer p, float u, float dir) { CH.send(PacketDistributor.PLAYER.with(() -> p), new Wind(u, dir)); }
    public static void init() { CH.messageBuilder(Sea.class, 0, NetworkDirection.PLAY_TO_CLIENT).encoder(Sea::enc).decoder(Sea::dec).consumerMainThread(Sea::handle).add();
        CH.messageBuilder(Current.class, 1, NetworkDirection.PLAY_TO_CLIENT).encoder(Current::enc).decoder(Current::dec).consumerMainThread(Current::handle).add();
        CH.messageBuilder(Wind.class, 2, NetworkDirection.PLAY_TO_CLIENT).encoder(Wind::enc).decoder(Wind::dec).consumerMainThread(Wind::handle).add();
        CH.messageBuilder(Flow.class, 3, NetworkDirection.PLAY_TO_CLIENT).encoder(Flow::enc).decoder(Flow::dec).consumerMainThread(Flow::handle).add();
        CH.messageBuilder(Planets.class, 4, NetworkDirection.PLAY_TO_CLIENT).encoder(Planets::enc).decoder(Planets::dec).consumerMainThread(Planets::handle).add(); }
    public static void current(ServerPlayer p, float fx, float fz, float speed) { CH.send(PacketDistributor.PLAYER.with(() -> p), new Current(fx, fz, speed)); }
    public static void sendAll() { CH.send(PacketDistributor.ALL.noArg(), new Sea(Tides.overrideState, Tides.fftOn, Tides.tideOn, Tides.tideAmp)); }
    public static void send(ServerPlayer p) { CH.send(PacketDistributor.PLAYER.with(() -> p), new Sea(Tides.overrideState, Tides.fftOn, Tides.tideOn, Tides.tideAmp)); }
}
