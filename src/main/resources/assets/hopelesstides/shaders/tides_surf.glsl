// 0.14.0 THE SURF on the GPU: core/Surf.java (ported from Hopeless Hydro 0.11), number for number: TidesSurf.bore / Surf.Field.corner
// (the bore train on the water) and Surf.Field.swashAt (the swash on the beach). The field is solved on the CPU (TidesSurfGpu uploads it).
// u_TidesSurf: 128 x 512 RGBA32F, four 128-row blocks: 0 (phase of the peak, Abreu r, Abreu phi, unbroken Hrms; Hrms < 0: not sea),
// 1 (rho, depth, phase gradient x, z), 2 (swash: bores' phase at the still-water line, metres up the beach, slope, R2; R2 <= 0: none),
// 3 (up the beach x, z, the ground's top y, 0), 4 (0.16.1: the K components' phase), 5 (their height; -1 where the wave does not reach), 6 (0.16.2: the wind sea's local fetch factor, 0, 0, 1 on sea), 7-9 (0.16.3: the wind-sea solve: phases, heights, Abreu r, phi, phase gradient).
#ifndef TIDES_SURF_GLSL
#define TIDES_SURF_GLSL
uniform sampler2D u_TidesSurf;
uniform vec4 u_TidesSurfO;   // the field's corner from the shader origin (x, z), its wave frame origin (x, z)
uniform vec4 u_TidesSurfT;   // x: clock(w_peak, t) (wrapped), y: the peak's phase0, z: the peak's period (s), w: 1 = on
const float TS_GAMMA_B = 0.78, TS_SETTLED = 0.6, TS_Q2 = 1.97788, TS_CREST = 45.0, TS_GROUP = 6.5, TS_PI2 = 6.2831853;
float ts_hash(int x, int y) { uint h = uint(x) * 1597334677u ^ uint(y) * 3812015801u; h ^= h >> 16; h *= 2246822519u; h ^= h >> 13; return float(h & 0x00ffffffu) / 16777215.0; }
float ts_noise(vec2 p) {
    ivec2 i = ivec2(floor(p)); vec2 f = p - vec2(i), u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = ts_hash(i.x, i.y) + (ts_hash(i.x + 1, i.y) - ts_hash(i.x, i.y)) * u.x, b = ts_hash(i.x, i.y + 1) + (ts_hash(i.x + 1, i.y + 1) - ts_hash(i.x, i.y + 1)) * u.x;
    return a + (b - a) * u.y;
}
float ts_waveQ(int n, vec2 p) { float u = max(0.002, ts_hash(n, 7919)); return sqrt(-log(u)) * (0.72 + 0.56 * ts_noise(vec2(float(n) / TS_GROUP, 3.5))) * (0.78 + 0.44 * ts_noise(p / TS_CREST + vec2(float(n) * 0.37, float(n) * 0.61))); }
float ts_smooth(float u) { u = clamp(u, 0.0, 1.0); return u * u * (3.0 - 2.0 * u); }
float ts_boreHeight(float qh, float h) { float hb = TS_GAMMA_B * h; if (qh <= hb) return qh; float o = ts_smooth((qh / hb - 1.0) / 0.5); return hb + (TS_SETTLED * h - hb) * o; }
vec2 ts_shape(float wt, float r, float phi) { float f = sqrt(1.0 - r * r), off = r * sin(phi) / (1.0 + f), s = sin(wt), c = cos(wt), den = 1.0 - r * cos(wt + phi), num = s + off;
    return vec2(f * num / den, f * (c * den - num * r * sin(wt + phi)) / (den * den)); }
// The bore train at block corner I (relative to the field's corner): (eta, weight, slope x, slope z); foam out.
vec4 tides_surfCorner(ivec2 I, out float foam) {
    foam = 0.0; float k = 0.0; vec4 a = vec4(0.0), b = vec4(0.0);
    for (int dj = -1; dj <= 0; dj++) for (int di = -1; di <= 0; di++) { ivec2 c = I + ivec2(di, dj); if (any(lessThan(c, ivec2(0))) || any(greaterThanEqual(c, ivec2(128)))) continue;
        vec4 A = texelFetch(u_TidesSurf, c, 0); if (A.w < 0.0) continue; a += A; b += texelFetch(u_TidesSurf, c + ivec2(0, 128), 0); k += 1.0; }
    if (k < 0.5) return vec4(0.0);
    a /= k; b /= k;
    float r = a.y, ph = a.z, hr = a.w, rh = b.x, depth = b.y, w = ts_smooth((rh - 0.35) / 0.45);
    if (w <= 0.0) return vec4(0.0);
    float thD = a.x - u_TidesSurfT.x + u_TidesSurfT.y, wt = -thD, cyc = -(thD - ph) / TS_PI2, nf = floor(cyc), fr = cyc - nf; int n = int(nf);
    vec2 pm = vec2(I) + u_TidesSurfO.zw;
    float hh = hr / max(1e-6, TS_GAMMA_B * rh), q1 = ts_waveQ(n, pm), q = q1;
    if (fr < 0.25) q = ts_waveQ(n - 1, pm) + (q1 - ts_waveQ(n - 1, pm)) * ts_smooth(fr / 0.25);
    float H = ts_boreHeight(hr * q, hh), amp = 0.5 * H * w; vec2 S = ts_shape(wt, r, ph);
    float broken = ts_smooth((hr * q / max(1e-6, TS_GAMMA_B * hh) - 0.85) / 0.3);
    foam = w * broken * exp(-fr * 5.0);
    return vec4(amp * S.x, w, -amp * S.y * b.z, -amp * S.y * b.w);
}
// 0.16.0: the standing wave in front of a wall at corner I: sqrt(1 + Kr^2 + 2 Kr cos 2kd) (Surf.Field.wallAmp), 1 away from walls
float tides_surfWall(ivec2 I) {
    float k = 0.0, amp = 0.0;
    for (int dj = -1; dj <= 0; dj++) for (int di = -1; di <= 0; di++) { ivec2 c = I + ivec2(di, dj); if (any(lessThan(c, ivec2(0))) || any(greaterThanEqual(c, ivec2(128)))) continue;
        vec4 A = texelFetch(u_TidesSurf, c, 0); if (A.w < 0.0) continue; vec4 W = texelFetch(u_TidesSurf, c + ivec2(0, 256), 0), Wb = texelFetch(u_TidesSurf, c + ivec2(0, 384), 0);
        float a = 1.0; if (Wb.w > 0.5) { vec4 B = texelFetch(u_TidesSurf, c + ivec2(0, 128), 0); float kk = length(B.zw), kr = W.y; a = sqrt(1.0 + kr * kr + 2.0 * kr * cos(2.0 * kk * W.x)); }
        amp += a; k += 1.0; }
    return k > 0.5 ? amp / k : 1.0;
}
// 0.16.1 THE SWELL TRAIN at world corner p (shader-origin frame), Surf.Field.swellCorner / Surf.planeSwell number for number: inside the
// field the K components from rows 4 (phase) and 5 (height; -1: the wave does not reach), shaped (Abreu), less the bore weight, times the
// swell's share; beyond the field and over its last 16 cells the plane train. Out: eta, slope x, slope z.
uniform vec4 u_TidesSwO;   // x 1 = on, y share, zw the shader origin's world position mod FRAME
uniform vec4 u_TidesSwC;   // the K clocks
uniform vec4 u_TidesSwK;   // the K deep wavenumbers
uniform vec4 u_TidesSwD;   // the K headings (toward, rad)
uniform vec4 u_TidesSwA;   // x plane amplitude (UNIT hs0 share), y UNIT, z the still sea surface (world y)
const vec4 TS_PHASE0 = vec4(0.0, 2.1, 4.4, 1.3); const float TS_UNIT = 0.17677670;   /* Surf.UNIT = 1 / (4 sqrt(K / 2)) */ const float TS_FRAME = 46080.0, TS_SWELL_EDGE = 16.0;
float ts_fade(float e) { e = clamp(e, 0.0, 1.0); return e * e * (3.0 - 2.0 * e); }
vec3 tides_planeSwell(vec2 xm) {
    vec3 o = vec3(0.0); float a = u_TidesSwA.x; if (a <= 0.0) return o;
    for (int q = 0; q < 4; q++) { vec2 d = vec2(cos(u_TidesSwD[q]), sin(u_TidesSwD[q])); float th = u_TidesSwK[q] * dot(xm - 0.5, d) - u_TidesSwC[q] + TS_PHASE0[q];
        o.x += a * sin(-th); float ds = a * cos(-th); o.yz -= ds * u_TidesSwK[q] * d; }
    return o;
}
vec3 tides_swellCorner(vec2 p) {
    vec2 xm = mod(p + u_TidesSwO.zw, TS_FRAME); vec3 pl = tides_planeSwell(xm);
    ivec2 I = ivec2(p - u_TidesSurfO.xy); if (u_TidesSurfT.w < 0.5 || any(lessThan(I, ivec2(0))) || any(greaterThan(I, ivec2(128)))) return pl;
    float we = ts_fade(float(min(I.x, 128 - I.x)) / TS_SWELL_EDGE) * ts_fade(float(min(I.y, 128 - I.y)) / TS_SWELL_EDGE); if (we <= 0.0) return pl;
    vec4 ph = vec4(0.0), hh = vec4(0.0); float r = 0.0, pp = 0.0, rh = 0.0, m = 0.0, sea = 0.0; vec2 g = vec2(0.0);
    for (int dj = -1; dj <= 0; dj++) for (int di = -1; di <= 0; di++) { ivec2 c = I + ivec2(di, dj); if (any(lessThan(c, ivec2(0))) || any(greaterThanEqual(c, ivec2(128)))) continue;
        vec4 A = texelFetch(u_TidesSurf, c, 0); if (A.w < 0.0) continue; sea += 1.0;
        vec4 H = texelFetch(u_TidesSurf, c + ivec2(0, 640), 0); if (H.x < 0.0) continue; m += 1.0;
        ph += texelFetch(u_TidesSurf, c + ivec2(0, 512), 0); hh += H; r += A.y; pp += A.z; rh += texelFetch(u_TidesSurf, c + ivec2(0, 128), 0).x; g += texelFetch(u_TidesSurf, c + ivec2(0, 128), 0).zw; }
    if (sea < 0.5) return pl;
    vec3 o = vec3(0.0);
    if (m > 0.5) { ph /= m; hh /= m; r /= m; pp /= m; rh /= m; g /= m; float wb = ts_smooth((rh - 0.35) / 0.45), kd = u_TidesSwK[1];
        for (int q = 0; q < 4; q++) { float a = u_TidesSwA.y * hh[q] * (1.0 - wb) * u_TidesSwO.y; if (a <= 0.0) continue; float th = ph[q] - u_TidesSwC[q] + TS_PHASE0[q], kr = u_TidesSwK[q] / kd;
            vec2 S = ts_shape(-th, r, pp); o.x += a * S.x; o.yz -= a * S.y * g * kr; } }
    return mix(pl, o, we);
}
// 0.16.2 LOCAL FETCH (Surf.fetchCorner): the wind sea's height factor at corner p (shader-origin frame): the 4 cells' mean, 1 at the field's edge
float tides_fetchCorner(vec2 p) {
    if (u_TidesSurfT.w < 0.5) return 1.0; ivec2 I = ivec2(p - u_TidesSurfO.xy); if (any(lessThan(I, ivec2(0))) || any(greaterThan(I, ivec2(128)))) return 1.0;
    float k = 0.0, m = 0.0;
    for (int dj = -1; dj <= 0; dj++) for (int di = -1; di <= 0; di++) { ivec2 c = I + ivec2(di, dj); if (any(lessThan(c, ivec2(0))) || any(greaterThanEqual(c, ivec2(128)))) continue;
        vec4 F = texelFetch(u_TidesSurf, c + ivec2(0, 768), 0); if (F.w < 0.5) continue; k += F.x; m += 1.0; }
    if (m < 0.5) return 1.0;
    float we = ts_fade(float(min(I.x, 128 - I.x)) / TS_SWELL_EDGE) * ts_fade(float(min(I.y, 128 - I.y)) / TS_SWELL_EDGE); return 1.0 + (k / m - 1.0) * we;
}
// 0.16.3 THE WIND SEA IN SHALLOW WATER (Surf.Field.chopCorner): the second solve's K components at corner p, Abreu shape, and the blend weight
// w = (1 - smoothstep(0.3 L0 .. 0.5 L0, depth)) x the edge fade (depth from row 1). Out: eta, slope x, slope z, w (0: none).
uniform vec4 u_TidesChC;   // the second solve's K clocks
uniform vec4 u_TidesChK;   // its K deep wavenumbers
uniform vec4 u_TidesChA;   // x its deep wavelength L0 (m), y 1 = on
vec4 tides_chopCorner(vec2 p) {
    if (u_TidesChA.y < 0.5 || u_TidesSurfT.w < 0.5) return vec4(0.0); ivec2 I = ivec2(p - u_TidesSurfO.xy); if (any(lessThan(I, ivec2(0))) || any(greaterThan(I, ivec2(128)))) return vec4(0.0);
    vec4 ph = vec4(0.0), hh = vec4(0.0); float r = 0.0, pp = 0.0, dep = 0.0, m = 0.0, sea = 0.0; vec2 g = vec2(0.0);
    for (int dj = -1; dj <= 0; dj++) for (int di = -1; di <= 0; di++) { ivec2 c = I + ivec2(di, dj); if (any(lessThan(c, ivec2(0))) || any(greaterThanEqual(c, ivec2(128)))) continue;
        vec4 A = texelFetch(u_TidesSurf, c, 0); if (A.w < 0.0) continue; sea += 1.0; dep += texelFetch(u_TidesSurf, c + ivec2(0, 128), 0).y;
        vec4 H = texelFetch(u_TidesSurf, c + ivec2(0, 1024), 0); if (H.x < 0.0) continue; m += 1.0;
        ph += texelFetch(u_TidesSurf, c + ivec2(0, 896), 0); hh += H; vec4 S = texelFetch(u_TidesSurf, c + ivec2(0, 1152), 0); r += S.x; pp += S.y; g += S.zw; }
    if (sea < 0.5) return vec4(0.0); dep /= sea; float L0 = u_TidesChA.x;
    float we = ts_fade(float(min(I.x, 128 - I.x)) / TS_SWELL_EDGE) * ts_fade(float(min(I.y, 128 - I.y)) / TS_SWELL_EDGE), w = (1.0 - ts_smooth((dep - 0.3 * L0) / (0.2 * L0))) * we;
    if (w <= 0.0) return vec4(0.0); if (m < 0.5) return vec4(0.0, 0.0, 0.0, w);
    ph /= m; hh /= m; r /= m; pp /= m; g /= m; vec3 o = vec3(0.0); float kd = u_TidesChK[1];
    for (int q = 0; q < 4; q++) { float a = TS_UNIT * hh[q]; if (a <= 0.0) continue; float th = ph[q] - u_TidesChC[q] + TS_PHASE0[q], kr = u_TidesChK[q] / kd; vec2 S = ts_shape(-th, r, pp); o.x += a * S.x; o.yz -= a * S.y * g * kr; }
    return vec4(o, w);
}
// 0.16.1 SURGE (Hopeless Seabed's SurgeSway, in Tides): plants under water move with the water around them, the swell's orbital motion.
// Linear (Airy) wave theory at finite depth h, a point zd below the still surface: horizontal excursion a cosh(k (h - zd)) / sinh(k h), in
// phase with -cos of the surface phase (Dean & Dalrymple, Water Wave Mechanics, ch. 4). Inside the field: the components' refracted phases
// and heights bilinear at the vertex (so a plant's faces stay joined), local k from the peak's phase gradient, local depth; beyond it deep
// water (e^(-k zd)) on the plane train. y: the vertex's world height; u_TidesSwA.z: the still sea surface (world y). Out: x, z (m), at most
// 1.5 m (Seabed's cap). Root: no motion at the bed, full from 1 block above it.
float ts_ratio(float k, float h, float zd) { float kh = k * h; if (kh > 8.0) return exp(-k * zd); return cosh(k * (h - zd)) / max(sinh(kh), 1e-4); }
vec2 tides_swellSurge(vec2 p, float y) {
    float zd = u_TidesSwA.z - y; if (zd <= 0.0) return vec2(0.0);
    vec2 o = vec2(0.0), xm = mod(p + u_TidesSwO.zw, TS_FRAME);
    vec2 lp = p - u_TidesSurfO.xy - 0.5; ivec2 c0 = ivec2(floor(lp)); vec2 f = lp - vec2(c0);
    bool inside = u_TidesSurfT.w > 0.5 && all(greaterThanEqual(c0, ivec2(0))) && all(lessThan(c0, ivec2(127)));
    if (inside) {
        vec4 ph = vec4(0.0), hh = vec4(0.0); vec2 g = vec2(0.0); float dep = 0.0, wsum = 0.0;
        for (int q = 0; q < 4; q++) { ivec2 c = c0 + ivec2(q & 1, q >> 1); float w = ((q & 1) == 1 ? f.x : 1.0 - f.x) * ((q >> 1) == 1 ? f.y : 1.0 - f.y);
            vec4 H = texelFetch(u_TidesSurf, c + ivec2(0, 640), 0); if (H.x < 0.0) continue; vec4 B = texelFetch(u_TidesSurf, c + ivec2(0, 128), 0);
            ph += w * texelFetch(u_TidesSurf, c + ivec2(0, 512), 0); hh += w * H; g += w * B.zw; dep += w * B.y; wsum += w; }
        if (wsum < 0.25) return vec2(0.0);
        ph /= wsum; hh /= wsum; g /= wsum; dep /= wsum; float kD = length(g); if (kD <= 0.0) return vec2(0.0); vec2 dir = g / kD;
        float root = clamp(zd > dep ? 0.0 : (dep - zd), 0.0, 1.0);
        for (int q = 0; q < 4; q++) { float k = kD * u_TidesSwK[q] / u_TidesSwK[1], a = u_TidesSwA.y * hh[q] * u_TidesSwO.y; float th = ph[q] - u_TidesSwC[q] + TS_PHASE0[q];
            o -= dir * a * ts_ratio(k, max(dep, zd), zd) * cos(-th); }
        o *= root;
    } else {
        for (int q = 0; q < 4; q++) { vec2 d = vec2(cos(u_TidesSwD[q]), sin(u_TidesSwD[q])); float th = u_TidesSwK[q] * dot(xm - 0.5, d) - u_TidesSwC[q] + TS_PHASE0[q];
            o -= d * u_TidesSwA.x * exp(-u_TidesSwK[q] * zd) * cos(-th); }
    }
    float l = length(o); return l > 1.5 ? o * (1.5 / l) : o;
}
// 0.17.0 THE SHORE MAP (core/ShoreMap.java, number for number with corner() / factors()): where the shore is for every water column in
// view, so every wave layer knows it. u_TidesShore: N x 3N RGBA32F, three N-row blocks: 0 (Phi: phase from the shore, rad, -1 not
// reached; depth m; grad Phi x, z), 1 (the swell's lee share, the wind sea's fetch factor, Kr, 1 = reached), 2 (Abreu r, phi, shoaled rms
// height, 0). u_TidesShO: the map's corner from the shader origin (x, z), the shader origin's world position mod TS_FRAME (x, z).
// u_TidesShT: x the clock (w t wrapped), y L0 (m), z 1 = on, w N.
uniform sampler2D u_TidesShore;
uniform vec4 u_TidesShO;
uniform vec4 u_TidesShT;
const float TS_SH_EDGE = 16.0;
// the cells round block corner I: a (phi, h, gx, gz), c (r, phi_a, hr, 0) and b.z (kr) over the reached cells (returned: their count), b.xy
// (lee, fetch) over the water cells (b.w: their count)
float ts_shoreCells(ivec2 I, out vec4 a, out vec4 b, out vec4 c) {
    a = vec4(0.0); b = vec4(0.0); c = vec4(0.0); float k = 0.0, w = 0.0; int n = int(u_TidesShT.w);
    for (int dj = -1; dj <= 0; dj++) for (int di = -1; di <= 0; di++) { ivec2 q = I + ivec2(di, dj); if (any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, ivec2(n)))) continue;
        vec4 A = texelFetch(u_TidesShore, q, 0); if (A.y <= 0.0) continue; vec4 B = texelFetch(u_TidesShore, q + ivec2(0, n), 0);
        w += 1.0; b.xy += B.xy; if (B.w < 0.5) continue;
        a += A; b.z += B.z; c += texelFetch(u_TidesShore, q + ivec2(0, 2 * n), 0); k += 1.0; }
    if (w > 0.5) b.xy /= w; if (k > 0.5) { a /= k; b.z /= k; c /= k; } b.w = w;
    return k;
}
// the surf field's weight at corner p (1 inside, fading over its edge; 0 without a field): there the field's own solve is drawn
float tides_fieldW(vec2 p) {
    if (u_TidesSurfT.w < 0.5) return 0.0; ivec2 I = ivec2(p - u_TidesSurfO.xy); if (any(lessThan(I, ivec2(0))) || any(greaterThan(I, ivec2(128)))) return 0.0;
    return ts_fade(float(min(I.x, 128 - I.x)) / TS_SWELL_EDGE) * ts_fade(float(min(I.y, 128 - I.y)) / TS_SWELL_EDGE);
}
// the lee share (swell) and the fetch factor (wind sea) at corner p; (1, 1) where the map knows nothing; z = 1 when the map knows
vec3 tides_shoreFactors(vec2 p) {
    if (u_TidesShT.z < 0.5) return vec3(1.0, 1.0, 0.0); ivec2 I = ivec2(p - u_TidesShO.xy); int n = int(u_TidesShT.w);
    if (any(lessThan(I, ivec2(0))) || any(greaterThan(I, ivec2(n)))) return vec3(1.0, 1.0, 0.0);
    vec4 a, b, c; ts_shoreCells(I, a, b, c); if (b.w < 0.5) return vec3(1.0, 1.0, 0.0);
    return vec3(b.x, b.y, 1.0);
}
// the shore train at corner p: (eta, slope x, slope z, weight); foam out
vec4 tides_shoreCorner(vec2 p, float fieldW, out float foam) {
    foam = 0.0; if (u_TidesShT.z < 0.5) return vec4(0.0); ivec2 I = ivec2(p - u_TidesShO.xy); int n = int(u_TidesShT.w);
    if (any(lessThan(I, ivec2(0))) || any(greaterThan(I, ivec2(n)))) return vec4(0.0);
    vec4 a, b, c; if (ts_shoreCells(I, a, b, c) < 0.5) return vec4(0.0);
    float L0 = u_TidesShT.y, we = ts_fade(float(min(I.x, n - I.x)) / TS_SH_EDGE) * ts_fade(float(min(I.y, n - I.y)) / TS_SH_EDGE);
    float w = we * (1.0 - ts_smooth((a.y - 0.3 * L0) / (0.2 * L0))) * (1.0 - fieldW); if (w <= 0.0) return vec4(0.0);
    float wt = u_TidesShT.x + a.x, cyc = wt / TS_PI2, nf = floor(cyc), fr = cyc - nf; int nn = int(nf);
    vec2 xm = mod(p + u_TidesShO.zw, TS_FRAME);
    float q1 = ts_waveQ(nn, xm), q = q1; if (fr < 0.25) { float q0 = ts_waveQ(nn - 1, xm); q = q0 + (q1 - q0) * ts_smooth(fr / 0.25); }
    float hd = max(a.y, 0.05), H = ts_boreHeight(c.z * q, hd), amp = 0.5 * H; vec2 S = ts_shape(wt, c.x, c.y);
    float broken = ts_smooth((c.z * q / (TS_GAMMA_B * hd) - 0.85) / 0.3); foam = w * broken * exp(-fr * 5.0);
    float eta = amp * S.x; vec2 sl = amp * S.y * a.zw;
    float kre = b.z * (1.0 - broken), wr = u_TidesShT.x - a.x; eta += kre * amp * sin(wr); sl -= kre * amp * cos(wr) * a.zw;
    return vec4(eta, sl, w);
}
#ifdef TIDES_FRAG
// The swash over the dry beach (Hydro HW_SwashPixel, Shen & Meyer 1963): wet sand, the sheet that runs up and back, lace foam at its
// front, the line of stranded bubbles at the top of each run-up, and sand that shines where the backwash just left it.
const float TS_GLOSS = 4.0;   // s: drained sand stays mirror-wet this long (Hydro, CHOSEN)
vec3 tides_swash(vec3 rgb, vec3 rel, vec2 light) {
    if (u_TidesSurfT.w < 0.5) return rgb;
    vec3 wp = rel + u_TidesCam.xyz; vec2 lp = wp.xz - u_TidesSurfO.xy; ivec2 c = ivec2(floor(lp));
    if (any(lessThan(c, ivec2(0))) || any(greaterThanEqual(c, ivec2(128)))) return rgb;
    vec4 A = texelFetch(u_TidesSurf, c + ivec2(0, 256), 0); if (A.w <= 0.0) return rgb;
    vec4 B = texelFetch(u_TidesSurf, c + ivec2(0, 384), 0);
    if (abs(wp.y - B.z) > 0.06) return rgb;                                    // the ground's top face only
    vec2 up = B.xy; float x = max(0.0, A.y + dot(lp - vec2(c) - 0.5, up)), beta = clamp(A.z, 0.005, 0.5), r2 = max(A.w, 1e-3), T = max(u_TidesSurfT.z, 0.5);
    vec2 pm = lp + u_TidesSurfO.zw;
    float wt = -(A.x - u_TidesSurfT.x + u_TidesSurfT.y), cyc = wt / TS_PI2, nf = floor(cyc), fr = cyc - nf; int n = int(nf);
    float a = 9.81 * beta;
    #ifdef TW_G
    a = TW_G * beta;
    #endif
    float sheet = 0.0, edge = 0.0, line = 0.0, gloss = 0.0, xFront = 0.0, best = 0.0;
    for (int m = 0; m < 4; m++) {
        float R = r2 * ts_waveQ(n - m, pm) / TS_Q2, u0 = sqrt(2.0 * a / beta * R), ts = 2.0 * u0 / a, age = fr * T + float(m) * T, xmax = R / beta;
        if (age < ts) { float xf = u0 * age - 0.5 * a * age * age, in_ = 1.0 - smoothstep(xf - 0.04, xf + 0.04, x);
            sheet = max(sheet, in_ * (1.0 - 0.6 * clamp(age / ts, 0.0, 1.0)));
            float up_ = 1.0 - clamp(age / (0.5 * ts), 0.0, 1.0), e = up_ * in_ * (1.0 - smoothstep(0.0, 0.25 + 0.9 * up_, xf - x));
            if (e > best) { best = e; xFront = xf; } edge = max(edge, e); }
        float since = age - 0.5 * ts; if (since > 0.0) line = max(line, exp(-since / 3.0) * (1.0 - smoothstep(0.0, 0.12, abs(x - xmax))));
        if (x < xmax) { float tb = (u0 + sqrt(max(0.0, u0 * u0 - 2.0 * a * x))) / a, dry = age - tb; if (dry > 0.0) gloss = max(gloss, exp(-dry / TS_GLOSS)); }
    }
    float pr = pow(0.02, (x * beta / r2) * (x * beta / r2)), dark = 1.0 - pow(1.0 - pr, 25.0);   // wetted by one of the last ~25 bores (Rayleigh run-ups)
    float lum = max(max(rgb.r, rgb.g), rgb.b);
    vec4 tex = texture(u_TidesFoam, (pm - up * xFront) * 0.35), texB = texture(u_TidesFoam, vec2(dot(pm, vec2(-up.y, up.x)), dot(pm, up)) * 0.35);
    float lace = max(edge > 0.005 ? smoothstep(1.0 - 0.85 * edge, 1.06 - 0.85 * edge, tex.a) : 0.0, line > 0.005 ? smoothstep(1.0 - 0.6 * line, 1.06 - 0.6 * line, texB.a) : 0.0);
    lace = max(lace, sheet * 0.7 * smoothstep(0.55, 0.8, tex.a));
    float wetA = 0.42 * max(dark, max(gloss, sheet));
    vec3 o = mix(rgb, rgb * 0.45, wetA);                                        // wet sand darkens
    vec3 sky = vec3(0.55, 0.68, 0.85) * (0.35 + 0.65 * light.y);
    o = mix(o, mix(o, sky, 0.35), max(sheet, gloss * 0.8) * 0.6);               // the sheet and the shine reflect the sky
    o = mix(o, mix(o, vec3(0.10, 0.22, 0.24) * lum * 2.0, 0.3), sheet);         // the film itself
    o = mix(o, vec3(0.86, 0.88, 0.88) * (0.25 + 0.75 * max(light.x, light.y)), clamp(lace, 0.0, 1.0) * 0.9);
    return o;
}
#endif
#endif
