package com.hopeless.tides.core;

import java.util.Arrays;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * 0.7.0: THE WATER CORE of Hopeless Hydro (Basin.cs, 0.8.2), ported line for line to Java. Game-free; checked offline (tools/BasinCheck).
 *
 * A square of N x N columns, each Dx metres across, over the ground. Every column holds an amount of water, counted in whole TICKS of
 * 2^-30 m of depth, and water only moves from one column to its neighbour as a whole number of ticks. So the total is exact:
 * Poured - Drained == Held, to the tick, on every step (ledgerError), and nothing is made or lost.
 *
 * HOW IT MOVES - the shallow-water equations on a staggered grid, in the momentum-conservative form of Stelling & Duinmeijer (2003):
 * depth at column centres, velocity on the faces between them.
 *   MOMENTUM, per face: du/dt = -g (eta_b - eta_a)/Dx - (upwind momentum-conservative advection) - g n^2 u|u| / h^(4/3)
 *     (Manning bed friction, implicit so it never reverses the flow; n = 0.030, Chow 1959, natural stream, clean, straight).
 *   MASS: each face carries u * h_face, h_face = water above the HIGHER of the two beds on the upwind side (hydrostatic
 *     reconstruction): a lake at rest stays at rest over any bed, and a dry column cannot push.
 *   No column gives more than it holds. X faces move first, row by row, then Z faces, column by column. What a face cannot move this
 *   substep as a whole tick is OWED to it and moved when it adds up. The step is split so no wave crosses more than half a column
 *   per substep (CFL). Only blocks of 16 x 16 columns holding water, and their neighbours, are worked on; still, flat blocks sleep.
 */
public final class Basin {
    public static final double TICK = 1.0 / 1073741824.0;     // metres of depth per tick (2^-30 m)
    public static final double MANNING_DEFAULT = 0.030;
    public static final int B = 16;
    public static final long DRY_TICKS = 2147484;               // under 2 mm: drawn as dry
    public static final double WET_FACE = 1e-4;

    public final int N; public final double Dx, Area;
    public final double[] Bed; public final long[] D; public final boolean[] Known;
    public float[] U, V;
    public double G = 9.81, Manning = MANNING_DEFAULT;
    public boolean Parallel = false, Advection = true, Sleep = true;
    public long Poured, Drained;
    public long Steps, Substeps, Wakes, Sleeps; public int ActiveBlocks, WetColumns, SubstepsLast, CflCapped;
    public double MaxSpeed, MaxDepth, LastMs;

    static final int SLEEP_AFTER = 30; static final float STILL_SPEED = 1e-3f; static final double WAKE_ETA = 1e-5, SLEEP_ETA = 1e-6;
    final int nb;
    final boolean[] active, wet, was, dirty, asleep, wake;
    final int[] quiet; final long[] qSum;
    int[] sleepList; int nSleep;
    int[] list; int[][] rowBlocks, colBlocks; int nList;
    boolean rescan = true, anyDirty;
    float[] u2, v2; final float[] qx, qz, hx, hz; final double[] fx, fz, owe, k;

    public Basin(int n, double dx) {
        N = n; Dx = dx; Area = dx * dx; int c = n * n;
        Bed = new double[c]; D = new long[c]; Known = new boolean[c]; Arrays.fill(Known, true);
        U = new float[c]; V = new float[c]; u2 = new float[c]; v2 = new float[c];
        qx = new float[c]; qz = new float[c]; hx = new float[c]; hz = new float[c];
        fx = new double[c]; fz = new double[c]; k = new double[c]; owe = new double[2 * c]; Arrays.fill(k, 1);
        nb = (n + B - 1) / B; int q = nb * nb;
        active = new boolean[q]; wet = new boolean[q]; was = new boolean[q]; dirty = new boolean[q]; asleep = new boolean[q]; wake = new boolean[q];
        quiet = new int[q]; qSum = new long[q]; sleepList = new int[q]; list = new int[q];
        rowBlocks = new int[nb][]; colBlocks = new int[nb][]; for (int i = 0; i < nb; i++) { rowBlocks[i] = new int[0]; colBlocks[i] = new int[0]; }
    }
    public int index(int i, int j) { return i + N * j; }
    public double depth(int c) { return D[c] * TICK; }
    public double surface(int c) { return Bed[c] + D[c] * TICK; }
    public long held() { long s = 0; for (long d : D) s += d; return s; }
    public String ledgerError() { long h = held(); return Poured - Drained == h ? null : "ledger off by " + (Poured - Drained - h) + " ticks"; }
    public double energy() { double e = 0; for (int c = 0; c < D.length; c++) { if (D[c] == 0) continue; double h = D[c] * TICK; e += G * h * (Bed[c] + 0.5 * h) * Area + 0.5 * (hx[c] * U[c] * U[c] + hz[c] * V[c] * V[c]) * Area; } return e; }
    int blockOf(int c) { return (c % N) / B + nb * ((c / N) / B); }

    /** Add (ticks > 0) or remove (ticks < 0, never more than is there) water in one column; returns what moved. */
    public long change(int c, long ticks) {
        if (ticks < 0) ticks = Math.max(ticks, -D[c]);
        D[c] += ticks;
        if (ticks != 0) { dirty[blockOf(c)] = true; anyDirty = true; }
        if (ticks > 0) Poured += ticks; else Drained -= ticks;
        return ticks;
    }
    public void setBed(int c, double bed) { Bed[c] = bed; dirty[blockOf(c)] = true; anyDirty = true; }
    public void setKnown(int c, boolean kn) { Known[c] = kn; dirty[blockOf(c)] = true; anyDirty = true; }
    public void rescan() { rescan = true; for (int q = 0; q < asleep.length; q++) if (asleep[q]) { asleep[q] = false; quiet[q] = 0; Wakes++; } }
    public boolean blockAsleep(int bi, int bj) { return asleep[bi + nb * bj]; }
    public boolean blockActive(int bi, int bj) { return active[bi + nb * bj]; }
    public int blocks() { return nb; }
    public int sleepingBlocks() { return nSleep; }

    public void step(double dt, int maxSub) {
        long t0 = System.nanoTime();
        activate();
        double c = 0, umax = 0;
        for (int q = 0; q < nList; q++) {
            int blk = list[q], i0 = (blk % nb) * B, j0 = (blk / nb) * B, i1 = Math.min(N, i0 + B), j1 = Math.min(N, j0 + B);
            for (int j = j0; j < j1; j++) for (int a = i0 + N * j, e = i1 + N * j; a < e; a++) {
                if (D[a] > 0) { double sp = Math.sqrt(G * D[a] * TICK); if (sp > c) c = sp; }
                double x = Math.abs(U[a]); if (x > umax) umax = x; x = Math.abs(V[a]); if (x > umax) umax = x;
            }
        }
        double speed = c + umax;
        int n = speed > 0 ? (int) Math.ceil(dt * speed / (0.5 * Dx)) : 1;
        if (n < 1) n = 1; if (n > maxSub) { n = maxSub; CflCapped++; }
        double h = dt / n;
        for (int s = 0; s < n; s++) { substep(h); Substeps++; }
        SubstepsLast = n; Steps++;
        measure(); settle();
        LastMs = (System.nanoTime() - t0) / 1e6;
    }

    void activate() {
        System.arraycopy(active, 0, was, 0, active.length);
        for (int q = 0; q < nb * nb; q++) {
            if (!asleep[q]) continue;
            boolean w = dirty[q] || wake[q] || !Sleep;
            if (!w) w = uneven(q, WAKE_ETA, true);
            if (w) { asleep[q] = false; quiet[q] = 0; Wakes++; }
        }
        Arrays.fill(wake, false);
        if (rescan) {
            Arrays.fill(wet, false);
            for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) if (D[i + N * j] > 0) wet[i / B + nb * (j / B)] = true;
            rescan = false;
        } else {
            for (int q = 0; q < nb * nb; q++) {
                if (asleep[q]) { wet[q] = true; continue; }
                if (!was[q] && !dirty[q]) { wet[q] = false; continue; }
                int i0 = (q % nb) * B, j0 = (q / nb) * B, i1 = Math.min(N, i0 + B), j1 = Math.min(N, j0 + B); boolean w = false;
                for (int j = j0; j < j1 && !w; j++) for (int a = i0 + N * j, e = i1 + N * j; a < e; a++) if (D[a] > 0) { w = true; break; }
                wet[q] = w;
            }
        }
        Arrays.fill(dirty, false); anyDirty = false;
        int act = 0, sl = 0;
        for (int bj = 0; bj < nb; bj++) for (int bi = 0; bi < nb; bi++) {
            int q = bi + nb * bj;
            if (asleep[q]) { active[q] = false; sleepList[sl++] = q; if (was[q]) clearBlock(q); continue; }
            boolean a = false;
            for (int dj = -1; dj <= 1 && !a; dj++) for (int di = -1; di <= 1 && !a; di++) { int x = bi + di, y = bj + dj; if (x >= 0 && y >= 0 && x < nb && y < nb && wet[x + nb * y] && !asleep[x + nb * y]) a = true; }
            active[q] = a;
            if (a) list[act++] = q; else if (was[q]) clearBlock(q);
        }
        nList = act; ActiveBlocks = act; nSleep = sl;
        int[] rc = new int[nb], cc = new int[nb];
        for (int q = 0; q < act; q++) { rc[list[q] / nb]++; cc[list[q] % nb]++; }
        for (int x = 0; x < nb; x++) { if (rowBlocks[x].length != rc[x]) rowBlocks[x] = new int[rc[x]]; if (colBlocks[x].length != cc[x]) colBlocks[x] = new int[cc[x]]; rc[x] = 0; cc[x] = 0; }
        for (int q = 0; q < act; q++) { int bi = list[q] % nb, bj = list[q] / nb; rowBlocks[bj][rc[bj]++] = bi; colBlocks[bi][cc[bi]++] = bj; }
    }
    boolean flatInside(int q, double tol) {
        int i0 = (q % nb) * B, j0 = (q / nb) * B, i1 = Math.min(N, i0 + B), j1 = Math.min(N, j0 + B);
        for (int j = j0; j < j1; j++) for (int i = i0; i < i1; i++) { int a = i + N * j; if (!Known[a]) continue;
            if (i + 1 < i1 && Known[a + 1] && !level(a, a + 1, tol)) return false;
            if (j + 1 < j1 && Known[a + N] && !level(a, a + N, tol)) return false; }
        return true;
    }
    boolean level(int a, int b, double tol) { double ea = eta(a), eb = eta(b), top = Math.max(Bed[a], Bed[b]); return Math.max(ea, eb) - top <= WET_FACE || Math.abs(ea - eb) <= tol; }
    boolean uneven(int q, double tol, boolean awakeOnly) {
        int bi = q % nb, bj = q / nb, i0 = bi * B, j0 = bj * B, i1 = Math.min(N, i0 + B) - 1, j1 = Math.min(N, j0 + B) - 1;
        for (int side = 0; side < 4; side++) {
            int nbi = bi + (side == 0 ? -1 : side == 1 ? 1 : 0), nbj = bj + (side == 2 ? -1 : side == 3 ? 1 : 0);
            if (nbi < 0 || nbj < 0 || nbi >= nb || nbj >= nb) continue;
            int nq = nbi + nb * nbj; if (awakeOnly && (asleep[nq] || !active[nq])) continue;
            int len = side < 2 ? j1 - j0 + 1 : i1 - i0 + 1;
            for (int t = 0; t < len; t++) { int a, b;
                if (side == 0) { a = i0 + N * (j0 + t); b = a - 1; } else if (side == 1) { a = i1 + N * (j0 + t); b = a + 1; }
                else if (side == 2) { a = (i0 + t) + N * j0; b = a - N; } else { a = (i0 + t) + N * j1; b = a + N; }
                if (!Known[a] || !Known[b]) continue;
                double ea = eta(a), eb = eta(b), top = Math.max(Bed[a], Bed[b]);
                if (Math.max(ea, eb) - top > WET_FACE && Math.abs(ea - eb) > tol) return true; }
        }
        return false;
    }
    void settle() {
        if (!Sleep) return;
        for (int q = 0; q < nList; q++) {
            int b = list[q]; if (!wet[b]) { quiet[b] = 0; continue; }
            int i0 = (b % nb) * B, j0 = (b / nb) * B, i1 = Math.min(N, i0 + B), j1 = Math.min(N, j0 + B);
            boolean still = true; long sum = 0;
            for (int j = j0; j < j1 && still; j++) for (int a = i0 + N * j, e = i1 + N * j; a < e; a++) {
                if (Math.abs(U[a]) >= STILL_SPEED || Math.abs(V[a]) >= STILL_SPEED) { still = false; break; } sum += D[a]; }
            if (!still || dirty[b] || !flatInside(b, SLEEP_ETA) || uneven(b, SLEEP_ETA, false)) { quiet[b] = 0; continue; }
            if (quiet[b] == 0) qSum[b] = sum;
            else if (Math.abs(sum - qSum[b]) > (long) (SLEEP_ETA * (i1 - i0) * (j1 - j0) / TICK)) { quiet[b] = 0; continue; }
            if (++quiet[b] >= SLEEP_AFTER) { asleep[b] = true; Sleeps++; }
        }
    }
    void clearBlock(int q) {
        int i0 = (q % nb) * B, j0 = (q / nb) * B, i1 = Math.min(N, i0 + B), j1 = Math.min(N, j0 + B);
        for (int j = j0; j < j1; j++) for (int a = i0 + N * j, e = i1 + N * j; a < e; a++) { U[a] = V[a] = u2[a] = v2[a] = 0; qx[a] = qz[a] = hx[a] = hz[a] = 0; fx[a] = fz[a] = 0; k[a] = 1; }
    }
    interface Col { void at(int i, int j); }
    void forActive(Col body) {
        IntConsumer blk = q -> { int b = list[q], i0 = (b % nb) * B, j0 = (b / nb) * B, i1 = Math.min(N, i0 + B), j1 = Math.min(N, j0 + B);
            for (int j = j0; j < j1; j++) for (int i = i0; i < i1; i++) body.at(i, j); };
        if (Parallel) IntStream.range(0, nList).parallel().forEach(blk); else for (int q = 0; q < nList; q++) blk.accept(q);
    }
    void forN(int n, IntConsumer body) { if (Parallel) IntStream.range(0, n).parallel().forEach(body); else for (int x = 0; x < n; x++) body.accept(x); }
    double h(int c) { return D[c] * TICK; }
    double eta(int c) { return Bed[c] + D[c] * TICK; }

    void substep(double dt) {
        double g = G, n2 = Manning * Manning, cap = 0.5 * Dx / dt;
        forActive((i, j) -> { int a = i + N * j;
            u2[a] = i + 1 < N ? momentum(a, a + 1, true, i, j, g, n2, dt, cap) : 0f;
            v2[a] = j + 1 < N ? momentum(a, a + N, false, i, j, g, n2, dt, cap) : 0f; });
        float[] t = U; U = u2; u2 = t; t = V; V = v2; v2 = t;
        double toTicks = Dx * dt / (Area * TICK);
        forActive((i, j) -> { int a = i + N * j;
            double hf = i + 1 < N ? faceDepth(a, a + 1, U[a]) : 0; if (hf <= 0) U[a] = 0;
            hx[a] = (float) hf; qx[a] = (float) (U[a] * hf); fx[a] = U[a] * hf * toTicks;
            hf = j + 1 < N ? faceDepth(a, a + N, V[a]) : 0; if (hf <= 0) V[a] = 0;
            hz[a] = (float) hf; qz[a] = (float) (V[a] * hf); fz[a] = V[a] * hf * toTicks; });
        forActive((i, j) -> { int a = i + N * j; double out = 0;
            if (fx[a] > 0) out += fx[a]; if (fz[a] > 0) out += fz[a];
            if (i > 0 && fx[a - 1] < 0) out -= fx[a - 1]; if (j > 0 && fz[a - N] < 0) out -= fz[a - N];
            k[a] = out > D[a] ? D[a] / out : 1.0; });
        int nn = N * N;
        forN(N, j -> { int[] row = rowBlocks[j / B];
            for (int r = 0; r < row.length; r++) { int i0 = row[r] * B, i1 = Math.min(N - 1, i0 + B);
                for (int i = i0; i < i1; i++) { int a = i + N * j; double f = fx[a]; if (f > 0) f *= k[a]; else if (f < 0) f *= k[a + 1]; move(a, a + 1, f, a); } } });
        forN(N, i -> { int[] col = colBlocks[i / B];
            for (int r = 0; r < col.length; r++) { int j0 = col[r] * B, j1 = Math.min(N - 1, j0 + B);
                for (int j = j0; j < j1; j++) { int a = i + N * j; double f = fz[a]; if (f > 0) f *= k[a]; else if (f < 0) f *= k[a + N]; move(a, a + N, f, nn + a); } } });
    }
    double faceDepth(int a, int b, double u) {
        if (!Known[a] || !Known[b]) return 0;
        double top = Math.max(Bed[a], Bed[b]);
        double hf = u > 0 ? eta(a) - top : u < 0 ? eta(b) - top : Math.max(eta(a), eta(b)) - top;
        return hf > WET_FACE ? hf : 0;
    }
    float momentum(int a, int b, boolean x, int i, int j, double g, double n2, double dt, double cap) {
        if (D[a] == 0 && D[b] == 0) return 0f;
        if (!Known[a] || !Known[b]) return 0f;
        float[] W = x ? U : V, Q = x ? qx : qz, Qo = x ? qz : qx;
        double u = W[a], ea = eta(a), eb = eta(b), top = Math.max(Bed[a], Bed[b]);
        if (Math.max(ea, eb) - top <= WET_FACE) return 0f;
        double v = u - g * dt * (eb - ea) / Dx;
        if (Advection) {
            double hm = 0.5 * (h(a) + h(b));
            if (hm > WET_FACE) {
                int s = x ? 1 : N; boolean hasBack = x ? i > 0 : j > 0, hasFwd = x ? i + 2 < N : j + 2 < N;
                double qa = 0.5 * (Q[a] + (hasBack ? Q[a - s] : 0)), qb = 0.5 * (Q[a] + (hasFwd ? Q[a + s] : 0)), adv = 0;
                if (qa > 0 && hasBack) adv += qa * (u - W[a - s]);
                if (qb < 0 && hasFwd) adv += qb * (W[a + s] - u);
                int p = x ? N : 1; boolean hasLo = x ? j > 0 : i > 0, hasHi = x ? j + 1 < N : i + 1 < N;
                if (hasLo) { double ql = 0.5 * (Qo[a - p] + Qo[b - p]); if (ql > 0) adv += ql * (u - W[a - p]); }
                if (hasHi) { double qh = 0.5 * (Qo[a] + Qo[b]); if (qh < 0) adv += qh * (W[a + p] - u); }
                v -= dt * adv / (hm * Dx);
            }
        }
        double hf = faceDepth(a, b, v); if (hf <= 0) return 0f;
        v /= 1.0 + dt * g * n2 * Math.abs(v) / Math.pow(hf, 4.0 / 3.0);
        if (v > cap) v = cap; else if (v < -cap) v = -cap;
        return (float) v;
    }
    void move(int from0, int to0, double ticks, int face) {
        double owed = owe[face] + ticks;
        long whole = Math.round(owed);
        int from = from0, to = to0; long w = whole;
        if (w < 0) { from = to0; to = from0; w = -w; }
        if (w > D[from]) w = D[from];
        if (w > 0) { D[from] -= w; D[to] += w; int qt = blockOf(to), qf = blockOf(from); if (asleep[qt]) wake[qt] = true; if (asleep[qf]) wake[qf] = true; }
        double moved = whole < 0 ? -w : w;
        owed -= moved;
        if (owed > 0.5) owed = 0.5; else if (owed < -0.5) owed = -0.5;
        owe[face] = owed;
    }
    void measure() {
        double vmax = 0, dmax = 0; int wc = 0;
        for (int q = 0; q < nList; q++) {
            int b = list[q], i0 = (b % nb) * B, j0 = (b / nb) * B, i1 = Math.min(N, i0 + B), j1 = Math.min(N, j0 + B);
            for (int j = j0; j < j1; j++) for (int a = i0 + N * j, e = i1 + N * j; a < e; a++) {
                if (D[a] >= DRY_TICKS) wc++; double d = D[a] * TICK; if (d > dmax) dmax = d;
                double x = Math.abs(U[a]); if (x > vmax) vmax = x; x = Math.abs(V[a]); if (x > vmax) vmax = x; }
        }
        MaxSpeed = vmax; MaxDepth = dmax; WetColumns = wc;
    }
    /** Flow velocity at a column (m/s, along i and j), averaged from its faces. */
    public double[] velocity(int i, int j) { int a = index(i, j); return new double[]{0.5 * (U[a] + (i > 0 ? U[a - 1] : 0)), 0.5 * (V[a] + (j > 0 ? V[a - N] : 0))}; }
    public static long ticks(double metres) { return Math.round(metres / TICK); }
}
