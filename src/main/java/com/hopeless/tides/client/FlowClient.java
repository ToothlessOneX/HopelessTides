package com.hopeless.tides.client;

import net.minecraft.client.Minecraft;

import java.util.HashSet;
import java.util.Set;

/** 0.7.0: the column simulation's speed and direction per column, as the server last sent them (TidesNet.Flow); read by the mesher
 *  to give each water quad its current, so the shader draws the river look on moving water whatever block shape it is. When a
 *  column's class changes, its chunk sections are re-meshed (at most 256 a packet). */
public final class FlowClient {
    private FlowClient() {}
    static { com.hopeless.tides.Tides.clientFlowClass = FlowClient::code; }   // 0.9.0: the client's physics reads the same classes the mesher does
    /** 0.9.0: speed class at a block corner, the fastest of the four columns round it (Tides.cornerCurrent uses the same rule). */
    public static int cornerSpeed(int cx, int cz) { return Math.max(Math.max(code(cx - 1, cz - 1) & 15, code(cx, cz - 1) & 15), Math.max(code(cx - 1, cz) & 15, code(cx, cz) & 15)); }
    public static volatile long packets, remeshed, lookups, hits;
    static volatile int ox = Integer.MIN_VALUE, oz, refY, n; static volatile byte[] sp = new byte[0], dir = new byte[0];
    public static void accept(int nox, int noz, int nrefY, int nn, byte[] nsp, byte[] ndir) {
        packets++;
        Set<Long> sections = new HashSet<>();
        for (int j = 0; j < nn; j++) for (int i = 0; i < nn; i++) {
            int c = i + nn * j, x = nox + i, z = noz + j;
            int before = code(x, z), after = (nsp[c] & 15) | ((ndir[c] & 15) << 4);
            if (before != after) for (int dz = 0; dz <= 1; dz++) for (int dx = 0; dx <= 1; dx++)   // 0.9.0: corners reach the next block too
                sections.add(((long) ((x + dx) >> 4) << 32) ^ (((z + dz) >> 4) & 0xFFFFFFFFL));
        }
        ox = nox; oz = noz; refY = nrefY; n = nn; sp = nsp; dir = ndir;
        var lr = Minecraft.getInstance().levelRenderer; int done = 0;
        for (long s : sections) { int sx = (int) (s >> 32), sz = (int) s;
            for (int sy = (nrefY - 40) >> 4; sy <= (nrefY + 16) >> 4; sy++) { lr.setSectionDirty(sx, sy, sz); remeshed++; }
            if (++done >= 256) break; }
    }
    /** speed class (low 4 bits) | direction class << 4, or 0 when the column has no data. */
    public static int code(int x, int z) {
        lookups++;
        int i = x - ox, j = z - oz, nn = n; byte[] s = sp, d = dir;
        if (ox == Integer.MIN_VALUE || i < 0 || j < 0 || i >= nn || j >= nn || s.length < nn * nn) return 0;
        int c = i + nn * j; int v = (s[c] & 15) | ((d[c] & 15) << 4); if (v != 0) hits++; return v;
    }
    public static String probe() { return "flow: packets " + packets + " sections re-meshed " + remeshed + " lookups " + lookups + " hits " + hits; }
}
