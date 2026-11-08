"""Runs the generated GLSL wave model on the GPU path (Mesa, headless) over the Java model's inputs and compares the answers."""
import numpy as np, moderngl, sys
rows = np.loadtxt(sys.argv[1]); glsl = open(sys.argv[2]).read()
ctx = moderngl.create_context(standalone=True, backend='egl')
src = '#version 430\nlayout(local_size_x=64) in;\n' + glsl + '''
struct In { float x, z, frac, st, e, d, t; int day; };
layout(std430, binding=0) buffer I { In inp[]; };
layout(std430, binding=1) buffer O { vec4 outv[]; };
layout(std430, binding=2) buffer O2 { vec2 outw[]; };
void main() { uint i = gl_GlobalInvocationID.x; if (i >= inp.length()) return; In q = inp[i];
  float w = tides_wind(q.day, q.frac); float cave = tides_seaStateCave(q.day, q.frac);
  vec3 h = tides_surface(vec2(q.x, q.z), q.t, q.st, w, q.e, q.d); outv[i] = vec4(h, 0.0); outw[i] = vec2(w, cave); }'''
cs = ctx.compute_shader(src)
n = len(rows)
inp = np.zeros(n, dtype=[('x', 'f4'), ('z', 'f4'), ('frac', 'f4'), ('st', 'f4'), ('e', 'f4'), ('d', 'f4'), ('t', 'f4'), ('day', 'i4')])
inp['x'], inp['z'], inp['day'], inp['frac'], inp['st'], inp['e'], inp['d'], inp['t'] = rows[:, 0], rows[:, 1], rows[:, 2], rows[:, 3], rows[:, 4], rows[:, 5], rows[:, 6], rows[:, 9 + 0]
inp['t'] = rows[:, 9]
bi = ctx.buffer(inp.tobytes()); bo = ctx.buffer(reserve=n * 16); bw = ctx.buffer(reserve=n * 8)
bi.bind_to_storage_buffer(0); bo.bind_to_storage_buffer(1); bw.bind_to_storage_buffer(2); cs.run((n + 63) // 64)
o = np.frombuffer(bo.read(), 'f4').reshape(n, 4); w = np.frombuffer(bw.read(), 'f4').reshape(n, 2)
dw = np.abs(np.angle(np.exp(1j * (w[:, 0] - rows[:, 7])))); dc = np.abs(w[:, 1] - rows[:, 8]); dh = np.abs(o[:, 0] - rows[:, 10]); ds = np.abs(o[:, 1] - rows[:, 11]) + np.abs(o[:, 2] - rows[:, 12])
amp = np.abs(rows[:, 10])
print('samples %d | wind max error %.2e rad | cave state max error %.2e | height max error %.4f blocks (mean %.5f, 99.9%% %.4f; largest height %.2f) | slope max error %.4f' % (n, dw.max(), dc.max(), dh.max(), dh.mean(), np.quantile(dh, 0.999), amp.max(), ds.max()))
k = np.argmax(dh); print('worst case: x %.0f z %.0f t %.1f java %.4f glsl %.4f' % (rows[k, 0], rows[k, 1], rows[k, 9], rows[k, 10], o[k, 0]))
