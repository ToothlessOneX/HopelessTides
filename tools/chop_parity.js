// chop_parity.js surf.glsl chop.json: tides_chopCorner (GPU) against Surf.Field.chopCorner (CPU) on ChopTest's beach. Saved tool.
const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const surf = fs.readFileSync(process.argv[2], 'utf8'); const d = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
  const b = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-webgl', '--ignore-gpu-blocklist'] }); const p = await b.newPage();
  const res = await p.evaluate(([surf, d]) => {
    const c = document.createElement('canvas'); const gl = c.getContext('webgl2'); if (!gl.getExtension('EXT_color_buffer_float')) return 'no float fb';
    const vs = `#version 300 es\nprecision highp float; precision highp int;\n${surf}\nuniform vec2 u_pt[256]; out vec3 v;\nvoid main(){ float fo; vec4 r = tides_chopCorner(u_pt[gl_VertexID]); v = vec3(r.x, r.y, r.w); fo = 0.0; gl_Position = vec4((float(gl_VertexID) + 0.5) / 128.0 - 1.0, 0.0, 0.0, 1.0); gl_PointSize = 1.0; }`;
    const fsrc = `#version 300 es\nprecision highp float;\nin vec3 v; out vec4 o; void main(){ o = vec4(v, 1.0); }`;
    function sh(t, s) { const x = gl.createShader(t); gl.shaderSource(x, s); gl.compileShader(x); if (!gl.getShaderParameter(x, gl.COMPILE_STATUS)) throw gl.getShaderInfoLog(x); return x; }
    const pr = gl.createProgram(); gl.attachShader(pr, sh(gl.VERTEX_SHADER, vs)); gl.attachShader(pr, sh(gl.FRAGMENT_SHADER, fsrc)); gl.linkProgram(pr); gl.useProgram(pr);
    const tex = gl.createTexture(); gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, 128, 1280, 0, gl.RGBA, gl.FLOAT, new Float32Array(d.tex)); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    gl.uniform1i(gl.getUniformLocation(pr, 'u_TidesSurf'), 0);
    gl.uniform4f(gl.getUniformLocation(pr, 'u_TidesSurfO'), 0, 0, 0, 0); gl.uniform4f(gl.getUniformLocation(pr, 'u_TidesSurfT'), 0, 2.1, 5.0, 1); gl.uniform4fv(gl.getUniformLocation(pr, 'u_TidesChC'), d.clk); gl.uniform4fv(gl.getUniformLocation(pr, 'u_TidesChK'), d.k); gl.uniform4f(gl.getUniformLocation(pr, 'u_TidesChA'), d.L0, 1, 0, 0);
    const pts = new Float32Array(512); d.pts.forEach((q, i) => { pts[2 * i] = q[0]; pts[2 * i + 1] = q[1]; }); gl.uniform2fv(gl.getUniformLocation(pr, 'u_pt'), pts);
    const ft = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, ft); gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, 256, 1, 0, gl.RGBA, gl.FLOAT, null);
    const fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, ft, 0);
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, tex); gl.viewport(0, 0, 256, 1); gl.drawArrays(gl.POINTS, 0, d.pts.length);
    const out = new Float32Array(256 * 4); gl.readPixels(0, 0, 256, 1, gl.RGBA, gl.FLOAT, out);
    let me = 0, mw = 0, mf = 0, big = 0; d.pts.forEach((q, i) => { const e = Math.abs(out[4 * i] - q[2]), w = Math.abs(out[4 * i + 1] - q[3]), f = Math.abs(out[4 * i + 2] - q[5]); me = Math.max(me, e); mw = Math.max(mw, w); mf = Math.max(mf, f); if (e > 0.01) big++; });
    return `chop corners ${d.pts.length}: max |eta GPU - CPU| ${me.toFixed(4)} m, slope x ${mw.toFixed(4)}, weight ${mf.toFixed(4)}; eta off by over 1 cm: ${big}`;
  }, [surf, d]);
  console.log(res); await b.close();
})();
