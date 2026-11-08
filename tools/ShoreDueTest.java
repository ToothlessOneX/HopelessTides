import com.hopeless.tides.client.TidesShore;
/** ShoreDueTest: the shore map's build timer (TidesShore.due), including the first build of a world (0.17.0 never built: an overflow). Saved tool. */
public class ShoreDueTest { public static void main(String[] a) { int f = 0;
  long[][] c = {{0, Long.MIN_VALUE, 1}, {5, -1_000_000L, 1}, {100, 90, 0}, {100, 60, 1}, {Long.MAX_VALUE / 2, Long.MIN_VALUE, 1}, {10, 500, 1}};
  for (long[] q : c) { boolean got = TidesShore.due(q[0], q[1], false, true, false, false); boolean ok = got == (q[2] == 1); if (!ok) f++; System.out.printf("%s now %d last %d -> %s%n", ok ? "PASS" : "FAIL", q[0], q[1], got); }
  boolean b1 = !TidesShore.due(100, 0, true, true, false, false), b2 = !TidesShore.due(100, 0, false, false, false, false); if (!b1) f++; if (!b2) f++;
  System.out.printf("%s busy blocks; %s nothing changed blocks%nShoreDueTest: %d fail%n", b1 ? "PASS" : "FAIL", b2 ? "PASS" : "FAIL", f); if (f > 0) System.exit(1); } }
