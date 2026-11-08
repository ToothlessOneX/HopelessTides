"""Hopeless Tides 0.7.0 look harness: Embeddium's terrain shaders as patched in the game (PatchDump), on Mesa (EGL), drawing a lake
(depth 1..15 toward the middle) and a flowing stream, blended over a sand-coloured bed the way the translucent pass blends.
Scenes: calm / breeze / storm (surf) / cave (block light only) / under the surface looking up / a swimmer with a wake.
Measures per scene: mean water colour, alpha by depth, glitter pixels, foam pixels, and frame-to-frame change.
usage: python3 look_harness.py PATCHED.vsh PATCHED.fsh EMB_SHADER_DIR WATER_STILL.png WATER_FLOW.png OUTDIR"""
import sys, os, re, math
import numpy as np, moderngl
from PIL import Image

VSP, FSP, EMB, TEXS, TEXF, OUT = sys.argv[1:7]
os.makedirs(OUT, exist_ok=True)
def resolve(src):
    return re.sub(r"#import <sodium:include/([a-z_]+\.glsl)>", lambda m: open(os.path.join(EMB, "include", m.group(1))).read() + "\n", src)
def defines(src, ds):
    l = src.split("\n"); return "\n".join([l[0]] + ["#define " + d for d in ds] + l[1:])
VS = defines(resolve(open(VSP).read()), ["USE_FOG"]); FS = defines(resolve(open(FSP).read()), ["USE_FOG"])
ctx = moderngl.create_standalone_context(require=330, backend="egl")
prog = ctx.program(vertex_shader=VS, fragment_shader=FS)
print("linked; tides uniforms:", sorted(k for k in prog if k.startswith("u_Tides")))

# atlas: water_still frame 0 in the left half (u 0..0.5), water_flow frame 0 in the right half (u 0.5..1), both scaled to 256 px
st = Image.open(TEXS).convert("RGBA"); st = st.crop((0, 0, st.width, st.width)).resize((256, 256), Image.NEAREST)
fl = Image.open(TEXF).convert("RGBA"); fl = fl.crop((0, 0, fl.width, fl.width)).resize((256, 256), Image.NEAREST)
sand = Image.new("RGBA", (256, 256)); import random as _r; _r.seed(3)
for yy in range(256):
    for xx in range(256): v = _r.randint(-12, 12); sand.putpixel((xx, yy), (196 + v, 178 + v, 128 + v, 255))
atlas = Image.new("RGBA", (768, 256)); atlas.paste(st, (0, 0)); atlas.paste(fl, (256, 0)); atlas.paste(sand, (512, 0))
tex = ctx.texture(atlas.size, 4, atlas.tobytes()); tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
# 0.12.0: a second atlas with Minecraft's lava sprites (frame 0) in the water's slots, for the lava scenes; the lava map from LavaDump
LAVATEX = tex; LAVAMAP = None
_LD = os.environ.get("TIDES_LAVA")
if _LD:
    ls_ = Image.open(os.path.join(_LD, "lava_still.png")).convert("RGBA"); ls_ = ls_.crop((0, 0, ls_.width, ls_.width)).resize((256, 256), Image.NEAREST)
    lf_ = Image.open(os.path.join(_LD, "lava_flow.png")).convert("RGBA"); lf_ = lf_.crop((0, 0, lf_.width, lf_.width)).resize((256, 256), Image.NEAREST)
    la_ = atlas.copy(); la_.paste(ls_, (0, 0)); la_.paste(lf_, (256, 0))
    LAVATEX = ctx.texture(la_.size, 4, la_.tobytes()); LAVATEX.filter = (moderngl.NEAREST, moderngl.NEAREST)
    LAVAMAP = ctx.texture((128, 128), 4, open(os.path.join(_LD, "lavamap.bin"), "rb").read()); LAVAMAP.filter = (moderngl.LINEAR, moderngl.LINEAR)
lm = ctx.texture((16, 16), 4, bytes([255] * 1024)); lm.filter = (moderngl.LINEAR, moderngl.LINEAR)
wm = bytearray(128 * 128 * 4)
def isw(i, j): return (4 <= i <= 43 and 10 <= j <= 49) or (50 <= i <= 53)
for j in range(128):
    for i in range(128):
        if isw(i, j): wm[(j * 128 + i) * 4 + 3] = 255
wet = ctx.texture((128, 128), 4, bytes(wm)); wet.filter = (moderngl.LINEAR, moderngl.LINEAR)
wl = bytearray(wm)   # 0.11.0 test: the land soaked by rain (r = wetness 0.9, g = the wet block's y 0: its top at y 1)
for j in range(128):
    for i in range(128):
        if not isw(i, j): wl[(j * 128 + i) * 4] = 230
WETL = ctx.texture((128, 128), 4, bytes(wl)); WETL.filter = (moderngl.LINEAR, moderngl.LINEAR)
foamim = Image.open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src/main/resources/assets/hopelesstides/textures/foam.png")).convert("RGBA")
foam = ctx.texture(foamim.size, 4, foamim.tobytes()); foam.build_mipmaps(); foam.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR); foam.repeat_x = foam.repeat_y = True

LAVAMARK = [False]
def encode(rgba, e, d, flow=0):
    if LAVAMARK[0]: r, g, b, a = rgba; return ((r & 0xF0) | 10, (g & 0xF0) | 5, (b & 0xF0) | 10, (a & 0xF0) | 1)
    r, g, b, a = rgba
    return ((r & 0xF0) | (e & 15), (g & 0xF0) | (d & 15), (b & 0xF0) | (flow & 15), (a & 0xF0) | ((flow >> 4) & 15))
TINT = (63, 118, 228, 255)
TINTS = {"white": (255, 255, 255, 255), "plain": (63, 118, 228, 255), "swamp": (97, 123, 100, 255), "warm": (67, 213, 238, 255)}   # Minecraft's biome water colours
CUR_TINT = [TINT]
N = 64
SEA = [False]; NOWATER = [False]   # NOWATER: the bed alone, to see its caustics (a test view; the game draws the water over it)
def body(i, j):
    if SEA[0]: return (True, 15, 15, False) if 0 <= i <= 160 and 0 <= j <= 160 else (False, 0, 0, False)   # 0.9.0: open sea
    if 4 <= i <= 44 and 10 <= j <= 50:
        edge = min(i - 4, 44 - i, j - 10, 50 - j); return True, min(15, int(2 + edge * 0.9)), min(15, 1 + edge // 2), False
    if 50 <= i <= 54: return True, 3, 2, True
    return False, 0, 0, False
def mesh(sky, blk, flowcode=0):
    out = []; n = 161 if SEA[0] else N
    for j in range(n):
        for i in range(n):
            cs = [body(i, j), body(i + 1, j), body(i + 1, j + 1), body(i, j + 1)]
            if not all(c[0] for c in cs): continue
            flowing = cs[0][3]
            q = [(i, j), (i + 1, j), (i + 1, j + 1), (i, j + 1)]
            for k in (0, 1, 2, 0, 2, 3):
                x, z = q[k]; _, e, d, _ = body(x, z); r, g, b, a = encode(CUR_TINT[0], e, d, flowcode if flowing else 0)
                u, v = q[k][0] - i, q[k][1] - j
                # still: u 0..0.5; flowing: u 0.5..1 with v growing downstream (+z), as vanilla turns the flow sprite to the flow
                uu, vv = (u / 3, v) if not flowing else (1 / 3 + u / 3, v * 0.5 + 0.0)
                y = 0.875 - (0.11 * (z - 32) / 32 if flowing else 0.0)   # the stream slopes down along +z (about one level per 9 blocks)
                out.append((x, y, z, r / 255, g / 255, b / 255, a / 255, uu, vv))
    bed = []
    for j in range(N):
        for i in range(N):
            if not isw(i, j) and not (0 <= i < N and 0 <= j < N): continue
            dep = body(i, j)[2] if isw(i, j) and body(i, j)[0] else 0
            y = 1.0 - dep if isw(i, j) else 1.0
            for k in (0, 1, 2, 0, 2, 3):
                x, z = [(i, j), (i + 1, j), (i + 1, j + 1), (i, j + 1)][k]
                bed.append((x, y, z, 1, 1, 1, 1, 2 / 3 + (x - i) / 3.01, (z - j) * 0.99))
    V = np.array(bed + (out if not NOWATER[0] else []), "f4"); lc = np.full(len(V), (((sky * 16) << 8) | (blk * 16)) << 16, "u4")
    return V, lc

def lookat(eye, tgt):
    f = np.array(tgt, float) - np.array(eye, float); f /= np.linalg.norm(f); s = np.cross(f, [0, 1, 0]); s /= np.linalg.norm(s); u = np.cross(s, f)
    m = np.identity(4); m[0, :3] = s; m[1, :3] = u; m[2, :3] = -f; return m
fov = math.radians(70); fz = 1 / math.tan(fov / 2); W, H = 800, 500; asp = W / H; n_, fa = 0.05, 300
P = np.array([[fz / asp, 0, 0, 0], [0, fz, 0, 0], [0, 0, (fa + n_) / (n_ - fa), 2 * fa * n_ / (n_ - fa)], [0, 0, -1, 0]])
fbo = ctx.framebuffer(color_attachments=[ctx.texture((W, H), 4)], depth_attachment=ctx.depth_renderbuffer((W, H)))

def put(n, v):
    if n in prog:
        if isinstance(v, (list, tuple)) and len(v) and isinstance(v[0], (list, tuple)): prog[n].write(np.array(v, "f4").tobytes())
        else: prog[n].value = v
FFTTEX = ctx.texture((256, 1024), 4, dtype="f2"); FFTTEX.filter = (moderngl.NEAREST, moderngl.NEAREST)
def fft_frame(d, t, wind):
    """0.9.0: the Fourier pair for time t from a FftDump run (seq_<slot>.bin), on unit 5, and its uniforms - as TidesFftGpu does."""
    k = int(math.floor(t / 0.25)); frac = t / 0.25 - k
    meta = {int(l.split()[0]): list(map(float, l.split()[1:])) for l in open(os.path.join(d, "seq.txt"))}
    for slot in (k, k + 1):
        raw = np.fromfile(os.path.join(d, f"seq_{slot}.bin"), dtype="<f2"); FFTTEX.write(raw.tobytes(), viewport=(0, (slot & 1) * 512, 256, 512))
    FFTTEX.use(5); put("u_TidesFft", 5)
    put("u_TidesFft0", (frac, meta[k + 1][2], meta[k + 1][1], float((k & 1) * 512))); put("u_TidesFft1", (1.0, wind, 0.0, 0.0))
RIPTEX = ctx.texture((128, 128), 1, dtype="f4"); RIPTEX.filter = (moderngl.LINEAR, moderngl.LINEAR)
def render(name, cam, look, U, sky=15, blk=0, state=0.2, sun=(0.15, 0.35, -0.92, 1.0), bodies=(), frames=12, bed=(0.62, 0.55, 0.38), flowcode=0, rain=0.0, fft=None, rip=None, storm=None, under=None, ice=False, flash=0.0, mist=0.0, tide=0.0, lava=False, tint="plain", wetland=False, dt=0.125):
    CUR_TINT[0] = TINTS[tint]; LAVAMARK[0] = lava
    V, lc = mesh(sky, blk, flowcode)
    vb = [ctx.buffer(V[:, 0:3].copy().tobytes()), ctx.buffer(V[:, 3:7].copy().tobytes()), ctx.buffer(V[:, 7:9].copy().tobytes()), ctx.buffer(lc.tobytes())]
    vao = ctx.vertex_array(prog, [f for f in [(vb[0], "3f", "a_PosId"), (vb[1], "4f", "a_Color"), (vb[2], "2f", "a_TexCoord"), (vb[3], "u4", "a_LightCoord")] if f[2] in prog])
    fbo.use(); prog["u_ProjectionMatrix"].write(P.T.astype("f4").tobytes()); prog["u_ModelViewMatrix"].write(lookat(cam, look).T.astype("f4").tobytes())
    put("u_BlockTex", 0); put("u_LightTex", 1); put("u_TidesWet", 7); tex.use(0); lm.use(1); wet.use(7)
    put("u_FogStart", 200.0); put("u_FogEnd", 300.0); put("u_FogColor", (0.6, 0.7, 0.9, 1.0)); put("u_FogShape", 0)
    put("u_TidesCam", (cam[0], cam[1], cam[2], 1.0)); put("u_RegionOffset", (-cam[0], -cam[1], -cam[2]))
    put("u_TidesUV0", (-0.001, -0.001, 1 / 3, 1.001)); put("u_TidesUV1", (1 / 3 + 1e-4, -0.001, 2 / 3, 1.001)); put("u_TidesFoam", 6); foam.use(6)
    put("u_TidesRain", (rain, 0.0, 0.0, 0.0))   # 0.8.0
    put("u_TidesSun", sun); put("u_TidesSky", (0.47, 0.65, 1.0, U)); put("u_TidesWetOrigin", (0.0, 0.0, 128.0, 1.0))
    b4 = [list(b[0]) for b in bodies] + [[0, 0, 0, 0]] * (8 - len(bodies)); v4 = [list(b[1]) for b in bodies] + [[0, 0, 0, 0]] * (8 - len(bodies))
    put("u_TidesBody", b4); put("u_TidesBodyV", v4); put("u_TidesBodyN", len(bodies))
    CUR_TINT[0] = TINTS[tint]
    put("u_TidesFlash", (flash, 0.0, 0.0, 0.0)); put("u_TidesMist", (mist, 0.0, 0.0, 0.0)); put("u_TidesTide", (tide, 0.0, 0.0, 0.0))   # 0.11.0
    put("u_TidesSway", (0.0, 0.0, 0.0, 0.0))
    if lava:   # test view: the still-water sprite drawn as lava (the test atlas has no lava): water off, lava on that sprite
        put("u_TidesLavaUV0", (-0.001, -0.001, 1 / 3, 1.001)); put("u_TidesLavaUV1", (1 / 3 + 1e-4, -0.001, 2 / 3, 1.001)); put("u_TidesUV0", (1.0, 0.0, 0.0, 0.0)); put("u_TidesUV1", (1.0, 0.0, 0.0, 0.0))
        LAVATEX.use(0)
        if LAVAMAP is not None: LAVAMAP.use(3); put("u_TidesLavaMap", 3); put("u_TidesLavaO", (0.0, 0.0, 128.0, 1.0))
    else: put("u_TidesLavaUV0", (1.0, 0.0, 0.0, 0.0)); put("u_TidesLavaUV1", (1.0, 0.0, 0.0, 0.0)); put("u_TidesLavaO", (0.0, 0.0, 128.0, 0.0))
    if wetland: WETL.use(7)
    put("u_TidesUnder", under if under else (0.0, 0.0, 0.0, 0.0))   # 0.10.0: eye under water, surface y, sky light at the eye
    put("u_TidesUVIce", (2 / 3, 0.0, 1.0, 1.0) if ice else (1.0, 0.0, 0.0, 0.0))   # 0.10.0 test view: the sand sprite drawn as ice
    put("u_TidesStorm", storm if storm else (0.0, 0.0, 9.0, 9.0))   # 0.10.0: U10, sigma, thresholds (StormFoam.java)
    if rip: RIPTEX.write(np.fromfile(rip, dtype="<f4").tobytes()); RIPTEX.use(4); put("u_TidesRip", 4); put("u_TidesRipO", (0.0, 0.0, 0.25, 1.0))
    else: put("u_TidesRipO", (0.0, 0.0, 0.25, 0.0))
    ims = []
    for f in range(frames):
        t = 100.0 + f * dt
        put("u_TidesWave", (t, state, state, 0.7)); put("u_TidesRipple", (t, 0.5, 0.75, 0.7))
        if fft: fft_frame(fft, t, 0.7)
        else: put("u_TidesFft1", (0.0, 0.0, 0.0, 0.0))
        if bodies: v4b = [[v[0], v[1], v[2] + f * 0.125, v[3]] for v in v4]; put("u_TidesBodyV", v4b)
        fbo.clear(*bed, 1.0); ctx.enable(moderngl.BLEND); ctx.blend_func = (moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA); ctx.enable(moderngl.DEPTH_TEST)
        vao.render(moderngl.TRIANGLES)
        ims.append(Image.frombytes("RGBA", fbo.size, fbo.read(components=4)).transpose(Image.FLIP_TOP_BOTTOM).convert("RGB"))
    ims[0].save(os.path.join(OUT, name + ".png")); ims[0].save(os.path.join(OUT, name + ".gif"), save_all=True, append_images=ims[1:], duration=125, loop=0)
    a = np.stack([np.asarray(i, "f4") for i in ims]); bedc = np.array(bed) * 255
    water = np.abs(a[0] - bedc).sum(axis=2) > 6
    glit = (a[0].min(axis=2) > 235) & water
    print(f"{name:10s} water px {water.sum():6d}  mean rgb {a[0][water].mean(axis=0).round(0)}  glitter/white px {glit.sum():5d}  frame change {np.abs(a[0] - a[-1]).mean(axis=2)[water].mean():5.2f}  nan {int(np.isnan(a).sum())}")
    return a[0]

cam, look = (24.0, 3.5, 44.0), (30.0, 0.0, 22.0)
if os.environ.get("LAVA_ONLY"):   # fast iteration on the lava scenes only
    _render_all = render
    def render(name, *a, **k): return _render_all(name, *a, **k) if k.get("lava") else np.zeros((H, W, 3), "f4")
render("calm", cam, look, U=1.0)
render("breeze", cam, look, U=5.0)
render("storm", cam, look, U=11.0, state=0.8)
render("shore", (8.0, 2.2, 16.0), (4.0, 0.5, 11.0), U=9.0, state=0.6)
render("cave", cam, look, U=0.5, sky=0, blk=13, sun=(0.15, 0.35, -0.92, 0.0))
render("night", cam, look, U=3.0, sun=(-0.2, 0.6, -0.7, 0.12))
render("swimmer", (20.0, 2.5, 34.0), (24.0, 0.0, 26.0), U=2.0, bodies=[((24.0, 0.3, 27.0, 0.3), (0.0, -2.2, 10.0, 1.0)), ((19.0, 0.3, 24.0, 0.3), (0.0, 0.0, 0.3, 1.0))])
render("river", (56.0, 3.0, 20.0), (52.0, 0.0, 34.0), U=2.0)
render("rapids", (56.0, 3.0, 20.0), (52.0, 0.0, 34.0), U=2.0, flowcode=(12 | (4 << 4)))   # the simulation says 3.3 m/s toward +z
render("bed", (10.0, 4.0, 20.0), (12.0, -1.0, 15.0), U=2.0, sun=(0.3, 0.8, -0.5, 1.0))
render("below", (20.0, -1.5, 30.0), (22.0, 1.5, 22.0), U=4.0, bed=(0.05, 0.12, 0.2))
# alpha by depth over a white and a black bed: alpha = 1 - (white - black)/255 per pixel
w = render("aw", (24.0, 30.0, 30.0), (24.0, 0.0, 29.9), U=1.0, bed=(1, 1, 1), frames=1)
k = render("ak", (24.0, 30.0, 30.0), (24.0, 0.0, 29.9), U=1.0, bed=(0, 0, 0), frames=1)
al = 1 - (w - k).mean(axis=2) / 255
for name, (x0, x1) in {"shore (depth 1-2)": (200, 230), "mid (depth 6-8)": (300, 330), "middle (depth 11+)": (385, 415)}.items():
    print(f"alpha straight down, {name}: {al[240:260, x0:x1].mean():.2f}")
Image.fromarray((np.clip(al, 0, 1) * 255).astype(np.uint8)).save(os.path.join(OUT, "alpha_map.png"))
print("alpha percentiles", np.percentile(al[al > 0.02], [1, 10, 50, 90]).round(2))
# close-ups: the shore edge from above (contact band) and the shallow bed (caustics), with and without the caustics
# 0.8.0 rain rings: a close look at open water (sky 15) with and without rain, and under a roof (sky 0: no rings)
dry_ = render("rain0", (24.0, 1.2, 30.0), (24.0, 0.0, 28.8), U=2.0, frames=4)
wet_ = render("rain1", (24.0, 1.2, 30.0), (24.0, 0.0, 28.8), U=2.0, frames=4, rain=1.0)
half = render("rain05", (24.0, 1.2, 30.0), (24.0, 0.0, 28.8), U=2.0, frames=4, rain=0.4)
roof = render("rainroof", (24.0, 1.2, 30.0), (24.0, 0.0, 28.8), U=2.0, frames=4, rain=1.0, sky=0, blk=12)
roof0 = render("rainroof0", (24.0, 1.2, 30.0), (24.0, 0.0, 28.8), U=2.0, frames=4, rain=0.0, sky=0, blk=12)
far1 = render("rainfar1", cam, look, U=2.0, frames=2, rain=1.0); far0 = render("rainfar0", cam, look, U=2.0, frames=2, rain=0.0)
print(f"rain rings, mean |change| per pixel: heavy {np.abs(wet_ - dry_).mean():.2f}, light {np.abs(half - dry_).mean():.2f}, under a roof {np.abs(roof - roof0).mean():.2f}, far view {np.abs(far1 - far0).mean():.2f}")
render("edge", (6.5, 3.0, 20.0), (5.0, 0.0, 19.0), U=6.0, state=0.4, sun=(0.3, 0.8, -0.5, 1.0), frames=2)

# 0.9.0: the Fourier sea over the column water, against the 12-wave model, same scene and time (TIDES_FFT = FftDump output root)
FD = os.environ.get("TIDES_FFT")
if FD:
    hi = (14.0, 9.0, 58.0); hl = (26.0, 0.0, 28.0)
    a0 = render("storm12", hi, hl, U=11.0, state=0.8, frames=12); a1 = render("stormfft", hi, hl, U=11.0, state=0.8, frames=12, fft=os.path.join(FD, "seq0.8"))
    b0 = render("fresh12", hi, hl, U=6.0, state=0.4, frames=12); b1 = render("freshfft", hi, hl, U=6.0, state=0.4, frames=12, fft=os.path.join(FD, "seq0.4"))
    render("stormfft_low", (20.0, 2.2, 40.0), (26.0, 0.0, 28.0), U=11.0, state=0.8, frames=12, fft=os.path.join(FD, "seq0.8"))
    print(f"fft vs 12-wave, mean |change| per pixel: storm {np.abs(a1 - a0).mean():.2f}, fresh {np.abs(b1 - b0).mean():.2f}")
    # 0.10.0: caustics on the bed under the Fourier sea (sun high), against the procedural-only pattern
    e0 = render("caus12", (10.0, 4.0, 20.0), (12.0, -1.0, 15.0), U=8.0, state=0.8, sun=(0.3, 0.8, -0.5, 1.0), frames=12)
    e1 = render("causfft", (10.0, 4.0, 20.0), (12.0, -1.0, 15.0), U=8.0, state=0.8, sun=(0.3, 0.8, -0.5, 1.0), frames=12, fft=os.path.join(FD, "seq0.8"))
    print(f"caustics, fft vs none, mean |change| per pixel: {np.abs(e1 - e0).mean():.2f}")
    NOWATER[0] = True
    f0 = render("causbed12", (14.0, 6.0, 34.0), (20.0, -4.0, 26.0), U=8.0, state=0.8, sun=(0.3, 0.8, -0.5, 1.0), frames=12)
    f1 = render("causbedfft", (14.0, 6.0, 34.0), (20.0, -4.0, 26.0), U=8.0, state=0.8, sun=(0.3, 0.8, -0.5, 1.0), frames=12, fft=os.path.join(FD, "seq0.8"))
    print(f"bed alone, fft vs none, mean |change| per pixel: {np.abs(f1 - f0).mean():.2f}")
    NOWATER[0] = False
    SEA[0] = True
    sc, sl = (80.0, 5.0, 150.0), (80.0, 0.0, 60.0)
    c0 = render("sea12", sc, sl, U=11.0, state=0.8, frames=12); c1 = render("seafft", sc, sl, U=11.0, state=0.8, frames=12, fft=os.path.join(FD, "seq0.8"))
    d0 = render("seafresh12", sc, sl, U=6.0, state=0.4, frames=12); d1 = render("seafreshfft", sc, sl, U=6.0, state=0.4, frames=12, fft=os.path.join(FD, "seq0.4"))
    st18 = (18.0, (0.06 + 1.55 * 0.8 ** 2) / 4.0, 0.683462, 0.807045)   # thunder: U 18 m/s, StormDump 18
    h0 = render("seagale", sc, sl, U=18.0, state=0.8, frames=12, fft=os.path.join(FD, "seq0.8"), storm=st18)
    print(f"storm foam at 18 m/s vs none (fft sea): mean |change| per pixel {np.abs(h0 - c1).mean():.2f}")
    print(f"open sea, fft vs 12-wave, mean |change| per pixel: storm {np.abs(c1 - c0).mean():.2f}, fresh {np.abs(d1 - d0).mean():.2f}")
    SEA[0] = False
    # 0.10.0: the ripple field (TidesRipples, the shipped class run by RipCheck: a boat-sized body 4 s at 4 m/s along +x) under the shader
    RP = os.environ.get("TIDES_RIP")
    if RP:
        g0 = render("wake0", (14.0, 4.0, 26.0), (18.0, 0.0, 16.0), U=1.0, state=0.1, frames=1)
        g1 = render("wake", (14.0, 4.0, 26.0), (18.0, 0.0, 16.0), U=1.0, state=0.1, frames=1, rip=RP)
        g2 = render("wakenear0", (17.0, 2.2, 19.5), (19.0, 0.0, 15.5), U=1.0, state=0.1, frames=1)
        g3 = render("wakenear", (17.0, 2.2, 19.5), (19.0, 0.0, 15.5), U=1.0, state=0.1, frames=1, rip=RP)
        print(f"ripple field close, with vs without: {np.abs(g3 - g2).mean():.2f}")
        print(f"ripple field, with vs without, mean |change| per pixel: {np.abs(g1 - g0).mean():.2f}")
    # 0.10.0: the view from inside the water, and the ice look (on the sand sprite, a test view: the atlas has no ice)
    ucam, ulook = (22.0, -1.0, 34.0), (24.0, -4.0, 20.0)
    i0 = render("under0", ucam, ulook, U=3.0, sun=(0.3, 0.8, -0.5, 1.0), frames=4, bed=(0.05, 0.12, 0.2))
    i1 = render("under1", ucam, ulook, U=3.0, sun=(0.3, 0.8, -0.5, 1.0), frames=4, bed=(0.05, 0.12, 0.2), under=(1.0, 0.875, 1.0, 0.0))
    print(f"under-water look on vs off: mean |change| per pixel {np.abs(i1 - i0).mean():.2f}")
    NOWATER[0] = True
    j0 = render("ice0", (14.0, 6.0, 34.0), (20.0, -4.0, 26.0), U=3.0, sun=(0.3, 0.8, -0.5, 1.0), frames=1)
    j1 = render("ice1", (14.0, 6.0, 34.0), (20.0, -4.0, 26.0), U=3.0, sun=(0.3, 0.8, -0.5, 1.0), frames=1, ice=True)
    print(f"ice look on vs off: mean |change| per pixel {np.abs(j1 - j0).mean():.2f}")
    NOWATER[0] = False
    # 0.11.0: lightning, morning mist, the tide, biome clarity, bioluminescence, puddles, lava (test views)
    k0 = render("flash0", cam, look, U=11.0, state=0.8, frames=1); k1 = render("flash1", cam, look, U=11.0, state=0.8, frames=1, flash=1.0)
    m0 = render("mist0", cam, look, U=1.0, frames=1, sun=(0.9, 0.12, -0.4, 0.6)); m1 = render("mist1", cam, look, U=1.0, frames=1, sun=(0.9, 0.12, -0.4, 0.6), mist=1.0)
    t0 = render("tidelow", (24.0, 1.6, 44.0), (24.0, 0.6, 30.0), U=2.0, frames=1, tide=-0.25); t1 = render("tidehigh", (24.0, 1.6, 44.0), (24.0, 0.6, 30.0), U=2.0, frames=1, tide=0.25)
    c0 = render("clear_plain", (24.0, 6.0, 30.0), (24.0, 0.0, 22.0), U=1.0, frames=1, sun=(0.3, 0.8, -0.5, 1.0))
    c1 = render("clear_swamp", (24.0, 6.0, 30.0), (24.0, 0.0, 22.0), U=1.0, frames=1, sun=(0.3, 0.8, -0.5, 1.0), tint="swamp")
    c2 = render("clear_warm", (24.0, 6.0, 30.0), (24.0, 0.0, 22.0), U=1.0, frames=1, sun=(0.3, 0.8, -0.5, 1.0), tint="warm")
    b0 = render("biolum0", (14.0, 4.0, 26.0), (18.0, 0.0, 16.0), U=1.0, frames=1, sun=(-0.2, 0.6, -0.7, 0.0), tint="warm", rip=RP if RP else None)
    p0 = render("puddle0", (40.0, 4.0, 56.0), (50.0, 0.0, 48.0), U=1.0, frames=1, sun=(0.3, 0.8, -0.5, 1.0))
    p1 = render("puddle1", (40.0, 4.0, 56.0), (50.0, 0.0, 48.0), U=1.0, frames=1, sun=(0.3, 0.8, -0.5, 1.0), wetland=True)
    print(f"0.11 changes, mean |change| per pixel: flash {np.abs(k1-k0).mean():.2f}, mist {np.abs(m1-m0).mean():.2f}, tide {np.abs(t1-t0).mean():.2f}, swamp vs plain {np.abs(c1-c0).mean():.2f}, warm vs plain {np.abs(c2-c0).mean():.2f}, puddles {np.abs(p1-p0).mean():.2f}")

# 0.12.0: lava, with Minecraft's lava sprites and the lava map (TIDES_LAVA = folder with lava_still.png, lava_flow.png, lavamap.bin)
if os.environ.get("TIDES_LAVA"):
    render("lava_dummy_unmarked", cam, look, U=2.0, frames=1)   # resets
    render("lava_day", cam, look, U=2.0, frames=8, dt=2.0, lava=True, tint="white", sun=(0.3, 0.8, -0.5, 1.0))
    render("lava_night", cam, look, U=2.0, frames=8, dt=2.0, lava=True, tint="white", sun=(-0.2, 0.6, -0.7, 0.0))
    render("lava_close", (14.0, 1.6, 22.0), (17.0, 0.6, 16.0), U=2.0, frames=8, dt=1.0, lava=True, tint="white", sun=(0.3, 0.8, -0.5, 1.0))
    render("lava_shore", (8.0, 2.2, 16.0), (4.0, 0.5, 11.0), U=2.0, frames=1, lava=True, tint="white", sun=(-0.2, 0.6, -0.7, 0.0))
    render("lava_flow", (56.0, 3.0, 20.0), (52.0, 0.0, 34.0), U=2.0, frames=8, dt=0.5, lava=True, tint="white", sun=(-0.2, 0.6, -0.7, 0.0))
