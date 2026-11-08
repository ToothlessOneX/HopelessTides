/** Writes Embeddium's terrain shaders as Hopeless Tides patches them in the game (TidesRender.patchVertexShader / patchFragmentShader).
 * args: in.vsh out.vsh [in.fsh out.fsh] */
public class PatchDump { public static void main(String[] a) throws Exception {
    String src = java.nio.file.Files.readString(java.nio.file.Path.of(a[0]));
    String out = com.hopeless.tides.client.TidesRender.patchVertexShader(src);
    java.nio.file.Files.writeString(java.nio.file.Path.of(a[1]), out); System.out.println("vertex patched +" + (out.length() - src.length()) + " chars");
    if (a.length >= 4) { String f = java.nio.file.Files.readString(java.nio.file.Path.of(a[2])); String fo = com.hopeless.tides.client.TidesRender.patchFragmentShader(f);
        java.nio.file.Files.writeString(java.nio.file.Path.of(a[3]), fo); System.out.println("fragment patched +" + (fo.length() - f.length()) + " chars"); }
}}
