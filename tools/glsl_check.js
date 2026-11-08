// glsl_check.js inc_dir out.vsh out.fsh: compiles Embeddium's terrain shaders AS TIDES PATCHES THEM (tools/PatchDump output) in WebGL2:
// #import lines inlined from inc_dir, "#version 330 core" -> "#version 300 es" with highp. Prints the compile log of each. Saved tool.
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path');
function resolve(src, inc) { return src.replace(/#import <sodium:include\/([^>]+)>/g, (m, f) => resolve(fs.readFileSync(path.join(inc, f), 'utf8'), inc)); }
function es(src) { src = src.replace('clamp(uv / 256.0', 'clamp(vec2(uv) / 256.0').replace('return _get_relative_chunk_coord(pos) * vec3(16.0)', 'return vec3(_get_relative_chunk_coord(pos)) * vec3(16.0)');   // Embeddium's own implicit int->float (desktop GLSL only)
  return src.replace('#version 330 core', '#version 300 es\nprecision highp float; precision highp int; precision highp sampler2D; precision highp usampler2D; precision highp isampler2D;'); }
(async () => {
  const [inc, v, f] = process.argv.slice(2); const vs = es(resolve(fs.readFileSync(v, 'utf8'), inc)), fsrc = es(resolve(fs.readFileSync(f, 'utf8'), inc));
  const b = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-webgl', '--ignore-gpu-blocklist'] }); const p = await b.newPage();
  const r = await p.evaluate(([vs, fsrc]) => { const gl = document.createElement('canvas').getContext('webgl2'); const out = [];
    for (const [t, s, n] of [[gl.VERTEX_SHADER, vs, 'vertex'], [gl.FRAGMENT_SHADER, fsrc, 'fragment']]) { const x = gl.createShader(t); gl.shaderSource(x, s); gl.compileShader(x); out.push(n + ': ' + (gl.getShaderParameter(x, gl.COMPILE_STATUS) ? 'compiled' : 'FAILED ' + gl.getShaderInfoLog(x).slice(0, 1500))); }
    return out.join('\n'); }, [vs, fsrc]);
  console.log(r); await b.close();
})();
