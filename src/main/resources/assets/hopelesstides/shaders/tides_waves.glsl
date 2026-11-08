// Hopeless Tides wave model, GENERATED from WaveModel.java by tools/GenGlsl.java - do not edit by hand.
// tides_surface(...) returns the height above still water and its slope, for one block corner.
#ifndef TIDES_WAVES_GLSL
#define TIDES_WAVES_GLSL
const int TIDES_N = 12;
const float TIDES_LEN[12] = float[](44.0000000, 13.0000000, 33.0000000, 9.50000000, 38.0000000, 16.0000000, 29.0000000, 7.00000000, 48.0000000, 11.0000000, 36.0000000, 19.0000000);
const float TIDES_K[12] = float[](0.142799666, 0.483321947, 0.190399555, 0.661387927, 0.165346982, 0.392699082, 0.216661562, 0.897597901, 0.130899694, 0.571198664, 0.174532925, 0.330693964);
const float TIDES_OM[12] = float[](1.18333323, 2.17817091, 1.36659280, 2.54469005, 1.27234502, 1.96349541, 1.45560460, 2.96356907, 1.13097336, 2.36666647, 1.30899694, 1.80117979);
const float TIDES_DIR[12] = float[](0.310000000, 0.480000000, 1.35719755, 1.52719755, 2.40439510, 2.57439510, 3.45159265, 3.62159265, 4.49879020, 4.66879020, 5.54598776, 5.71598776);
const float TIDES_DX[12] = float[](0.952333570, 0.886994923, 0.211978256, 0.0435849644, -0.740355314, -0.843409958, -0.952333570, -0.886994923, -0.211978256, -0.0435849644, 0.740355314, 0.843409958);
const float TIDES_DZ[12] = float[](0.305058636, 0.461779176, 0.977274383, 0.999049724, 0.672215746, 0.537270548, -0.305058636, -0.461779176, -0.977274383, -0.999049724, -0.672215746, -0.537270548);
const float TIDES_PHASE[12] = float[](0.00000000, 2.10000000, 4.40000000, 1.30000000, 3.70000000, 5.60000000, 0.800000000, 2.90000000, 5.10000000, 1.90000000, 4.00000000, 0.400000000);
const float TIDES_BREAK = 0.550000000;
float tides_hash01(uint x) { x ^= x >> 16u; x *= 0x7feb352du; x ^= x >> 15u; x *= 0x846ca68bu; x ^= x >> 16u; return float(x & 0xFFFFFFu) / 16777216.0; }
float tides_seaStateWeather(float rain, float thunder) { return clamp(0.10 + 0.40 * rain + 0.50 * thunder, 0.0, 1.0); }
// day: whole days; frac: 0..1 into the day (the shader cannot hold day * 24000 + ticks precisely in a float)
float tides_seaStateCave(int day, float frac) {
    float t = (float(day) + frac) / 1.7; float fi = floor(t); int i = int(fi); float f = t - fi, s = f * f * (3.0 - 2.0 * f);
    float a = tides_hash01(uint(i * 7919 + 13)), b = tides_hash01(uint((i + 1) * 7919 + 13)), v = a + (b - a) * s;
    return 0.06 + 0.50 * v * v;
}
float tides_wind(int day, float frac) {
    float s = frac * frac * (3.0 - 2.0 * frac);
    float a = tides_hash01(uint(day * 104729 + 7)) * 6.28318531, b = tides_hash01(uint((day + 1) * 104729 + 7)) * 6.28318531;
    float d = b - a; d -= floor((d + 3.14159265) / 6.28318531) * 6.28318531;
    return a + d * s;
}
float tides_heightOpen(float s) { return 0.06 + 1.55 * s * s; }
float tides_peakLength(float s) { return 9.0 + 26.0 * s; }
// height above still water (x) and slope d/dx, d/dz (y, z) at a corner, for exposure and depth 0..15
vec3 tides_surface(vec2 p, float tSec, float s, float w, float exposure, float depth) {
    float e = exposure / 15.0; if (e <= 0.0 || depth <= 0.0) return vec3(0.0);
    float hs = tides_heightOpen(s) * e * e, lp = tides_peakLength(s) * (0.45 + 0.55 * e), D = max(depth, 0.5);
    float wt[TIDES_N]; float amp[TIDES_N]; float wsum = 0.0, total = 0.0;
    for (int i = 0; i < TIDES_N; i++) { float r = log(TIDES_LEN[i] / lp) / 0.45, c = max(0.0, cos(TIDES_DIR[i] - w)); wt[i] = exp(-0.5 * r * r) * (c * c + 0.04); wsum += wt[i]; }
    for (int i = 0; i < TIDES_N; i++) { float ks = min(pow(tanh(TIDES_K[i] * D), -0.25), 1.8); amp[i] = 0.25 * hs * sqrt(2.0 * wt[i] / wsum) * ks; total += amp[i] * amp[i]; }
    total = 4.0 * sqrt(total / 2.0);
    float scale = total > TIDES_BREAK * D ? TIDES_BREAK * D / total : 1.0;
    vec3 o = vec3(0.0);
    for (int i = 0; i < TIDES_N; i++) {
        float a = amp[i] * scale, ph = TIDES_K[i] * (TIDES_DX[i] * p.x + TIDES_DZ[i] * p.y) - TIDES_OM[i] * tSec + TIDES_PHASE[i];
        o.x += a * sin(ph); float c = a * TIDES_K[i] * cos(ph); o.y += c * TIDES_DX[i]; o.z += c * TIDES_DZ[i];
    }
    return o;
}
// A water-surface vertex carries data in the low nibble of each colour channel (0.7.0; was the low 3 bits): red = exposure, green =
// depth, blue = the column simulation's speed class (0 none, k: (k-1) x 0.3 m/s), alpha = its direction class (k x 22.5 deg).
// The caller decides which vertices are water surface (texture / block id, and height in the block). Returns (exposure, depth).
vec2 tides_decode(vec4 color) {
    int r = int(color.r * 255.0 + 0.5), g = int(color.g * 255.0 + 0.5);
    return vec2(float(r & 15), float(g & 15));
}
vec2 tides_decodeFlow(vec4 color) {
    int b = int(color.b * 255.0 + 0.5), a = int(color.a * 255.0 + 0.5);
    return vec2(float(b & 15), float(a & 15));
}
#endif
