import com.hopeless.tides.core.Streams;
import java.util.*;
/** HydroSim days seed: runs the stream stores under vanilla-style weather (the same random cycle as ServerLevel) and measures how
 *  often each kind of stream runs and how long the spells last. Writes a CSV trace (day, rain, thunder, soil, ground, gully, creek, flood). */
public class HydroSim {
    public static void main(String[] a) throws Exception {
        int days = Integer.parseInt(a[0]); Random r = new Random(Long.parseLong(a[1]));
        int rainTime = 0, thunderTime = 0; boolean raining = false, thundering = false; double rainL = 0, thunderL = 0;
        Streams.Hydro h = new Streams.Hydro(); long ticks = days * 24000L;
        long gullyOn = 0, creekOn = 0, creekFull = 0, f1 = 0, f2 = 0, rainOn = 0; int spells = 0, creekSpells = 0; boolean was = false, wasC = false; long longestDry = 0, dry = 0;
        java.io.PrintWriter csv = new java.io.PrintWriter(a.length > 2 ? a[2] : "/dev/null");
        for (long t = 0; t < ticks; t++) {
            // vanilla weather cycle (ServerLevel.advanceWeatherCycle)
            if (thunderTime > 0) { if (--thunderTime == 0) thundering = !thundering; } else thunderTime = thundering ? 3600 + r.nextInt(12000) : 12000 + r.nextInt(168000);
            if (rainTime > 0) { if (--rainTime == 0) raining = !raining; } else rainTime = raining ? 12000 + r.nextInt(12000) : 12000 + r.nextInt(168000);
            rainL = Math.max(0, Math.min(1, rainL + (raining ? 0.01 : -0.01))); thunderL = Math.max(0, Math.min(1, thunderL + (thundering && raining ? 0.01 : -0.01)));
            if (t % 20 == 0) {
                h.step(20, rainL, thunderL);
                boolean g = h.ephemeral() > 0, c = h.intermittent() > 0; if (rainL > 0.5) rainOn += 20;
                if (g) gullyOn += 20; if (c) creekOn += 20; if (h.intermittent() >= 1) creekFull += 20; if (h.flood() >= 1) f1 += 20; if (h.flood() == 2) f2 += 20;
                if (g && !was) spells++; if (c && !wasC) creekSpells++; was = g; wasC = c;
                if (!c) { dry += 20; longestDry = Math.max(longestDry, dry); } else dry = 0;
                if (t % 1200 == 0) csv.printf("%.3f,%.2f,%.2f,%.3f,%.3f,%.2f,%.2f,%d%n", t / 24000.0, rainL, thunderL, h.soil, h.ground, h.ephemeral(), h.intermittent(), h.flood());
            }
        }
        csv.close();
        double T = ticks;
        System.out.printf("%d days: raining %.0f%% | gullies run %.0f%% (%d spells, %.2f days each) | creeks run %.0f%% (full %.0f%%; %d spells, %.1f days each; longest dry %.1f days) | river bank-full %.1f%% flood %.1f%%%n",
            days, 100 * rainOn / T, 100 * gullyOn / T, spells, gullyOn / 24000.0 / Math.max(1, spells), 100 * creekOn / T, 100 * creekFull / T, creekSpells, creekOn / 24000.0 / Math.max(1, creekSpells), longestDry / 24000.0, 100 * f1 / T, 100 * f2 / T);
    }
}
