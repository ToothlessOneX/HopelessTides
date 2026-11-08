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
// A water-surface vertex carries its corner's exposure and depth in the low three bits of its colour channels (a colour change of at
// most 7/255, not visible): red = exposure & 7; green bit 0 = exposure >> 3, bits 1-2 = depth & 3; blue bits 0-1 = depth >> 2.
// The caller decides which vertices are water surface (texture / block id, and height in the block). Returns (exposure, depth).
vec2 tides_decode(vec4 color) {
    int r = int(color.r * 255.0 + 0.5), g = int(color.g * 255.0 + 0.5), b = int(color.b * 255.0 + 0.5);
    return vec2(float((r & 7) | ((g & 1) << 3)), float(((g >> 1) & 3) | ((b & 3) << 2)));
}
