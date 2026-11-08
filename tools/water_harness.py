"""Hopeless Tides water harness: runs Embeddium's own terrain shaders, patched exactly as the game patches them (PatchDump ->
TidesRender.patchVertexShader), on a headless OpenGL context (Mesa llvmpipe), over a water mesh whose vertex colours carry exposure
and depth the way FluidRendererMixin packs them.
 measure: the height range of the water-surface vertices over 4 s of time, per water body and sea state (transform feedback of the
          lifted position);
 frames:  renders N frames of the scene (vanilla water texture, biome water tint) to PNGs and a GIF.
usage: python3 water_harness.py PATCHED.vsh EMB_SHADER_DIR WATER_STILL.png OUTDIR"""
import sys, os, re, math
import numpy as np, moderngl
from PIL import Image

PATCHED, EMB, TEX, OUT = sys.argv[1:5]
PFS = os.environ.get("PFS")   # the patched fragment shader (0.6.0), else Embeddium's own
os.makedirs(OUT, exist_ok=True)

def resolve(src):
    def inc(m): return open(os.path.join(EMB, "include", m.group(1))).read() + "\n"
    return re.sub(r"#import <sodium:include/([a-z_]+\.glsl)>", inc, src)
def defines(src, ds):
    lines = src.split("\n"); return "\n".join([lines[0]] + ["#define " + d for d in ds] + lines[1:])
VS = defines(resolve(open(PATCHED).read()), ["USE_FOG"])
FS = defines(resolve(open(PFS or os.path.join(EMB, "blocks/block_layer_opaque.fsh")).read()), ["USE_FOG"])

ctx = moderngl.create_standalone_context(require=330, backend="egl")
prog = ctx.program(vertex_shader=VS, fragment_shader=FS)
print("program linked; uniforms:", sorted(k for k in prog if k.startswith("u_Tides")))

# ---- the exposure/depth packing, as TidesRender.encode (ABGR int -> here RGBA bytes)
def encode(rgba, exposure, depth):
    r, g, b, a = rgba
    r = (r & ~7) | (exposure & 7); g = (g & ~7) | ((exposure >> 3) & 1) | ((depth & 3) << 1); b = (b & ~7) | ((depth >> 2) & 3)
    return (r, g, b, a)

# ---- a scene: a lake 40 x 40 with open middle and shores, and a stream 4 wide beside it
N = 64
def body(i, j):
    """(is water, exposure 0..15, depth 0..15) for corner (i, j): lake in i 4..44, j 10..50; stream i 50..54."""
    if 4 <= i <= 44 and 10 <= j <= 50:
        edge = min(i - 4, 44 - i, j - 10, 50 - j); e = min(15, int(2 + edge * 0.9)); d = min(15, 1 + edge // 2); return True, e, d
    if 50 <= i <= 54: return True, 3, 2
    return False, 0, 0

TINT = (63, 118, 228, 255)        # vanilla plains water colour
verts = []
for j in range(N):
    for i in range(N):
        cells = [body(i, j), body(i + 1, j), body(i + 1, j + 1), body(i, j + 1)]
        if not all(c[0] for c in cells): continue
        quad = [(i, j), (i + 1, j), (i + 1, j + 1), (i, j + 1)]
        for k in (0, 1, 2, 0, 2, 3):
            x, z = quad[k]; _, e, d = body(x, z)
            r, g, b, a = encode(TINT, e, d)
            u, v = (x % 1, z % 1) if False else ((quad[k][0] - i), (quad[k][1] - j))
            if i >= 50: u, v = u + 5, v + 5                     # the stream is flowing water: the water_flow sprite's place (UV1)
            verts.append((x, 0.875, z, r / 255, g / 255, b / 255, a / 255, u, v))
V = np.array(verts, "f4")

def light_coord(sky): return np.uint32(((sky * 16) << 24) | (0 << 16) | 0)   # (light >> 16): block (bits 0-7) , sky (bits 8-15); draw params 0

def run(sky_light, t_day_ticks, sea_override=None, cam=(32.0, 70.0, 32.0)):
    """Returns the lifted y of every water vertex (camera-relative position.y + cam y)."""
    tf = ctx.program(vertex_shader=VS.replace("void main() {", "out float tf_y;\nvoid main() {", 1).replace("gl_Position = u_ProjectionMatrix", "tf_y = position.y + u_TidesCam.y;\n    gl_Position = u_ProjectionMatrix", 1),
                     varyings=["tf_y"])
    return tf

lc = light_coord(15)
def buffers(sky):
    lcv = np.full(len(V), ((sky * 16) << 8) << 16, "u4")
    pos = V[:, 0:3].copy(); col = V[:, 3:7].copy(); uv = V[:, 7:9].copy()
    return pos, col, uv, lcv

def uniforms(p, cam, t_sec, sky_state, cave_state, wind):
    def put(n, v):
        if n in p: p[n].value = v
    put("u_TidesCam", (cam[0], cam[1], cam[2], 1.0)); put("u_TidesWave", (t_sec, sky_state, cave_state, wind))
    put("u_TidesUV0", (-0.001, -0.001, 1.001, 1.001)); put("u_TidesUV1", (5.0, 5.0, 6.0, 6.0))
    put("u_RegionOffset", (-cam[0], -cam[1], -cam[2]))
    put("u_TidesRipple", (t_sec, RIP[0], RIP[1], wind))

MW = 512
RIP = [float(os.environ.get("RIP", "0.12")), max(0.75, float(os.environ.get("RIP", "0.12")))]
def measure(sky, state, label):
    """The lifted height of every water vertex, read back by drawing each vertex as one point of a float texture
    (transform feedback does not work on this Mesa build)."""
    vs = VS.replace("void main() {", "out float tf_y;\nvoid main() {", 1).replace("gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);",
        "tf_y = position.y + u_TidesCam.y;\n    gl_Position = vec4((float(gl_VertexID %% %d) + 0.5) / %d.0 * 2.0 - 1.0, (float(gl_VertexID / %d) + 0.5) / float(ROWS) * 2.0 - 1.0, 0.0, 1.0); gl_PointSize = 1.0;" % (MW, MW, MW), 1).replace("ROWS", str((len(V) + MW - 1) // MW))
    fs = "#version 330 core\nin float tf_y; out vec4 o; void main() { o = vec4(tf_y, 0.0, 0.0, 1.0); }"
    mp = ctx.program(vertex_shader=vs, fragment_shader=fs)
    pos, col, uv, lcv = buffers(15 if sky else 0)
    vb = [ctx.buffer(pos.tobytes()), ctx.buffer(col.tobytes()), ctx.buffer(uv.tobytes()), ctx.buffer(lcv.tobytes())]
    fmt = [f for f in [(vb[0], "3f", "a_PosId"), (vb[1], "4f", "a_Color"), (vb[2], "2f", "a_TexCoord"), (vb[3], "u4", "a_LightCoord")] if f[2] in mp]
    vao = ctx.vertex_array(mp, fmt)
    rows = (len(V) + MW - 1) // MW; tex = ctx.texture((MW, rows), 1, dtype="f4"); fb = ctx.framebuffer([tex]); fb.use(); ctx.disable(moderngl.DEPTH_TEST)
    lake = np.array([4 <= v[0] <= 44 for v in V]); open_ = np.array([14 <= v[0] <= 34 and 20 <= v[2] <= 40 for v in V]); stream = ~lake
    hi = {"open lake": [], "lake shore": [], "stream": []}
    for t in np.linspace(0, 4, 17):
        uniforms(mp, (32.0, 70.0, 32.0), float(t), state if sky else 0.1, state if not sky else 0.1, 0.7)
        fb.clear(); vao.render(moderngl.POINTS)
        y = np.frombuffer(tex.read(), "f4")[:len(V)] - 0.875
        hi["open lake"].append(y[open_]); hi["lake shore"].append(y[lake & ~open_]); hi["stream"].append(y[stream])
    res = {k: (float(np.concatenate(v).min()), float(np.concatenate(v).max())) for k, v in hi.items()}
    print(f"{label:34s} " + "  ".join(f"{k}: {a:+.3f}..{b:+.3f} ({16 * (b - a):.1f} px)" for k, (a, b) in res.items()))
    return res

if __name__ == "__main__":
    for sky, s, lab in ((1, 0.10, "surface, clear (sea state 0.10)"), (1, 0.50, "surface, rain (0.50)"), (1, 1.0, "surface, thunder (1.0)"),
                        (0, 0.06, "cave, calm spell (0.06)"), (0, 0.31, "cave, middle (0.31)"), (0, 0.56, "cave, rough spell (0.56)")):
        measure(sky, s, lab)
    # frames
    tex = Image.open(TEX).convert("RGBA"); tex = tex.crop((0, 0, tex.width, tex.width))
    t = ctx.texture(tex.size, 4, tex.tobytes()); t.filter = (moderngl.NEAREST, moderngl.NEAREST); t.repeat_x = t.repeat_y = True
    lm = ctx.texture((16, 16), 4, bytes([255] * 16 * 16 * 4)); lm.filter = (moderngl.LINEAR, moderngl.LINEAR)
    fbo = ctx.simple_framebuffer((640, 400)); fbo.use()
    pos, col, uv, lcv = buffers(0)
    vbo = [ctx.buffer(pos.tobytes()), ctx.buffer(col.tobytes()), ctx.buffer(uv.tobytes()), ctx.buffer(lcv.tobytes())]
    vao = ctx.vertex_array(prog, [(vbo[0], "3f", "a_PosId"), (vbo[1], "4f", "a_Color"), (vbo[2], "2f", "a_TexCoord"), (vbo[3], "u4", "a_LightCoord")])
    cam = (24.0, 3.5, 44.0); look = (30.0, 0.0, 22.0)
    def lookat(eye, tgt):
        f = np.array(tgt) - np.array(eye); f /= np.linalg.norm(f); s = np.cross(f, [0, 1, 0]); s /= np.linalg.norm(s); u = np.cross(s, f)
        m = np.identity(4); m[0, :3] = s; m[1, :3] = u; m[2, :3] = -f; return m          # camera at origin (positions are camera-relative)
    fov = math.radians(70); f = 1 / math.tan(fov / 2); asp = 640 / 400; n_, fa = 0.05, 300
    P = np.array([[f / asp, 0, 0, 0], [0, f, 0, 0], [0, 0, (fa + n_) / (n_ - fa), 2 * fa * n_ / (n_ - fa)], [0, 0, -1, 0]])
    prog["u_ProjectionMatrix"].write(P.T.astype("f4").tobytes()); prog["u_ModelViewMatrix"].write(lookat(cam, look).T.astype("f4").tobytes())
    prog["u_BlockTex"].value = 0; prog["u_LightTex"].value = 1; t.use(0); lm.use(1)
    for k in ("u_FogStart", "u_FogEnd"): prog[k].value = 200.0 if k == "u_FogStart" else 300.0
    prog["u_FogColor"].value = (0.6, 0.7, 0.9, 1.0); prog["u_FogShape"].value = 0
    state = float(sys.argv[5]) if len(sys.argv) > 5 else 0.31
    frames = []
    for i in range(24):
        uniforms(prog, cam, i * 0.125, 0.1, state, 0.7)
        fbo.clear(0.08, 0.09, 0.11, 1.0); ctx.enable(moderngl.DEPTH_TEST); vao.render(moderngl.TRIANGLES)
        im = Image.frombytes("RGBA", fbo.size, fbo.read(components=4)).transpose(Image.FLIP_TOP_BOTTOM).convert("RGB"); frames.append(im)
    frames[0].save(os.path.join(OUT, "frame0.png")); frames[12].save(os.path.join(OUT, "frame12.png"))
    frames[0].save(os.path.join(OUT, "water.gif"), save_all=True, append_images=frames[1:], duration=125, loop=0)
    arr = np.stack([np.asarray(f, "f4") for f in frames]); water = arr[0][:, :, 2] > arr[0][:, :, 0] + 40
    flick = arr.std(axis=0).mean(axis=2)[water].mean(); diff = np.abs(arr[0] - arr[12]).mean(axis=2)[water].mean()
    print(f"frames: water pixels {water.sum()}, mean change t=0 -> 1.5 s {diff:.2f}/255, mean per-pixel std over 3 s {flick:.2f}/255")
