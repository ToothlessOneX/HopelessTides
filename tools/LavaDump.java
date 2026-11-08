import com.hopeless.tides.core.LavaMap;
/** LavaDump OUT.bin : the lava map (LavaMap.texels, the shipped code) for the look harness's lake and stream, 128 x 128, lava top at y 0. */
public class LavaDump { public static void main(String[] a) throws Exception {
    int n = 128; boolean[] lava = new boolean[n * n]; int[] sy = new int[n * n];
    for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) lava[i + n * j] = (4 <= i && i <= 43 && 10 <= j && j <= 49) || (50 <= i && i <= 53);
    java.nio.file.Files.write(java.nio.file.Path.of(a[0]), LavaMap.texels(lava, sy, n));
    int[] counts = new int[9]; byte[] t = LavaMap.texels(lava, sy, n); for (int i = 0; i < n * n; i++) if (lava[i]) counts[(t[i * 4] & 255) / 31]++;
    System.out.println("edge distance histogram (lava cells): " + java.util.Arrays.toString(counts));
}}
