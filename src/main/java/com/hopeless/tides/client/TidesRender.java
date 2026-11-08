package com.hopeless.tides.client;

import com.hopeless.tides.Tides;
import com.hopeless.tides.core.SeaInfo;
import com.hopeless.tides.core.WaveModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Client side of the drawn waves (Embeddium renderer, and shader packs that read the same vertex code):
 *  - while a chunk is meshed, each water-surface vertex gets its corner's exposure and depth packed into its colour;
 *  - Embeddium's terrain vertex shader gets the wave model (tides_waves.glsl) and lifts those vertices to the wave surface;
 *  - each frame the shader gets the camera position, the time and the sea state.
 * Probes count what happened, for /tides info on the client log.
 */
public final class TidesRender {
    private TidesRender() {}
    public static volatile long vertsMarked, quadsMarked, shaderPatched, uniformSets, uniformMissing;   // probes
    public static final ThreadLocal<Object[]> FLUID = new ThreadLocal<>();            // {BlockPos, Boolean water surface} for the fluid being meshed

    /** Called at the start of meshing one fluid block. */
    public static void beginFluid(FluidState fs, BlockPos pos) {
        ClientLevel l = Minecraft.getInstance().level;
        boolean surface = l != null && fs.is(FluidTags.WATER) && !l.getFluidState(pos.above()).is(FluidTags.WATER);
        if (surface) { FLUID.set(new Object[]{pos.immutable()}); return; }
        // 0.12.0: lava: every face of it is marked (LAVA_MARK) so the shader knows lava by the vertex, not by the texture alone (a block
        // whose sprite sits next to lava's in the atlas shares its edge coordinates and would otherwise be taken for lava)
        FLUID.set(l != null && fs.is(FluidTags.LAVA) ? new Object[]{pos.immutable(), Boolean.TRUE} : null);
    }
    /** 0.7.0: packs a water vertex's data into the low NIBBLE of each colour channel (Embeddium's ABGR): red = exposure, green =
     *  depth, blue = the column simulation's speed class, alpha = its direction class. The vertex shader reads them and puts the
     *  colour back to the middle of each nibble before using it (a change of at most 8/255, and none to alpha: 15 -> 255). */
    /** 0.12.0: the lava mark in the low nibbles (red 10, green 5, blue 10, alpha 1): tides_isLava in the vertex shader checks it. */
    public static int markLava(int abgr) {
        int r = abgr & 0xFF, g = (abgr >> 8) & 0xFF, b = (abgr >> 16) & 0xFF, a = (abgr >>> 24);
        r = (r & 0xF0) | 10; g = (g & 0xF0) | 5; b = (b & 0xF0) | 10; a = (a & 0xF0) | 1;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
    public static volatile long lavaVertsMarked;
    public static int encode(int abgr, int exposure, int depth, int flow) {
        int r = abgr & 0xFF, g = (abgr >> 8) & 0xFF, b = (abgr >> 16) & 0xFF, a = (abgr >>> 24);
        r = (r & 0xF0) | (exposure & 15); g = (g & 0xF0) | (depth & 15); b = (b & 0xF0) | (flow & 15); a = (a & 0xF0) | ((flow >> 4) & 15);
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
    /** The corner's code for a vertex at world (x, y, z) of the surface block at pos; -1 if the vertex is not on the surface. */
    public static int[] corner(BlockPos pos, float vx, float vy, float vz) {
        if (vy < 0.5f) return null;                                        // low in the block: not the surface (the shader uses the same test)
        ClientLevel l = Minecraft.getInstance().level; if (l == null) return null;
        int cx = Math.round(pos.getX() + vx), cz = Math.round(pos.getZ() + vz);
        return SeaInfo.corner(Tides.nodes(l), cx, cz, pos.getY());
    }

    // ------------------------------------------------------------------ the shader
    static String waves, fft, surf;
    public static String patchVertexShader(String src) {
        try {
            if (waves == null) try (InputStream in = TidesRender.class.getResourceAsStream("/assets/hopelesstides/shaders/tides_waves.glsl")) { waves = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            if (fft == null) try (InputStream in = TidesRender.class.getResourceAsStream("/assets/hopelesstides/shaders/tides_fft.glsl")) { fft = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            if (surf == null) try (InputStream in = TidesRender.class.getResourceAsStream("/assets/hopelesstides/shaders/tides_surf.glsl")) { surf = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            String head = "\n// ---- Hopeless Tides\nuniform vec4 u_TidesCam;\nuniform vec4 u_TidesOrigin;\nuniform vec4 u_TidesWave;\nuniform vec4 u_TidesUV0;\nuniform vec4 u_TidesUV1;\nout float tides_kind;\nout vec3 tides_rel;\nout vec2 tides_world;\nout vec2 tides_slope;\nout vec2 tides_ed;\nout vec2 tides_light;\nout vec2 tides_flow;\nout float tides_cap;\nout float tides_h;\nuniform vec4 u_TidesSwayUV[5];\nuniform vec4 u_TidesSway;\nuniform vec4 u_TidesTide;\nuniform vec4 u_TidesLavaUV0;\nuniform vec4 u_TidesLavaUV1;\n" + waves + "\n" + fft + "\n" + surf + "\n// ---- end Hopeless Tides\n";
            int m = src.indexOf("void main()"); if (m < 0) return src;
            String out = src.substring(0, m) + head + src.substring(m);
            String anchor = "vec3 position = _vert_position + translation;";
            int a = out.indexOf(anchor); if (a < 0) return src;
            String lift = anchor + "\n    bool tidesWater = u_TidesCam.w > 0.5 && fract(position.y + u_TidesCam.y) >= 0.5\n"
                + "        && (all(greaterThanEqual(_vert_tex_diffuse_coord, u_TidesUV0.xy)) && all(lessThanEqual(_vert_tex_diffuse_coord, u_TidesUV0.zw))\n"
                + "         || all(greaterThanEqual(_vert_tex_diffuse_coord, u_TidesUV1.xy)) && all(lessThanEqual(_vert_tex_diffuse_coord, u_TidesUV1.zw)));\n"
                + "    tides_kind = 0.0; tides_rel = position; tides_world = position.xz + u_TidesCam.xz; tides_slope = vec2(0.0); tides_ed = vec2(0.0); tides_flow = vec2(-1.0, 0.0); tides_cap = 0.0; tides_h = 0.0;\n"
                + "    tides_light = vec2(float(_vert_tex_light_coord.x), float(_vert_tex_light_coord.y)) / 240.0;\n"
                + "    if (tidesWater) {\n"
                + "        vec2 tidesEd = tides_decode(_vert_color);\n"
                + "        vec3 tidesWp = position + u_TidesCam.xyz;\n"
                + "        float tidesState = float(_vert_tex_light_coord.y) / 16.0 >= 8.0 ? u_TidesWave.y : u_TidesWave.z;\n"
                + "        vec3 tidesS = tides_surface(floor(tidesWp.xz + 0.5) + u_TidesOrigin.xy, u_TidesWave.x, tidesState, u_TidesWave.w, tidesEd.x, tidesEd.y);\n"
                // 0.9.0: open-sky water takes the Fourier sea instead, scaled at this corner by shelter, depth and the current
                + "        if (u_TidesFft1.x > 0.5 && float(_vert_tex_light_coord.y) / 16.0 >= 8.0) {\n"
                + "            vec2 tidesC = tides_decodeFlow(_vert_color); float tidesV = tidesC.x > 1.5 ? (tidesC.x - 1.0) * 0.3 : 0.0;\n"
                + "            vec2 tidesP = floor(tidesWp.xz + 0.5); vec4 tidesF = tides_fftCorner(tidesP, u_TidesWave.y, u_TidesFft1.y, tidesEd.x, tidesEd.y, tidesV);\n"
                + "            vec3 tidesSf = tides_shoreFactors(tidesP); float tidesFk = tidesSf.z > 0.5 ? tidesSf.y : tides_fetchCorner(tidesP); tidesF.xyz *= tidesFk;\n"   // 0.17.0: the fetch over the whole shore map (the surf field's own where there is no map)
                // 0.16.3: in shallow water the wind sea is the surf's own wind-sea solve: it turns to the shore, steepens and calms behind land
                + "            vec4 tidesCh = tides_chopCorner(tidesP); if (tidesCh.w > 0.0) tidesF.xyz = mix(tidesF.xyz, tidesCh.xyz * tidesFk, tidesCh.w);\n"   // 0.16.2: the wind sea grows with the open water upwind of this spot
                + "            tidesS = tidesF.xyz; tides_cap = tidesF.w;\n"
                + "        }\n"
                // 0.14.0: the surf: in the shallows the open sea gives way to the bore train (tides_surf.glsl, Hydro 0.11)
                + "        if (u_TidesSurfT.w > 0.5 && float(_vert_tex_light_coord.y) / 16.0 >= 8.0) { float tsF; vec4 tsB = tides_surfCorner(ivec2(floor(tidesWp.xz + 0.5) - u_TidesSurfO.xy), tsF);\n"
                + "            if (tsB.y > 0.0) { tidesS.x = tidesS.x * (1.0 - tsB.y) + tsB.x; tidesS.yz = tidesS.yz * (1.0 - tsB.y) + tsB.zw; tides_cap = max(tides_cap, tsF); }\n"
                + "            tidesS *= tides_surfWall(ivec2(floor(tidesWp.xz + 0.5) - u_TidesSurfO.xy)); }\n"
                // 0.16.1 THE SWELL TRAIN: the swell from the open ocean drawn from the surf's own solution (refracted toward the shore, calm behind
                // land, phase-continuous crests), a plane train beyond the field; the Fourier sea carries only the local wind sea then
                + "        if (u_TidesSwO.x > 0.5 && float(_vert_tex_light_coord.y) / 16.0 >= 8.0) { vec2 tidesQ = floor(tidesWp.xz + 0.5); vec3 tsw = tides_swellCorner(tidesQ); tsw *= mix(tides_shoreFactors(tidesQ).x, 1.0, tides_fieldW(tidesQ)); tidesS += tsw; }\n"   // 0.17.0: the swell is blocked by land beyond the surf field too
                // 0.17.0 THE SHORE MAP (0.17.2: also inside the surf field round the player, where the owner looks): in shallow water the sea becomes the shore train: crests along the shore, shoaling, breaking, reflecting off steep banks
                + "        if (float(_vert_tex_light_coord.y) / 16.0 >= 8.0) { vec2 tidesQ = floor(tidesWp.xz + 0.5); float tshF; vec4 tshB = tides_shoreCorner(tidesQ, 0.0, tshF); if (tshB.w > 0.0) { tidesS = mix(tidesS, tshB.xyz, tshB.w); tides_cap = max(tides_cap, tshF); } }\n"
                + "        tides_h = tidesS.x;\n"
                // 0.11.0: the tide on open water under the sky (WaveModel.tide x tideWeight(exposure), as the physics)
                + "        if (float(_vert_tex_light_coord.y) / 16.0 >= 8.0) { float tw8 = clamp((tidesEd.x - 8.0) / 6.0, 0.0, 1.0); tidesS.x += u_TidesTide.x * tw8 * tw8 * (3.0 - 2.0 * tw8); }\n"
                + "        position.y += tidesS.x; tides_slope = tidesS.yz; tides_ed = tidesEd;\n"
                + "        vec2 tidesF = tides_decodeFlow(_vert_color); if (tidesF.x > 0.5) tides_flow = vec2((tidesF.x - 1.0) * 0.3, tidesF.y * 0.39269908);\n"                + "        tides_kind = (all(greaterThanEqual(_vert_tex_diffuse_coord, u_TidesUV1.xy)) && all(lessThanEqual(_vert_tex_diffuse_coord, u_TidesUV1.zw))) ? 2.0 : 1.0;\n"
                + "        tides_rel = position; tides_world = tidesWp.xz;\n"
                + "    }\n"
                // 0.11.0: kelp and seagrass sway with the water under the waves: one displacement field of world position and time (so
                // stacked kelp blocks stay joined), along the wind, stronger in a rough sea (u_TidesSway: x amplitude, y wind, z time)
                + "    if (!tidesWater && u_TidesSway.x > 0.0) {\n"
                + "        bool tidesPlant = false; for (int i = 0; i < 5; i++) tidesPlant = tidesPlant || (all(greaterThanEqual(_vert_tex_diffuse_coord, u_TidesSwayUV[i].xy)) && all(lessThanEqual(_vert_tex_diffuse_coord, u_TidesSwayUV[i].zw)));\n"
                + "        if (tidesPlant) {\n"
                + "            vec3 tw = position + u_TidesCam.xyz; float tt = u_TidesSway.z;\n"
                // 0.16.1: under water with a swell train, the plants move with the swell's orbital motion (tides_swellSurge, Seabed's SurgeSway)
                + "            if (u_TidesSwO.x > 0.5 && tw.y < u_TidesSwA.z) { position.xz += tides_swellSurge(tw.xz, tw.y); } else {\n"
                + "            float ph = dot(tw.xz, vec2(0.21, 0.17)) + tw.y * 0.35;\n"
                + "            float sw = sin(tt * 0.9 + ph) + 0.45 * sin(tt * 1.73 + ph * 1.9 + 1.3);\n"
                + "            vec2 wd = vec2(cos(u_TidesSway.y), sin(u_TidesSway.y)), sd = vec2(-wd.y, wd.x);\n"
                + "            float topW = clamp(fract(tw.y) + 0.25, 0.0, 1.0);\n"
                + "            position.xz += (wd * sw + sd * 0.35 * sin(tt * 1.21 + ph * 0.7)) * u_TidesSway.x * mix(0.6, 1.0, topW);\n"
                + "            }\n"
                + "            tides_kind = 0.0;\n"
                + "        }\n"
                + "    }\n"
                // 0.11.0: lava: a slow heavy swell on the top surface, and the crust look (tides_kind 3 still, 4 flowing)
                + "    bool tidesLava0 = all(greaterThanEqual(_vert_tex_diffuse_coord, u_TidesLavaUV0.xy)) && all(lessThanEqual(_vert_tex_diffuse_coord, u_TidesLavaUV0.zw));\n"
                + "    bool tidesLava1 = all(greaterThanEqual(_vert_tex_diffuse_coord, u_TidesLavaUV1.xy)) && all(lessThanEqual(_vert_tex_diffuse_coord, u_TidesLavaUV1.zw));\n"
                + "    ivec4 tidesNib = ivec4(_vert_color * 255.0 + 0.5) & 15;\n"
                + "    if (!tidesWater && u_TidesLavaUV0.x < u_TidesLavaUV0.z && (tidesLava0 || tidesLava1) && tidesNib == ivec4(10, 5, 10, 1)) {\n"
                + "        vec3 tw = position + u_TidesCam.xyz; float tt = u_TidesSway.z;\n"
                + "        if (fract(tw.y) >= 0.5) position.y += 0.035 * (sin(tw.x * 0.5 + tt * 0.3) + sin(tw.z * 0.37 - tt * 0.21 + 1.3));\n"
                + "        tides_kind = tidesLava1 ? 4.0 : 3.0; tides_rel = position; tides_world = tw.xz;\n"
                + "    }";
            out = out.substring(0, a) + lift + out.substring(a + anchor.length());
            // 0.7.0: a water vertex's colour carries data in its low nibbles: use the middle of each nibble (alpha: the top)
            String col = "v_Color = _vert_color * _sample_lightmap(u_LightTex, _vert_tex_light_coord);"; int ci = out.indexOf(col);
            if (ci < 0) return src;
            out = out.substring(0, ci) + "v_Color = (tides_kind > 0.5 ? vec4((floor(_vert_color.rgb * 255.0 / 16.0 + 0.001) * 16.0 + 8.0) / 255.0, (floor(_vert_color.a * 255.0 / 16.0 + 0.001) * 16.0 + 15.0) / 255.0) : _vert_color) * _sample_lightmap(u_LightTex, _vert_tex_light_coord);" + out.substring(ci + col.length());
            shaderPatched++; vertexOk = true;
            return out;
        } catch (Exception e) { com.hopeless.tides.HopelessTides.LOG.error("[Hopeless Tides] shader patch failed", e); return src; }
    }

    /** 0.11.0: morning mist over water, 0..1: from before dawn to mid-morning (day time 22500 -> 2500), in calm air, not in rain
     *  (radiation fog forms on still, clear nights and burns off after sunrise). Shape CHOSEN. */
    public static double mist(double dayTime, double seaState, double rain) {
        double d = ((dayTime % 24000) + 24000) % 24000, x = d >= 22500 ? (d - 22500) / 1500.0 : d <= 2500 ? 1 + d / 2500.0 : -1;
        if (x < 0) return 0;
        double shape = x < 1 ? x : 1 - (x - 1);                        // up to full at sunrise (0), gone by 2500
        return Math.max(0, shape) * Math.max(0, 1 - seaState / 0.35) * (1 - rain);
    }
    public static volatile long fftErrors;
    public static volatile boolean swayOn = true, lavaOn = true;   // 0.11.0
    public static volatile boolean vertexOk; public static volatile long fragPatched, lookErrors;
    /**
     * 0.6.0: the fragment shader shades water by small moving ripples (wind ripples on still water, faster broken ripples on flowing
     * water): a ripple normal from 6 short waves (0.37-1.83 blocks, deep-water speed sqrt(g k)) lights the surface - brighter where it
     * faces the light, a glint where it mirrors it, a little sky at grazing angles - scaled by the light that reaches the water, so a
     * dark cave stays dark. Patched only when the vertex shader was (it writes the water flag and positions this reads).
     */
    static String look;
    public static String patchFragmentShader(String src) {
        try {
            if (!vertexOk) return src;
            if (look == null) try (InputStream in = TidesRender.class.getResourceAsStream("/assets/hopelesstides/shaders/tides_look.glsl")) { look = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            String head = "\n// ---- Hopeless Tides\nuniform vec4 u_TidesRipple;\nin float tides_kind;\nin vec3 tides_rel;\nin vec2 tides_world;\nin vec2 tides_slope;\nin vec2 tides_ed;\nin vec2 tides_light;\nin vec2 tides_flow;\nin float tides_cap;\nin float tides_h;\n#define TIDES_FRAG\n" + look + "\n" + surf + "\n";
            int m = src.indexOf("void main()"); if (m < 0) return src;
            String out = src.substring(0, m) + head + src.substring(m);
            String anchor = "fragColor = _linearFog("; int a = out.indexOf(anchor); if (a < 0) return src;
            // 0.7.0: water takes Hopeless Hydro's look (only near enough for its detail to matter; beyond 160 blocks vanilla's), all
            // other terrain the wet-bank darkening
            String shade = "if (tides_kind > 2.5) diffuseColor = tides_lava(diffuseColor, tides_rel, tides_kind > 3.5 ? 1.0 : 0.0, v_TexCoord, tides_light);\n"
                + "    else if (tides_kind > 0.5 && length(tides_rel) < 160.0) diffuseColor = tides_look(diffuseColor, tides_rel, tides_world, tides_slope, tides_ed, tides_light, tides_kind > 1.5 ? 1.0 : 0.0, v_TexCoord, tides_flow);\n"
                + "    else if (tides_kind < 0.01) diffuseColor = tides_ice(diffuseColor, tides_rel, v_TexCoord, tides_light);\n"
                + "    if (tides_kind < 0.01) diffuseColor.rgb = tides_under(tides_lavaGlow(tides_caustics(tides_wet(tides_swash(diffuseColor.rgb, tides_rel, tides_light), tides_rel), tides_rel, tides_light), tides_rel), tides_rel, tides_light);\n    ";
            out = out.substring(0, a) + shade + out.substring(a);
            fragPatched++; return out;
        } catch (Exception e) { com.hopeless.tides.HopelessTides.LOG.error("[Hopeless Tides] fragment patch failed", e); return src; }
    }

    /**
     * 0.13.3: the shader origin. A float holds about 7 digits: 3,000 km from the world's origin (Hopeless Infinity) a world position in
     * the shader snaps to 0.25 m, so the water's fine detail, normals and maps jump as the camera moves (seen 2026-11-02 at 1,745,374,
     * -2,991,329). Every world position given to the shaders is now measured from OX, OZ: a multiple of ORIGIN_STEP (a multiple of the
     * 512 m Fourier tile and every map and noise cell, so they line up exactly) near the camera, moved only when the camera is more than
     * REBASE from it (then the fine detail patterns, which are not periodic, shift once). Vertex corners add the origin back as whole
     * numbers (exact in a float below 16.7 million) for the 12-wave model. Probes: rebases, origin.
     */
    public static final double ORIGIN_STEP = 16384, REBASE = 12000; public static volatile long surfErrors;
    public static volatile double OX, OZ; public static volatile long rebases;
    static void rebase(Vec3 cam) {
        if (Math.abs(cam.x - OX) <= REBASE && Math.abs(cam.z - OZ) <= REBASE) return;
        OX = Math.rint(cam.x / ORIGIN_STEP) * ORIGIN_STEP; OZ = Math.rint(cam.z / ORIGIN_STEP) * ORIGIN_STEP; rebases++;
        com.hopeless.tides.HopelessTides.LOG.info("[Hopeless Tides] shader origin {} {} (camera {} {})", (long) OX, (long) OZ, String.format("%.1f", cam.x), String.format("%.1f", cam.z)); }
    static final Map<Integer, int[]> LOC = new HashMap<>();
    /** Sets the wave uniforms on the terrain program that is bound now (no-op for programs without them, such as a shader pack's). */
    public static void setUniforms() {
        int prog = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM); if (prog == 0) return;
        int[] loc = LOC.computeIfAbsent(prog, p -> new int[]{GL20.glGetUniformLocation(p, "u_TidesCam"), GL20.glGetUniformLocation(p, "u_TidesWave"), GL20.glGetUniformLocation(p, "u_TidesUV0"), GL20.glGetUniformLocation(p, "u_TidesUV1"), GL20.glGetUniformLocation(p, "u_TidesRipple"), GL20.glGetUniformLocation(p, "u_TidesStorm"), GL20.glGetUniformLocation(p, "u_TidesUnder"), GL20.glGetUniformLocation(p, "u_TidesUVIce"), GL20.glGetUniformLocation(p, "u_TidesSwayUV"), GL20.glGetUniformLocation(p, "u_TidesSway"), GL20.glGetUniformLocation(p, "u_TidesLavaUV0"), GL20.glGetUniformLocation(p, "u_TidesLavaUV1"), GL20.glGetUniformLocation(p, "u_TidesFlash"), GL20.glGetUniformLocation(p, "u_TidesTide"), GL20.glGetUniformLocation(p, "u_TidesMist"), GL20.glGetUniformLocation(p, "u_TidesPlanet"), GL20.glGetUniformLocation(p, "u_TidesOrigin")});
        if (loc[0] < 0 || loc[1] < 0) { uniformMissing++; return; }
        Minecraft mc = Minecraft.getInstance(); ClientLevel l = mc.level; if (l == null) return;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition(); float pt = mc.getFrameTime(); rebase(cam);
        double dayTime = l.getDayTime() + pt;
        double sky = Tides.overrideState >= 0 ? Tides.overrideState : WaveModel.seaStateWeather(l.getRainLevel(pt), l.getThunderLevel(pt));
        float[] cw = Tides.caveWindClient;                                                    // 0.6.0: the cave wind the server measured here
        double cave = Tides.overrideState >= 0 ? Tides.overrideState : WaveModel.caveStateFromWind(cw == null ? 0 : cw[0]);
        double wind = cw != null ? cw[1] : WaveModel.wind(dayTime), t = WaveModel.daySeconds(dayTime);
        double uSky = 3 + 9 * l.getRainLevel(pt) + 6 * l.getThunderLevel(pt);                 // a breeze in fair weather, a gale in a storm (m/s)
        double still = WaveModel.rippleFromWind(cw != null ? cw[0] : (Minecraft.getInstance().player != null && l.getBrightness(net.minecraft.world.level.LightLayer.SKY, Minecraft.getInstance().player.blockPosition()) >= 8 ? uSky : 0));
        GL20.glUniform4f(loc[0], (float) (cam.x - OX), (float) cam.y, (float) (cam.z - OZ), 1f);   // 0.13.3: camera from the shader origin
        if (loc[16] >= 0) GL20.glUniform4f(loc[16], (float) OX, (float) OZ, 0f, 0f);
        GL20.glUniform4f(loc[1], (float) t, (float) sky, (float) cave, (float) wind);
        if (loc[4] >= 0) GL20.glUniform4f(loc[4], (float) t, (float) still, (float) Math.max(0.75, still), (float) wind);
        try { TidesLook.setUniforms(prog, l, cam, pt, cw != null ? cw[0] : (Minecraft.getInstance().player != null && l.getBrightness(net.minecraft.world.level.LightLayer.SKY, Minecraft.getInstance().player.blockPosition()) >= 8 ? uSky : 0.3)); } catch (Throwable e) { lookErrors++; }
        float[] a = spriteUV("block/water_still"), b = spriteUV("block/water_flow");
        if (loc[2] >= 0) GL20.glUniform4f(loc[2], a[0], a[1], a[2], a[3]);
        if (loc[3] >= 0) GL20.glUniform4f(loc[3], b[0], b[1], b[2], b[3]);
        if (loc[5] >= 0) {   // 0.10.0: the storm sea's foam (StormFoam: thresholds for Monahan's cover at this wind)
            float[] th = com.hopeless.tides.core.StormFoam.ready(uSky);
            GL20.glUniform4f(loc[5], th == null ? 0f : (float) uSky, (float) (WaveModel.heightOpen(sky) * Tides.heightScale(l) / 4.0), th == null ? 9f : th[0], th == null ? 9f : th[1]);
        }
        if (loc[8] >= 0) { float[] all = new float[20]; String[] names = {"block/kelp", "block/kelp_plant", "block/seagrass", "block/tall_seagrass_top", "block/tall_seagrass_bottom"};
            for (int i = 0; i < 5; i++) System.arraycopy(spriteUV(names[i]), 0, all, i * 4, 4); GL20.glUniform4fv(loc[8], all); }   // 0.11.0 sway
        if (loc[9] >= 0) GL20.glUniform4f(loc[9], swayOn ? (float) (0.06 + 0.22 * sky) : 0f, (float) WaveModel.wind(dayTime), (float) t, 0f);
        if (loc[10] >= 0) { float[] a0 = lavaOn ? spriteUV("block/lava_still") : new float[]{1, 0, 0, 0}, a1 = lavaOn ? spriteUV("block/lava_flow") : new float[]{1, 0, 0, 0};
            GL20.glUniform4f(loc[10], a0[0], a0[1], a0[2], a0[3]); if (loc[11] >= 0) GL20.glUniform4f(loc[11], a1[0], a1[1], a1[2], a1[3]); }
        if (loc[12] >= 0) GL20.glUniform4f(loc[12], l.getSkyFlashTime() > 0 ? Math.min(1f, l.getSkyFlashTime() / 2f) : 0f, 0, 0, 0);   // 0.11.0 lightning
        if (loc[13] >= 0) GL20.glUniform4f(loc[13], Tides.tideOn ? (float) Tides.tideNow(l, dayTime) : 0f, 0, 0, 0);   // 0.11.0
        if (loc[14] >= 0) GL20.glUniform4f(loc[14], (float) mist(dayTime, sky, l.getRainLevel(pt)), 0, 0, 0);
        if (loc[15] >= 0) GL20.glUniform4f(loc[15], (float) Tides.gravity(l), (float) Tides.heightScale(l), 0f, 0f);   // 0.13.0: the planet's gravity (ripples, wakes)
        if (loc[7] >= 0) { float[] ic = spriteUV("block/ice"); GL20.glUniform4f(loc[7], ic[0], ic[1], ic[2], ic[3]); }   // 0.10.0
        if (loc[6] >= 0) { float[] u = under(mc, l); GL20.glUniform4f(loc[6], u[0], u[1], u[2], 0f); }   // 0.10.0
        try { TidesOptics.setUniforms(prog); } catch (Throwable e) { surfErrors++; }   // 0.15.0
        try { TidesShore.setUniforms(prog, l, pt); } catch (Throwable e) { surfErrors++; if (surfErrors < 3) com.hopeless.tides.HopelessTides.LOG.error("[Hopeless Tides] shore map upload failed", e); }   // 0.17.0
        try { TidesSurfGpu.setUniforms(prog, l, pt); } catch (Throwable e) { surfErrors++; if (surfErrors < 3) com.hopeless.tides.HopelessTides.LOG.error("[Hopeless Tides] surf upload failed", e); }   // 0.14.0
        try { TidesFftGpu.setUniforms(prog, l, t, dayTime, sky); } catch (Throwable e) { fftErrors++; if (fftErrors < 3) com.hopeless.tides.HopelessTides.LOG.error("[Hopeless Tides] fft upload failed", e); }   // 0.9.0
        uniformSets++;
    }
    /** 0.10.0: {1 if the eye is under water, the surface's world y over it, the sky light at the eye 0..1}. */
    public static float[] under(Minecraft mc, ClientLevel l) {
        var cam = mc.gameRenderer.getMainCamera();
        if (cam.getFluidInCamera() != net.minecraft.world.level.material.FogType.WATER) { underNow = false; return new float[]{0, 0, 0}; }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(); BlockPos e = cam.getBlockPosition(); double top = cam.getPosition().y + 1;
        for (int y = e.getY(); y <= e.getY() + 64; y++) { m.set(e.getX(), y, e.getZ()); FluidState fs = l.getFluidState(m); if (!fs.is(FluidTags.WATER)) break; top = y + fs.getHeight(l, m); }
        m.set(e.getX(), (int) Math.floor(top), e.getZ());
        underNow = true; underFrames++;
        return new float[]{1, (float) top, l.getBrightness(net.minecraft.world.level.LightLayer.SKY, m) / 15f};
    }
    public static volatile boolean underNow; public static volatile long underFrames;
    /** The water sprites' place in the block atlas: the shader lifts only vertices textured with them. */
    static float[] spriteUV(String name) {
        net.minecraft.client.renderer.texture.TextureAtlasSprite s = Minecraft.getInstance().getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS).apply(new net.minecraft.resources.ResourceLocation(name));
        return new float[]{s.getU0() - 1e-5f, s.getV0() - 1e-5f, s.getU1() + 1e-5f, s.getV1() + 1e-5f};
    }
    public static String probe() { return "render: quads marked " + quadsMarked + " vertices " + vertsMarked + " shader patched " + shaderPatched + " fragment patched " + fragPatched + " uniform sets " + uniformSets + " programs without wave uniforms " + uniformMissing + " look errors " + lookErrors + " fft errors " + fftErrors + " | " + TidesSurfGpu.probe() + " errors " + surfErrors + " | shader origin " + (long) OX + " " + (long) OZ + " rebases " + rebases + " | " + TidesFftGpu.probe() + " | " + TidesLook.probe(); }
}
