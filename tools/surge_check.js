// surge_check.js surf.glsl swell.json: tides_swellSurge (GPU) against linear wave theory. Beyond the field (plane train, deep water) the
// RMS horizontal excursion over many clocks at depths 1-20 m must be sqrt(sum (a e^(-k z))^2 / 2) (Airy); inside the field it must fall
// with depth and be 0 below the bed. Saved tool.
const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const surf = fs.readFileSync(process.argv[2], 'utf8'); const d = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
  const b = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-webgl', '--ignore-gpu-blocklist'] }); const p = await b.newPage();
  const res = await p.evaluate(([surf, d]) => {
    const gl = document.createElement('canvas').getContext('webgl2'); gl.getExtension('EXT_color_buffer_float');
    const vs = `#version 300 es\nprecision highp float; precision highp int;\n${surf}\nuniform vec3 u_q[64]; out vec3 v;\nvoid main(){ vec3 q = u_q[gl_VertexID]; v = vec3(tides_swellSurge(q.xy, q.z), 0.0); gl_Position = vec4((float(gl_VertexID) + 0.5) / 32.0 - 1.0, 0.0, 0.0, 1.0); gl_PointSize = 1.0; }`;
    const fsrc = `#version 300 es\nprecision highp float;\nin vec3 v; out vec4 o; void main(){ o = vec4(v, 1.0); }`;
    function sh(t, s) { const x = gl.createShader(t); gl.shaderSource(x, s); gl.compileShader(x); if (!gl.getShaderParameter(x, gl.COMPILE_STATUS)) throw gl.getShaderInfoLog(x); return x; }
    const pr = gl.createProgram(); gl.attachShader(pr, sh(gl.VERTEX_SHADER, vs)); gl.attachShader(pr, sh(gl.FRAGMENT_SHADER, fsrc)); gl.linkProgram(pr); gl.useProgram(pr);
    const tex = gl.createTexture(); gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, 128, 768, 0, gl.RGBA, gl.FLOAT, new Float32Array(d.tex)); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    const U = n => gl.getUniformLocation(pr, n); gl.uniform1i(U('u_TidesSurf'), 0); gl.uniform4f(U('u_TidesSurfO'), 0, 0, 0, 0); gl.uniform4f(U('u_TidesSurfT'), 0, 2.1, 5, 1);
    gl.uniform4f(U('u_TidesSwO'), 1, d.share, 1000, 2000); gl.uniform4fv(U('u_TidesSwK'), d.k); gl.uniform4fv(U('u_TidesSwD'), d.d); gl.uniform4f(U('u_TidesSwA'), d.amp, d.unit, 0.888, 0);
    const ft = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, ft); gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, 64, 1, 0, gl.RGBA, gl.FLOAT, null);
    const fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, ft, 0); gl.viewport(0, 0, 64, 1);
    const depths = [1, 2, 5, 10, 20]; const out = [];
    // points: 0-4 outside (-60, 30) at each depth; 5-9 inside front of the island (36, 64) at each depth (bed 12 m)
    const q = new Float32Array(64 * 3); depths.forEach((z, i) => { q.set([-60, 30, 0.888 - z], 3 * i); q.set([36, 64, 0.888 - z], 3 * (5 + i)); });
    gl.uniform3fv(U('u_q'), q); const acc = new Array(10).fill(0); const N = 400; const o = new Float32Array(64 * 4);
    for (let s = 0; s < N; s++) { const c = d.k.map((k, i) => Math.sqrt(k * 9.81) * (s * 0.173 + i * 0.01)); gl.uniform4fv(U('u_TidesSwC'), c); gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.drawArrays(gl.POINTS, 0, 10); gl.readPixels(0, 0, 64, 1, gl.RGBA, gl.FLOAT, o); for (let i = 0; i < 10; i++) acc[i] += o[4 * i] ** 2 + o[4 * i + 1] ** 2; }
    depths.forEach((z, i) => { const rms = Math.sqrt(acc[i] / N), want = Math.sqrt(d.k.reduce((t, k) => t + (d.amp * Math.exp(-k * z)) ** 2 / 2, 0)); out.push(`outside, ${z} m down: RMS excursion ${rms.toFixed(4)} m, Airy ${want.toFixed(4)} m (${(100 * Math.abs(rms - want) / want).toFixed(1)} % off)`); });
    depths.forEach((z, i) => out.push(`inside (12 m water), ${z} m down: RMS excursion ${Math.sqrt(acc[5 + i] / N).toFixed(4)} m`));
    return out.join('\n');
  }, [surf, d]);
  console.log(res); await b.close();
})();
