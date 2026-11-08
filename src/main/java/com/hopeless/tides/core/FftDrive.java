package com.hopeless.tides.core;

/**
 * 0.9.0: keeps the Fourier fields for the slots round now (a field every FftSea.SLOT seconds of game time), built ahead on one worker
 * thread, so the game thread only reads. The server keeps one per level, the client one for its level; both build the same slots from
 * the same time, sea state and wind, so they agree. Before the first fields exist, pair() returns null and the caller uses the 12-wave model.
 * Probes: fields built, build ms (last, max), slots missed (asked for but not ready).
 */
public final class FftDrive {
    public final String name;
    final java.util.concurrent.ConcurrentHashMap<Long, FftSea.Field> fields = new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.Set<Long> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();
    final java.util.concurrent.ExecutorService worker;
    /** 0.13.0: surface gravity of this dimension (m/s2; TidesPlanets). A change drops the fields built for the old value. */
    public volatile double g = FftSea.G; double builtG = FftSea.G;
    /** 0.16.0: the fetch (m) and the swell (Hs m, Tp s, toward rad) this drive builds for (Tides.drive sets them: quantized, so small
     *  changes do not rebuild); a change drops the old fields like a change of g. */
    public volatile double fetch = FftSea.FETCH, swellHs, swellTp, swellDir; String builtKey = "";
    public volatile double lastMs, maxMs; public volatile long builds, misses, errors; public volatile String lastError = "";
    public FftDrive(String name) {
        this.name = name;
        worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Tides FFT " + name); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY + 1); return t; });
    }
    /** Ask for the slots round time t (s) at sea state s (quantized here) and wind windAt(slot time); old slots are dropped. Cheap: call every tick. */
    public void want(double t, double sIn, java.util.function.DoubleUnaryOperator windAt) {
        final double s = FftSea.quantize(sIn); final double gNow = g, fNow = fetch, hsNow = swellHs, tpNow = swellTp, dirNow = swellDir;
        String dkey = gNow + "/" + fNow + "/" + hsNow + "/" + tpNow + "/" + dirNow;
        if (gNow != builtG || !dkey.equals(builtKey)) { fields.clear(); builtG = gNow; builtKey = dkey; }
        long k = (long) Math.floor(t / FftSea.SLOT);
        for (long q = k; q <= k + 2; q++) {
            final long key = Math.floorMod(q, NSLOT), uq = q;
            FftSea.Field have = fields.get(key);
            if (have != null && have.s == s && have.g == gNow && have.fetch == Math.max(10, Math.min(FftSea.FETCH, fNow)) && have.swellHs == hsNow) continue;                       // a field for this slot at this state (a changed state rebuilds it)
            if (!pending.add(key)) continue;
            worker.submit(() -> {
                try { double ts = uq * FftSea.SLOT; FftSea.Field f = FftSea.build(ts, s, windAt.applyAsDouble(ts), gNow, fNow, hsNow, tpNow, dirNow, null); f.slot = key; fields.put(key, f); builds++; lastMs = f.ms; maxMs = Math.max(maxMs, f.ms); }
                catch (Throwable e) { errors++; lastError = String.valueOf(e); }
                finally { pending.remove(key); }
            });
        }
        fields.keySet().removeIf(q -> { long d = Math.floorMod(q - k, NSLOT); return d > 4 && d < NSLOT - 1; });
    }
    /** Slots in one turn of the day clock (the field repeats then, FftSea.REPEAT): slot keys wrap with it. */
    public static final long NSLOT = Math.round(FftSea.REPEAT / FftSea.SLOT);
    public static final class Pair { public FftSea.Field prev, cur; public double frac, dt; }
    /** The fields either side of t, or null if not ready (the caller falls back to the 12-wave model). */
    public Pair pair(double t, Pair out) {
        long k = (long) Math.floor(t / FftSea.SLOT);
        FftSea.Field a = fields.get(Math.floorMod(k, NSLOT)), b = fields.get(Math.floorMod(k + 1, NSLOT));
        if (a == null || b == null) { misses++; return null; }
        if (out == null) out = new Pair();
        out.prev = a; out.cur = b; out.frac = t / FftSea.SLOT - k; out.dt = FftSea.SLOT; return out;
    }
    public String probe() { return String.format("fft %s (g %.2f, fetch %.0f m, swell %.2f m %.1f s): fields built %d, last %.1f ms, max %.1f ms, held %d, misses %d, errors %d%s", name, g, fetch, swellHs, swellTp, builds, lastMs, maxMs, fields.size(), misses, errors, errors > 0 ? " (" + lastError + ")" : ""); }
}
