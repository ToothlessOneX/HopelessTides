package com.hopeless.tides.client;

import com.hopeless.tides.HopelessTides;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

/** Client probes into the game log every 30 seconds while they change: is the water being marked, the shader patched, the uniforms set? */
public final class TidesClientLog {
    private TidesClientLog() {}
    static int ticks; static String last = "";
    public static void init() {
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
            if (e.phase != TickEvent.Phase.END || ++ticks % 600 != 0) return;
            String s = TidesRender.probe() + " | " + TidesShore.probe() + " | " + TidesGl.probe() + " | " + HopelessTides.probe();
            if (!s.equals(last)) { HopelessTides.LOG.info("[Hopeless Tides] client: {}", s); last = s; }
        });
    }
}
