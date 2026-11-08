// Hopeless Tides 0.9.0: the Fourier sea (FftSea.java) on top of the column water, for one block corner, number for number as
// FftCorner.fill does it for the physics. Needs tides_waves.glsl (constants, tides_heightOpen, tides_peakLength) before it.
//
// u_TidesFft: RGBA16F, 256 x 1024: rows 0-255 field A (h, Dx, Dz, J) and 256-511 field B (Jxx, Jzz, Jxz, 0) of the field with an
// even slot, rows 512-1023 the same for the odd slot. The pair (slot k, slot k + 1) is always one even and one odd.
uniform sampler2D u_TidesFft;
uniform vec4 u_TidesFft0;   // x = frac (0..1 from slot k to k + 1), y = heightOpen(s) / hsFull (the open sea's scale), z = breakJ, w = row of slot k (0 or 512)
uniform vec4 u_TidesFft1;   // x = 1 when the Fourier sea is on and its fields are uploaded, y = the open-sky wind (rad; the physics' wind)

const float TIDES_FFT_L = 512.0;
const float TIDES_FFT_CELL = 2.0;         // m a grid cell (L / N)
const float TIDES_FFT_CALM = 0.3;         // FftCorner.FLOW_CALM (m/s)

// bilinear on the periodic grid from the halves, as FftSea.fetch
vec4 tides_fftTex(vec2 x, int row) {
    vec2 f = x / TIDES_FFT_CELL; vec2 fl = floor(f); vec2 t = f - fl;
    int ix = int(fl.x) & 255, iz = int(fl.y) & 255, ix1 = (ix + 1) & 255, iz1 = (iz + 1) & 255;
    vec4 a = texelFetch(u_TidesFft, ivec2(ix, row + iz), 0), b = texelFetch(u_TidesFft, ivec2(ix1, row + iz), 0);
    vec4 c = texelFetch(u_TidesFft, ivec2(ix, row + iz1), 0), d = texelFetch(u_TidesFft, ivec2(ix1, row + iz1), 0);
    return mix(mix(a, b, t.x), mix(c, d, t.x), t.y);
}
// field A or B of the pair at frac (part 0 = A, 256 = B)
vec4 tides_fftPair(vec2 x, int part) {
    int r0 = int(u_TidesFft0.w + 0.5), r1 = 512 - r0;
    vec4 p = tides_fftTex(x, r0 + part), c = tides_fftTex(x, r1 + part);
    return p + (c - p) * u_TidesFft0.x;
}
// WaveModel.localScale and depthBreaking: the 12-wave table's height at (exposure, depth) over the open sea's. x = scale, y = 1 if capped
vec2 tides_localScale(float s, float w, float exposure, float depth) {
    if (exposure <= 0.0 || depth <= 0.0) return vec2(0.0);
    float e = exposure / 15.0, hs = tides_heightOpen(s) * e * e, lp = tides_peakLength(s) * (0.45 + 0.55 * e), D = max(depth, 0.5);
    float wt[TIDES_N]; float wsum = 0.0, total = 0.0;
    for (int i = 0; i < TIDES_N; i++) { float r = log(TIDES_LEN[i] / lp) / 0.45, c = max(0.0, cos(TIDES_DIR[i] - w)); wt[i] = exp(-0.5 * r * r) * (c * c + 0.04); wsum += wt[i]; }
    for (int i = 0; i < TIDES_N; i++) { float ks = min(pow(tanh(TIDES_K[i] * D), -0.25), 1.8); float a = 0.25 * hs * sqrt(2.0 * wt[i] / wsum) * ks; total += a * a; }
    total = 4.0 * sqrt(total / 2.0);
    float cap = TIDES_BREAK * D;
    return vec2(min(total, cap) / tides_heightOpen(s), total > cap ? 1.0 : 0.0);
}
// The corner at world p (whole blocks): x = height above still water, yz = world slope d/dx, d/dz, w = whitecap 0..1.
// speed: the corner's current (m/s, from the vertex's speed class).
vec4 tides_fftCorner(vec2 p, float s, float w, float exposure, float depth, float speed) {
    vec2 ls = tides_localScale(s, w, exposure, depth);
    float r = speed / TIDES_FFT_CALM;
    float a = u_TidesFft0.y * ls.x / (1.0 + r * r);
    if (a <= 0.0) return vec4(0.0);
    vec2 q = p - TIDES_FFT_L * floor(p / TIDES_FFT_L);            // the field repeats every 512 blocks: keep the float small
    vec2 x0 = q;
    for (int it = 0; it < 3; it++) { vec4 A = tides_fftPair(x0, 0); x0 = q - a * A.yz; }   // the rest point whose water lands here
    vec4 A = tides_fftPair(x0, 0), B = tides_fftPair(x0, 256);
    float h = a * A.x;
    float jxx = a * B.x, jzz = a * B.y, jxz = a * B.z;
    float J = (1.0 + jxx) * (1.0 + jzz) - jxz * jxz;
    // slope: the height gradient at the rest point, carried to the world through the displacement (inverse of d x / d x0)
    float hxp = tides_fftPair(x0 + vec2(1.0, 0.0), 0).x, hxm = tides_fftPair(x0 - vec2(1.0, 0.0), 0).x;
    float hzp = tides_fftPair(x0 + vec2(0.0, 1.0), 0).x, hzm = tides_fftPair(x0 - vec2(0.0, 1.0), 0).x;
    vec2 g0 = a * 0.5 * vec2(hxp - hxm, hzp - hzm);
    float det = max(J, 0.05);
    vec2 g = vec2((1.0 + jzz) * g0.x - jxz * g0.y, -jxz * g0.x + (1.0 + jxx) * g0.y) / det;
    float cap = 1.0 - smoothstep(u_TidesFft0.z - 0.06, u_TidesFft0.z, J);   // whitecap where the crest folds (physics: J < breakJ breaks)
    return vec4(h, g, cap);
}
