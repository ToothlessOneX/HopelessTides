import com.hopeless.tides.core.StormFoam;
public class StormDump { public static void main(String[] a) { for (double u : (a.length > 0 ? java.util.Arrays.stream(a).mapToDouble(Double::parseDouble).toArray() : new double[]{8, 12, 16, 20, 25})) { float[] t = StormFoam.thresholds(u); System.out.printf(java.util.Locale.ROOT, "%.1f %.6f %.6f %.6f%n", u, t[0], t[1], StormFoam.monahan(u)); } } }
