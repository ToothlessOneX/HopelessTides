const { chromium } = require('playwright'); const fs = require('fs');
(async () => {
  const look = fs.readFileSync(process.argv[2], 'utf8'), surf = fs.readFileSync(process.argv[3], 'utf8');
  const fsrc = `#version 300 es\nprecision highp float; precision highp int;\nuniform vec4 u_TidesRipple;\nin float tides_cap;\nin float tides_h;\nin float tides_kind;\nin vec3 tides_rel;\nin vec2 tides_world;\nin vec2 tides_slope;\nin vec2 tides_ed;\nin vec2 tides_light;\nin vec2 tides_flow;\n#define TIDES_FRAG\n${look}\n${surf}\nout vec4 o;\nvoid main(){ vec4 c = tides_look(vec4(0.2,0.3,0.6,0.8), vec3(1.0,0.0,2.0), vec2(3.0), vec2(0.0), vec2(15.0,8.0), vec2(0.5,1.0), 0.0, vec2(0.1), vec2(0.0)); o = vec4(tides_swash(c.rgb, vec3(1.0), vec2(1.0)), 1.0); }`;
  const b = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-webgl', '--ignore-gpu-blocklist'] }); const p = await b.newPage();
  const r = await p.evaluate((s) => { const gl = document.createElement('canvas').getContext('webgl2'); const sh = gl.createShader(gl.FRAGMENT_SHADER); gl.shaderSource(sh, s); gl.compileShader(sh); return gl.getShaderParameter(sh, gl.COMPILE_STATUS) ? 'OK' : gl.getShaderInfoLog(sh); }, fsrc);
  console.log(r); await b.close();
})();
