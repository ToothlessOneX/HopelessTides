import com.hopeless.tides.core.*;
import java.io.*;
/** GenGlsl out.glsl : writes the wave model as GLSL, constants taken from the Java model so the shader and the physics cannot drift apart. */
public class GenGlsl {
    public static void main(String[] a) throws Exception {
        StringBuilder b = new StringBuilder();
        b.append("// Hopeless Tides wave model, GENERATED from WaveModel.java by tools/GenGlsl.java - do not edit by hand.\n");
        b.append("// tides_surface(...) returns the height above still water and its slope, for one block corner.\n");
        b.append("#ifndef TIDES_WAVES_GLSL\n#define TIDES_WAVES_GLSL\n");
        b.append("const int TIDES_N = ").append(WaveModel.N).append(";\n");
        b.append("const float TIDES_LEN[").append(WaveModel.N).append("] = float[](").append(join(WaveModel.LEN)).append(");\n");
        double[] om = new double[WaveModel.N], k = new double[WaveModel.N], dx = new double[WaveModel.N], dz = new double[WaveModel.N];
        for (int i = 0; i < WaveModel.N; i++) { k[i] = 2 * Math.PI / WaveModel.LEN[i]; om[i] = WaveModel.omega(k[i]); dx[i] = Math.cos(WaveModel.DIR[i]); dz[i] = Math.sin(WaveModel.DIR[i]); }
        b.append("const float TIDES_K[").append(WaveModel.N).append("] = float[](").append(join(k)).append(");\n");
        b.append("const float TIDES_OM[").append(WaveModel.N).append("] = float[](").append(join(om)).append(");\n");
        b.append("const float TIDES_DIR[").append(WaveModel.N).append("] = float[](").append(join(WaveModel.DIR)).append(");\n");
        b.append("const float TIDES_DX[").append(WaveModel.N).append("] = float[](").append(join(dx)).append(");\n");
        b.append("const float TIDES_DZ[").append(WaveModel.N).append("] = float[](").append(join(dz)).append(");\n");
        b.append("const float TIDES_PHASE[").append(WaveModel.N).append("] = float[](").append(join(WaveModel.phases())).append(");\n");
        b.append("const float TIDES_BREAK = ").append(f(WaveModel.BREAK)).append(";\n");
        b.append(new String(java.nio.file.Files.readAllBytes(new File(a[1]).toPath())));
        b.append("#endif\n");
        try (Writer w = new FileWriter(a[0])) { w.write(b.toString()); }
        System.out.println("wrote " + a[0] + " (" + b.length() + " chars)");
    }
    static String f(double v) { String s = String.format(java.util.Locale.ROOT, "%.9g", v); return s.contains(".") || s.contains("e") ? s : s + ".0"; }
    static String join(double[] v) { StringBuilder b = new StringBuilder(); for (int i = 0; i < v.length; i++) { if (i > 0) b.append(", "); b.append(f(v[i])); } return b.toString(); }
}
