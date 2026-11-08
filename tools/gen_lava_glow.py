"""0.12.0: the colour of glowing lava by temperature, from physics: Planck's law integrated against the CIE 1931 colour matching
functions (Wyman, Sloan & Shirley 2013 multi-lobe fit), XYZ -> linear sRGB (D65), luminance relative to fresh lava (1140 C) (the shader decides how bright it shows). Writes the GLSL table that
tides_look.glsl's tl_glow reads (450-1250 C, 25 C steps). usage: python3 gen_lava_glow.py  (prints the GLSL)"""
import numpy as np
def g(x, mu, s1, s2): return np.exp(-0.5 * ((x - mu) / np.where(x < mu, s1, s2)) ** 2)
lam = np.arange(380.0, 781.0, 1.0)
xb = 1.056 * g(lam, 599.8, 37.9, 31.0) + 0.362 * g(lam, 442.0, 16.0, 26.7) - 0.065 * g(lam, 501.1, 20.4, 26.2)
yb = 0.821 * g(lam, 568.8, 46.9, 40.5) + 0.286 * g(lam, 530.9, 16.3, 31.1)
zb = 1.217 * g(lam, 437.0, 11.8, 36.0) + 0.681 * g(lam, 459.0, 26.0, 13.8)
C2 = 0.014388
def XYZ(c):
    T = c + 273.15; L = lam * 1e-9; B = 1.0 / (L ** 5 * (np.exp(C2 / (L * T)) - 1.0))
    return np.array([np.sum(B * xb), np.sum(B * yb), np.sum(B * zb)])
M = np.array([[3.2406, -1.5372, -0.4986], [-0.9689, 1.8758, 0.0415], [0.0557, -0.2040, 1.0570]])
Y0 = XYZ(1140.0)[1]
def enc(v): return np.where(v <= 0.0031308, 12.92 * v, 1.055 * np.power(np.maximum(v, 0), 1 / 2.4) - 0.055)
rows = []
for c in np.arange(450.0, 1251.0, 25.0):
    X = XYZ(c); Y = X[1] / Y0; lin = np.maximum(M @ (X / X[1]), 0)   # chromaticity in linear sRGB, luminance 1
    lin = lin / (0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2])
    rows.append((lin[0], lin[1], lin[2], Y))
print("const vec4 TL_GLOW[33] = vec4[](" + ", ".join(f"vec4({r[0]:.4f}, {r[1]:.4f}, {r[2]:.5f}, {r[3]:.4e})" for r in rows) + ");   // 450-1250 C, 25 C steps: linear sRGB colour of luminance 1, luminance relative to 1140 C (tools/gen_lava_glow.py)")
for c in (525, 600, 800, 1000, 1140, 1200): i = int((c - 450) / 25); print(f"// {c} C -> Y {rows[i][3]:.3e}")
