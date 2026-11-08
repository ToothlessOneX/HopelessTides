package com.hopeless.tides.client;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

/**
 * 0.13.1: safe texture uploads. glTex(Sub)Image reads client memory as the GL's pixel-unpack state says: row length, skipped rows and
 * pixels, alignment, and a bound pixel-unpack buffer. Minecraft's own texture uploads (NativeImage) set row length and skips and can
 * leave them set; then the driver reads past the end of our buffer and the game dies in the driver (seen 2026-11-02: nvoglv64.dll access
 * violation in TidesRipples' upload). begin() saves that state and sets it plain (0 row length, no skips, alignment 1, no unpack
 * buffer); end() puts it back. Probe: uploads guarded, and how many found the state not plain.
 */
public final class TidesGl {
    private TidesGl() {}
    public static volatile long guarded, foundDirty;
    public static int[] begin() {
        int[] s = {GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH), GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS), GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS),
            GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT), GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING), GL11.glGetInteger(GL12.GL_UNPACK_IMAGE_HEIGHT), GL11.glGetInteger(GL12.GL_UNPACK_SKIP_IMAGES)};
        if (s[0] != 0 || s[1] != 0 || s[2] != 0 || s[4] != 0 || s[5] != 0 || s[6] != 0) foundDirty++;
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0); GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0); GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1); GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0); GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        if (s[4] != 0) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        guarded++; return s;
    }
    public static void end(int[] s) {
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, s[0]); GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, s[1]); GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, s[2]);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, s[3]); GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, s[5]); GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, s[6]);
        if (s[4] != 0) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, s[4]);
    }
    public static String probe() { return "uploads guarded " + guarded + " (unpack state not plain " + foundDirty + ")"; }
}
