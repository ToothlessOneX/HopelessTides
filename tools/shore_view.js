// shore_view.js dataDir outDir [radius] [focusX focusZ]: draws ShoreView's data OUT OF THE GAME with the mod's own wave shaders
// (tides_waves.glsl, tides_fft.glsl, tides_surf.glsl, read from the mod's resources) in WebGL2 (headless Chromium). Left: the shore map
// off (0.16.3: beyond the surf field the sea ran through land). Right: on (0.17.0). Writes frame_NN.png; make a GIF with ffmpeg.
// The vertex heights, slopes and foam are the shader's own numbers for each block corner, composed as TidesRender's vertex patch does
// beyond the 128-block surf field (Fourier wind sea x fetch, + plane swell x lee, -> shore train in shallow water). Simplified, and said
// on the image: the mesher's per-corner exposure (15 = open), the look shader's lighting (a plain sky reflection, the shader's foam value
// as white), the water drawn opaque with the bed's colour mixed in by depth. Saved tool.
const { chromium } = require('playwright'); const fs = require('fs'); const path = require('path');
const SH = path.join(__dirname, '../src/main/resources/assets/hopelesstides/shaders');
(async () => {
  const [dataDir, outDir] = process.argv.slice(2); const R = +(process.argv[4] || 96); fs.mkdirSync(outDir, { recursive: true });
  const meta = JSON.parse(fs.readFileSync(path.join(dataDir, 'meta.json'), 'utf8'));
  const fx = process.argv[5] ? +process.argv[5] - meta.ox : meta.n / 2, fz = process.argv[6] ? +process.argv[6] - meta.oz : meta.n / 2;
  const glsl = ['tides_waves.glsl', 'tides_fft.glsl', 'tides_surf.glsl'].map(f => fs.readFileSync(path.join(SH, f), 'utf8')).join('\n');
  const terrain = fs.readFileSync(path.join(dataDir, 'terrain.bin')).toString('base64'), shore = fs.readFileSync(path.join(dataDir, 'shore.bin')).toString('base64');
  const ffts = meta.fft.map((_, m) => fs.readFileSync(path.join(dataDir, `fft_${m}.bin`)).toString('base64'));
  const b = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-webgl', '--ignore-gpu-blocklist'] }); const p = await b.newPage();
  p.on('console', m => console.log('page:', m.text()));
  const frames = await p.evaluate(([glsl, meta, terrainB, shoreB, fftsB, R, fx, fz]) => {
    const dec = s => { const bin = atob(s); const u = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return u.buffer; };
    const n = meta.n, tb = dec(terrainB), top = new Int16Array(tb, 0, n * n), mat = new Uint8Array(tb, n * n * 2, n * n), shore = new Float32Array(dec(shoreB));
    const W = 720, H = 480, cv = document.createElement('canvas'); cv.width = W; cv.height = H; const gl = cv.getContext('webgl2', { preserveDrawingBuffer: true, antialias: true });
    function sh(t, s) { const x = gl.createShader(t); gl.shaderSource(x, s); gl.compileShader(x); if (!gl.getShaderParameter(x, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(x) + '\n' + s.split('\n').slice(0, 5).join('\n')); return x; }
    function prog(v, f) { const pr = gl.createProgram(); gl.attachShader(pr, sh(gl.VERTEX_SHADER, v)); gl.attachShader(pr, sh(gl.FRAGMENT_SHADER, f)); gl.linkProgram(pr); if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(pr)); return pr; }
    const head = '#version 300 es\nprecision highp float; precision highp int; precision highp sampler2D;\n';
    // the water: one vertex per block corner of a water column; the shader composes the sea as TidesRender's vertex patch does
    const waterVS = head + glsl + `
uniform mat4 u_mvp; uniform int u_shoreOn; uniform float u_sea, u_s;
in vec2 a_corner; in float a_depth; out vec3 v_n; out float v_foam, v_depth; out vec3 v_pos;
void main() {
  vec2 P = a_corner; float dep = a_depth;
  vec4 F = tides_fftCorner(P, u_s, u_TidesFft1.y, 15.0, clamp(dep, 0.0, 15.0), 0.0);
  vec3 sf = tides_shoreFactors(P); if (u_shoreOn == 1 && sf.z > 0.5) F.xyz *= sf.y;
  vec3 S = F.xyz; float cap = F.w;
  vec3 tsw = tides_swellCorner(P); if (u_shoreOn == 1) tsw *= sf.x; S += tsw;
  if (u_shoreOn == 1) { float f; vec4 B = tides_shoreCorner(P, 0.0, f); if (B.w > 0.0) { S = mix(S, B.xyz, B.w); cap = max(cap, f); } }
  vec3 pos = vec3(P.x, u_sea + S.x, P.y); v_pos = pos; v_n = normalize(vec3(-S.y, 1.0, -S.z)); v_foam = cap; v_depth = dep;
  gl_Position = u_mvp * vec4(pos, 1.0);
}`;
    const waterFS = head + `
in vec3 v_n; in float v_foam, v_depth; in vec3 v_pos; uniform vec3 u_eye, u_sun; uniform int u_map; uniform float u_seaTop, u_hScale; out vec4 o;
void main() {
  if (u_map == 1) { float e = clamp((v_pos.y - u_seaTop) / u_hScale, -1.0, 1.0); vec3 lo = vec3(0.02, 0.10, 0.30), mid = vec3(0.10, 0.35, 0.62), hi = vec3(0.75, 0.90, 1.0); vec3 c = e < 0.0 ? mix(mid, lo, -e) : mix(mid, hi, e); c = mix(c, vec3(1.0), clamp(v_foam, 0.0, 1.0)); o = vec4(c, 1.0); return; }
  vec3 N = normalize(v_n), V = normalize(u_eye - v_pos), L = normalize(u_sun);
  float fres = 0.02 + 0.98 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
  vec3 R = reflect(-V, N); vec3 sky = mix(vec3(0.62, 0.74, 0.88), vec3(0.25, 0.45, 0.78), clamp(R.y, 0.0, 1.0));
  vec3 bed = vec3(0.80, 0.74, 0.55) * (0.55 + 0.45 * max(N.y, 0.0)); vec3 deep = vec3(0.04, 0.16, 0.30);
  vec3 body = mix(bed * vec3(0.55, 0.8, 0.85), deep, 1.0 - exp(-v_depth / 2.5)) * (0.55 + 0.6 * max(dot(N, L), 0.0));
  vec3 c = mix(body, sky, fres) + vec3(1.0, 0.95, 0.85) * pow(max(dot(R, L), 0.0), 120.0) * 0.8;
  float foam = clamp(v_foam, 0.0, 1.0); c = mix(c, vec3(0.93, 0.95, 0.96) * (0.6 + 0.4 * max(dot(N, L), 0.0)), foam);
  o = vec4(pow(c, vec3(1.0 / 1.15)), 1.0);
}`;
    const landVS = head + `uniform mat4 u_mvp; in vec3 a_pos; in vec3 a_col; out vec3 v_col; void main(){ v_col = a_col; gl_Position = u_mvp * vec4(a_pos, 1.0); }`;
    const landFS = head + `in vec3 v_col; out vec4 o; void main(){ o = vec4(v_col, 1.0); }`;
    const pw = prog(waterVS, waterFS), pl = prog(landVS, landFS);
    // textures: the shore map (unit 1), the Fourier pair (unit 0)
    const shoreTex = gl.createTexture(); gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, shoreTex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, n, 3 * n, 0, gl.RGBA, gl.FLOAT, shore); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    const fftTex = gl.createTexture(); gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, fftTex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, 256, 1024, 0, gl.RGBA, gl.HALF_FLOAT, null); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    const fftData = fftsB.map(s => new Uint16Array(dec(s)));
    // geometry round the focus
    const x0 = Math.max(1, Math.round(fx - R)), x1 = Math.min(n - 2, Math.round(fx + R)), z0 = Math.max(1, Math.round(fz - R)), z1 = Math.min(n - 2, Math.round(fz + R));
    const MATCOL = { SAND: [0.86, 0.80, 0.58], GRAVEL: [0.55, 0.53, 0.52], GRASS: [0.36, 0.58, 0.25], DIRT: [0.47, 0.34, 0.22], STONE: [0.5, 0.5, 0.5], CLAY: [0.6, 0.62, 0.68], MUD: [0.3, 0.27, 0.25], SNOW_BLOCK: [0.95, 0.96, 0.98], SANDSTONE: [0.85, 0.78, 0.6], PODZOL: [0.4, 0.3, 0.2], CALCITE: [0.9, 0.9, 0.88], RED_SAND: [0.75, 0.42, 0.2], TERRACOTTA: [0.6, 0.38, 0.28] };
    const col = k => MATCOL[meta.mats[mat[k]]] || [0.5, 0.5, 0.5];
    const L = [], C = []; const sea = meta.sea, seaTop = sea + 0.888;
    function quad(a, b2, c, d, cl) { for (const v of [a, b2, c, a, c, d]) { L.push(...v); C.push(...cl); } }
    let landN = [0, 0], waterN = [0, 0], nl = 0, nw = 0;
    for (let z = z0; z < z1; z++) for (let x = x0; x < x1; x++) { const k = x + n * z, y = top[k] + 1, cl = col(k), wet = top[k] < sea;
      const shade = wet ? 0.85 : 1; quad([x, y, z], [x + 1, y, z], [x + 1, y, z + 1], [x, y, z + 1], cl.map(v => v * shade));
      if (wet) { waterN[0] += x; waterN[1] += z; nw++; } else { landN[0] += x; landN[1] += z; nl++; }
      for (const [dx, dz, f] of [[1, 0, 0.75], [-1, 0, 0.7], [0, 1, 0.62], [0, -1, 0.8]]) { const q = (x + dx) + n * (z + dz), yq = top[q] + 1; if (yq >= y) continue;
        const ax = dx === 1 ? x + 1 : x, az = dz === 1 ? z + 1 : z, bx = dx === -1 ? x : dx === 1 ? x + 1 : x + 1, bz = dz === 1 ? z + 1 : dz === -1 ? z : z + 1;
        const ex = dx === 0 ? x : ax, ez = dz === 0 ? z : az; const fx2 = dx === 0 ? x + 1 : ax, fz2 = dz === 0 ? z : (dz === 1 ? z + 1 : z);
        const p1 = dx !== 0 ? [ax, y, z] : [x, y, az], p2 = dx !== 0 ? [ax, y, z + 1] : [x + 1, y, az];
        quad(p1, p2, [p2[0], yq, p2[2]], [p1[0], yq, p1[2]], cl.map(v => v * f * shade)); } }
    const landBuf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, landBuf); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(L), gl.STATIC_DRAW);
    const landCol = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, landCol); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(C), gl.STATIC_DRAW);
    const WV = [], WD = [];
    for (let z = z0; z < z1; z++) for (let x = x0; x < x1; x++) { const k = x + n * z; if (top[k] >= sea) continue; const d = seaTop - (top[k] + 1);
      for (const [a, c] of [[0, 0], [1, 0], [1, 1], [0, 0], [1, 1], [0, 1]]) { WV.push(x + a, z + c); WD.push(d); } }
    const wBuf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, wBuf); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(WV), gl.STATIC_DRAW);
    const dBuf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, dBuf); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(WD), gl.STATIC_DRAW);
    // camera: on the land side of the focus, looking out to sea, high as in a flying view
    let dx = nw && nl ? waterN[0] / nw - landN[0] / nl : 1, dz = nw && nl ? waterN[1] / nw - landN[1] / nl : 0; const dl = Math.hypot(dx, dz) || 1; dx /= dl; dz /= dl;
    const tgt = [fx + dx * R * 0.25, seaTop, fz + dz * R * 0.25], eye = [tgt[0] - dx * R * 0.95, seaTop + R * 0.55, tgt[2] - dz * R * 0.95];
    function look(e, t) { const f = [t[0] - e[0], t[1] - e[1], t[2] - e[2]]; const fl = Math.hypot(...f); f.forEach((v, i) => f[i] = v / fl); let s = [f[1] * 0 - f[2] * 1, f[2] * 0 - f[0] * 0, f[0] * 1 - f[1] * 0]; const sl = Math.hypot(...s); s = s.map(v => v / sl); const u = [s[1] * f[2] - s[2] * f[1], s[2] * f[0] - s[0] * f[2], s[0] * f[1] - s[1] * f[0]];
      return [s[0], u[0], -f[0], 0, s[1], u[1], -f[1], 0, s[2], u[2], -f[2], 0, -(s[0] * e[0] + s[1] * e[1] + s[2] * e[2]), -(u[0] * e[0] + u[1] * e[1] + u[2] * e[2]), f[0] * e[0] + f[1] * e[1] + f[2] * e[2], 1]; }
    function persp(fov, a, nr, fr) { const t = 1 / Math.tan(fov / 2); return [t / a, 0, 0, 0, 0, t, 0, 0, 0, 0, (fr + nr) / (nr - fr), -1, 0, 0, 2 * fr * nr / (nr - fr), 0]; }
    function mul(a, b2) { const r = new Array(16).fill(0); for (let i = 0; i < 4; i++) for (let j = 0; j < 4; j++) for (let k = 0; k < 4; k++) r[j * 4 + i] += a[k * 4 + i] * b2[j * 4 + k]; return r; }
    const mvp = mul(persp(1.05, W / H, 0.5, 2000), look(eye, tgt));
    // straight down onto the square (x right, z down), the same blocks
    const sx = 2 / (x1 - x0), sz = 2 / (z1 - z0) * W / H; const ortho = [sx * H / W * (x1 - x0) / (z1 - z0) > 0 ? 2 / (x1 - x0) * Math.min(1, (z1 - z0) / (x1 - x0) * W / H) / (W / H) * (W / H) : 0, 0, 0, 0, 0, 0, -0.001, 0, 0, -2 / (z1 - z0) * Math.min(1, (z1 - z0) / (x1 - x0) * W / H), 0, 0, 0, 0, 0, 1];
    { const s0 = Math.min(W / (x1 - x0), H / (z1 - z0)), ax = 2 * s0 / W, az = 2 * s0 / H; ortho[0] = ax; ortho[9] = -az; ortho[12] = -ax * (x0 + x1) / 2; ortho[13] = az * (z0 + z1) / 2; ortho[10] = 0; ortho[6] = -0.01; ortho[14] = 0; }
    const out = document.createElement('canvas'); out.width = W * 2 + 10; out.height = 2 * H + 84; const ctx = out.getContext('2d');
    const U = (pr, nm) => gl.getUniformLocation(pr, nm); const res = [];
    for (let fi = 0; fi < meta.frames.length; fi++) { const fr = meta.frames[fi];
      // the pair (slot k, k + 1): even slot rows 0-511, odd 512-1023, each A then B
      const slotA = fi, slotB = fi + 1, kA = Math.round(fr.t / 0.25);
      gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, fftTex);
      gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, (kA & 1) * 512, 256, 512, gl.RGBA, gl.HALF_FLOAT, fftData[slotA]); gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, ((kA + 1) & 1) * 512, 256, 512, gl.RGBA, gl.HALF_FLOAT, fftData[slotB]);
      ctx.fillStyle = '#0e121a'; ctx.fillRect(0, 0, out.width, out.height);
      for (let side = 0; side < 2; side++) {
        gl.viewport(0, 0, W, H); gl.clearColor(0.62, 0.74, 0.88, 1); gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT); gl.enable(gl.DEPTH_TEST);
        gl.useProgram(pl); gl.uniformMatrix4fv(U(pl, 'u_mvp'), false, mvp);
        let loc = gl.getAttribLocation(pl, 'a_pos'); gl.bindBuffer(gl.ARRAY_BUFFER, landBuf); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 3, gl.FLOAT, false, 0, 0);
        let lc = gl.getAttribLocation(pl, 'a_col'); gl.bindBuffer(gl.ARRAY_BUFFER, landCol); gl.enableVertexAttribArray(lc); gl.vertexAttribPointer(lc, 3, gl.FLOAT, false, 0, 0);
        gl.drawArrays(gl.TRIANGLES, 0, L.length / 3); gl.disableVertexAttribArray(loc); gl.disableVertexAttribArray(lc);
        gl.useProgram(pw); gl.uniformMatrix4fv(U(pw, 'u_mvp'), false, mvp); gl.uniform1i(U(pw, 'u_shoreOn'), side); gl.uniform1f(U(pw, 'u_sea'), seaTop); gl.uniform1f(U(pw, 'u_s'), meta.s);
        gl.uniform3f(U(pw, 'u_eye'), ...eye); gl.uniform3f(U(pw, 'u_sun'), 0.4, 0.8, 0.3);
        gl.uniform1i(U(pw, 'u_TidesFft'), 0); gl.uniform1i(U(pw, 'u_TidesShore'), 1);
        gl.uniform4f(U(pw, 'u_TidesFft0'), 0, meta.heightOpen / meta.fft[slotB].hsFull, meta.fft[slotB].breakJ, (kA & 1) * 512); gl.uniform4f(U(pw, 'u_TidesFft1'), 1, meta.wind, 0, 0);
        gl.uniform4f(U(pw, 'u_TidesSurfT'), 0, 0, 0, 0); gl.uniform4f(U(pw, 'u_TidesShO'), 0, 0, meta.frameMod[0], meta.frameMod[1]); gl.uniform4f(U(pw, 'u_TidesShT'), fr.shClk, meta.L0, 1, n);
        gl.uniform4f(U(pw, 'u_TidesSwO'), meta.swH > 0 ? 1 : 0, 1, meta.frameMod[0], meta.frameMod[1]); gl.uniform4fv(U(pw, 'u_TidesSwC'), fr.swC); gl.uniform4fv(U(pw, 'u_TidesSwK'), meta.swK); gl.uniform4fv(U(pw, 'u_TidesSwD'), meta.swD); gl.uniform4f(U(pw, 'u_TidesSwA'), meta.swA, 0.1767767, seaTop, 0);
        loc = gl.getAttribLocation(pw, 'a_corner'); gl.bindBuffer(gl.ARRAY_BUFFER, wBuf); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
        const ld = gl.getAttribLocation(pw, 'a_depth'); gl.bindBuffer(gl.ARRAY_BUFFER, dBuf); gl.enableVertexAttribArray(ld); gl.vertexAttribPointer(ld, 1, gl.FLOAT, false, 0, 0);
        gl.drawArrays(gl.TRIANGLES, 0, WV.length / 2); gl.disableVertexAttribArray(loc); gl.disableVertexAttribArray(ld);
        ctx.drawImage(cv, side * (W + 10), 64);
        // the same frame from straight above, water coloured by its height (white = foam), land green: the crests themselves
        gl.clearColor(0.08, 0.1, 0.14, 1); gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
        gl.useProgram(pl); gl.uniformMatrix4fv(U(pl, 'u_mvp'), false, ortho); loc = gl.getAttribLocation(pl, 'a_pos'); gl.bindBuffer(gl.ARRAY_BUFFER, landBuf); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 3, gl.FLOAT, false, 0, 0);
        lc = gl.getAttribLocation(pl, 'a_col'); gl.bindBuffer(gl.ARRAY_BUFFER, landCol); gl.enableVertexAttribArray(lc); gl.vertexAttribPointer(lc, 3, gl.FLOAT, false, 0, 0); gl.drawArrays(gl.TRIANGLES, 0, L.length / 3); gl.disableVertexAttribArray(loc); gl.disableVertexAttribArray(lc);
        gl.useProgram(pw); gl.uniformMatrix4fv(U(pw, 'u_mvp'), false, ortho); gl.uniform1i(U(pw, 'u_map'), 1); gl.uniform1f(U(pw, 'u_seaTop'), seaTop); gl.uniform1f(U(pw, 'u_hScale'), 0.5);
        loc = gl.getAttribLocation(pw, 'a_corner'); gl.bindBuffer(gl.ARRAY_BUFFER, wBuf); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
        gl.bindBuffer(gl.ARRAY_BUFFER, dBuf); gl.enableVertexAttribArray(ld); gl.vertexAttribPointer(ld, 1, gl.FLOAT, false, 0, 0); gl.drawArrays(gl.TRIANGLES, 0, WV.length / 2); gl.disableVertexAttribArray(loc); gl.disableVertexAttribArray(ld);
        gl.uniform1i(U(pw, 'u_map'), 0); ctx.drawImage(cv, side * (W + 10), 74 + H); }
      ctx.fillStyle = '#fff'; ctx.font = 'bold 15px sans-serif'; ctx.fillText('0.16.3: shore map off - the sea runs through the land', 10, 20); ctx.fillText('0.17.0: shore map on - the sea knows where the shore is', W + 20, 20);
      ctx.font = '12px sans-serif'; ctx.fillText(`Hopeless Tides shaders (tides_fft / tides_surf .glsl) on Hopeless Infinity seed ${meta.seed} terrain round ${meta.cx} ${meta.cz}; t = ${(fr.t - meta.frames[0].t).toFixed(2)} s; swell Hs ${meta.swH.toFixed(2)} m ${meta.swT.toFixed(1)} s, wind sea Hs ${meta.hw.toFixed(2)} m (fetch ${Math.round(meta.fetch)} m)`, 10, 40);
      ctx.fillStyle = '#aab'; ctx.fillText('Vertex heights, slopes, foam: the shader\'s own numbers. Simplified: lighting, opaque water, exposure 15 (as beyond the surf field).', 10, 56);
      res.push(out.toDataURL('image/png')); }
    return res;
  }, [glsl, meta, terrain, shore, ffts, R, fx, fz]);
  frames.forEach((d, i) => fs.writeFileSync(path.join(outDir, `frame_${String(i).padStart(2, '0')}.png`), Buffer.from(d.split(',')[1], 'base64')));
  console.log(`frames written: ${frames.length}`); await b.close();
})();
