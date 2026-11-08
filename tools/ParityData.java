import com.hopeless.tides.core.*;
import java.util.*;
/** ParityData n : random inputs and the Java model's answers (for the GLSL parity test). */
public class ParityData { public static void main(String[] a) {
    int n = Integer.parseInt(a[0]); Random r = new Random(7); WaveModel.Sample s = new WaveModel.Sample();
    for (int i = 0; i < n; i++) {
        double x = (r.nextDouble() - 0.5) * 60000, z = (r.nextDouble() - 0.5) * 60000; int day = r.nextInt(3000); double frac = r.nextDouble();
        double st = r.nextDouble(); int e = r.nextInt(16), d = r.nextInt(16); double dayTime = day * 24000.0 + frac * 24000.0;
        double w = WaveModel.wind(dayTime), t = WaveModel.daySeconds(dayTime), cave = WaveModel.seaStateCave(dayTime);
        x = Math.round(x); z = Math.round(z);   // corners are whole blocks
        WaveModel.sample(x, z, t, st, w, e, d, s);
        System.out.printf(Locale.ROOT, "%.1f %.1f %d %.9f %.6f %d %d %.9f %.9f %.9f %.9f %.9f %.9f%n", x, z, day, frac, st, e, d, w, cave, t, s.h, s.sx, s.sz);
    } } }
