// shore_parity.js surf.glsl shore.json: tides_shoreCorner / tides_shoreFactors (GPU, WebGL2 in headless Chromium) against
// ShoreMap.corner / factors (CPU) on ShoreTest's island. Saved tool.
const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const surf = fs.readFileSync(process.argv[2], 'utf8'); const d = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
  const b = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-webgl', '--ignore-gpu-blocklist'] }); const p = await b.newPage();
  const res = await p.evaluate(([surf, d]) => {
    const c = document.createElement('canvas'); const gl = c.getContext('webgl2'); if (!gl.getExtension('EXT_color_buffer_float')) return 'no float fb';
    const vs = `#version 300 es\nprecision highp float; precision highp int;\n${surf}\nuniform vec2 u_pt[256]; uniform int u_mode; out vec4 v;\nvoid main(){ float fo; vec2 q = u_pt[gl_VertexID]; vec4 r = tides_shoreCorner(q, 0.0, fo); vec3 f = tides_shoreFactors(q); v = u_mode == 0 ? r : vec4(fo, f.x, f.y, f.z); gl_Position = vec4((float(gl_VertexID) + 0.5) / 128.0 - 1.0, 0.0, 0.0, 1.0); gl_PointSize = 1.0; }`;
    const fsrc = `#version 300 es\nprecision highp float;\nin vec4 v; out vec4 o; void main(){ o = v; }`;
    function sh(t, s) { const x = gl.createShader(t); gl.shaderSource(x, s); gl.compileShader(x); if (!gl.getShaderParameter(x, gl.COMPILE_STATUS)) throw gl.getShaderInfoLog(x); return x; }
    const pr = gl.createProgram(); gl.attachShader(pr, sh(gl.VERTEX_SHADER, vs)); gl.attachShader(pr, sh(gl.FRAGMENT_SHADER, fsrc)); gl.linkProgram(pr); if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) return gl.getProgramInfoLog(pr); gl.useProgram(pr);
    const n = d.n, tex = gl.createTexture(); gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, n, 3 * n, 0, gl.RGBA, gl.FLOAT, new Float32Array(d.tex)); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    gl.uniform1i(gl.getUniformLocation(pr, 'u_TidesShore'), 0);
    gl.uniform4f(gl.getUniformLocation(pr, 'u_TidesShO'), 0, 0, 1000, 2000); gl.uniform4f(gl.getUniformLocation(pr, 'u_TidesShT'), d.clk, d.L0, 1, n);
    gl.uniform4f(gl.getUniformLocation(pr, 'u_TidesSurfT'), 0, 0, 0, 0);
    const pts = new Float32Array(512); d.pts.forEach((q, i) => { pts[2 * i] = q[0]; pts[2 * i + 1] = q[1]; }); gl.uniform2fv(gl.getUniformLocation(pr, 'u_pt'), pts);
    const ft = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, ft); gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, 256, 1, 0, gl.RGBA, gl.FLOAT, null);
    const fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, ft, 0);
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, tex); gl.viewport(0, 0, 256, 1);
    const out = [];
    for (let mode = 0; mode < 2; mode++) { gl.uniform1i(gl.getUniformLocation(pr, 'u_mode'), mode); gl.clear(gl.COLOR_BUFFER_BIT); gl.drawArrays(gl.POINTS, 0, d.pts.length); const o = new Float32Array(256 * 4); gl.readPixels(0, 0, 256, 1, gl.RGBA, gl.FLOAT, o); out.push(o); }
    const mx = new Array(7).fill(0); let big = 0, drawn = 0, foamy = 0;
    d.pts.forEach((q, i) => { const g = [out[0][4 * i], out[0][4 * i + 1], out[0][4 * i + 2], out[0][4 * i + 3], out[1][4 * i], out[1][4 * i + 1], out[1][4 * i + 2]];
      for (let k = 0; k < 7; k++) mx[k] = Math.max(mx[k], Math.abs(g[k] - q[2 + k])); if (Math.abs(g[0] - q[2]) > 0.01) big++; if (q[5] > 0) drawn++; if (q[6] > 0.05) foamy++; });
    return `shore corners ${d.pts.length} (${drawn} drawn, ${foamy} foaming): max |GPU - CPU| eta ${mx[0].toFixed(4)} m, slope x ${mx[1].toFixed(4)}, z ${mx[2].toFixed(4)}, weight ${mx[3].toFixed(4)}, foam ${mx[4].toFixed(4)}, lee ${mx[5].toFixed(4)}, fetch ${mx[6].toFixed(4)}; eta off by over 1 cm: ${big}`;
  }, [surf, d]);
  console.log(res); await b.close();
})();
