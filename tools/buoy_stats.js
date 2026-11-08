// BUOY STATS (saved tool, 2026-11-02): NOAA NDBC standard meteorological records -> wave statistics for Tides.
// Run in a browser tab open on https://www.ndbc.noaa.gov (the built-in browser pane, javascript_tool): paste this file, then
//   await buoyStats(['46042','41025'], [2015,2016,2017,2018,2019])
// Per station: hours read, median / 25th / 75th percentile significant wave height (WVHT, m), median dominant period (DPD, s),
// and the same in CALM hours (wind under 3 m/s at the buoy: no local wind sea, so what is left is swell from far away: measured).
// 2026-11-02 (Infinity shores): He, the wave height exceeded 12 hours a year (the 1 - 12/8766 = 99.863 percentile; Hallermeier 1978's
// closure-depth wave), and Te, the median dominant period of the hours within 10 % of He; plus the yearly mean Hs (for Sunamura).
async function buoyStats(stations, years) {
  const q = (a, p) => { if (!a.length) return NaN; const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(p * s.length))]; };
  const out = {};
  for (const st of stations) {
    const hs = [], dp = [], ws = [], chs = [], cdp = []; let files = 0;
    for (const y of years) {
      const r = await fetch(`/view_text_file.php?filename=${st.toLowerCase()}h${y}.txt.gz&dir=data/historical/stdmet/`); if (!r.ok) continue;
      const t = await r.text(); if (!t.startsWith('#')) continue; files++;
      const lines = t.split('\n'); const head = lines[0].replace('#', '').trim().split(/\s+/);
      const iH = head.indexOf('WVHT'), iD = head.indexOf('DPD'), iW = head.indexOf('WSPD');
      for (const ln of lines) { if (!ln || ln[0] === '#') continue; const f = ln.trim().split(/\s+/);
        const h = +f[iH], d = +f[iD], w = +f[iW]; if (!(h > 0 && h < 90 && d > 0 && d < 90)) continue;
        hs.push(h); dp.push(d); if (w >= 0 && w < 90) { ws.push(w); if (w < 3) { chs.push(h); cdp.push(d); } } }
    }
    out[st] = { files, hours: hs.length, hs25: q(hs, .25), hs50: q(hs, .5), hs75: q(hs, .75), tp50: q(dp, .5), wind50: q(ws, .5),
      calmHours: chs.length, calmHs25: q(chs, .25), calmHs50: q(chs, .5), calmTp50: q(cdp, .5) };
    const He = q(hs, 1 - 12 / 8766), near = []; for (let i = 0; i < hs.length; i++) if (Math.abs(hs[i] - He) <= 0.1 * He) near.push(dp[i]);
    out[st].He = He; out[st].Te = q(near, .5); out[st].heHours = near.length; out[st].hsMean = hs.reduce((a, b) => a + b, 0) / Math.max(1, hs.length);
  }
  return out;
}
