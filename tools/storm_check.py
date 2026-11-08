"""0.10.0: the shader's storm scores (tides_look.glsl) on the GPU against the thresholds StormFoam.java found: the white share must be
Monahan's (30 % active, 70 % residual). usage: python3 storm_check.py < StormDump output"""
import sys, os, re, numpy as np, moderngl
src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src/main/resources/assets/hopelesstides/shaders/tides_look.glsl")).read()
def fn(name):
    i = src.index(name + "("); i = src.rfind("\n", 0, i) + 1; j = src.index("{", i); d = 0
    for k in range(j, len(src)):
        d += src[k] == "{"; d -= src[k] == "}"
        if d == 0: return src[i:k + 1]
vs = "#version 330 core\nuniform vec4 u_TidesStorm;\n" + fn("float tw_hash") + "\n" + fn("float tw_noise") + "\n" + fn("vec2 tw_stormScores") + """
in vec4 a_P; out vec2 o; uniform int u_W;
void main() { o = tw_stormScores(a_P.xy, a_P.z, a_P.w); int i = gl_VertexID; gl_Position = vec4((float(i % u_W) + 0.5) / float(u_W) * 2.0 - 1.0, (float(i / u_W) + 0.5) / float(u_W) * 2.0 - 1.0, 0.0, 1.0); gl_PointSize = 1.0; }"""
fs = "#version 330 core\nin vec2 o; out vec4 f; void main() { f = vec4(o, 0.0, 1.0); }"
ctx = moderngl.create_standalone_context(require=330, backend="egl"); ctx.enable(moderngl.PROGRAM_POINT_SIZE)
prog = ctx.program(vertex_shader=vs, fragment_shader=fs); W = 256; n = W * W
rng = np.random.default_rng(9)
P = np.stack([rng.uniform(-2000, 2000, n), rng.uniform(-2000, 2000, n), rng.normal(0, 1, n), rng.uniform(0, 1200, n)], 1).astype("f4")
vao = ctx.vertex_array(prog, [(ctx.buffer(P.tobytes()), "4f", "a_P")])
for line in sys.stdin:
    u, ta, tr, Wm = map(float, line.split())
    prog["u_TidesStorm"].value = (u, 1.0, ta, tr); prog["u_W"].value = W
    fb = ctx.framebuffer(color_attachments=[ctx.texture((W, W), 4, dtype="f4")]); fb.use(); fb.clear(); vao.render(moderngl.POINTS, vertices=n)
    S = np.frombuffer(fb.read(components=4, dtype="f4"), "f4").reshape(-1, 4)
    print(f"U {u:4.1f} m/s: Monahan W {Wm*100:5.2f} %  active share {np.mean(S[:, 0] > ta)*100:5.2f} % (want {30*Wm:5.2f})  residual share {np.mean(S[:, 1] > tr)*100:5.2f} % (want {70*Wm:5.2f})")
