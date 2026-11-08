package com.hopeless.tides.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.10.0: the water on the lens (Crest's meniscus; rain-on-glass drops). Two screen effects, first person only:
 *  - the WATERLINE: when the eye is within 0.3 m of the surface, a thin bright line with a darker band under it where the surface
 *    crosses the view (the surface plane 2 m ahead, projected with the camera's pitch and field of view);
 *  - DROPS: when the eye comes up out of the water, 14-30 drops (more the longer it was under, up to 4 s) stay on the lens, slide down
 *    slowly and dry in 2-4 s. Texture lens_drop.png made for the mod.
 * Probes: waterline frames, drop sets, drops drawn.
 */
public final class TidesLens {
    private TidesLens() {}
    static final ResourceLocation DROP = new ResourceLocation("hopelesstides", "textures/gui/lens_drop.png");
    static final class Drop { float x, y, size, life, age, slide; }
    static final List<Drop> DROPS = new ArrayList<>();
    static boolean wasUnder; static double underSince, lastNs = System.nanoTime() / 1e9;
    public static volatile long lineFrames, dropSets, dropsDrawn;
    public static volatile boolean enabled = true;

    public static void init() { MinecraftForge.EVENT_BUS.addListener(TidesLens::draw); }

    /** The world y of the water surface at or over the eye column (eye in water), or just under it (eye up to 0.3 m above). NaN: none. */
    static double surfaceY(ClientLevel l, Vec3 c) {
        BlockPos p = BlockPos.containing(c.x, c.y - 0.4, c.z); double top = Double.NaN;
        for (int k = 0; k < 6; k++) { BlockPos b = p.above(k); FluidState f = l.getFluidState(b); if (!f.is(FluidTags.WATER)) { if (!Double.isNaN(top)) break; continue; } top = b.getY() + f.getHeight(l, b); }
        return top;
    }
    static void draw(RenderGuiEvent.Post e) {
        Minecraft mc = Minecraft.getInstance(); ClientLevel l = mc.level;
        if (!enabled || l == null || mc.player == null || !mc.options.getCameraType().isFirstPerson()) return;
        GuiGraphics g = e.getGuiGraphics(); int W = mc.getWindow().getGuiScaledWidth(), H = mc.getWindow().getGuiScaledHeight();
        double now = System.nanoTime() / 1e9, dt = Math.min(0.1, now - lastNs); lastNs = now;
        var cam = mc.gameRenderer.getMainCamera(); Vec3 c = cam.getPosition();
        boolean under = cam.getFluidInCamera() == net.minecraft.world.level.material.FogType.WATER;
        if (under && !wasUnder) underSince = now;
        if (!under && wasUnder) {                                                  // came up: drops stay on the lens
            int n = (int) Math.round(14 + 16 * Math.min(1, (now - underSince) / 4)); var r = l.random;
            for (int i = 0; i < n; i++) { Drop d = new Drop(); d.x = r.nextFloat() * W; d.y = r.nextFloat() * H; d.size = 4 + r.nextFloat() * 10; d.life = 2 + 2 * r.nextFloat(); d.slide = 2 + r.nextFloat() * 10; DROPS.add(d); }
            dropSets++;
        }
        wasUnder = under;
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        // the waterline
        double sy = surfaceY(l, c);
        if (!Double.isNaN(sy) && Math.abs(sy - c.y) < 0.3) {
            double pitch = Math.toRadians(cam.getXRot()), fov = Math.toRadians(mc.options.fov().get());
            double phi = Math.atan2(sy - c.y, 2.0), rel = phi + pitch;
            if (Math.abs(rel) < fov / 2) {
                int y = (int) Math.round(H / 2.0 - (H / 2.0) * Math.tan(rel) / Math.tan(fov / 2));
                float fade = (float) (1 - Math.abs(sy - c.y) / 0.3);
                g.fill(0, y - 1, W, y, argb(0.55f * fade, 0.85f, 0.95f, 1.0f));
                g.fillGradient(0, y, W, y + 6, argb(0.45f * fade, 0.05f, 0.22f, 0.28f), argb(0f, 0.05f, 0.22f, 0.28f));
                lineFrames++;
            }
        }
        // the drops
        for (java.util.Iterator<Drop> it = DROPS.iterator(); it.hasNext(); ) {
            Drop d = it.next(); d.age += dt; if (d.age >= d.life || under) { it.remove(); continue; }
            d.y += d.slide * dt * (d.size / 14f);                                  // bigger drops slide faster
            float a = 1 - (d.age / d.life); a *= a;
            RenderSystem.setShaderColor(1, 1, 1, a);
            int s = Math.round(d.size); g.blit(DROP, Math.round(d.x - s / 2f), Math.round(d.y - s / 2f), 0, 0, s, s, s, s);
            dropsDrawn++;
        }
        RenderSystem.setShaderColor(1, 1, 1, 1); RenderSystem.disableBlend();
    }
    static int argb(float a, float r, float g, float b) { return ((int) (a * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255); }
    public static String probe() { return "lens: waterline frames " + lineFrames + " drop sets " + dropSets + " drops drawn " + dropsDrawn; }
}
