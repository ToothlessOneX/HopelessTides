"""Hopeless Tides 0.9.0: the shader's tides_fftCorner (tides_fft.glsl) against the physics' FftCorner.fill, on the GPU (Mesa EGL),
from the same field pair (tools/FftDump.java). usage: python3 fft_parity.py DUMPDIR"""
import sys, os, numpy as np, moderngl
D = sys.argv[1]; SH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src/main/resources/assets/hopelesstides/shaders")
waves = open(os.path.join(SH, "tides_waves.glsl")).read(); fft = open(os.path.join(SH, "tides_fft.glsl")).read()
vs = "#version 330 core\n" + waves + "\n" + fft + """
in vec4 a_In;      // x, z, exposure, depth
in float a_Speed;  // m/s
uniform float u_S;
out vec4 o_Out;
uniform int u_W;
void main() { o_Out = tides_fftCorner(a_In.xy, u_S, u_TidesFft1.y, a_In.z, a_In.w, a_Speed);
    int i = gl_VertexID; gl_Position = vec4((float(i % u_W) + 0.5) / float(u_W) * 2.0 - 1.0, (float(i / u_W) + 0.5) / float(u_W) * 2.0 - 1.0, 0.0, 1.0); gl_PointSize = 1.0; }
"""
fs = "#version 330 core\nin vec4 o_Out; out vec4 f; void main() { f = o_Out; }\n"
W = 128   # results as pixels of a float target (transform feedback reads back zeros on this Mesa)
def run(prog, vao, n):
    fb = ctx.framebuffer(color_attachments=[ctx.texture((W, W), 4, dtype="f4")]); fb.use(); fb.clear(0, 0, 0, 0)
    prog["u_W"].value = W; vao.render(moderngl.POINTS, vertices=n)
    return np.frombuffer(fb.read(components=4, dtype="f4"), "f4").reshape(-1, 4)[:n]
ctx = moderngl.create_standalone_context(require=330, backend="egl"); ctx.enable(moderngl.PROGRAM_POINT_SIZE)
prog = ctx.program(vertex_shader=vs, fragment_shader=fs)
raw = np.fromfile(os.path.join(D, "fft.bin"), dtype="<f2").reshape(1024, 256, 4)
tex = ctx.texture((256, 1024), 4, raw.astype("f2").tobytes(), dtype="f2"); tex.filter = (moderngl.NEAREST, moderngl.NEAREST); tex.use(5)
u = [list(map(float, l.split())) for l in open(os.path.join(D, "uniforms.txt"))]
prog["u_TidesFft"].value = 5; prog["u_TidesFft0"].value = tuple(u[0]); prog["u_TidesFft1"].value = tuple(u[1]); prog["u_S"].value = 0.8
C = np.loadtxt(os.path.join(D, "corners.csv"))
inp = C[:, 0:4].astype("f4"); sp = np.where(C[:, 4] <= 1, 0.0, (C[:, 4] - 1) * 0.3).astype("f4")
b1 = ctx.buffer(inp.tobytes()); b2 = ctx.buffer(sp.tobytes())
vao = ctx.vertex_array(prog, [(b1, "4f", "a_In"), (b2, "f", "a_Speed")]); G = run(prog, vao, len(C))
print("sample shader/physics h:", [(round(float(G[i,0]),5), C[i,5]) for i in range(5)])
dh = np.abs(G[:, 0] - C[:, 5]); far = C[:, 0] > 500000
print(f"corners {len(C)}  |h| mean {np.abs(C[:, 5]).mean():.3f} max {np.abs(C[:, 5]).max():.3f}")
print(f"h: max diff {dh.max():.2e} m, mean {dh.mean():.2e}; near origin max {dh[~far].max():.2e}, x ~1e6 max {dh[far].max():.2e}")
brk = C[:, 6] > 0.5; cap = G[:, 3] > 0.5
print(f"breaking corners (physics) {brk.sum()}, shader whitecap > 0.5 at {cap.sum()}, both {np.sum(brk & cap)}")
print(f"slope: mean |s| {np.hypot(G[:, 1], G[:, 2]).mean():.3f}, max {np.hypot(G[:, 1], G[:, 2]).max():.3f}, nan {int(np.isnan(G).sum())}")
# slope check: the shader's slope against the shader's own heights one block apart (a smooth surface: they must agree roughly)
xs = np.array([[x, z, 15, 15] for x in range(0, 60) for z in (0, 1)] , "f4")
b3 = ctx.buffer(xs.tobytes()); b4 = ctx.buffer(np.zeros(len(xs), "f4").tobytes())
H = run(prog, ctx.vertex_array(prog, [(b3, "4f", "a_In"), (b4, "f", "a_Speed")]), len(xs)).reshape(60, 2, 4)
fd = (H[2:, 0, 0] - H[:-2, 0, 0]) / 2; an = H[1:-1, 0, 1]
print(f"slope x vs centred difference along a line: corr {np.corrcoef(fd, an)[0, 1]:.3f}, rms diff {np.sqrt(np.mean((fd - an) ** 2)):.3f}, rms slope {np.sqrt(np.mean(an ** 2)):.3f}")
