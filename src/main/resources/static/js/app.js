/* Crease Live dashboard: REST for initial state, Server-Sent Events for live updates. */
(() => {
  'use strict';

  const state = {
    cards: new Map(),      // id -> MatchCard
    filter: 'ALL',
    selectedId: null,
    sc: null,              // selected Scorecard
    tab: 'live',
    comm: [],              // commentary, newest first
    commDone: false,
    lbType: 'runs',
    teams: [],
    matchES: null,
    feedES: null,
    summaryAt: -1,
  };

  // ------------------------------------------------------------------ helpers
  const $ = (s, el = document) => el.querySelector(s);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const isLive = (s) => s === 'LIVE' || s === 'INNINGS_BREAK';

  async function api(path, opts = {}) {
    const res = await fetch(path, {
      ...opts,
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...(opts.headers || {}) },
    });
    if (res.status === 204) return null;
    const body = await res.json().catch(() => null);
    if (!res.ok) throw new Error((body && (body.detail || body.title)) || `HTTP ${res.status}`);
    return body;
  }

  function toast(msg, err = false) {
    const t = $('#toast');
    t.textContent = msg;
    t.className = 'toast show' + (err ? ' err' : '');
    clearTimeout(toast.t);
    toast.t = setTimeout(() => (t.className = 'toast'), 2600);
  }

  function ballClass(label) {
    if (label === 'W' || label.includes('+W')) return 'w';
    if (label === '4') return 'r4';
    if (label === '6') return 'r6';
    if (label === '0') return 'r0';
    if (/wd|nb|lb|b$/.test(label)) return 'x';
    return '';
  }
  const ballChip = (label, extra = '') =>
    `<span class="ball ${ballClass(label)} ${extra}">${label === '0' ? '•' : esc(label)}</span>`;

  function countdown(iso) {
    const s = Math.max(0, Math.round((new Date(iso) - Date.now()) / 1000));
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  }

  function statusBadge(status) {
    return {
      LIVE: '<span class="badge live">Live</span>',
      INNINGS_BREAK: '<span class="badge brk">Break</span>',
      UPCOMING: '<span class="badge up">Upcoming</span>',
      COMPLETED: '<span class="badge done">Result</span>',
    }[status] || `<span class="badge done">${esc(status)}</span>`;
  }

  // ------------------------------------------------------------------ match list + ticker
  let listFrame = 0;
  function scheduleListRender() {
    if (listFrame) return;
    listFrame = requestAnimationFrame(() => { listFrame = 0; renderList(); renderTicker(); });
  }

  function sortedCards() {
    const rank = (c) => (isLive(c.status) ? 0 : c.status === 'UPCOMING' ? 1 : 2);
    return [...state.cards.values()].sort((a, b) => rank(a) - rank(b) || b.id - a.id);
  }

  function renderList() {
    const f = state.filter;
    const cards = sortedCards().filter((c) => f === 'ALL' || c.status === f || (f === 'LIVE' && isLive(c.status)));
    const el = $('#matchList');
    if (!cards.length) { el.innerHTML = '<div class="empty">No matches here yet.</div>'; return; }
    el.innerHTML = cards.map((c) => {
      const line = (team) => {
        const inns = c.innings.filter((i) => i.teamShort === team.shortName);
        const batting = isLive(c.status) && c.innings.length && c.innings[c.innings.length - 1].teamShort === team.shortName;
        const sc = inns.length
          ? inns.map((i) => `${i.runs}/${i.wickets}<small>(${i.overs})</small>`).join(' ')
          : '<small>Yet to bat</small>';
        return `<div class="row ${isLive(c.status) && !batting ? 'dim' : ''}">
          <span class="tm"><i style="background:${esc(team.color)}"></i>${esc(team.shortName)}</span>
          <span class="sc">${sc}</span></div>`;
      };
      const st = c.status === 'UPCOMING' ? `Starts in ${countdown(c.scheduledStart)}` : c.statusText;
      return `<button class="mcard ${c.id === state.selectedId ? 'on' : ''} ${c.status === 'COMPLETED' ? 'done' : ''}" data-id="${c.id}">
        <div class="meta"><span>${esc(c.title)}</span>${statusBadge(c.status)}</div>
        ${line(c.teamA)}${line(c.teamB)}
        <div class="st" data-st="${c.id}">${esc(st || '')}</div></button>`;
    }).join('');
  }

  function renderTicker() {
    const live = sortedCards().filter((c) => isLive(c.status));
    $('#ticker').innerHTML = live.map((c) => {
      const s = c.innings.map((i) => `${esc(i.teamShort)} <b>${i.runs}/${i.wickets}</b> (${i.overs})`).join(' · ');
      return `<span>${s}</span>`;
    }).join('') || '<span>No live matches right now</span>';
  }

  // ------------------------------------------------------------------ selection + streams
  async function selectMatch(id) {
    if (state.matchES) { state.matchES.close(); state.matchES = null; }
    state.selectedId = id;
    state.sc = null;
    state.comm = [];
    state.commDone = false;
    state.summaryAt = -1;
    history.replaceState(null, '', `#match=${id}`);
    renderList();
    const card = document.querySelector(`.mcard[data-id="${id}"]`);
    if (card) card.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    try {
      state.sc = await api(`/api/matches/${id}`);
    } catch (e) {
      toast(e.message, true);
      return;
    }
    renderHero(null);
    await loadCommentary(true);
    renderTab();
    openMatchStream(id);
  }

  function openMatchStream(id) {
    const es = new EventSource(`/api/matches/${id}/stream`);
    state.matchES = es;
    es.addEventListener('scorecard', (ev) => {
      const sc = JSON.parse(ev.data);
      if (sc.id !== state.selectedId || (state.sc && sc.version < state.sc.version)) return;
      const prev = state.sc;
      state.sc = sc;
      state.cards.set(sc.id, toCard(sc));
      scheduleListRender();
      renderHero(prev);
      if (state.tab === 'scorecard' || state.tab === 'charts' || state.tab === 'scorer') renderTab();
      if (state.tab === 'summary' && (sc.status !== prev?.status || sc.version - state.summaryAt > 6)) renderTab();
    });
    es.addEventListener('ball', (ev) => {
      const b = JSON.parse(ev.data);
      if (state.comm.length && state.comm[0].seq >= b.seq) return;
      state.comm.unshift(b);
      if (state.tab === 'live') renderCommentary(b.seq);
      flash(b);
    });
  }

  function toCard(sc) {
    return {
      id: sc.id, title: sc.title, venue: sc.venue, oversPerInnings: sc.oversPerInnings, status: sc.status,
      teamA: sc.teamA, teamB: sc.teamB, statusText: sc.statusText, scheduledStart: sc.scheduledStart,
      autoSimulate: sc.autoSimulate, version: sc.version,
      innings: sc.innings.map((i) => ({ number: i.number, teamShort: i.battingTeam.shortName, runs: i.runs, wickets: i.wickets, overs: i.overs, complete: i.complete })),
    };
  }

  function connectFeed() {
    const conn = $('#conn');
    const es = new EventSource('/api/stream');
    state.feedES = es;
    es.onopen = () => { conn.className = 'conn ok'; $('.txt', conn).textContent = 'Live'; };
    es.onerror = () => { conn.className = 'conn bad'; $('.txt', conn).textContent = 'Reconnecting…'; };
    es.addEventListener('card', (ev) => {
      const c = JSON.parse(ev.data);
      const old = state.cards.get(c.id);
      if (old && old.version > c.version) return;
      state.cards.set(c.id, c);
      scheduleListRender();
      if (!state.selectedId) selectMatch(c.id);
    });
    es.addEventListener('removed', (ev) => {
      const { id } = JSON.parse(ev.data);
      state.cards.delete(id);
      scheduleListRender();
    });
  }

  function flash(b) {
    const kind = b.wicketType ? 'fw' : b.batRuns === 6 ? 'f6' : b.batRuns === 4 ? 'f4' : null;
    if (!kind || document.hidden) return;
    const el = $('#flash');
    el.textContent = kind === 'fw' ? 'OUT!' : kind === 'f6' ? 'SIX!' : 'FOUR!';
    el.className = 'flash';
    void el.offsetWidth; // restart animation
    el.className = `flash go ${kind}`;
  }

  // ------------------------------------------------------------------ hero scoreboard
  function renderHero(prev) {
    const sc = state.sc;
    const hero = $('#hero');
    if (!sc) { hero.innerHTML = '<div class="skeleton">Select a match</div>'; return; }

    const head = `<div class="hero-head"><span><span class="ttl">${esc(sc.title)}</span> · ${esc(sc.venue)}</span>${statusBadge(sc.status)}</div>`;

    if (sc.status === 'UPCOMING') {
      hero.innerHTML = `${head}
        <div class="teams">${[sc.teamA, sc.teamB].map((t) => `<div class="team-line idle"><i style="background:${esc(t.color)}"></i><span class="nm">${esc(t.name)}</span><span class="big"></span></div>`).join('')}</div>
        <div class="status-line">First ball in</div>
        <div class="countdown" id="heroCountdown">${countdown(sc.scheduledStart)}</div>
        <div class="hero-actions"><button class="btn primary" data-act="start">Start now</button></div>`;
      return;
    }

    const current = sc.innings[sc.innings.length - 1];
    const prevRuns = prev && prev.id === sc.id ? Object.fromEntries(prev.innings.map((i) => [i.number, i.runs])) : {};
    const teamLine = (t) => {
      const inns = sc.innings.filter((i) => i.battingTeam.id === t.id);
      const batting = isLive(sc.status) && current.battingTeam.id === t.id;
      const idle = isLive(sc.status) && !batting;
      const score = inns.length
        ? inns.map((i) => `${i.runs}/${i.wickets}<small>(${i.overs})</small>`).join(' ')
        : '<small>Yet to bat</small>';
      const changed = inns.some((i) => prevRuns[i.number] !== undefined && prevRuns[i.number] !== i.runs);
      const won = sc.winner && sc.winner.id === t.id ? '<small>Winner</small>' : '';
      return `<div class="team-line ${idle ? 'idle' : ''}"><i style="background:${esc(t.color)}"></i>
        <span class="nm">${esc(t.name)}${won}</span><span class="big ${changed ? 'flash-up' : ''}">${score}</span></div>`;
    };

    let stats = '', crease = '', over = '';
    const lv = sc.live;
    if (lv) {
      const items = [['CRR', lv.currentRunRate.toFixed(2)]];
      if (lv.requiredRunRate != null) items.push(['RRR', lv.requiredRunRate.toFixed(2)]);
      if (lv.target != null) items.push(['Target', lv.target]);
      if (lv.projectedScore != null) items.push(['Projected', lv.projectedScore]);
      items.push(['Partnership', `${lv.partnershipRuns}<small style="font-size:13px;color:var(--muted)"> (${lv.partnershipBalls})</small>`]);
      stats = `<div class="stats-row">${items.map(([k, v]) => `<div class="stat"><span>${k}</span><b>${v}</b></div>`).join('')}</div>`;

      const bat = (p) => p ? `<div class="p"><span class="n ${p.onStrike ? 'strike' : ''}">${playerLink(p.playerId, p.name)}</span>
          <span class="f">${p.runs}<small>(${p.balls})</small></span></div>
          <div class="sub">${p.fours}×4 · ${p.sixes}×6 · SR ${p.strikeRate.toFixed(1)}</div>` : '';
      const bw = lv.bowler;
      crease = `<div class="crease">
        <div class="box"><h4>Batting</h4>${bat(lv.striker)}${bat(lv.nonStriker)}</div>
        <div class="box"><h4>Bowling</h4>${bw ? `<div class="p"><span class="n">${playerLink(bw.playerId, bw.name)}</span>
          <span class="f">${bw.wickets}-${bw.runs}<small>(${bw.overs})</small></span></div>
          <div class="sub">Econ ${bw.economy.toFixed(2)} · ${bw.dots} dots · ${bw.maidens} maiden${bw.maidens === 1 ? '' : 's'}</div>` : '<div class="sub">Between overs</div>'}
        </div></div>`;
      const fresh = prev && prev.live && prev.id === sc.id ? prev.live.thisOver.length : 0;
      over = `<div class="this-over"><span class="lbl">This over</span>${lv.thisOver.length
        ? lv.thisOver.map((l, i) => ballChip(l, i >= fresh ? 'new' : '')).join('')
        : '<span class="note" style="text-transform:none;letter-spacing:0">New over coming up</span>'}</div>`;
    }

    hero.innerHTML = `${head}
      <div class="teams">${teamLine(sc.teamA)}${teamLine(sc.teamB)}</div>
      <p class="status-line ${sc.status === 'COMPLETED' ? 'done' : ''}">${esc(sc.statusText || '')}</p>
      ${stats}${crease}${over}`;
  }

  const playerLink = (id, name) => `<button class="link" data-player="${id}">${esc(name)}</button>`;

  // ------------------------------------------------------------------ tabs
  function renderTab() {
    for (const t of ['live', 'scorecard', 'charts', 'summary', 'scorer']) $(`#tab-${t}`).hidden = t !== state.tab;
    if (!state.sc) return;
    ({ live: () => renderCommentary(), scorecard: renderScorecard, charts: renderCharts, summary: renderSummary, scorer: renderScorer })[state.tab]();
  }

  // ---- commentary
  async function loadCommentary(reset) {
    const before = !reset && state.comm.length ? `&before=${state.comm[state.comm.length - 1].seq}` : '';
    const page = await api(`/api/matches/${state.selectedId}/commentary?limit=40${before}`);
    state.comm = reset ? page : state.comm.concat(page);
    state.commDone = page.length < 40;
  }

  function renderCommentary(newSeq) {
    const el = $('#tab-live');
    const sc = state.sc;
    if (!state.comm.length) {
      el.innerHTML = `<div class="empty">${sc.status === 'UPCOMING' ? 'Commentary begins with the first ball.' : 'No deliveries yet.'}</div>`;
      return;
    }
    let html = '<ul class="comm">';
    for (const b of state.comm) {
      if (b.overComplete) {
        const inn = sc.innings[b.innings - 1];
        const overIdx = parseInt(b.over, 10);
        const runs = inn && inn.overRuns[overIdx] != null ? inn.overRuns[overIdx] : '';
        html += `<li class="over-end" style="display:flex"><span>End of over <b>${overIdx + 1}</b> · ${runs} run${runs === 1 ? '' : 's'}</span>
          <span>${esc(inn ? inn.battingTeam.shortName : '')} <b>${b.scoreAfter}/${b.wicketsAfter}</b></span></li>`;
      }
      const hl = b.wicketType ? 'hl-w' : b.batRuns === 6 ? 'hl-6' : b.batRuns === 4 ? 'hl-4' : '';
      html += `<li class="${hl} ${b.seq === newSeq ? 'new' : ''}"><span class="ov">${esc(b.over)}</span>${ballChip(b.label)}
        <span class="txt">${esc(b.commentary)}${b.dismissal ? `<span class="ds">${esc(b.dismissedName)} ${esc(b.dismissal)}</span>` : ''}</span></li>`;
    }
    html += '</ul>';
    if (!state.commDone) html += '<button class="btn more" data-act="more">Load earlier deliveries</button>';
    el.innerHTML = html;
  }

  // ---- scorecard
  function renderScorecard() {
    const sc = state.sc;
    const el = $('#tab-scorecard');
    if (!sc.innings.length) { el.innerHTML = '<div class="empty">Scorecard appears once play begins.</div>'; return; }
    el.innerHTML = sc.innings.map((i) => `
      <div class="inn-block">
        <div class="inn-head"><h3>${esc(i.battingTeam.name)} · Innings ${i.number}</h3>
          <span class="tot">${i.runs}/${i.wickets} <small>(${i.overs} ov, RR ${i.runRate.toFixed(2)})</small></span></div>
        <div class="tbl-wrap"><table class="tbl">
          <thead><tr><th>Batter</th><th>R</th><th>B</th><th>4s</th><th>6s</th><th>SR</th></tr></thead>
          <tbody>${i.battingCard.map((b) => `<tr class="${b.atCrease ? 'active' : ''}">
            <td>${playerLink(b.playerId, b.name)}${b.onStrike ? ' *' : ''}<span class="dis ${b.out ? '' : 'no'}">${esc(b.status)}</span></td>
            <td class="r">${b.runs}</td><td>${b.balls}</td><td>${b.fours}</td><td>${b.sixes}</td><td>${b.strikeRate.toFixed(1)}</td></tr>`).join('')}
          </tbody></table></div>
        <div class="inn-foot">
          <div><b>Extras ${i.extras.total}</b> (b ${i.extras.byes}, lb ${i.extras.legByes}, w ${i.extras.wides}, nb ${i.extras.noBalls})</div>
          ${i.yetToBat.length ? `<div><b>Yet to bat:</b> ${i.yetToBat.map(esc).join(', ')}</div>` : ''}
          ${i.fallOfWickets.length ? `<div><b>Fall of wickets:</b> ${i.fallOfWickets.map(esc).join(' · ')}</div>` : ''}
        </div>
        <div class="tbl-wrap"><table class="tbl">
          <thead><tr><th>Bowler</th><th>O</th><th>M</th><th>R</th><th>W</th><th>Econ</th><th>0s</th><th>Wd</th><th>Nb</th></tr></thead>
          <tbody>${i.bowlingCard.map((b) => `<tr class="${b.bowling ? 'active' : ''}">
            <td>${playerLink(b.playerId, b.name)}</td><td>${b.overs}</td><td>${b.maidens}</td><td>${b.runs}</td>
            <td class="r">${b.wickets}</td><td>${b.economy.toFixed(2)}</td><td>${b.dots}</td><td>${b.wides}</td><td>${b.noBalls}</td></tr>`).join('')}
          </tbody></table></div>
      </div>`).join('');
  }

  // ---- charts (hand-rolled SVG, no dependencies)
  function renderCharts() {
    const sc = state.sc;
    const el = $('#tab-charts');
    if (!sc.innings.length) { el.innerHTML = '<div class="empty">Charts appear once play begins.</div>'; return; }
    const colors = sc.innings.map((i) => i.battingTeam.color);
    const legend = `<div class="legend">${sc.innings.map((i, k) => `<span><i style="background:${esc(colors[k])}"></i>${esc(i.battingTeam.shortName)}</span>`).join('')}</div>`;
    el.innerHTML = `
      <div class="chart-card"><h3>Worm</h3><p>Cumulative runs after each over; red dots mark wickets.</p>${legend}${worm(sc, colors)}</div>
      <div class="chart-card"><h3>Manhattan</h3><p>Runs scored in each over.</p>${legend}${manhattan(sc, colors)}</div>`;
  }

  function axes(W, H, P, maxX, maxY, xStep) {
    let g = '';
    const yStep = maxY > 200 ? 50 : maxY > 80 ? 20 : maxY > 30 ? 10 : 5;
    for (let y = 0; y <= maxY; y += yStep) {
      const py = H - P.b - (y / maxY) * (H - P.t - P.b);
      g += `<line class="grid" x1="${P.l}" x2="${W - P.r}" y1="${py}" y2="${py}"/><text class="axis" x="${P.l - 6}" y="${py + 3}" text-anchor="end">${y}</text>`;
    }
    for (let x = 0; x <= maxX; x += xStep) {
      const px = P.l + (x / maxX) * (W - P.l - P.r);
      g += `<text class="axis" x="${px}" y="${H - P.b + 14}" text-anchor="middle">${x}</text>`;
    }
    return g;
  }

  function worm(sc, colors) {
    const W = 640, H = 250, P = { l: 34, r: 10, t: 10, b: 22 };
    const overs = sc.oversPerInnings;
    const series = sc.innings.map((i) => { let c = 0; return [0, ...i.overRuns.map((r) => (c += r))]; });
    const maxY = Math.max(20, ...series.flat()) * 1.08;
    const X = (o) => P.l + (o / overs) * (W - P.l - P.r);
    const Y = (r) => H - P.b - (r / maxY) * (H - P.t - P.b);
    let g = axes(W, H, P, overs, Math.ceil(maxY), overs > 20 ? 10 : overs > 10 ? 5 : 1);
    sc.innings.forEach((inn, k) => {
      const pts = series[k].map((r, o) => `${X(o).toFixed(1)},${Y(r).toFixed(1)}`).join(' ');
      g += `<polyline fill="none" stroke="${esc(colors[k])}" stroke-width="2.5" stroke-linejoin="round" points="${pts}"/>`;
      inn.overWickets.forEach((w, o) => {
        for (let j = 0; j < w; j++) g += `<circle class="wk" r="4" cx="${X(o + 1)}" cy="${Y(series[k][o + 1]) - j * 9}"/>`;
      });
    });
    return `<svg class="chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="Worm chart">${g}</svg>`;
  }

  function manhattan(sc, colors) {
    const W = 640, H = 220, P = { l: 34, r: 10, t: 10, b: 22 };
    const overs = sc.oversPerInnings;
    const maxY = Math.max(12, ...sc.innings.flatMap((i) => i.overRuns)) * 1.15;
    const slot = (W - P.l - P.r) / overs;
    const n = sc.innings.length;
    const bw = Math.max(2, (slot * 0.78) / n);
    const Y = (r) => H - P.b - (r / maxY) * (H - P.t - P.b);
    let g = axes(W, H, P, overs, Math.ceil(maxY), overs > 20 ? 10 : overs > 10 ? 5 : 1);
    sc.innings.forEach((inn, k) => {
      inn.overRuns.forEach((r, o) => {
        const x = P.l + o * slot + slot * 0.11 + k * bw;
        g += `<rect x="${x.toFixed(1)}" y="${Y(r).toFixed(1)}" width="${(bw - 1).toFixed(1)}" height="${(H - P.b - Y(r)).toFixed(1)}" rx="1.5" fill="${esc(colors[k])}"><title>Over ${o + 1}: ${r}</title></rect>`;
        for (let j = 0; j < inn.overWickets[o]; j++) g += `<circle class="wk" r="3.5" cx="${(x + bw / 2).toFixed(1)}" cy="${Y(r) - 6 - j * 8}"/>`;
      });
    });
    return `<svg class="chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="Manhattan chart">${g}</svg>`;
  }

  // ---- summary
  async function renderSummary() {
    const el = $('#tab-summary');
    if (state.sc.status === 'UPCOMING') { el.innerHTML = '<div class="empty">Summary is available once play begins.</div>'; return; }
    state.summaryAt = state.sc.version;
    let s;
    try { s = await api(`/api/matches/${state.selectedId}/summary`); } catch (e) { toast(e.message, true); return; }
    if (state.tab !== 'summary' || s.id !== state.selectedId) return;
    const plist = (arr) => `<ul class="plist">${arr.map((p) => `<li><span>${playerLink(p.playerId, p.name)} <span class="sub">${esc(p.teamShort)}</span><br><span class="sub">${esc(p.detail)}</span></span><span class="fig">${esc(p.figures)}</span></li>`).join('')}</ul>`;
    el.innerHTML = `<div class="sum-grid">
      ${s.playerOfTheMatch ? `<div class="pom"><div class="trophy">★</div><div><div class="lbl">Player of the match</div>
        <div class="who">${playerLink(s.playerOfTheMatch.playerId, s.playerOfTheMatch.name)} <span class="sub">${esc(s.playerOfTheMatch.teamShort)}</span></div>
        <div class="fig">${esc(s.playerOfTheMatch.figures)}</div></div></div>` : ''}
      <div class="card" style="grid-column:1/-1"><h3>${esc(s.result || '')}</h3><p class="note" style="margin:6px 0 0">${esc(s.toss || '')}</p>
        <div class="kpis" style="grid-template-columns:repeat(4,1fr);margin-top:12px">
          <div><span>Fours</span><b>${s.totalFours}</b></div><div><span>Sixes</span><b>${s.totalSixes}</b></div>
          <div><span>Wickets</span><b>${s.totalWickets}</b></div><div><span>Extras</span><b>${s.totalExtras}</b></div></div></div>
      <div class="card"><div class="card-head"><h3>Top batters</h3></div>${plist(s.topBatters)}</div>
      <div class="card"><div class="card-head"><h3>Top bowlers</h3></div>${plist(s.topBowlers)}</div>
      ${s.highlights.length ? `<div class="card" style="grid-column:1/-1"><div class="card-head"><h3>Highlights</h3></div><ul class="highlights">${s.highlights.map((h) => `<li>${esc(h)}</li>`).join('')}</ul></div>` : ''}
    </div>`;
  }

  // ---- scorer (manual scoring through the REST API)
  const scorerState = { wicket: 'CAUGHT' };
  function renderScorer() {
    const sc = state.sc;
    const el = $('#tab-scorer');
    if (sc.status === 'COMPLETED') { el.innerHTML = '<div class="empty">This match is complete.</div>'; return; }
    const fielders = sc.innings.length ? (sc.innings[sc.innings.length - 1].bowlingTeam) : null;
    el.innerHTML = `<div class="scorer">
      <div class="card"><div class="row" style="justify-content:space-between">
        <label class="switch"><input type="checkbox" data-act="auto" ${sc.autoSimulate ? 'checked' : ''}>
          <span>Automatic live feed</span></label>
        ${sc.status === 'UPCOMING' ? '<button class="btn primary" data-act="start">Start match</button>' : ''}
      </div><p class="note" style="margin:10px 0 0">Turn the feed off to score this match by hand. Every button below calls
        <code class="api">POST /api/matches/${sc.id}/balls</code>; the update reaches every connected viewer over SSE.</p></div>
      ${sc.status === 'UPCOMING' ? '' : `
      <div class="pad">
        ${[0, 1, 2, 3, 4, 6].map((r) => `<button data-ball='{"batRuns":${r}}' class="${r === 4 ? 'b4' : r === 6 ? 'b6' : ''}">${r === 0 ? '•' : r}</button>`).join('')}
        <button class="ex" data-ball='{"extraType":"WIDE"}'>Wide</button>
        <button class="ex" data-ball='{"extraType":"NO_BALL"}'>No ball</button>
        <button class="ex" data-ball='{"extraType":"BYE","extraRuns":1}'>Bye</button>
        <button class="ex" data-ball='{"extraType":"LEG_BYE","extraRuns":1}'>Leg bye</button>
        <button class="wk" data-act="wicket" style="grid-column:span 2">Wicket</button>
      </div>
      <div class="row"><span class="note">Dismissal</span>
        <select id="wkType">${['CAUGHT', 'BOWLED', 'LBW', 'RUN_OUT', 'STUMPED', 'HIT_WICKET'].map((w) => `<option ${w === scorerState.wicket ? 'selected' : ''}>${w}</option>`).join('')}</select>
        ${fielders ? `<span class="note">Fielding side: ${esc(fielders.name)}</span>` : ''}
      </div>`}
    </div>`;
  }

  async function postBall(body) {
    try {
      await api(`/api/matches/${state.selectedId}/balls`, { method: 'POST', body: JSON.stringify(body) });
    } catch (e) { toast(e.message, true); }
  }

  // ------------------------------------------------------------------ side panels
  async function refreshSide() {
    try {
      const [lb, ov] = await Promise.all([
        api(`/api/players/leaderboard?type=${state.lbType}&limit=8`),
        api('/api/overview'),
      ]);
      const val = (p) => state.lbType === 'wickets' ? p.wickets : state.lbType === 'sixes' ? p.sixes : p.runs;
      const sub = (p) => state.lbType === 'wickets'
        ? `${esc(p.teamShort)} · Econ ${p.economy ?? '-'}`
        : `${esc(p.teamShort)} · SR ${p.strikeRate}`;
      $('#leaders').innerHTML = lb.map((p) => `<li><div><div class="n">${playerLink(p.playerId, p.name)}</div><div class="t">${sub(p)}</div></div><span class="v">${val(p)}</span></li>`).join('')
        || '<li style="grid-template-columns:1fr" class="note">No data yet</li>';
      $('#kpis').innerHTML = [['Live', ov.live], ['Upcoming', ov.upcoming], ['Balls bowled', ov.ballsBowled.toLocaleString()],
        ['Sixes', ov.totalSixes], ['Runs', ov.totalRuns.toLocaleString()], ['Viewers', ov.subscribers]]
        .map(([k, v]) => `<div><span>${k}</span><b>${v}</b></div>`).join('');
    } catch { /* transient, next refresh will retry */ }
  }

  async function showPlayer(id) {
    let p;
    try { p = await api(`/api/players/${id}`); } catch (e) { toast(e.message, true); return; }
    const s = p.stats;
    let dlg = $('#playerDlg');
    if (!dlg) {
      dlg = document.createElement('dialog');
      dlg.id = 'playerDlg';
      dlg.addEventListener('click', (e) => { if (e.target === dlg) dlg.close(); });
      document.body.appendChild(dlg);
    }
    const role = s.role.replace('_', ' ').toLowerCase();
    dlg.innerHTML = `<div class="dlg">
      <div class="dlg-head"><div><div class="who">${esc(s.name)}</div><div class="note">${esc(s.teamShort)} · ${esc(role)}</div></div>
        <button class="btn" data-close>Close</button></div>
      <div class="kpis" style="grid-template-columns:repeat(4,1fr)">
        <div><span>Matches</span><b>${s.matches}</b></div><div><span>Runs</span><b>${s.runs}</b></div>
        <div><span>Average</span><b>${s.average ?? '-'}</b></div><div><span>Strike rate</span><b>${s.strikeRate}</b></div>
        <div><span>High score</span><b>${s.highest}${s.highestNotOut ? '*' : ''}</b></div><div><span>4s / 6s</span><b>${s.fours}/${s.sixes}</b></div>
        <div><span>Wickets</span><b>${s.wickets}</b></div><div><span>Best</span><b>${s.bestBowling ?? '-'}</b></div></div>
      ${p.recent.length ? `<table class="tbl" style="margin-top:12px"><thead><tr><th>Match</th><th>Batting</th><th>Bowling</th></tr></thead>
        <tbody>${p.recent.map((r) => `<tr><td>${esc(r.title)}</td><td>${esc(r.batting ?? '-')}</td><td>${esc(r.bowling ?? '-')}</td></tr>`).join('')}</tbody></table>` : ''}
    </div>`;
    dlg.showModal();
  }

  // ------------------------------------------------------------------ wiring
  function bind() {
    $('#filters').addEventListener('click', (e) => {
      const b = e.target.closest('button'); if (!b) return;
      state.filter = b.dataset.f;
      $('#filters').querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b));
      renderList();
    });
    $('#matchList').addEventListener('click', (e) => {
      const c = e.target.closest('.mcard'); if (c) selectMatch(Number(c.dataset.id));
    });
    $('#tabs').addEventListener('click', (e) => {
      const b = e.target.closest('button'); if (!b) return;
      state.tab = b.dataset.tab;
      $('#tabs').querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b));
      renderTab();
    });
    $('#lbSeg').addEventListener('click', (e) => {
      const b = e.target.closest('button'); if (!b) return;
      state.lbType = b.dataset.t;
      $('#lbSeg').querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b));
      refreshSide();
    });

    document.addEventListener('click', async (e) => {
      const pl = e.target.closest('[data-player]');
      if (pl) { showPlayer(pl.dataset.player); return; }
      if (e.target.closest('[data-close]')) { $('#playerDlg').close(); return; }
      const ball = e.target.closest('[data-ball]');
      if (ball) { postBall(JSON.parse(ball.dataset.ball)); return; }
      const act = e.target.closest('[data-act]');
      if (!act) return;
      const id = state.selectedId;
      try {
        if (act.dataset.act === 'start') { await api(`/api/matches/${id}/start`, { method: 'POST' }); toast('Match started'); }
        if (act.dataset.act === 'more') { await loadCommentary(false); renderCommentary(); }
        if (act.dataset.act === 'wicket') {
          const type = $('#wkType').value;
          scorerState.wicket = type;
          await postBall({ wicketType: type });
        }
      } catch (err) { toast(err.message, true); }
    });

    document.addEventListener('change', async (e) => {
      if (e.target.matches('[data-act=auto]')) {
        try {
          await api(`/api/matches/${state.selectedId}/simulation`, { method: 'PATCH', body: JSON.stringify({ enabled: e.target.checked }) });
          toast(e.target.checked ? 'Automatic feed resumed' : 'Manual scoring: feed paused');
        } catch (err) { toast(err.message, true); }
      }
      if (e.target.id === 'wkType') scorerState.wicket = e.target.value;
    });

    $('#fixtureForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const f = new FormData(e.target);
      const body = { teamAId: Number(f.get('teamAId')), teamBId: Number(f.get('teamBId')), overs: Number(f.get('overs')), startsInSeconds: 10 };
      try {
        const card = await api('/api/matches', { method: 'POST', body: JSON.stringify(body) });
        state.cards.set(card.id, card);
        selectMatch(card.id);
        toast(`${card.title} scheduled`);
      } catch (err) { toast(err.message, true); }
    });

    // countdowns for upcoming fixtures
    setInterval(() => {
      for (const c of state.cards.values()) {
        if (c.status !== 'UPCOMING') continue;
        const el = document.querySelector(`[data-st="${c.id}"]`);
        if (el) el.textContent = `Starts in ${countdown(c.scheduledStart)}`;
      }
      const hc = $('#heroCountdown');
      if (hc && state.sc) hc.textContent = countdown(state.sc.scheduledStart);
    }, 1000);
    setInterval(() => { if (!document.hidden) refreshSide(); }, 8000);
  }

  async function init() {
    bind();
    try {
      const [cards, teams] = await Promise.all([api('/api/matches'), api('/api/teams')]);
      cards.forEach((c) => state.cards.set(c.id, c));
      state.teams = teams;
      const opts = teams.map((t) => `<option value="${t.id}">${esc(t.name)}</option>`).join('');
      const [a, b] = $('#fixtureForm').querySelectorAll('select[name=teamAId], select[name=teamBId]');
      a.innerHTML = opts; b.innerHTML = opts;
      if (teams[1]) b.value = teams[1].id;
    } catch (e) {
      toast('Could not reach the server: ' + e.message, true);
    }
    renderList();
    renderTicker();
    connectFeed();
    refreshSide();
    const fromHash = Number((location.hash.match(/match=(\d+)/) || [])[1]);
    const first = state.cards.has(fromHash) ? fromHash : (sortedCards()[0] || {}).id;
    if (first) selectMatch(first);
  }

  init();
})();
