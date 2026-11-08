// ---- Hopeless Tides 0.7.0: the water's look, ported from Hopeless Hydro 0.8.2's water shader (BillboardWater.hlsli) to
// Embeddium's terrain fragment shader. Same physics and the owner's Hydro numbers; Minecraft inputs in place of Space Engineers ones:
//   the engine's environment probe   -> the sky colour (u_TidesSky.rgb) where the sky light reaches, the water's own lit colour in caves
//   the sun's shadow cascades        -> the sky light at the water (vanilla has no shadow map): 1 in the open, 0 under a roof
//   the depth buffer's thickness     -> the corner's depth to the bed (0..15 blocks), packed in the vertex colour by the mesher
//   the plugin's per-triangle current -> the water_flow sprite's own direction (vanilla turns that sprite to the flow) and the
//                                       surface's slope (Star Citizen: flow speed follows slope)
//   the wind from the 18 m variance  -> u_TidesSky.w, the wind the server measured (cave wind) or the weather's breeze (open sky)
// In caves the lit water sees torches and lanterns: their light is a lamp overhead, warm, for the glitter and the body.

uniform vec4 u_TidesCam;        // camera position (world), w = 1 when the wave lift is on
uniform vec4 u_TidesSun;        // direction TO the sun or moon (world), w = daylight 0..1 (moon light ~0.12)
uniform vec4 u_TidesSky;        // sky colour (rgb), w = wind m/s over still water
uniform vec4 u_TidesBody[8];    // bodies in the water near the camera: x, y (feet), z, radius (world)
uniform vec4 u_TidesBodyV[8];   // their velocity x, z (m/s), seconds since they went in, 1 = in the water now
uniform int u_TidesBodyN;
uniform sampler2D u_TidesWet;   // per column round the camera: r = wetness of ground the water left, g = that ground's y, b = the water surface block's y (mod 256 / 255), a = 1 where there is water
uniform sampler2D u_TidesFoam;  // Revale's sea-foam coverage (Hopeless Water visual6/7, original artwork for the project): white rgb, coverage in alpha, mipmapped
uniform vec4 u_TidesRain;        // 0.8.0: x = how hard it rains at the camera, 0..1 (0 where the biome snows or never rains)
uniform vec4 u_TidesWetOrigin;  // world x, z of the map's corner, its size in blocks, 1 = on

const float TW_N = 1.333;
const float TW_K = 0.12;                 // per metre, green (Hydro WaterLook.K)
const float TW_FLOOR = 0.42;             // Hydro 0.6.2, the owner's look: even a film covers this much of the bed
const float TW_MURK = 2.5;
const float TW_COLUMN = 1.65;
const float TW_SKYFILL = 0.40;
const float TW_BEACH = 0.05;             // beach slope the breaker timing assumes (1 in 20; Hydro, CHOSEN)
const float TW_GERSTNER = 0.50;
const float TW_FLOW_CYCLE = 2.0;         // s, a flow layer's cycle (Hydro, CHOSEN)
const float TW_RAPIDS = 1.5;             // m/s: faster water foams (Hydro, CHOSEN)
const float TW_TENSION = 7.3e-5;         // surface tension / density, m3/s2 (capillary dispersion)
uniform vec4 u_TidesPlanet;      // 0.13.0: x = surface gravity (m/s2), y = wind-wave height scale (9.81 / g); 0 when not set (shader packs)
#define TW_G (u_TidesPlanet.x > 0.0 ? u_TidesPlanet.x : 9.81)
// Detail waves: wavelength (m), direction off the wind (rad), phase (rad). Revale's visual1+ set (owner-accepted): eight bands from 8 m
// down to 8 cm - broad moving wave faces plus short ripples; incommensurate lengths and crossed directions reduce repetition.
const vec3 TW_DETAIL[8] = vec3[](vec3(8.00, 0.08, 0.0), vec3(4.60, -0.55, 1.7), vec3(2.40, 0.72, 3.9), vec3(1.10, -0.28, 0.8),
                                 vec3(0.57, 0.95, 5.1), vec3(0.28, -0.80, 2.4), vec3(0.13, 0.40, 4.6), vec3(0.08, -0.18, 1.1));

// Smooth value noise (visual2+): integer-hashed lattice with quintic interpolation - no sine lattice, no diamond pattern.
float tw_hash(ivec2 c) { uint h = uint(c.x) * 1597334677u ^ uint(c.y) * 3812015801u; h ^= h >> 16; h *= 2246822519u; h ^= h >> 13; return float(h & 0x00ffffffu) / 16777215.0; }
float tw_noise(vec2 p) {
    ivec2 c = ivec2(floor(p)); vec2 f = fract(p), u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    return mix(mix(tw_hash(c), tw_hash(c + ivec2(1, 0)), u.x), mix(tw_hash(c + ivec2(0, 1)), tw_hash(c + ivec2(1, 1)), u.x), u.y);
}
// Revale's HW_SurfaceFroth: two explicitly mip-filtered samples of the foam texture in differently turned and scaled MIRRORED
// coordinates (no tile edge to match; overlapping scales hide the repeat); the world-pixel footprint picks the mip level.
float tw_froth(vec2 parcel, float footprint) {
    vec2 uv = parcel * 0.26;
    vec2 a = abs(fract(uv * 0.5) * 2.0 - 1.0);
    vec2 rot = vec2(0.8 * uv.x - 0.6 * uv.y, 0.6 * uv.x + 0.8 * uv.y) * 1.61 + vec2(0.31, 0.73);
    vec2 b = abs(fract(rot * 0.5) * 2.0 - 1.0);
    float lod = max(0.0, log2(max(footprint * 0.26 * 512.0, 1.0)));
    return clamp(0.65 * textureLod(u_TidesFoam, a, lod).a + 0.35 * textureLod(u_TidesFoam, b, lod + 0.687).a, 0.0, 1.0);
}
// The foam texture moves with the water parcels: their orbital motion from the first three detail waves (same phases and
// dispersion), and the current. Returns the parcel position.
vec2 tw_parcel(vec2 p, float t, float flowOff) {
    vec2 orbital = vec2(0.0);
    for (int j = 0; j < 3; j++) {
        float k = 6.2831853 / TW_DETAIL[j].x; vec2 d = vec2(cos(TW_DETAIL[j].y), sin(TW_DETAIL[j].y));
        float omega = sqrt(TW_G * k + TW_TENSION * k * k * k);
        orbital += d * (0.09 * cos(k * dot(d, p) - omega * t + TW_DETAIL[j].z));
    }
    return p - orbital - vec2(flowOff, 0.0);
}
// Revale's HW_Foam (visual5-7): a thin, broken contact band at the shore and sparse whitecaps on breaking crests, both drawn
// with the texture; it fades once its detail is smaller than a pixel (gone at a 2 m footprint).
float tw_foam(vec2 parcel, float z, float slope, float crest, float footprint, float froth) {
    float visibility = clamp(1.0 - footprint / 2.0, 0.0, 1.0);
    float broken = smoothstep(0.3, 0.68, tw_noise(parcel * 0.83 + 17.0));
    float breaking = smoothstep(0.35, 0.62, crest);
    float contact = (1.0 - smoothstep(0.025, 0.32, z)) * broken * froth * (0.12 + 0.88 * breaking);
    float whitecap = smoothstep(0.08, 0.24, slope) * breaking * broken * froth * smoothstep(0.3, 1.2, z);
    return clamp((0.85 * contact + 0.95 * whitecap) * visibility, 0.0, 1.0);
}
// Revale's caustic filaments: smoothly warped interference, not crossed sine stripes.
// 0.10.0: the Fourier sea in the fragment shader (same texture and uniforms as tides_fft.glsl in the vertex shader): the height of the
// pair at a rest point x (world mod 512). Used by the caustics: the bed's light pattern is focused and carried by the waves above it.
uniform sampler2D u_TidesFft;
uniform vec4 u_TidesFft0;
uniform vec4 u_TidesFft1;
float tw_fftTexH(vec2 x, int row) {
    vec2 f = x * 0.5; vec2 fl = floor(f); vec2 t = f - fl;
    int ix = int(fl.x) & 255, iz = int(fl.y) & 255, ix1 = (ix + 1) & 255, iz1 = (iz + 1) & 255;
    float a = texelFetch(u_TidesFft, ivec2(ix, row + iz), 0).x, b = texelFetch(u_TidesFft, ivec2(ix1, row + iz), 0).x;
    float c = texelFetch(u_TidesFft, ivec2(ix, row + iz1), 0).x, d = texelFetch(u_TidesFft, ivec2(ix1, row + iz1), 0).x;
    return mix(mix(a, b, t.x), mix(c, d, t.x), t.y);
}
float tw_fftH(vec2 x) { int r0 = int(u_TidesFft0.w + 0.5); float p = tw_fftTexH(x, r0), c = tw_fftTexH(x, 512 - r0); return p + (c - p) * u_TidesFft0.x; }
// 0.10.0: the storm sea's whitecaps and foam streaks (Hydro / Water StormSea, HW_StormScores): x active caps (groups of breakers high
// on the wave), y the residual foam they leave, drawn into streaks along the wind from Beaufort 7. p: the wind frame (x downwind, m);
// hs: the height over sigma. StormFoam.java runs the same arithmetic to find the thresholds that make the white area Monahan's.
uniform vec4 u_TidesStorm;   // x = U10 (m/s), y = sigma (Hs / 4, blocks), z = active threshold, w = residual threshold
vec2 tw_stormScores(vec2 p, float hs, float t) {
    float U = u_TidesStorm.x; vec2 q = p - vec2(0.03 * U * t, 0.0);
    float grp = tw_noise(q * vec2(0.010, 0.022) + vec2(t * 0.05, 3.7));
    vec2 qa = q * vec2(0.35, 0.50) + vec2(11.3, -t * 0.2);
    float torn = (4.0 * tw_noise(qa) + 2.0 * tw_noise(qa * 2.03 + 5.7) + tw_noise(qa * 4.11 - 3.1)) / 7.0;
    float sA = 0.45 * grp + 0.30 * clamp((hs - 0.6) / 1.4, 0.0, 1.0) + 0.25 * torn;
    float streaky = smoothstep(13.9, 20.8, U);
    vec2 qs = q * vec2(mix(0.05, 0.010, streaky), mix(0.08, 0.35, streaky)) + vec2(-5.1, 2.3);
    float str = (2.0 * tw_noise(qs) + tw_noise(qs * 2.03 + 1.9)) / 3.0;
    float lace = 1.0 - abs(2.0 * str - 1.0);
    vec2 qf = q * vec2(0.30, 0.80) + vec2(2.9, 8.1);
    float fine2 = (2.0 * tw_noise(qf) + tw_noise(qf * 2.07 - 4.3)) / 3.0;
    float brk = tw_noise(q * vec2(0.08, 0.20) + vec2(7.7, -1.3));
    return vec2(sA, 0.55 * lace + 0.25 * brk + 0.20 * fine2);
}
float tw_stormFoam(vec2 p, float hs, float t, float footprint) {
    float W = clamp(3.84e-6 * pow(max(u_TidesStorm.x, 0.0), 3.41), 0.0, 1.0);
    float far = 0.3 * W + 0.6 * 0.7 * W, toFar = smoothstep(1.5, 8.0, footprint);
    if (toFar >= 1.0) return far;
    vec2 sc = tw_stormScores(p, hs, t);
    float near = max(smoothstep(u_TidesStorm.z - 0.02, u_TidesStorm.z + 0.02, sc.x), 0.6 * smoothstep(u_TidesStorm.w - 0.02, u_TidesStorm.w + 0.02, sc.y));
    return mix(near, far, toFar);
}
// 0.10.0: the ripple field's slope at world xz (0 outside the 32-block square)
uniform sampler2D u_TidesRip;
uniform vec4 u_TidesRipO;   // world x, z of the field's corner, cell (m), 1 = on
vec2 tw_ripple(vec2 world) {
    if (u_TidesRipO.w < 0.5) return vec2(0.0);
    vec2 f = (world - u_TidesRipO.xy) / u_TidesRipO.z; float n = 128.0;
    if (any(lessThan(f, vec2(1.0))) || any(greaterThan(f, vec2(n - 2.0)))) return vec2(0.0);
    vec2 uv = f / n, e = vec2(1.0 / n, 0.0);
    float hx = texture(u_TidesRip, uv + e.xy).r - texture(u_TidesRip, uv - e.xy).r, hz = texture(u_TidesRip, uv + e.yx).r - texture(u_TidesRip, uv - e.yx).r;
    return vec2(hx, hz) / (2.0 * u_TidesRipO.z);
}
// 0.11.0: clarity factor from the biome's water tint (1 = Minecraft's plain water): swamp-like green-grey 2.5, warm cyan 0.5
uniform vec4 u_TidesFlash;   // x = lightning flash 0..1 (Minecraft's sky flash)
uniform vec4 u_TidesMist;    // x = morning mist over water 0..1
// 0.15.0: the water's type at a place (TidesOptics, core/WaterOptics: Forel-Ule hue, Novoa et al. 2013; Kd490, Pitarch et al. 2019):
// xyz the body's hue (linear), w its Kd (1/m); w = 0 where it is not known (then the biome's tint and murk, as before)
uniform sampler2D u_TidesOptic;
uniform vec4 u_TidesOpticO;  // the map's corner from the shader origin (x, z), its size, 1 = on
vec4 tides_optic(vec2 world) {
    if (u_TidesOpticO.w < 0.5) return vec4(0.0);
    vec2 uv = (world - u_TidesOpticO.xy) / u_TidesOpticO.z; if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return vec4(0.0);
    vec4 o = texture(u_TidesOptic, uv); return o.a < 0.5 / 255.0 ? vec4(0.0) : vec4(o.rgb, o.a * 2.55);
}
float tw_biomeMurk(vec3 c) {
    float m = max(max(c.r, c.g), c.b); if (m < 1e-3) return 1.0; c /= m;
    float blue = c.b - max(c.r, c.g);                                    // plain 0.48, cold 0.6, warm 0.1, swamp under 0.05
    float warm = smoothstep(0.75, 0.9, c.g) * smoothstep(0.8, 0.95, c.b);
    return mix(mix(2.5, 1.0, smoothstep(0.02, 0.25, blue)), 0.5, warm);
}
// 0.11.0: the foam trail field (TidesRipples' second channel): foam that a moving body leaves, drifting and fading over seconds
float tw_foamTrail(vec2 world) {
    if (u_TidesRipO.w < 0.5) return 0.0;
    vec2 f = (world - u_TidesRipO.xy) / u_TidesRipO.z;
    if (any(lessThan(f, vec2(1.0))) || any(greaterThan(f, vec2(126.0)))) return 0.0;
    return clamp(texture(u_TidesRip, f / 128.0).g, 0.0, 1.0);
}
float tw_causticField(vec2 q, float t) {
    vec2 warp = vec2(tw_noise(q * 0.31 + t * 0.035), tw_noise(q * 0.27 - t * 0.029 + 13.7)) - 0.5;
    q += warp * 3.0;
    return (sin(q.x + t * 0.43) + sin(q.y - t * 0.37) + 0.7 * sin(q.x * 0.61 + q.y * 0.79 + t * 0.19)) / 2.7;
}

float tw_fresnel(float cosI, float n1, float n2) {
    cosI = clamp(cosI, 0.0, 1.0);
    float sinT = n1 / n2 * sqrt(max(0.0, 1.0 - cosI * cosI));
    if (sinT >= 1.0) return 1.0;                                          // total internal reflection (from below)
    float cosT = sqrt(1.0 - sinT * sinT);
    float rs = (n1 * cosI - n2 * cosT) / (n1 * cosI + n2 * cosT), rp = (n1 * cosT - n2 * cosI) / (n1 * cosT + n2 * cosI);
    return 0.5 * (rs * rs + rp * rp);
}
// Anisotropic Beckmann (Cox & Munk 1954 split the slope variance along and across the wind).
float tw_glitter(vec3 N, vec3 V, vec3 L, vec3 windW, float pixelVar, float u10, float nv) {
    float nl = dot(N, L); if (nl <= 0.0 || L.y <= 0.0) return 0.0;
    float r2 = clamp((3.16e-3 * u10) / max(0.003 + 1.92e-3 * u10, 1e-4), 0.35, 2.0);
    float sA = max(pixelVar * sqrt(r2), 1e-4), sC = max(pixelVar / sqrt(r2), 1e-4);
    vec3 Tw = windW - N * dot(windW, N); Tw = dot(Tw, Tw) > 1e-8 ? normalize(Tw) : vec3(1.0, 0.0, 0.0);
    vec3 Tc = cross(N, Tw), H = normalize(L + V);
    float nh = max(dot(N, H), 1e-3), zx = dot(H, Tw) / nh, zy = dot(H, Tc) / nh;
    float D = exp(-(zx * zx / sA + zy * zy / sC)) / (3.14159265 * sqrt(sA * sC) * nh * nh * nh * nh);
    return min(D * tw_fresnel(dot(V, H), 1.0, TW_N) / (4.0 * nv), 2000.0);
}

// 0.8.0 RAIN ON THE WATER: Hopeless Hydro 0.9.2 HW_RainRings (owner, 2026-10-18: "rain droplets causing ripples"), ported. Drops land on
// a world-anchored grid, one every TW_RAIN_CELL at the heaviest rain (lighter rain lands in fewer cells); each sends out a thin ring that
// spreads and dies. Returns the rings' slope in the frame p is measured in. Every number is Hydro's (CHOSEN there): rings of ~2.5 cm,
// 0.30 m/s, 0.9 s; where a pixel is too big to show them, the rain roughens the surface instead (TW_RAIN_VAR).
const float TW_RAIN_CELL = 0.45, TW_RAIN_LIFE = 0.9, TW_RAIN_SPEED = 0.30, TW_RAIN_SLOPE = 0.35, TW_RAIN_WAVE = 0.025, TW_RAIN_VAR = 0.004;
vec2 tw_rainRings(vec2 p, float t, float rain, float footprint) {
    float vis = clamp(TW_RAIN_WAVE / (4.0 * footprint) - 1.0, 0.0, 1.0);
    vec2 g = vec2(0.0);
    if (rain <= 0.0 || vis <= 0.0) return g;
    ivec2 c0 = ivec2(floor(p / TW_RAIN_CELL));
    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
        ivec2 cell = c0 + ivec2(dx, dy);
        float h1 = tw_hash(cell), h2 = tw_hash(cell + ivec2(37, 11)), h3 = tw_hash(cell + ivec2(-23, 71));
        vec2 centre = (vec2(cell) + 0.2 + 0.6 * vec2(h1, h2)) * TW_RAIN_CELL;
        float age = fract(t / TW_RAIN_LIFE + h1 * 7.13 + h2 * 3.71) * TW_RAIN_LIFE;
        vec2 d = p - centre; float r = length(d);
        float x = (r - age * TW_RAIN_SPEED) / TW_RAIN_WAVE;
        float env = exp(-0.5 * x * x) * (1.0 - age / TW_RAIN_LIFE) / (1.0 + r / 0.05);
        float on = h3 <= rain ? 1.0 : 0.0;
        g += on * TW_RAIN_SLOPE * env * sin(6.2831853 * x) * d / max(r, 1e-4);
    }
    return g * vis;
}

// Bodies: a ring spreading from where a body went in, a bulge round a body in the water, and the Kelvin wake behind a moving one.
// Returns the slope (world x, z) they add at p, and their foam.
vec3 tw_bodies(vec2 p, float wy, float t) {
    vec3 o = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        if (i >= u_TidesBodyN) break;
        vec4 b = u_TidesBody[i], v = u_TidesBodyV[i];
        if (abs(wy - b.y - 0.6) > 1.6) continue;                       // not at this surface
        vec2 d = p - b.xz; float r = length(d); if (r > 14.0) continue;
        vec2 dir = r > 1e-3 ? d / r : vec2(1.0, 0.0);
        // entry ring: the crest of a capillary-gravity ring moves out at ~0.6 m/s + 0.25 m/s per second (dispersion), fading in 4 s
        float age = v.z;
        if (age < 4.0) {
            float R = b.w + 0.6 * age + 0.12 * age * age, w = exp(-age * 0.9) * 0.35;
            float x = r - R; o.xy += dir * w * sin(x * 9.0) * exp(-x * x * 6.0);
            o.z += 0.6 * exp(-age * 2.0) * exp(-x * x * 20.0);
        }
        if (v.w < 0.5) continue;
        // the body itself: water rises round it (Hydro Wake.cs: it piles up ahead and drops behind)
        float sp = length(v.xy); vec2 fw = sp > 0.05 ? v.xy / sp : vec2(0.0);
        float bump = exp(-pow(max(r - b.w, 0.0) * 3.0, 2.0)) * (0.12 + 0.25 * min(sp, 3.0) * dot(dir, fw));
        o.xy += dir * bump * 2.0;
        // Kelvin wake: arms at 19.47 deg behind a moving body, decaying with distance
        if (sp > 0.25) {
            vec2 rel = d; float back = -dot(rel, fw), side = abs(dot(rel, vec2(-fw.y, fw.x)));
            if (back > 0.0) {
                float arm = side - 0.3536 * back;                          // tan 19.47 deg
                float a = exp(-arm * arm * 8.0) * exp(-back * 0.25) * min(sp, 3.0) * 0.18;
                float kx = 6.2831853 * TW_G / max(sp * sp, 0.2);            // transverse waves at the body's speed: lambda = 2 pi U^2 / g
                o.xy += fw * a * sin(back * min(kx, 30.0) - t * 6.0) + vec2(-fw.y, fw.x) * sign(dot(rel, vec2(-fw.y, fw.x))) * a * 0.5;
                o.z += a * 0.8 * smoothstep(0.6, 1.4, sp);
            }
        }
    }
    return o;
}

// The look. base: the terrain shader's colour for this pixel (texture x biome tint x light map), alpha the texture's.
// rel: position from the camera; world: world x, z; slope: the drawn waves' slope; ed: (exposure, depth) 0..15; light: (block, sky) 0..1.
vec4 tides_look(vec4 base, vec3 rel, vec2 world, vec2 slope, vec2 ed, vec2 light, float flowing, vec2 uv, vec2 simFlow) {
    float t = u_TidesRipple.x;
    vec3 dpx = dFdx(rel), dpy = dFdy(rel);
    vec3 ng = cross(dpx, dpy); ng = dot(ng, ng) > 1e-12 ? normalize(ng) : vec3(0.0, 1.0, 0.0);
    if (abs(ng.y) < 0.5) return base;                                   // a falling sheet's side: vanilla's look
    float ulp = max(abs(world.x), abs(world.y)) * 2.3841858e-7;        // visual7: no wavelengths under float precision far from the origin
    float footprint = max(max(max(length(dpx), length(dpy)), ulp), 1e-3);   // metres one pixel covers here
    float sky = light.y, blk = light.x;

    // --- wind and current
    float u10 = max(u_TidesSky.w, 0.3);
    vec2 windD = vec2(cos(u_TidesRipple.w), sin(u_TidesRipple.w));
    float flowV = 0.0, river = 0.0; vec2 flowD = windD;
    if (flowing > 0.5) {
        // vanilla turns the flow sprite so its v runs downstream: the world direction in which v grows is the current
        vec2 dvx = vec2(dFdx(uv.y)), dvy = vec2(dFdy(uv.y));
        mat2 M = mat2(dpx.x, dpy.x, dpx.z, dpy.z);                       // rows: d(world x, z)/d(screen x), /d(screen y)
        float det = determinant(M);
        if (abs(det) > 1e-12) { vec2 g = inverse(M) * vec2(dvx.x, dvy.x); if (dot(g, g) > 1e-12) flowD = normalize(g); }
        float geoSlope = length(vec2(ng.x, ng.z)) / max(abs(ng.y), 0.2);
        flowV = clamp(0.4 + 7.0 * geoSlope, 0.3, 4.5);                  // CHOSEN: vanilla's 1-level-per-block slope (0.11) gives 1.2 m/s
        river = clamp((flowV - 0.15) / 0.35, 0.0, 1.0);
    }
    if (simFlow.x >= 0.0) {                                             // the column simulation's own current (HydroSim): any block shape
        flowV = simFlow.x; river = clamp((flowV - 0.15) / 0.35, 0.0, 1.0);
        if (river > 0.0) flowD = vec2(cos(simFlow.y), sin(simFlow.y));
    }
    vec2 windS = river > 0.0 ? flowD : windD, sideS = vec2(-windS.y, windS.x);
    vec2 p = vec2(dot(world, windS), dot(world, sideS));

    // --- detail waves (Hydro: eight short waves carry the slope a pixel can show; the rest widens the glitter)
    float total = 0.003 + 5.12e-3 * u10;                                // Cox-Munk clean-surface slope variance
    float share = max(total - 0.002, 0.0) / 8.0;
    float windAmp = clamp(0.45 + 0.55 * (u10 / 7.0), 0.0, 1.0);
    float s = sqrt(2.0 * share) * windAmp * 1.25 * (1.0 + 0.5 * river * min(flowV, 4.0));   // visual1: stronger resolved wave lighting
    float fph = fract(t / TW_FLOW_CYCLE);
    float off1 = river * flowV * fph * TW_FLOW_CYCLE, off2 = river * flowV * fract(fph + 0.5) * TW_FLOW_CYCLE;
    float wt1 = 1.0 - abs(1.0 - 2.0 * fph);
    vec2 gD = vec2(0.0); float pixelVar = 0.002, crest = 0.0, crestW = 0.0;
    for (int i = 0; i < 8; i++) {
        float lambda = TW_DETAIL[i].x;
        float vis = clamp(lambda / (8.0 * footprint) - 1.0, 0.0, 1.0);  // gone under 8 pixels, full over 16
        float k = 6.2831853 / lambda, omega = sqrt(TW_G * k + TW_TENSION * k * k * k);
        float spread = TW_DETAIL[i].y * mix(1.0, 0.35, river);
        vec2 d = vec2(cos(spread), sin(spread));
        float ph1 = k * dot(d, p - vec2(off1, 0.0)) - omega * t + TW_DETAIL[i].z, ph2 = k * dot(d, p - vec2(off2, 0.0)) - omega * t + TW_DETAIL[i].z;
        float sp = mix(sin(ph2), sin(ph1), wt1), cp = mix(cos(ph2), cos(ph1), wt1);
        float fold = max(0.35, 1.0 - TW_GERSTNER * s * sp);
        vec2 gs = vis * (s * cp / fold) * d;
        gD += windS * gs.x + sideS * gs.y;
        crest += vis * max(sp, 0.0); crestW += vis;
        pixelVar += (1.0 - vis * vis) * share * windAmp * windAmp * 1.5625;
    }
    float wy = rel.y + u_TidesCam.y;
    vec3 bo = tw_bodies(world, wy, t);
    // 0.10.0: the ripple field round the camera (TidesRipples: the damped wave equation pushed by the bodies) carries the rings and
    // wakes in place of the bodies' drawn rings; their foam stays
    vec2 rg = tw_ripple(world);
    gD += u_TidesRipO.w > 0.5 ? rg : bo.xy;
    // 0.8.0 the rain: rings where the sky reaches the water (sky light 15 = open sky; Minecraft's rain falls there), roughness where a
    // pixel is too big for them. Rings are drawn in the world frame (they are round).
    float rain = u_TidesRain.x * smoothstep(0.88, 0.98, sky);
    if (rain > 0.0) { gD += tw_rainRings(world, t, rain, footprint); pixelVar += rain * TW_RAIN_VAR; }

    // --- surf: breaker lines along the depth contours, only with waves (Hydro / Revale)
    float zM = ed.y;
    // the shore edge to the pixel, from the water map (linear filtering of 'water here' is 0.5 on the edge between a water and a
    // land column and 1 one block in): the corner depths come from a 4-block grid, too coarse for a contact band of 0.025-0.32 m
    if (u_TidesWetOrigin.w > 0.5) {
        vec2 uvw = (world - u_TidesWetOrigin.xy) / u_TidesWetOrigin.z;
        ivec2 tc = ivec2(floor(world - u_TidesWetOrigin.xy));
        if (all(greaterThanEqual(tc, ivec2(1))) && all(lessThan(tc, ivec2(int(u_TidesWetOrigin.z) - 1))) && texelFetch(u_TidesWet, tc, 0).a > 0.5) {
            float edge = max(texture(u_TidesWet, uvw).a - 0.5, 0.0);   // blocks from the land edge, up to 0.5
            if (edge < 0.5) zM = min(zM, edge * 0.9);                   // CHOSEN: Minecraft banks are steep, about 0.9 m of depth per block
        }
    }
    float swash = clamp((0.65 - zM) / 0.65, 0.0, 1.0), steepen = clamp((3.5 - zM) / 3.0, 0.0, 1.0) * (1.0 - swash);   // visual1+ numbers
    vec2 g = slope * mix(1.0, 0.3, swash) + gD * (1.0 + 0.9 * steepen) * (1.0 - swash);
    vec3 N = normalize(vec3(-g.x, 1.0, -g.y));
    vec3 V = normalize(-rel);
    vec3 windW = vec3(windS.x, 0.0, windS.y);

    // --- light: the sun or moon where the sky reaches, a lamp overhead where block light does
    vec3 L = normalize(u_TidesSun.xyz);
    float sunVis = sky * sky * u_TidesSun.w;
    vec3 sunCol = mix(vec3(1.0, 0.62, 0.38), vec3(1.0, 0.97, 0.9), clamp(L.y * 3.0, 0.0, 1.0)) * sunVis;
    vec3 lampL = normalize(vec3(0.25, 1.0, 0.15)); vec3 lampCol = vec3(1.0, 0.78, 0.5) * blk * blk * 0.55;
    vec3 skyRefl = u_TidesSky.rgb * (0.25 + 0.75 * u_TidesSun.w) * sky * sky;
    skyRefl += vec3(0.75, 0.8, 1.0) * u_TidesFlash.x * sky;          // 0.11.0: a lightning flash mirrored in the water
    float lum = max(max(base.r, base.g), base.b);

    if (V.y >= 0.0) {
        float nv = max(dot(N, V), 0.02);
        float F = tw_fresnel(nv, 1.0, TW_N);
        float gloss = clamp(1.0 - 2.5 * sqrt(pixelVar), 0.0, 1.0);
        vec3 env = (skyRefl * mix(0.7, 1.0, gloss) + base.rgb * 0.35 * (1.0 - sky)) * F;   // under a roof it mirrors its lit surroundings
        vec3 spec = sunCol * tw_glitter(N, V, L, windW, pixelVar, u10, nv) + lampCol * tw_glitter(N, V, lampL, windW, pixelVar + 0.01, u10, nv);
        // through the surface: the bed fades on the light's way down and back up
        float sunUp = max(0.05, sky > 0.3 ? L.y : 1.0);
        float sinT = sqrt(max(0.0, 1.0 - nv * nv)) / TW_N, cosV = sqrt(1.0 - sinT * sinT);
        float sinS = sqrt(max(0.0, 1.0 - sunUp * sunUp)) / TW_N, cosS = sqrt(1.0 - sinS * sinS);
        float depth = ed.y >= 15.0 ? 40.0 : ed.y;                       // 15 = 15 or more
        float optical = depth / cosS + depth / cosV;
        // 0.11.0: clarity by the biome's water (Minecraft tints water per biome): green-grey swamp water is murky, the warm seas' cyan
        // clear (Jerlov's ocean types: tropical I-II clearest, coastal 3-9 murkiest; CHOSEN steps), and a river in rain carries mud
        vec4 opt = tides_optic(world);
        // 0.15.0: where the water's type is known, its Kd (Pitarch et al. 2019 medians) relative to the cool open sea's (0.099 /m), which
        // keeps the look Tides had (Hopeless Water's ShaderK rule), held to 0.48 /m (Water's GAMEPLAY floor: no faster than the 10 m sight)
        float murk = (opt.w > 0.0 ? TW_MURK * min(opt.w, 0.48) / 0.099 : TW_MURK * tw_biomeMurk(base.rgb)) * (1.0 + 1.5 * flowing * u_TidesRain.x);
        float opaque = 1.0 - (1.0 - TW_FLOOR) * exp(-TW_K * murk * TW_COLUMN * optical);
        opaque = 1.0 - (1.0 - opaque) * mix(TW_SKYFILL, 1.0, max(sky, blk));
        vec3 body = base.rgb * 0.78;                                    // Hydro's darker body, in the biome's own water colour
        if (opt.w > 0.0) { float lb = dot(base.rgb, vec3(0.2126, 0.7152, 0.0722)), lo = max(dot(opt.rgb, vec3(0.2126, 0.7152, 0.0722)), 1e-3); body = opt.rgb * (lb / lo) * 0.78; }   // 0.15.0: the type's hue at the biome water's brightness
        spec = vec3(1.0) - exp(-spec * 0.35);                           // no HDR target here: the glint rolls off softly instead of clipping flat
        vec3 rgb = env + spec + (1.0 - F) * opaque * body;
        float alpha = clamp(F + (1.0 - F) * opaque, 0.0, 1.0);
        // foam (Revale visual7, owner-accepted): textured shore contact band and crest whitecaps carried by the water - no broad surf
        // zone, no contour bars. The rapids, the wake of steep chop and the bodies' rings use the same texture inside their masks.
        vec2 parcel = tw_parcel(p, t, off1 * 0.5 + off2 * 0.5);
        float froth = tw_froth(parcel, footprint);
        float crestN = crest / max(crestW, 1e-4);
        float foam = tw_foam(parcel, zM, length(g), crestN, footprint, froth) * smoothstep(0.03, 0.18, V.y);
        float rapids = clamp((flowV - TW_RAPIDS) / 2.5, 0.0, 1.0);
        float streak = clamp(0.5 + 0.5 * sin(p.y * 2.3 + crest * 1.7) * sin((p.x - flowV * t) * 0.35), 0.0, 1.0);
        float vis2 = clamp(1.0 - footprint / 2.0, 0.0, 1.0);
        float wake = clamp((length(g) - 0.32) * 1.4, 0.0, 1.0);
        foam = max(foam, max(rapids * (0.35 + 0.65 * streak), max(wake, bo.z)) * froth * vis2 * 1.3);
        foam = max(foam, tides_cap * froth * smoothstep(0.03, 0.18, V.y));   // 0.9.0: whitecaps where the Fourier crests fold (physics: breaking)
        foam = max(foam, tw_foamTrail(world) * (0.35 + 0.65 * froth) * smoothstep(0.03, 0.18, V.y));   // 0.11.0: foam trails that bodies leave
        // 0.10.0: the storm sea (only at sea: exposed, deep, open sky) - whitecaps and wind streaks at Monahan's cover, and light through
        // the thin top of a high crest (Hydro's HW_StormCrestLight; Sea of Thieves' subsurface crests)
        float seaW = smoothstep(9.0, 15.0, ed.x) * smoothstep(3.0, 8.0, ed.y) * smoothstep(0.88, 0.98, sky);
        float hsig = u_TidesStorm.y > 1e-3 ? tides_h / u_TidesStorm.y : 0.0;
        if (seaW > 0.0 && u_TidesStorm.x > 0.0) {
            foam = max(foam, tw_stormFoam(p, hsig, t, footprint) * seaW * (0.45 + 0.55 * froth) * smoothstep(0.03, 0.18, V.y));   // textured by the foam artwork
            float hi = smoothstep(1.2, 2.2, hsig), back = 0.3 + 0.7 * pow(clamp(dot(-V, L), 0.0, 1.0), 2.0);
            rgb += vec3(0.02, 0.30, 0.26) * hi * back * max(dot(N, V), 0.0) * sunCol * seaW * 0.6;
        }
        foam = clamp(foam, 0.0, 1.0);
        // 0.11.0: bioluminescence (dinoflagellates light where the water is disturbed): at night, in the warm seas' water, foam and
        // wakes glow blue; strength CHOSEN (a faint glow, not a lamp)
        float warmSea = smoothstep(0.75, 0.9, base.g / max(max(base.r, base.b), 1e-3)) * (1.0 - u_TidesSun.w) * smoothstep(0.88, 0.98, sky);
        vec3 glow = vec3(0.05, 0.45, 0.85) * warmSea * clamp(foam * 1.5 + tw_foamTrail(world) + length(rg) * 2.0, 0.0, 1.0) * 0.5;
        vec3 foamLit = vec3(0.82, 0.85, 0.86) * max(lum * 1.5, 0.02);   // visual4: near-white lit foam
        rgb = rgb * (1.0 - foam) + foamLit * foam;
        rgb += glow; alpha = max(alpha, clamp(max(max(glow.r, glow.g), glow.b) * 1.5, 0.0, 1.0));
        alpha = clamp(alpha * (1.0 - foam) + foam, 0.0, 1.0);
        // 0.11.0: morning mist lying on the water (radiation fog): the far water whitens toward the sky's colour
        float haze = u_TidesMist.x * smoothstep(4.0, 48.0, length(rel)) * smoothstep(0.5, 0.95, sky);
        vec3 mistCol = mix(u_TidesSky.rgb, vec3(0.92, 0.93, 0.95), 0.55) * (0.35 + 0.65 * u_TidesSun.w);
        rgb = mix(rgb * alpha, mistCol, haze * 0.75) / max(alpha, 1e-3); alpha = mix(alpha, 1.0, haze * 0.75);
        rgb = rgb / max(alpha, 0.05);                                    // the terrain pass blends by alpha: keep the glitter and the sky at full strength
        return vec4(min(rgb, vec3(1.0)), alpha);
    } else {
        // from below: the detail only (no facets), Snell's window and total internal reflection, caustics, shafts toward the sun
        vec3 Nb = normalize(vec3(-gD.x, 1.0, -gD.y));
        float nv = clamp(dot(-Nb, V), 0.0, 1.0);
        float F = tw_fresnel(nv, TW_N, 1.0);
        float chop = clamp(length(gD) * 4.0, 0.0, 1.0);
        float cau = sin(p.x * 0.83 + p.y * 0.47 + t * 0.35) * sin(p.x * 0.29 - p.y * 0.71 - t * 0.22);
        float caustic = (0.72 + 0.28 * cau) * (0.8 + 0.55 * chop);
        vec2 sunH = L.xz; float sl2 = dot(sunH, sunH);
        vec2 side = sl2 > 1e-4 ? vec2(-sunH.y, sunH.x) * inversesqrt(sl2) : vec2(1.0, 0.0);
        float shaft = pow(clamp(sin(dot(world, side) * 0.09 + t * 0.1) * 0.5 + 0.5, 0.0, 1.0), 6.0);
        float towardSun = pow(clamp(dot(normalize(rel), L), 0.0, 1.0), 3.0);
        vec3 lit = base.rgb * 0.78 * caustic * (0.75 + 0.7 * shaft * (0.2 + 0.8 * towardSun) * sunVis);
        float alpha = clamp(F * 0.8 + 0.12 + chop * 0.15, 0.0, 1.0);
        return vec4(min(F * lit / max(alpha, 0.05), vec3(1.0)), alpha);
    }
}

// Wet ground the water left (Hydro WetBank, Star Citizen's wet edge colour): darkens the top of a column that is wet.
vec3 tides_wet(vec3 rgb, vec3 rel) {
    if (u_TidesWetOrigin.w < 0.5) return rgb;
    vec3 wp = rel + u_TidesCam.xyz;
    vec2 uvw = (wp.xz - u_TidesWetOrigin.xy) / u_TidesWetOrigin.z;
    if (any(lessThan(uvw, vec2(0.0))) || any(greaterThan(uvw, vec2(1.0)))) return rgb;
    vec2 w = vec2(texture(u_TidesWet, uvw).r, texelFetch(u_TidesWet, ivec2(floor(wp.xz - u_TidesWetOrigin.xy)), 0).g);
    if (w.r <= 0.0) return rgb;
    float topY = w.g * 255.0, dy = mod(wp.y - topY + 128.0, 256.0) - 128.0;   // the wet block's top at topY + 1
    float on = 1.0 - smoothstep(0.9, 1.2, abs(dy - 0.5));             // the wet block only, top and sides
    vec3 wet = mix(rgb, vec3(0.032, 0.023, 0.019) * 4.0 * max(max(rgb.r, rgb.g), rgb.b) + rgb * 0.35, 0.45 * w.r * on);
    // 0.11.0: puddles: on soaked flat tops under the sky, water stands in the hollows (world noise) and mirrors the sky (Fresnel, n 1.333)
    vec3 pdx = dFdx(rel), pdy = dFdy(rel); vec3 n = cross(pdx, pdy); n = dot(n, n) > 1e-12 ? normalize(n) : vec3(0.0, 1.0, 0.0); if (n.y < 0.0) n = -n;
    float puddle = smoothstep(0.55, 0.75, w.r) * on * smoothstep(0.9, 0.98, n.y)                  // soaked top faces of the wet block
                 * smoothstep(0.56, 0.66, tw_noise(wp.xz * 0.9) * 0.7 + tw_noise(wp.xz * 2.6 + 7.1) * 0.3);   // hollows 0.5-1.5 blocks across
    if (puddle > 0.0) {
        float F = tw_fresnel(max(dot(n, normalize(-rel)), 0.02), 1.0, TW_N);
        vec3 refl = u_TidesSky.rgb * (0.25 + 0.75 * u_TidesSun.w);
        wet = mix(wet, wet * 0.55 * (1.0 - F) + refl * F, puddle);
    }
    return wet;
}
// Caustics on the bed (Revale visual1-2, ported to the terrain pass): a submerged terrain pixel traces back toward the sun along the
// refracted ray to the surface and lights narrow, depth-softened filaments there. Bounded to 32 blocks, fades with depth (8-18) and
// at night; nothing above water, in caves without sky, or where the water map has no water.
vec3 tides_caustics(vec3 rgb, vec3 rel, vec2 light) {
    if (u_TidesWetOrigin.w < 0.5 || u_TidesSun.w <= 0.0 || light.y < 0.05) return rgb;
    float range = length(rel); if (range > 32.0) return rgb;
    vec3 wp = rel + u_TidesCam.xyz;
    vec2 uvw = (wp.xz - u_TidesWetOrigin.xy) / u_TidesWetOrigin.z;
    if (any(lessThan(uvw, vec2(0.0))) || any(greaterThan(uvw, vec2(1.0)))) return rgb;
    vec4 m = texelFetch(u_TidesWet, ivec2(floor(wp.xz - u_TidesWetOrigin.xy)), 0);
    vec3 dx = dFdx(rel), dy = dFdy(rel); vec3 nrm = cross(dx, dy); nrm = dot(nrm, nrm) > 1e-12 ? normalize(nrm) : vec3(0.0, 1.0, 0.0);
    if (dot(nrm, -rel) < 0.0) nrm = -nrm;
    if (m.a < 0.5) return rgb;
    float surfY = m.b * 255.0, dyS = mod(surfY - wp.y + 128.0, 256.0) - 128.0 + 0.875;   // the water surface above this pixel
    float submerged = dyS; if (submerged < 0.08 || submerged > 18.0) return rgb;
    vec3 L = normalize(u_TidesSun.xyz);
    vec3 refr = refract(-L, vec3(0.0, 1.0, 0.0), 1.0 / TW_N);
    vec3 sp = wp - refr * (submerged / max(-refr.y, 0.2));
    // 0.10.0: the Fourier waves over this spot bend the light: a refracted ray moves sideways by z (1 - 1/n) grad h, so the bed's
    // pattern rides with the waves and is brighter where they focus it, 1 / det(I + z (1 - 1/n) Hess h) (Hydro's receiver caustics,
    // with the real surface in place of one carrier wave). The fine filaments stay the short waves no vertex can carry.
    float focus = 1.0; vec2 warp = vec2(0.0);
    if (u_TidesFft1.x > 0.5 && light.y > 0.5) {
        vec2 c0 = sp.xz - 512.0 * floor(sp.xz / 512.0);
        float a = u_TidesFft0.y * clamp(submerged / 4.0, 0.25, 1.0);           // shallow water carries less of the open sea (no shelter data here)
        float h0 = tw_fftH(c0), hxp = tw_fftH(c0 + vec2(1.0, 0.0)), hxm = tw_fftH(c0 - vec2(1.0, 0.0)), hzp = tw_fftH(c0 + vec2(0.0, 1.0)), hzm = tw_fftH(c0 - vec2(0.0, 1.0));
        float hpp = tw_fftH(c0 + vec2(1.0, 1.0)), hmm = tw_fftH(c0 - vec2(1.0, 1.0));
        float zc = submerged * (1.0 - 1.0 / TW_N) * a;
        vec2 g = 0.5 * vec2(hxp - hxm, hzp - hzm);
        float hxx = hxp - 2.0 * h0 + hxm, hzz = hzp - 2.0 * h0 + hzm, hxz = 0.5 * (hpp + hmm - hxp - hxm - hzp - hzm + 2.0 * h0);
        float det = (1.0 + zc * hxx) * (1.0 + zc * hzz) - zc * zc * hxz * hxz;
        focus = clamp(1.0 / max(abs(det), 0.25), 0.4, 3.0);
        warp = zc * g;
    }
    vec2 q = (sp.xz + warp) * 2.2; float t = u_TidesRipple.x;
    float field = tw_causticField(q, t);
    float width = max(0.12 + 0.012 * min(submerged, 10.0), fwidth(field));
    float lines = exp2(-2.0 * field * field / (width * width)) * (0.10 / width) * smoothstep(0.25, 0.75, tw_noise(q * 0.43 + vec2(t * 0.04, 7.3)));
    float fade = (1.0 - smoothstep(18.0, 32.0, range)) * (1.0 - smoothstep(8.0, 18.0, submerged)) * (1.0 - smoothstep(0.15, 0.65, max(length(dx), length(dy))));
    float sunL = smoothstep(0.08, 0.35, L.y) * max(dot(nrm, L), 0.0) * light.y * light.y * u_TidesSun.w;
    vec3 att = exp(-vec3(0.10, 0.085, 0.075) * (submerged + range * 0.25));
    // short-wave slope variance (Cox & Munk 1954, clean sea: mss = 0.003 + 0.00512 U) sets how hard the filaments focus: calm water
    // draws faint lines, a gale strong ones; 1 at a 5 m/s breeze
    float mss = 0.003 + 0.00512 * max(u_TidesSky.w, 0.3), contrast = clamp(sqrt(mss / 0.0286), 0.45, 1.7);
    return rgb * (1.0 + lines * focus * contrast * fade * sunL * 0.9 * att);                // LDR: a brightening of the lit bed (SE added 0.018 of HDR sun)
}

// 0.10.0: the view from inside the water (Hydro VolumeLook / Revale's under-water model, ported to the terrain pass): what the eye
// sees under water loses red first (Hydro's under-water fog spectrum, 3.33 : 1 : 0.92 against green's 0.12 /m; half of it here,
// Minecraft's own water fog does the rest, CHOSEN) and fills with the water's lit colour; sun shafts where the sky reaches, light
// that came down the refracted sun direction through gaps in the surface pattern, fading with the path in the water.
uniform vec4 u_TidesUnder;   // x = 1 when the eye is under water, y = the surface's world y over it, z = sky light at the eye 0..1
vec3 tides_under(vec3 rgb, vec3 rel, vec2 light) {
    if (u_TidesUnder.x < 0.5) return rgb;
    float d = length(rel); if (d < 1e-3) return rgb;
    vec3 dir = rel / d, eye = u_TidesCam.xyz;
    vec3 k = vec3(0.40, 0.12, 0.11) * 0.5;
    vec3 T = exp(-k * d);
    float day = u_TidesSun.w * u_TidesUnder.z;
    vec3 inCol = vec3(0.035, 0.20, 0.27) * (0.15 + 0.85 * day) * (0.4 + 0.6 * max(light.y, light.x));
    vec3 outc = rgb * T + inCol * (1.0 - T);
    if (day > 0.02) {
        vec3 L = normalize(u_TidesSun.xyz);
        vec3 rs = refract(-L, vec3(0.0, 1.0, 0.0), 1.0 / TW_N);
        float t = u_TidesRipple.x, len = min(d, 24.0), acc = 0.0;
        for (int i = 0; i < 8; i++) {
            float sd = (float(i) + 0.5) / 8.0 * len; vec3 pp = eye + dir * sd;
            float z = u_TidesUnder.y - pp.y; if (z < 0.0) continue;
            vec2 q = pp.xz - rs.xz * (z / max(-rs.y, 0.2));
            float beam = smoothstep(0.55, 0.8, tw_noise(q * 0.45 + vec2(t * 0.15, -t * 0.08))) * smoothstep(0.4, 0.7, tw_noise(q * 0.13 - vec2(t * 0.03, 1.7)));
            acc += beam * exp(-0.12 * (sd + z));
        }
        outc += vec3(0.30, 0.55, 0.55) * acc / 8.0 * day * smoothstep(0.05, 0.3, L.y) * 0.9 * (len / 24.0);
    }
    return outc;
}

// 0.10.0: ice (Hopeless Hydro / Water HW_IcePixel, on Minecraft's ice blocks): world-anchored cracks (two noise octaves, thin lines
// where the noise crosses 0.5, gone as the pixel grows past 0.5 m), a cool blue-white body and the sky mirrored at grazing angles
// (Fresnel, n = 1.31). Half of Minecraft's own ice texture is kept so it still reads as that block (CHOSEN).
uniform vec4 u_TidesUVIce;      // the ice sprite's place in the block atlas (u0, v0, u1, v1); u0 > u1 = off
vec4 tides_ice(vec4 base, vec3 rel, vec2 uv, vec2 light) {
    if (u_TidesUVIce.x > u_TidesUVIce.z || any(lessThan(uv, u_TidesUVIce.xy)) || any(greaterThan(uv, u_TidesUVIce.zw))) return base;
    vec3 wp = rel + u_TidesCam.xyz;
    vec3 dpx = dFdx(rel), dpy = dFdy(rel); vec3 n = cross(dpx, dpy); n = dot(n, n) > 1e-12 ? normalize(n) : vec3(0.0, 1.0, 0.0);
    vec3 V = normalize(-rel); if (dot(n, V) < 0.0) n = -n;
    vec2 p = abs(n.y) > 0.5 ? wp.xz : (abs(n.x) > 0.5 ? wp.zy : wp.xy);
    float footprint = max(length(dpx), length(dpy));
    float n1 = tw_noise(p * 0.35), n2 = tw_noise(p * 1.7 + 11.3);
    float crack = (clamp(1.0 - abs(n1 - 0.5) * 40.0, 0.0, 1.0) * 0.6 + clamp(1.0 - abs(n2 - 0.5) * 60.0, 0.0, 1.0) * 0.3) * clamp(1.0 - footprint / 0.5, 0.0, 1.0);
    vec3 alb = mix(vec3(0.62, 0.74, 0.86), vec3(0.92, 0.95, 0.98), crack * 0.7);
    float lit = 0.35 + 0.65 * max(light.y, light.x);
    float F = tw_fresnel(max(dot(n, V), 0.02), 1.0, 1.31) * light.y;
    vec3 rgb = mix(base.rgb, alb * lit, 0.5) * (1.0 - F) + u_TidesSky.rgb * F;
    return vec4(rgb, max(base.a, 0.55 + 0.35 * crack));
}

// ---- 0.12.0 LAVA, all the way (replaces 0.11's crust noise). What a basalt lava surface does, measured at Kilauea (Hon, Kauahikaua
// & Mackay 1993, USGS OF 93-342A: thermocouples in the flow 1116-1142 C; the crust surface 414 C at 0.186 h, 310 C at 0.708 h,
// 251 C at 1.82 h, linear in log time after 0.1 h, faster before it while glass forms; crust thickens as the square root of time,
// Dehn et al. 2007 / USGS 70170402):
//   - the surface is a crust of PLATES that break and drift; what shows between them is the lava inside, at about 1140 C;
//   - a plate's skin cools from the moment it forms (tl_surfaceC, the measured curve), so young plates glow and old ones go black;
//   - the light a surface gives is its Planck radiance (tl_glow: R 610, G 550, B 465 nm), so the colour follows temperature with no
//     painted ramp: yellow-orange at 1140 C, cherry red near 800 C, a dull red under 650 C, nothing under the Draper point (525 C);
//   - lava cools where it touches ground: the plates are older (colder) near the edge (the lava map's distance from the edge);
//   - a lake's crust turns over slowly (CHOSEN: a plate lives 150 s), a flow tears its crust into small rafts that live 30 s;
//   - fresh crust is glassy and shines (pahoehoe), with ropes across the flow; gas bubbles burst through and show fresh lava;
//   - a falling sheet (a lava fall's side) is torn, nearly all incandescent, streaked downward.
// The block's own texture stays as fine detail (25 %). Lava gives its own light: the crust is lit by the sky and the glow.
uniform sampler2D u_TidesLavaMap;   // TidesLavaMap: r = blocks from the edge x 31, g = surface y, b = nearness for ground, a = lava
uniform vec4 u_TidesLavaO;          // world x, z of the map's corner, its size (blocks), 1 = on
const float TL_CORE_C = 1140.0;
float tl_surfaceC(float age) {
    float th = age / 3600.0;
    if (th >= 0.1) return max(40.0, 294.0 - 71.5 * log(th));          // Hon et al.: 414 C at 0.186 h, 251 C at 1.82 h (fit, linear in log t)
    float f = clamp(log(max(age, 0.5) / 0.5) / log(720.0), 0.0, 1.0); // before 0.1 h (glass): from the core temperature to 459 C at 360 s
    return mix(TL_CORE_C, 459.0, f);
}
const vec4 TL_GLOW[33] = vec4[](vec4(4.7037, 0.0000, 0.00000, 1.3664e-07), vec4(4.7037, 0.0000, 0.00000, 3.9105e-07), vec4(4.7037, 0.0000, 0.00000, 1.0493e-06), vec4(4.7037, 0.0000, 0.00000, 2.6555e-06), vec4(4.7037, 0.0000, 0.00000, 6.3700e-06), vec4(4.7037, 0.0000, 0.00000, 1.4551e-05), vec4(4.6405, 0.0188, 0.00000, 3.1777e-05), vec4(4.5718, 0.0392, 0.00000, 6.6587e-05), vec4(4.5025, 0.0598, 0.00000, 1.3432e-04), vec4(4.4330, 0.0805, 0.00000, 2.6156e-04), vec4(4.3636, 0.1011, 0.00000, 4.9301e-04), vec4(4.2947, 0.1216, 0.00000, 9.0156e-04), vec4(4.2265, 0.1418, 0.00000, 1.6030e-03), vec4(4.1591, 0.1619, 0.00000, 2.7764e-03), vec4(4.0927, 0.1816, 0.00000, 4.6929e-03), vec4(4.0274, 0.2010, 0.00000, 7.7532e-03), vec4(3.9634, 0.2201, 0.00000, 1.2538e-02), vec4(3.9006, 0.2387, 0.00000, 1.9875e-02), vec4(3.8391, 0.2570, 0.00000, 3.0919e-02), vec4(3.7790, 0.2749, 0.00000, 4.7258e-02), vec4(3.7203, 0.2923, 0.00000, 7.1042e-02), vec4(3.6629, 0.3094, 0.00000, 1.0514e-01), vec4(3.6069, 0.3260, 0.00000, 1.5332e-01), vec4(3.5523, 0.3422, 0.00000, 2.2048e-01), vec4(3.4991, 0.3581, 0.00000, 3.1292e-01), vec4(3.4472, 0.3735, 0.00000, 4.3860e-01), vec4(3.3967, 0.3885, 0.00000, 6.0756e-01), vec4(3.3475, 0.4031, 0.00000, 8.3222e-01), vec4(3.2995, 0.4174, 0.00000, 1.1279e+00), vec4(3.2528, 0.4313, 0.00000, 1.5133e+00), vec4(3.2074, 0.4448, 0.00000, 2.0110e+00), vec4(3.1631, 0.4580, 0.00000, 2.6480e+00), vec4(3.1200, 0.4708, 0.00000, 3.4567e+00));   // 450-1250 C, 25 C steps: linear sRGB colour of luminance 1, luminance relative to 1140 C (tools/gen_lava_glow.py)
// The light a surface at c degrees C gives, in LINEAR light where a sunlit white surface is 1. Physics (tools/gen_lava_glow.py): Planck's
// law against the CIE 1931 observer. Fresh lava at 1140 C is about as bright as sunlit white paper (a 1413 K black body: about
// 3e4 cd/m2), so in daylight only the hottest lava shows and a 800 C crust (1e-3 of it) is lost, as in photographs. In the dark the eye
// adapts: 'dark' (0 day .. 1 night or underground) blends to a log-like response 1 - exp(-2 sqrt(Y)) that shows dull red crust (CHOSEN).
vec3 tl_glow(float c, float dark) {
    if (c <= 450.0) return vec3(0.0);
    float f = clamp((c - 450.0) / 25.0, 0.0, 31.999); int i = int(f);
    vec4 g = mix(TL_GLOW[i], TL_GLOW[i + 1], f - float(i));
    float Y = mix(g.w, 1.0 - exp(-2.0 * sqrt(g.w)), dark);
    vec3 lin = g.rgb * Y;
    return lin / max(1.0, max(lin.r, max(lin.g, lin.b)));                 // keep the hue where red would clip
}
vec2 tl_hash2(vec2 c) { ivec2 i = ivec2(c); return vec2(tw_hash(i), tw_hash(i + ivec2(57, 113))); }
// Voronoi plates: x = distance to the nearest plate centre, y = how far inside the plate (F2 - F1, cell units), z = the plate's id
vec3 tl_plates(vec2 p) {
    vec2 c = floor(p), f = p - c; float d1 = 8.0, d2 = 8.0, id = 0.0;
    for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
        vec2 g = vec2(float(i), float(j)); vec2 r = g + tl_hash2(c + g) * 0.85 + 0.075 - f; float d = dot(r, r);
        if (d < d1) { d2 = d1; d1 = d; id = tw_hash(ivec2(c + g) + ivec2(911, 3)); } else if (d < d2) d2 = d;
    }
    return vec3(sqrt(d1), sqrt(d2) - sqrt(d1), id);
}
// One layer of the surface at advected point q: temperature (C) at this pixel and the crust's roughness / rope slope.
// S plate size (m), P plate life (s), edgeAge (s added near the edge), fp the pixel's footprint (m).
vec4 tl_layer(vec2 q, float t, float S, float P, float edgeAge, float fp, vec2 ropeDir) {
    // plates are not tidy cells: the coordinates are bent (domain warp, 2 octaves) so edges wander and corners are not 120 degrees,
    // and a second, smaller plate size breaks some plates up (lava lakes show plates from under a metre to several metres)
    vec2 wq = q + S * 0.42 * (vec2(tw_noise(q / S * 0.9 + 3.1), tw_noise(q / S * 0.9 - 7.7)) - 0.5) + S * 0.18 * (vec2(tw_noise(q / S * 2.7 + 1.3), tw_noise(q / S * 2.7 + 9.4)) - 0.5);
    vec3 v = tl_plates(wq / S);
    vec3 vs = tl_plates(wq / (S * 0.45) + 5.3);
    bool split = fract(v.z * 13.7) < 0.35;                                // a third of the plates have broken again
    float id = split ? vs.z : v.z;
    float dist = split ? min(v.y * S * 0.5, vs.y * S * 0.225) : v.y * S * 0.5;   // metres from the plate's edge
    float age = fract(id + t / P) * P + edgeAge;
    float Tp = tl_surfaceC(age) + 45.0 * (tw_noise(q * 1.7 + id * 31.0) - 0.5) * (1.0 - smoothstep(60.0, 300.0, age));   // thin spots stay hotter
    // a crack's width changes along it (pinches and gapes; CHOSEN 0.02-0.11 m) and so does its heat
    float along = tw_noise(wq * 2.3 + id * 5.0);
    float wC = (0.02 + 0.09 * along * along) * (1.0 - 0.5 * smoothstep(0.0, 400.0, edgeAge));
    float aa = max(fp, 1e-3);
    float crack = 1.0 - smoothstep(wC * 0.5 - aa * 0.5, wC * 0.5 + aa * 0.5, dist);
    crack = mix(crack, min(1.0, wC / (S * 0.5)), smoothstep(wC, wC * 4.0, fp));   // too small to see: its share of the area
    float rim = exp(-max(dist - wC * 0.5, 0.0) / 0.05);                  // the thin plate edge over the hot gap (heat through it)
    float T = mix(Tp, max(Tp, 880.0), rim * 0.5);
    // young crust breaks again into small polygons (secondary fractures) before it thickens (CHOSEN: up to 2 min old)
    vec3 v2 = tl_plates(wq / 0.32 + id * 17.0);
    float w2 = 0.010 * (1.0 - smoothstep(25.0, 120.0, age));
    float c2 = w2 > 0.0 ? (1.0 - smoothstep(w2 * 0.5 - aa * 0.5, w2 * 0.5 + aa * 0.5, v2.y * 0.16)) * (1.0 - smoothstep(w2, w2 * 4.0, fp)) : 0.0;
    T = mix(T, max(T, 960.0), c2);
    T = mix(T, TL_CORE_C - 120.0 * (1.0 - along) * fract(id * 3.7), crack);   // a narrow, deep crack shows a little cooler lava
    // pahoehoe ropes across the drift on crust between 20 s and 10 min old (wavelength 0.25 m): a slope for the shading, gone before
    // a pixel covers half a rope (no moire)
    float ropes = smoothstep(20.0, 60.0, age) * (1.0 - smoothstep(300.0, 600.0, age)) * (1.0 - crack) * (1.0 - smoothstep(0.01, 0.03, fp));
    float rp = dot(q, ropeDir) * 25.0 + 4.0 * tw_noise(q * 0.7 + id * 9.0);
    float slope = cos(rp) * 0.18 * ropes;
    float gloss = 1.0 - smoothstep(60.0, 900.0, age);                     // fresh glass shines, old crust is dull
    return vec4(T, slope, gloss, crack);
}
vec4 tides_lava(vec4 base, vec3 rel, float flowing, vec2 uv, vec2 light) {
    vec3 wp = rel + u_TidesCam.xyz; float t = u_TidesRipple.x;
    vec3 dpx = dFdx(rel), dpy = dFdy(rel);
    vec3 ng = cross(dpx, dpy); ng = dot(ng, ng) > 1e-12 ? normalize(ng) : vec3(0.0, 1.0, 0.0);
    vec3 V = normalize(-rel); if (dot(ng, V) < 0.0) ng = -ng;
    float fp = max(max(length(dpx), length(dpy)), 1e-4);
    float baseLum = dot(base.rgb, vec3(0.299, 0.587, 0.114));
    float detail = mix(1.0, clamp(baseLum / 0.62, 0.6, 1.4), 0.25);      // vanilla's lava texture as fine detail
    // the edge: plates are older there
    float edge = 8.0;
    if (u_TidesLavaO.w > 0.5) {
        vec2 f = (wp.xz - u_TidesLavaO.xy) / u_TidesLavaO.z;
        if (all(greaterThan(f, vec2(0.0))) && all(lessThan(f, vec2(1.0)))) { vec4 m = texture(u_TidesLavaMap, f); if (m.a > 0.25) edge = m.r * 255.0 / 31.0; }
    }
    float T; float slope = 0.0, gloss = 0.0, crack = 0.0;
    vec2 dir = vec2(1.0, 0.0);
    if (abs(ng.y) < 0.5) {
        // a falling sheet: torn crust shreds stretched down the fall, the rest incandescent
        vec2 hz = normalize(vec2(-ng.z, ng.x)); float along = dot(wp.xz, hz);
        vec3 v = tl_plates(vec2(along * 1.4, (wp.y + t * 1.6) * 0.35));
        float shred = smoothstep(0.55, 0.75, fract(v.z * 5.3)) * smoothstep(0.08, 0.2, v.y);
        T = mix(TL_CORE_C - 60.0 * tw_noise(vec2(along * 3.0, (wp.y + t * 1.6) * 1.2)), tl_surfaceC(8.0 + 40.0 * fract(v.z * 3.1)), shred);
    } else {
        vec2 vel;
        if (flowing > 0.5) {
            // the current: vanilla turns the flow sprite so its v runs downstream (as for water)
            vec2 dvx = vec2(dFdx(uv.y)), dvy = vec2(dFdy(uv.y)); mat2 M = mat2(dpx.x, dpy.x, dpx.z, dpy.z);
            vec2 fd = vec2(1.0, 0.0); if (abs(determinant(M)) > 1e-12) { vec2 g = inverse(M) * vec2(dvx.x, dvy.x); if (dot(g, g) > 1e-12) fd = normalize(g); }
            vel = fd * 0.5;                                               // m/s (Minecraft lava creeps; CHOSEN)
        } else {
            vec2 c = wp.xz * 0.05; float e = 0.5;                         // a lake: slow convection drift, the curl of a large noise
            float n0 = tw_noise(c + vec2(0.0, t * 0.002)), nx = tw_noise(c + vec2(e, t * 0.002)), nz = tw_noise(c + vec2(0.0, e + t * 0.002));
            vel = vec2(nz - n0, -(nx - n0)) / e * 0.6;
        }
        dir = length(vel) > 1e-4 ? normalize(vel) : vec2(1.0, 0.0);
        float S = flowing > 0.5 ? 1.0 : 2.2, P = flowing > 0.5 ? 30.0 : 150.0;
        float edgeAge = (1.0 - clamp(edge / 8.0, 0.0, 1.0)) * (flowing > 0.5 ? 120.0 : 900.0);
        // carried by the flow: two layers half a cycle apart, each reset every TC s, blended (no stretching without end)
        float TC = 24.0, ph0 = fract(t / TC), ph1 = fract(t / TC + 0.5), w0 = 1.0 - abs(2.0 * ph0 - 1.0);
        vec4 L0 = tl_layer(wp.xz - vel * ph0 * TC, t, S, P, edgeAge, fp, dir);
        vec4 L1 = tl_layer(wp.xz - vel * ph1 * TC + vec2(37.1, 11.7), t, S, P, edgeAge, fp, dir);
        vec4 L = mix(L1, L0, w0);
        T = L.x; slope = L.y; gloss = L.z; crack = L.w;
        // gas bubbles burst through (CHOSEN: one per 3 m cell every ~9 s, a ring out to 0.7 m in 1.2 s, fresh lava inside)
        vec2 bc = floor(wp.xz / 3.0); float bph = fract(t / 9.0 + tw_hash(ivec2(bc) + ivec2(5, 9))); float tau = bph * 9.0;
        if (tau < 1.6) {
            vec2 bp = (bc + 0.2 + 0.6 * tl_hash2(bc + floor(t / 9.0 + tw_hash(ivec2(bc) + ivec2(5, 9))))) * 3.0;
            float rr = length(wp.xz - bp), R = 0.7 * sqrt(min(tau, 1.2) / 1.2);
            float inside = 1.0 - smoothstep(R - 0.04, R + 0.02, rr), ring = exp(-pow((rr - R) / 0.05, 2.0));
            float skin = tw_noise((wp.xz - bp) * 9.0 + floor(t / 9.0));        // the burst skin is torn, not a clean disc
            T = mix(T, mix(TL_CORE_C, 640.0, smoothstep(0.2, 1.4, tau)) - 160.0 * skin * smoothstep(0.2, 0.8, tau), inside * (1.0 - smoothstep(1.2, 1.6, tau)));
            float spatter = exp(-pow((rr - R - 0.12) / 0.06, 2.0)) * step(0.55, tw_noise((wp.xz - bp) * 14.0)) * (1.0 - smoothstep(1.0, 1.6, tau));
            T = mix(T, min(T, 560.0), spatter);                               // drops thrown out land and chill at once
            slope += ring * 0.6 * (1.0 - smoothstep(1.0, 1.6, tau));
        }
    }
    // light on the crust: the sky (sun or moon) and its dome; nothing from the glow itself (it is the source). Worked in linear light
    // and encoded to sRGB at the end (the glow table is sRGB): basalt's albedo 0.07 is a dark grey, not black, in sunlight.
    vec2 mic = vec2(tw_noise(wp.xz * 9.0) - 0.5, tw_noise(wp.xz * 9.0 + 4.7) - 0.5) * 0.1 * (1.0 - smoothstep(0.002, 0.008, fp));   // the crust's glassy grain (only right under the eye)
    vec3 N = normalize(vec3(-slope * dir.x + mic.x, 1.0, -slope * dir.y + mic.y)); if (abs(ng.y) < 0.5) N = ng;
    vec3 L = normalize(u_TidesSun.xyz); float skyL = light.y;
    vec3 sunC = mix(vec3(1.0, 0.62, 0.38), vec3(1.0, 0.97, 0.9), clamp(L.y * 3.0, 0.0, 1.0)) * u_TidesSun.w * skyL * skyL;
    vec3 skyLin = pow(u_TidesSky.rgb, vec3(2.2));
    vec3 crustAlb = vec3(0.075, 0.068, 0.064);                           // basalt glass and rock (albedo about 0.07)
    vec3 lit = crustAlb * (sunC * max(dot(N, L), 0.0) + skyLin * skyL * (0.2 + 0.6 * u_TidesSun.w) + vec3(0.03, 0.015, 0.01));
    vec3 H = normalize(L + V); float nv = max(dot(N, V), 0.02);
    float F = tw_fresnel(nv, 1.0, 1.5) * mix(0.35, 1.0, gloss);           // basalt glass, n about 1.5; a rough old crust mirrors less
    vec3 spec = sunC * pow(max(dot(N, H), 0.0), mix(12.0, 160.0, gloss)) * F * (0.3 + 2.0 * gloss) + skyLin * skyL * F * (0.05 + 0.25 * gloss);
    float dark = 1.0 - u_TidesSun.w * skyL;
    vec3 glowLin = tl_glow(T, dark) * detail;
    vec3 lin = (lit + spec) * (1.0 - crack) + glowLin;
    vec3 rgb = pow(min(lin, vec3(1.0)), vec3(1.0 / 2.2));
    return vec4(min(rgb, vec3(1.0)), 1.0);
}
// The warm light lava throws on the ground beside it (the lava map's nearness, within 4 blocks up or down of the surface), with a
// slow flicker. Vanilla lights it white at light level 15; this gives that light lava's colour (CHOSEN strength).
vec3 tides_lavaGlow(vec3 rgb, vec3 rel) {
    if (u_TidesLavaO.w < 0.5) return rgb;
    vec3 wp = rel + u_TidesCam.xyz; vec2 f = (wp.xz - u_TidesLavaO.xy) / u_TidesLavaO.z;
    if (any(lessThan(f, vec2(0.0))) || any(greaterThan(f, vec2(1.0)))) return rgb;
    vec4 m = texture(u_TidesLavaMap, f); if (m.b <= 0.0) return rgb;
    float ly = texelFetch(u_TidesLavaMap, ivec2(clamp(floor(wp.xz - u_TidesLavaO.xy), vec2(0.0), vec2(u_TidesLavaO.z - 1.0))), 0).g * 255.0;
    float dy = mod(wp.y - (ly + 0.9) + 128.0, 256.0) - 128.0;
    float w = m.b * (1.0 - smoothstep(1.0, 4.0, abs(dy))) * (0.88 + 0.12 * sin(u_TidesRipple.x * 2.3 + wp.x * 0.7 + wp.z * 0.4));
    w *= 1.0 - 0.75 * u_TidesSun.w;                                     // daylight drowns it out (CHOSEN): strong at night and in the Nether
    return rgb + (rgb * vec3(0.95, 0.40, 0.12) * 0.55 + vec3(0.05, 0.015, 0.0)) * w;
}
// ---- end Hopeless Tides look
