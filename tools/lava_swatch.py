"""0.12.0: tl_glow and tl_surfaceC (tides_look.glsl) on the GPU: the colour of a lava surface from 450 to 1200 C, and of a crust by
age. usage: python3 lava_swatch.py OUT.png"""
import sys, os, numpy as np, moderngl
from PIL import Image, ImageDraw
src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src/main/resources/assets/hopelesstides/shaders/tides_look.glsl")).read()
def fn(name):
    i = src.index(name + "("); i = src.rfind("\n", 0, i) + 1; j = src.index("{", i); d = 0
    for k in range(j, len(src)):
        d += src[k] == "{"; d -= src[k] == "}"
        if d == 0: return src[i:k + 1]
fs = "#version 330 core\nconst float TL_CORE_C = 1140.0;\n" + fn("float tl_surfaceC") + "\n" + [l for l in src.splitlines() if l.startswith("const vec4 TL_GLOW")][0] + "\n" + fn("vec3 tl_glow") + """
in vec2 v; out vec4 f; void main() {
    float c = v.y > 0.5 ? mix(450.0, 1200.0, v.x) : tl_surfaceC(pow(10.0, mix(0.0, 4.0, v.x)));   // top: temperature; bottom: crust age 1 s .. 10^4 s
    vec3 lin = tl_glow(c, v.y > 0.75 || (v.y < 0.5 && v.y > 0.25) ? 1.0 : 0.0); f = vec4(pow(lin, vec3(1.0 / 2.2)), 1.0); }   // upper half of each strip: dark-adapted; lower: daylight"""
vs = "#version 330 core\nin vec2 p; out vec2 v; void main() { v = p * 0.5 + 0.5; gl_Position = vec4(p, 0.0, 1.0); }"
ctx = moderngl.create_standalone_context(require=330, backend="egl"); prog = ctx.program(vertex_shader=vs, fragment_shader=fs)
W, H = 900, 160; fb = ctx.framebuffer(color_attachments=[ctx.texture((W, H), 4)]); fb.use()
q = ctx.buffer(np.array([-1, -1, 1, -1, -1, 1, 1, 1], "f4").tobytes()); ctx.vertex_array(prog, [(q, "2f", "p")]).render(moderngl.TRIANGLE_STRIP)
im = Image.frombytes("RGBA", (W, H), fb.read(components=4)).transpose(Image.FLIP_TOP_BOTTOM).convert("RGB")
out = Image.new("RGB", (W, H + 40), (20, 20, 20)); out.paste(im, (0, 20)); d = ImageDraw.Draw(out)
for c in (525, 600, 700, 800, 900, 1000, 1140): x = int((c - 450) / 750 * W); d.line([(x, 20), (x, 28)], fill=(255, 255, 255)); d.text((x - 12, 4), f"{c}C", fill=(255, 255, 255))
for a, lab in ((1, "1 s"), (10, "10 s"), (100, "100 s"), (1000, "17 min"), (10000, "2.8 h")): x = int(np.log10(a) / 4 * (W - 1)); d.text((min(x, W - 40), H + 24), lab, fill=(255, 255, 255))
d.text((4, H // 2 + 22), "crust age ->", fill=(200, 200, 200)); out.save(sys.argv[1])
px = np.asarray(im, "f4"); print("1140 C rgb", px[10, int((1140 - 450) / 750 * W) - 1].round(), " 800 C rgb", px[10, int(350 / 750 * W)].round(), " 600 C rgb", px[10, int(150 / 750 * W)].round())
