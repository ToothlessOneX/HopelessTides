"""Hopeless Tides 0.6.1: high-resolution water textures (override vanilla's water_still / water_flow), painted procedurally.
Same rules as vanilla so the game treats them the same: grey (the biome tints it), alpha 180, 32 animation frames that loop, the
flow texture twice the still one's size (it is drawn at half scale). Vanilla is 16 px (still) / 32 px (flow); these are 128 / 256.
The pattern tiles in space and loops in time: sums of waves whose wave numbers are whole cycles over the tile and whose phase turns
whole cycles over the 32 frames. Ridges of crossing waves make the bright caustic lines.
usage: python3 gen_water_tex.py OUT_DIR"""
import sys, os, math
import numpy as np
from PIL import Image

OUT = sys.argv[1]; os.makedirs(OUT, exist_ok=True)
FR = 32

def noise(n, t, rng, kmax, amp_pow=1.2, nw=40):
    """Tiling smooth noise: random waves with whole-number wave vectors (tile) and whole turns of phase per loop (loops)."""
    y, x = np.mgrid[0:n, 0:n] / n; v = np.zeros((n, n))
    for _ in range(nw):
        kx, ky = rng.integers(-kmax, kmax + 1, 2)
        if kx == 0 and ky == 0: continue
        k = math.hypot(kx, ky); w = rng.choice([-2, -1, 1, 2]); ph = rng.uniform(0, 2 * math.pi)
        v += np.sin(2 * math.pi * (kx * x + ky * y + w * t) + ph) / k ** amp_pow
    return v / (np.abs(v).max() + 1e-9)

def worley_lines(n, t, cells, seed, width, warp, sx=1.0, sy=1.0):
    """Caustic network: borders between Voronoi cells (F2 - F1 small) of points that wrap at the tile edges and each move once
    round a small closed loop per animation loop. The domain is warped by tiling noise so the lines wobble like light through waves."""
    rng = np.random.default_rng(seed)
    P = rng.uniform(0, 1, (cells, 2)); R = rng.uniform(0.02, 0.06, cells); W = rng.choice([-1, 1], cells); PH = rng.uniform(0, 2 * math.pi, cells)
    pts = P + np.stack([R * np.cos(2 * math.pi * W * t + PH), R * np.sin(2 * math.pi * W * t + PH)], 1)
    y, x = np.mgrid[0:n, 0:n] / n
    x = x + warp * noise(n, t, np.random.default_rng(seed + 100), 3); y = y + warp * noise(n, t, np.random.default_rng(seed + 200), 3)
    f1 = np.full((n, n), 9.0); f2 = np.full((n, n), 9.0)
    for (qx, qy) in pts:
        dx = (x - qx + 0.5) % 1.0 - 0.5; dy = (y - qy + 0.5) % 1.0 - 0.5          # wrap: the nearest copy of the point
        d = np.hypot(dx * sx, dy * sy)
        f2 = np.where(d < f1, f1, np.minimum(f2, d)); f1 = np.minimum(f1, d)
    e = (f2 - f1) / width
    return np.exp(-e * e)                                                           # 1 on a border, falling off across it

def pattern(n, t, flow=False):
    """One frame, 0..1: a fine and a coarse caustic network, brighter where they cross, on a soft swell of light and shade."""
    if flow:   # flowing water: cells stretched along the flow (down the tile)
        a = worley_lines(n, t, 14, 11, 0.035, 0.03, sx=1.0, sy=0.45); b = worley_lines(n, t, 30, 12, 0.022, 0.02, sx=1.0, sy=0.45)
    else:
        a = worley_lines(n, t, 12, 1, 0.040, 0.035); b = worley_lines(n, t, 30, 2, 0.025, 0.025)
    swell = 0.5 + 0.5 * noise(n, t, np.random.default_rng(5 if flow else 3), 2)
    return np.clip(0.20 * swell + 0.45 * a + 0.35 * b + 0.4 * a * b, 0, 1)

def sheet(n, flow, name, frametime):
    frames = []
    for f in range(FR):
        t = f / FR
        p = pattern(n, t, flow=flow)
        if flow:                                                                       # flowing water: the whole pattern runs down the tile once a loop
            p = np.roll(p, int(round(t * n)), axis=0)
        g = 168 + 26 * p + 14 * np.clip((p - 0.65) / 0.35, 0, 1)            # 0.7.0: quiet - the shader draws the waves now                      # vanilla's range: 165 grey up to white highlights
        rgba = np.dstack([g, g, g, np.full_like(g, 180)]).clip(0, 255).astype(np.uint8)
        frames.append(rgba)
    img = Image.fromarray(np.concatenate(frames, axis=0), "RGBA"); img.save(os.path.join(OUT, name + ".png"))
    open(os.path.join(OUT, name + ".png.mcmeta"), "w").write('{\n  "animation": {\n    "frametime": %d,\n    "interpolate": true\n  }\n}\n' % frametime)
    a = np.asarray(img).astype(float)
    print(f"{name}: {n}x{n} x {FR} frames, grey mean {a[..., 0].mean():.1f} (vanilla still 177.1, flow 168.8) min {a[..., 0].min():.0f} max {a[..., 0].max():.0f} alpha {a[..., 3].mean():.0f}")

sheet(128, False, "water_still", 2)
sheet(256, True, "water_flow", 1)
