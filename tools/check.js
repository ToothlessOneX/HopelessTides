const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const surf = fs.readFileSync(process.argv[2], 'utf8');
  const vs = `#version 300 es\nprecision highp float; precision highp int;\n${surf}\nin vec3 pos; out float f;\nvoid main(){ float fo; vec4 b = tides_surfCorner(ivec2(pos.xz), fo); f = b.x + fo; gl_Position = vec4(pos, 1.0); }`;
  const fsrc = `#version 300 es\nprecision highp float; precision highp int;\nuniform vec4 u_TidesCam; uniform sampler2D u_TidesFoam;\n#define TW_G 11.53\n#define TIDES_FRAG\n${surf}\nin float f; out vec4 o;\nvoid main(){ o = vec4(tides_swash(vec3(0.8,0.7,0.5), vec3(f), vec2(1.0)), 1.0); }`;
  const b = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-webgl', '--ignore-gpu-blocklist'] }); const p = await b.newPage();
  const r = await p.evaluate(([vs, fsrc]) => { const c = document.createElement('canvas'); const gl = c.getContext('webgl2'); if (!gl) return 'no webgl2';
    const out = []; for (const [t, s] of [[gl.VERTEX_SHADER, vs], [gl.FRAGMENT_SHADER, fsrc]]) { const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh); out.push((t === gl.VERTEX_SHADER ? 'vertex: ' : 'fragment: ') + (gl.getShaderParameter(sh, gl.COMPILE_STATUS) ? 'OK' : gl.getShaderInfoLog(sh))); }
    return out.join('\n'); }, [vs, fsrc]);
  console.log(r); await b.close();
})();
