/* Agent Deck web dashboard.
 *
 * Rules this file keeps:
 * - Every server-provided string reaches the DOM through textContent / text nodes,
 *   never innerHTML.
 * - The device token lives only in an HttpOnly cookie; nothing secret is stored here.
 * - Mutating requests carry a requestId that is kept after an uncertain failure, so a
 *   retry can never deliver the same action twice. Nothing retries automatically.
 * - Polling runs every 5 s while the page is visible, never overlapping itself.
 */
'use strict';

(() => {
  const POLL_MS = 5000;
  const MESSAGE_LIMIT = 200;
  const LONG_MESSAGE = 1600;
  const MODEL_ID_RE = /^[A-Za-z0-9._:/@+[\]-]{1,128}$/;
  const STATUS_LABELS = {
    working: 'Working', needs_input: 'Needs input', idle: 'Idle', completed: 'Done',
    error: 'Error', unknown: 'Unknown', offline: 'Offline',
  };
  const AGENT_LABELS = { claude: 'Claude', codex: 'Codex' };
  const narrowQuery = window.matchMedia('(max-width: 899px)');

  const $ = (id) => document.getElementById(id);

  function el(tag, opts, ...children) {
    const node = document.createElement(tag);
    const o = opts || {};
    if (o.cls) node.className = o.cls;
    if (o.text != null) node.textContent = String(o.text);
    if (o.attrs) {
      for (const [k, v] of Object.entries(o.attrs)) {
        if (v === true) node.setAttribute(k, '');
        else if (v != null && v !== false) node.setAttribute(k, String(v));
      }
    }
    if (o.on) for (const [k, fn] of Object.entries(o.on)) node.addEventListener(k, fn);
    for (const c of children) if (c != null && c !== false) node.append(c);
    return node;
  }

  // ------------------------------------------------------------ storage
  // sessionStorage only holds drafts and pending request IDs (no credentials).
  const store = {
    get(k) { try { return window.sessionStorage.getItem(k); } catch { return null; } },
    set(k, v) { try { window.sessionStorage.setItem(k, v); return true; } catch { return false; } },
    del(k) { try { window.sessionStorage.removeItem(k); } catch { /* unavailable */ } },
    clearAll() {
      try {
        for (const k of Object.keys(window.sessionStorage)) {
          if (k.startsWith('agentdeck.')) window.sessionStorage.removeItem(k);
        }
      } catch { /* unavailable */ }
    },
  };

  const drafts = new Map();
  function getDraft(sid) {
    if (drafts.has(sid)) return drafts.get(sid);
    const v = store.get('agentdeck.draft.' + sid);
    return v == null ? '' : v;
  }
  function setDraft(sid, text) {
    if (!sid) return;
    drafts.set(sid, text);
    if (text) store.set('agentdeck.draft.' + sid, text);
    else store.del('agentdeck.draft.' + sid);
  }

  // --------------------------------------------------------------- api
  class ApiError extends Error {
    constructor(status, code, message, uncertain) {
      super(message);
      this.status = status;
      this.code = code;
      this.uncertain = uncertain;
    }
  }

  async function api(method, path, body) {
    const epoch = S.epoch;
    const init = {
      method,
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { 'X-AgentDeck-Web': '1', Accept: 'application/json' },
    };
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    let res;
    try {
      res = await fetch(path, init);
    } catch {
      throw new ApiError(0, 'network', 'Can’t reach the Mac.', true);
    }
    let data = null;
    try { data = await res.json(); } catch {
      if (res.ok) throw new ApiError(res.status, 'invalid_response', 'The Mac response was incomplete. Check the chat before retrying.', true);
    }
    if (!res.ok) {
      const err = (data && data.error) || {};
      const code = err.code || 'http_' + res.status;
      const uncertain = res.status >= 500 || res.status === 408 || code === 'delivery_uncertain';
      const e = new ApiError(res.status, code, err.message || 'Request failed (' + res.status + ').', uncertain);
      if (res.status === 401 && path !== '/web/pair' && epoch === S.epoch) onUnauthorized();
      throw e;
    }
    return data;
  }

  function newRequestId() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') return 'web-' + window.crypto.randomUUID();
    const bytes = new Uint8Array(16);
    window.crypto.getRandomValues(bytes);
    return 'web-' + Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  }

  // -------------------------------------------------------------- state
  const S = {
    epoch: 0,             // bumps on sign-out; stale async results are dropped
    stopped: true,
    timer: null,
    polling: false,
    pollAgain: false,
    me: null,
    sessions: [],
    byId: new Map(),
    alias: new Map(),
    extra: new Map(),     // sessions returned by start/resume before discovery lists them
    lastOk: 0,
    lastError: null,
    listSig: '',
    selectedId: null,
    selGen: 0,
    detailInFlight: null,
    detail: null,
    tab: 'messages',
    filter: { q: '', agent: 'all', scope: 'all' },
    expanded: new Set(),
    collapsedProjects: new Set(),
    expandedMessages: new Set(),
    pending: loadPending(),  // opKey -> { requestId, sig, reason }
    busy: new Set(),
    notes: new Map(),        // sid -> { text, error }
    apkInfo: null,
  };

  function emptyDetail() {
    return { messages: null, msgSig: '', terminal: null, approvals: [], approvalsAt: 0, approvalsError: null, error: null };
  }

  function loadPending() {
    try {
      const raw = store.get('agentdeck.pending');
      const v = raw ? JSON.parse(raw) : {};
      return v && typeof v === 'object' ? v : {};
    } catch { return {}; }
  }
  function savePending() { store.set('agentdeck.pending', JSON.stringify(S.pending)); }

  /* Run one mutating action. A pending uncertain attempt with the same signature
   * reuses its requestId (server-side idempotency makes the retry safe); a different
   * payload is refused unless `force` is set, so nothing is silently duplicated. */
  async function runOp(key, sig, fn, force) {
    if (S.busy.has(key)) return { skipped: true };
    let prior = S.pending[key];
    if (prior && (prior.deviceId !== S.me.deviceId || !Number.isFinite(prior.createdAt)
      || Date.now() - prior.createdAt > 23 * 3600 * 1000 || prior.createdAt > Date.now())) {
      if (!window.confirm('This saved request belongs to an older pairing or is near or beyond the 24-hour retry window. It may already have happened. Check the chat first: sending it again can repeat the action. Send a new request anyway?')) return { skipped: true };
      prior = null;
    }
    if (prior && prior.sig !== sig && !force) return { conflict: prior };
    const requestId = prior && prior.sig === sig ? prior.requestId : newRequestId();
    const epoch = S.epoch;
    const createdAt = prior ? prior.createdAt : Date.now();
    const deviceId = S.me.deviceId;
    S.pending[key] = { requestId, sig, reason: null, createdAt, deviceId };
    savePending();
    S.busy.add(key);
    renderBusy();
    try {
      const result = await fn(requestId);
      if (epoch !== S.epoch) return { skipped: true };
      delete S.pending[key];
      savePending();
      return { ok: true, result };
    } catch (error) {
      if (epoch !== S.epoch) return { skipped: true };
      if (error.uncertain && error.status !== 401) {
        S.pending[key] = { requestId, sig, reason: error.message, createdAt, deviceId };
      } else {
        delete S.pending[key];
      }
      savePending();
      return { ok: false, error };
    } finally {
      if (epoch === S.epoch) {
        S.busy.delete(key);
        renderBusy();
      }
    }
  }

  // ------------------------------------------------------------ helpers
  function fmtTime(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '';
    const now = new Date();
    if (d.toDateString() === now.toDateString()) return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    return d.toLocaleDateString([], { month: 'short', day: 'numeric' });
  }
  function fmtClock(ms) { return new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }); }
  function fmtAgo(ms) {
    const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
    if (s < 60) return s + ' s ago';
    if (s < 3600) return Math.round(s / 60) + ' min ago';
    return Math.round(s / 3600) + ' h ago';
  }
  function fmtSize(bytes) { return bytes >= 1048576 ? (bytes / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(bytes / 1024)) + ' KB'; }
  function basename(path) {
    if (!path) return 'Unknown folder';
    const parts = String(path).replace(/\/+$/, '').split('/');
    return parts[parts.length - 1] || path;
  }
  function statusEl(status) {
    const st = STATUS_LABELS[status] ? status : 'unknown';
    return el('span', { cls: 'status ' + st, text: STATUS_LABELS[st] });
  }
  function isNarrow() { return narrowQuery.matches; }
  function sessionPath(sid, suffix) { return '/api/v1/sessions/' + encodeURIComponent(sid) + (suffix || ''); }
  function getSession(sid) { return S.byId.get(sid) || S.alias.get(sid) || S.extra.get(sid) || null; }
  function parentOf(s) { return s && s.parentSessionId ? S.alias.get(s.parentSessionId) || null : null; }
  function childrenOf(s) { return S.sessions.filter((c) => c.id !== s.id && parentOf(c) === s); }

  function showView(name) {
    for (const id of ['loadingView', 'offlineView', 'welcomeView', 'dashView']) $(id).hidden = id !== name;
    const dash = name === 'dashView';
    document.body.classList.toggle('dash-mode', dash);
    $('newChatBtn').hidden = !dash;
    $('logoutBtn').hidden = !dash;
    $('apkTopLink').hidden = !dash;
    $('syncStatus').hidden = !dash;
    if (!dash) document.body.classList.remove('show-detail');
  }

  // -------------------------------------------------------------- boot
  async function boot() {
    showView('loadingView');
    loadApkInfo();
    let me;
    try {
      me = await api('GET', '/web/me');
    } catch (e) {
      if (e.status === 401) { showWelcome(null); return; }
      $('offlineText').textContent = e.status === 0
        ? 'The Mac helper didn’t answer. Check that the Mac is awake and on your tailnet.'
        : 'The Mac helper returned an error: ' + e.message;
      showView('offlineView');
      return;
    }
    startDashboard(me);
  }

  async function loadApkInfo() {
    let info = null;
    try {
      const res = await fetch('/web/apk-info', { credentials: 'same-origin', cache: 'no-store' });
      if (res.ok) info = await res.json();
    } catch { info = null; }
    S.apkInfo = info;
    renderApkInfo($('apkInfo'), info);
    renderApkInfo($('apkInfoDash'), info);
  }

  function renderApkInfo(container, info) {
    if (!container) return;
    if (!info) {
      container.replaceChildren(el('p', { cls: 'muted', text: 'APK details unavailable right now.' }),
        el('a', { cls: 'btn primary', text: 'Download APK', attrs: { href: '/apk' } }));
      return;
    }
    if (!info.available) {
      container.replaceChildren(el('p', { cls: 'muted', text: 'No signed APK has been built yet.' }));
      return;
    }
    const meta = [info.version ? 'Version ' + info.version : null, info.size ? fmtSize(info.size) : null].filter(Boolean).join(' · ');
    container.replaceChildren(
      el('a', { cls: 'btn primary', text: 'Download ' + (info.name || 'APK'), attrs: { href: info.url || '/apk', download: info.name || true } }),
      el('div', { cls: 'apk-meta', text: meta }),
      el('div', { cls: 'hash', text: 'SHA-256 ' + (info.sha256 || '') }),
    );
  }

  // ----------------------------------------------------------- pairing
  function showWelcome(reason) {
    const notice = $('welcomeNotice');
    notice.hidden = !reason;
    notice.textContent = reason || '';
    $('pairError').hidden = true;
    showView('welcomeView');
  }

  function extractCode(raw) {
    const text = String(raw || '').trim();
    const m = /[?&]code=([^&#\s]+)/.exec(text);
    if (m) {
      try { return decodeURIComponent(m[1]); } catch { return m[1]; }
    }
    return text;
  }

  let pairing = false;
  async function onPair(ev) {
    ev.preventDefault();
    if (pairing) return;
    const code = extractCode($('pairCode').value);
    const errEl = $('pairError');
    if (code.replace(/[\s-]/g, '').length < 6) {
      errEl.textContent = 'Enter the code shown by “agentdeck pair”.';
      errEl.hidden = false;
      return;
    }
    pairing = true;
    $('pairBtn').disabled = true;
    errEl.hidden = true;
    try {
      await api('POST', '/web/pair', { code, deviceName: $('pairName').value.trim() || undefined });
      $('pairCode').value = '';
      const me = await api('GET', '/web/me');
      startDashboard(me);
    } catch (e) {
      errEl.textContent = e.code === 'invalid_pairing_code'
        ? 'That code is invalid or expired. Create a new one with “agentdeck pair”.'
        : e.code === 'rate_limited' ? 'Too many attempts. Wait a few minutes and try again.' : e.message;
      errEl.hidden = false;
    } finally {
      pairing = false;
      $('pairBtn').disabled = false;
    }
  }

  function onUnauthorized() {
    if (S.stopped) return;  // boot and pairing handle their own 401s
    signedOut('This browser is no longer paired. Unsent drafts are kept in this tab. Pair again, then check the chat before retrying a message.', false);
  }

  function signedOut(reason, clearDrafts = true) {
    S.epoch += 1;
    S.stopped = true;
    clearTimeout(S.timer);
    S.timer = null;
    S.me = null;
    S.sessions = [];
    S.byId = new Map();
    S.alias = new Map();
    S.extra = new Map();
    S.listSig = '';
    S.selectedId = null;
    S.selGen += 1;
    S.detail = null;
    if (clearDrafts) S.pending = {};
    S.busy.clear();
    S.notes.clear();
    S.expanded.clear();
    S.expandedMessages.clear();
    if (clearDrafts) {
      drafts.clear();
      store.clearAll();
    }
    $('sessionList').replaceChildren();
    $('messages').replaceChildren();
    $('terminalText').textContent = '';
    $('composerText').value = '';
    const dlg = $('newChatDialog');
    if (dlg.open) dlg.close();
    showWelcome(reason);
  }

  async function onLogout() {
    if (!window.confirm('Sign out this browser? It will need a new pairing code to reconnect.')) return;
    $('logoutBtn').disabled = true;
    try {
      await api('POST', '/web/logout', {});
      signedOut('Signed out. This browser has been unpaired.');
    } catch (e) {
      if (e.status !== 401) showBanner('Couldn’t sign out: ' + e.message);
    } finally {
      $('logoutBtn').disabled = false;
    }
  }

  function showBanner(text) {
    $('syncStatus').hidden = false;
    $('syncStatus').className = 'sync stale';
    $('syncStatus').textContent = text;
  }

  // ----------------------------------------------------------- polling
  function startDashboard(me) {
    S.me = me;
    S.stopped = false;
    S.epoch += 1;
    showView('dashView');
    renderList(true);
    renderDetail();
    renderSync();
    tick();
  }

  function schedule(ms) {
    clearTimeout(S.timer);
    S.timer = null;
    if (S.stopped || document.visibilityState !== 'visible') return;
    S.timer = setTimeout(tick, ms);
  }

  function refreshSoon() {
    if (S.polling) { S.pollAgain = true; return; }
    schedule(250);
  }

  async function tick() {
    if (S.stopped || document.visibilityState !== 'visible') return;
    if (S.polling) { S.pollAgain = true; return; }
    S.polling = true;
    S.pollAgain = false;
    const epoch = S.epoch;
    try {
      await refreshAll(epoch);
    } finally {
      S.polling = false;
      if (epoch === S.epoch) schedule(S.pollAgain ? 250 : POLL_MS);
      else if (!S.stopped) schedule(250);  // re-paired while an old poll was in flight
    }
  }

  async function refreshAll(epoch) {
    try {
      const data = await api('GET', '/api/v1/sessions');
      if (epoch !== S.epoch) return;
      setSessions(Array.isArray(data.sessions) ? data.sessions : []);
      S.lastOk = Date.now();
      S.lastError = null;
    } catch (e) {
      if (epoch !== S.epoch) return;
      S.lastError = e;
    }
    renderSync();
    renderList(false);
    if (S.selectedId) await refreshDetail(S.selectedId, S.selGen, epoch);
  }

  function setSessions(list) {
    S.sessions = list.filter((s) => s && typeof s.id === 'string');
    S.byId = new Map(S.sessions.map((s) => [s.id, s]));
    S.alias = new Map();
    for (const s of S.sessions) S.alias.set(s.id, s);
    for (const s of S.sessions) {
      const key = s.nativeId ? s.agent + ':' + s.nativeId : null;
      if (key && !S.alias.has(key)) S.alias.set(key, s);
    }
    for (const id of [...S.extra.keys()]) if (S.byId.has(id)) S.extra.delete(id);
  }

  function renderSync() {
    const node = $('syncStatus');
    if (S.stopped) return;
    node.replaceChildren();
    if (S.lastError) {
      node.className = 'sync stale';
      const base = S.lastError.status === 0 ? 'Offline — can’t reach the Mac.' : 'Update failed: ' + S.lastError.message;
      const when = S.lastOk ? ' Showing data from ' + fmtClock(S.lastOk) + ' (' + fmtAgo(S.lastOk) + '). Retrying.' : ' Retrying.';
      node.append(el('span', { cls: 'dot' }), base + when);
    } else if (S.lastOk) {
      node.className = 'sync';
      const paused = document.visibilityState !== 'visible' ? ' · paused in background' : '';
      node.append(el('span', { cls: 'dot' }), 'Live · updated ' + fmtClock(S.lastOk) + paused);
    } else {
      node.className = 'sync';
      node.textContent = 'Loading chats…';
    }
  }

  // ------------------------------------------------------------- list
  function matches(s) {
    const f = S.filter;
    if (f.agent !== 'all' && s.agent !== f.agent) return false;
    if (f.scope === 'managed' && !s.managed) return false;
    if (f.scope === 'active' && s.status !== 'working' && s.status !== 'needs_input') return false;
    if (f.q) {
      const hay = [s.title, s.task, s.lastMessage, s.cwd, s.projectRoot, s.agentName, s.agentRole, s.model, s.stage]
        .filter(Boolean).join('\n').toLowerCase();
      if (!hay.includes(f.q)) return false;
    }
    return true;
  }

  function buildTree() {
    const kids = new Map();
    const tops = [];
    for (const s of S.sessions) {
      const p = parentOf(s);
      if (p && p !== s) {
        if (!kids.has(p.id)) kids.set(p.id, []);
        kids.get(p.id).push(s);
      } else {
        tops.push(s);
      }
    }
    const byTime = (a, b) => String(b.updatedAt || '').localeCompare(String(a.updatedAt || ''));
    for (const list of kids.values()) list.sort(byTime);
    tops.sort(byTime);
    return { tops, kids };
  }

  function filtersActive() { return S.filter.q !== '' || S.filter.agent !== 'all' || S.filter.scope !== 'all'; }

  function renderList(force) {
    const sig = JSON.stringify([S.sessions, S.filter, S.selectedId, [...S.expanded], [...S.collapsedProjects]]);
    if (!force && sig === S.listSig) return;
    S.listSig = sig;
    const container = $('sessionList');
    const scroll = container.scrollTop;
    const { tops, kids } = buildTree();
    const active = filtersActive();
    const visible = new Map();
    const seen = new Set();
    const isVisible = (s) => {
      if (visible.has(s.id)) return visible.get(s.id);
      if (seen.has(s.id)) return false;  // cycle guard
      seen.add(s.id);
      const v = matches(s) || (kids.get(s.id) || []).some(isVisible);
      visible.set(s.id, v);
      return v;
    };

    const groups = new Map();
    for (const s of tops) {
      if (!isVisible(s)) continue;
      const key = s.projectRoot || s.cwd || '';
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(s);
    }

    const frag = document.createDocumentFragment();
    if (!S.lastOk && !S.lastError) {
      frag.append(el('p', { cls: 'list-empty', text: 'Loading chats…' }));
    } else if (S.sessions.length === 0) {
      frag.append(el('p', { cls: 'list-empty', text: S.lastError ? 'No chats loaded yet.' : 'No Claude Code or Codex chats found on the Mac yet.' }));
    } else if (groups.size === 0) {
      frag.append(el('p', { cls: 'list-empty', text: 'No chats match these filters.' }));
    }

    for (const [key, list] of groups) {
      const collapsed = S.collapsedProjects.has(key) && !active;
      const head = el('button', {
        cls: 'project-head',
        attrs: { type: 'button', 'aria-expanded': collapsed ? 'false' : 'true', title: key || 'Unknown folder' },
        on: { click: () => { toggleSet(S.collapsedProjects, key); renderList(true); } },
      }, el('span', { cls: 'chev' }), el('span', { text: basename(key) }), el('span', { cls: 'path', text: key }),
      el('span', { text: String(list.length) }));
      const section = el('div', { cls: 'project' }, head);
      if (!collapsed) {
        for (const s of list) section.append(renderNode(s, kids, active, isVisible, 0));
      }
      frag.append(section);
    }
    container.replaceChildren(frag);
    container.scrollTop = scroll;
  }

  function toggleSet(set, key) { if (set.has(key)) set.delete(key); else set.add(key); }

  function renderNode(s, kids, active, isVisible, depth) {
    const children = (kids.get(s.id) || []).filter(isVisible);
    const allChildren = kids.get(s.id) || [];
    const expanded = depth < 8 && children.length > 0 && (S.expanded.has(s.id) || (active && children.length > 0));
    const node = el('div', { cls: 'node' });
    const row = el('div', { cls: 'node-row' }, sessionButton(s, allChildren.length));
    if (allChildren.length > 0) {
      const label = (expanded ? 'Hide ' : 'Show ') + allChildren.length + (allChildren.length === 1 ? ' subagent' : ' subagents');
      row.append(el('button', {
        cls: 'toggle',
        attrs: { type: 'button', 'aria-expanded': expanded ? 'true' : 'false', 'aria-label': label, title: label },
        on: { click: () => { toggleSet(S.expanded, s.id); renderList(true); } },
      }, el('span', { cls: 'chev' })));
    }
    node.append(row);
    if (expanded) {
      const box = el('div', { cls: 'children' });
      for (const c of children) box.append(renderNode(c, kids, active, isVisible, depth + 1));
      node.append(box);
    }
    return node;
  }

  function sessionButton(s, childCount) {
    const sub = el('span', { cls: 'session-sub' }, statusEl(s.status),
      el('span', { text: AGENT_LABELS[s.agent] || s.agent }));
    if (s.isSubagent && s.agentRole) sub.append(el('span', { cls: 'tag', text: s.agentRole }));
    if (!s.managed) sub.append(el('span', { cls: 'tag', text: 'read-only' }));
    if (childCount) sub.append(el('span', { text: childCount + (childCount === 1 ? ' subagent' : ' subagents') }));
    const t = fmtTime(s.updatedAt);
    if (t) sub.append(el('span', { text: t }));
    const btn = el('button', {
      cls: 'session-btn',
      attrs: { type: 'button', 'aria-current': s.id === S.selectedId ? 'true' : 'false' },
      on: { click: () => select(s.id) },
    }, el('span', { cls: 'session-title', text: s.title || s.id }), sub);
    const last = s.stage || s.lastMessage;
    if (last) btn.append(el('span', { cls: 'session-last', text: last }));
    return btn;
  }

  // ----------------------------------------------------------- detail
  function select(sid, opts) {
    const o = opts || {};
    if (S.selectedId && S.selectedId !== sid) setDraft(S.selectedId, $('composerText').value);
    const changed = S.selectedId !== sid;
    S.selectedId = sid;
    if (changed) {
      S.selGen += 1;
      S.detail = emptyDetail();
      S.tab = 'messages';
      S.expandedMessages.clear();
      $('composerText').value = getDraft(sid);
      $('messages').replaceChildren(el('p', { cls: 'muted', text: 'Loading messages…' }));
      $('terminalText').textContent = '';
    }
    if (isNarrow() && o.push !== false && !(window.history.state && window.history.state.sid === sid)) {
      window.history.pushState({ sid }, '');
    }
    setDetailVisible(true);
    renderList(true);
    renderDetail();
    $('detailScroll').scrollTop = 0;
    if (changed) refreshDetail(sid, S.selGen, S.epoch);
  }

  function setDetailVisible(on) {
    $('dashView').classList.toggle('show-detail', on);
    document.body.classList.toggle('show-detail', on);
    $('backBtn').hidden = !on;
  }

  function goBack() {
    if (window.history.state && window.history.state.sid) window.history.back();
    else setDetailVisible(false);
  }

  async function refreshDetail(sid, gen, epoch) {
    if (S.detailInFlight === gen) return;
    S.detailInFlight = gen;
    const s = getSession(sid);
    const managed = !!(s && s.managed);
    const wantApprovals = managed && s.capabilities && s.capabilities.approve;
    const wantTerminal = managed && S.tab === 'terminal';
    try {
      const [msgs, term, appr] = await Promise.allSettled([
        api('GET', sessionPath(sid, '/messages?limit=' + MESSAGE_LIMIT)),
        wantTerminal ? api('GET', sessionPath(sid, '/terminal')) : Promise.resolve(null),
        wantApprovals ? api('GET', sessionPath(sid, '/approvals')) : Promise.resolve({ approvals: [] }),
      ]);
      if (epoch !== S.epoch || gen !== S.selGen || sid !== S.selectedId) return;
      const d = S.detail;
      d.error = null;
      if (msgs.status === 'fulfilled') d.messages = Array.isArray(msgs.value.messages) ? msgs.value.messages : [];
      else d.error = msgs.reason;
      if (term.status === 'fulfilled' && term.value) d.terminal = term.value;
      else if (term.status === 'rejected') d.error = d.error || term.reason;
      if (appr.status === 'fulfilled') {
        d.approvals = Array.isArray(appr.value.approvals) ? appr.value.approvals : [];
        d.approvalsAt = Date.now();
        d.approvalsError = null;
      } else {
        d.approvalsError = appr.reason;
      }
      renderDetail();
    } finally {
      if (S.detailInFlight === gen) S.detailInFlight = null;
    }
  }

  function renderDetail() {
    const sid = S.selectedId;
    const s = sid ? getSession(sid) : null;
    $('detailEmpty').hidden = !!sid;
    $('detail').hidden = !sid;
    if (!sid) return;
    const d = S.detail || emptyDetail();
    const ops = Object.keys(S.pending).concat([...S.busy]).filter((k) => k.includes(sid) || (s && k.includes(s.id)));
    const opState = ops.map((k) => [k, S.busy.has(k), !!S.pending[k]]);
    const kids = s ? childrenOf(s) : [];
    patch($('detailHead'), [sid, s, parentOf(s || {}), opState, S.notes.get('head:' + sid) || null], () => renderHead(sid, s));
    patch($('detailNotice'), [s && s.isSubagent, s && s.managed, s && s.live, s && s.status, s && s.canResume, s && s.capabilities], () => renderNotice(s));
    patch($('detailChildren'), [sid, kids, kids.map((c) => childrenOf(c).length), S.selectedId], () => renderChildren(s));
    patch($('detailApprovals'), [sid, s && s.managed, s && s.capabilities, d.approvals, d.approvalsAt, d.approvalsError && d.approvalsError.message,
      opState, S.notes.get('approval:' + sid) || null], () => renderApprovals(sid, s, d));
    renderTabs(s);
    const errNode = $('detailError');
    if (d.error) {
      errNode.textContent = (d.error.status === 0 ? 'Can’t reach the Mac. ' : '') + 'Couldn’t refresh this chat: ' + d.error.message
        + (d.messages ? ' Showing the last loaded messages.' : '');
      errNode.hidden = false;
    } else {
      errNode.hidden = true;
    }
    renderMessages(d);
    renderTerminal(s, d);
    renderComposer(sid, s);
  }

  // Rebuild a region only when its inputs changed, so 5 s polling doesn't steal focus.
  const regionSigs = new WeakMap();
  function patch(node, inputs, build) {
    const sig = JSON.stringify(inputs);
    if (regionSigs.get(node) === sig) return;
    build();
    regionSigs.set(node, sig);
  }

  function renderHead(sid, s) {
    const head = $('detailHead');
    if (!s) {
      head.replaceChildren(el('h1', { text: 'Chat' }), el('p', { cls: 'muted', text: 'This chat is no longer listed on the Mac.' }));
      return;
    }
    const meta = el('div', { cls: 'meta' }, statusEl(s.status), el('span', { text: AGENT_LABELS[s.agent] || s.agent }));
    if (s.model) meta.append(el('span', { text: s.model }));
    if (s.agentRole) meta.append(el('span', { cls: 'tag', text: s.agentRole }));
    meta.append(el('span', { text: s.managed ? 'Managed by Agent Deck' : 'Read-only' }));
    if (s.updatedAt) meta.append(el('span', { text: 'Updated ' + fmtTime(s.updatedAt) }));
    const nodes = [el('h1', { text: s.title || s.id }), meta];
    if (s.cwd) nodes.push(el('div', { cls: 'meta' }, el('span', { cls: 'path', text: s.cwd })));
    if (s.isSubagent) {
      const p = parentOf(s);
      if (p) {
        nodes.push(el('a', {
          cls: 'parent-link', text: 'Parent: ' + (p.title || p.id), attrs: { href: '#' },
          on: { click: (ev) => { ev.preventDefault(); select(p.id); } },
        }));
      } else {
        nodes.push(el('p', { cls: 'muted small', text: 'Parent chat is not listed right now.' }));
      }
    }
    if (s.task) nodes.push(el('div', { cls: 'task', text: s.task }));
    nodes.push(renderProgress(s));
    if (s.statusEvidence) nodes.push(el('p', { cls: 'muted small', text: 'Status from ' + s.statusEvidence }));
    const actions = el('div', { cls: 'head-actions' });
    if (s.managed && s.capabilities && s.capabilities.stop && (s.status === 'working' || s.status === 'needs_input')) {
      const key = 'stop:' + s.id;
      actions.append(el('button', {
        cls: 'btn danger', text: S.busy.has(key) ? 'Stopping…' : (S.pending[key] ? 'Retry stop' : 'Stop'),
        attrs: { type: 'button', disabled: S.busy.has(key) },
        on: { click: () => onStop(s) },
      }));
    }
    if (!s.managed && !s.isSubagent && s.canResume && s.status !== 'working' && s.status !== 'needs_input' && !s.live) {
      const key = 'resume:' + s.id;
      actions.append(el('button', {
        cls: 'btn', text: S.busy.has(key) ? 'Opening…' : 'Resume in Agent Deck',
        attrs: { type: 'button', disabled: S.busy.has(key) },
        on: { click: () => onResume(s) },
      }));
    }
    if (actions.childNodes.length) nodes.push(actions);
    const note = S.notes.get('head:' + s.id);
    if (note) nodes.push(el('p', { cls: note.error ? 'form-error' : 'muted small', text: note.text }));
    head.replaceChildren(...nodes.filter(Boolean));
  }

  function renderProgress(s) {
    const p = s.progress;
    if (p && typeof p.current === 'number' && typeof p.total === 'number' && p.total > 0) {
      const pct = Math.max(0, Math.min(100, (p.current / p.total) * 100));
      const label = (s.stage ? s.stage + ' · ' : '') + p.current + ' / ' + p.total + (p.unit ? ' ' + p.unit : '') + ' (' + Math.round(pct) + '%)';
      const fill = el('span');
      fill.style.width = pct + '%';
      return el('div', { cls: 'progress' }, el('div', { cls: 'progress-label', text: label }),
        el('div', { cls: 'bar', attrs: { role: 'progressbar', 'aria-valuemin': 0, 'aria-valuemax': p.total, 'aria-valuenow': p.current } }, fill));
    }
    if (s.status === 'working') {
      return el('div', { cls: 'progress' }, el('div', { cls: 'progress-label', text: s.stage || 'Working' }),
        el('div', { cls: 'bar indeterminate', attrs: { role: 'progressbar', 'aria-label': 'In progress, total unknown' } }, el('span')));
    }
    if (s.stage) return el('div', { cls: 'progress' }, el('div', { cls: 'progress-label', text: s.stage }));
    return null;
  }

  function renderNotice(s) {
    const box = $('detailNotice');
    box.replaceChildren();
    if (!s) return;
    let text = null;
    if (s.isSubagent) text = 'Subagent — read-only. Send follow-ups through the parent chat.';
    else if (!s.managed && (s.live || s.status === 'working' || s.status === 'needs_input')) text = 'Read-only: this chat is active in another terminal. Agent Deck won’t open a second copy of a live agent.';
    else if (!s.managed && s.canResume) text = 'Read-only: discovered from the transcript. Resume it in Agent Deck to send follow-ups (close it in other terminals first).';
    else if (!s.managed) text = 'Read-only: this chat isn’t attached to Agent Deck.';
    else if (s.capabilities && !s.capabilities.send) text = 'Sending isn’t available for this session.';
    if (text) box.append(el('div', { cls: 'notice' }, el('p', { text })));
  }

  function renderChildren(s) {
    const box = $('detailChildren');
    box.replaceChildren();
    if (!s) return;
    const kids = childrenOf(s);
    if (!kids.length) return;
    kids.sort((a, b) => String(b.updatedAt || '').localeCompare(String(a.updatedAt || '')));
    const list = el('div', { cls: 'child-list' });
    for (const c of kids) list.append(sessionButton(c, childrenOf(c).length));
    box.append(el('div', { cls: 'section' }, el('h2', { text: 'Subagents (' + kids.length + ')' }), list));
  }

  function renderApprovals(sid, s, d) {
    const box = $('detailApprovals');
    box.replaceChildren();
    if (!s || !s.managed) return;
    const canApprove = s.capabilities && s.capabilities.approve;
    if (!d.approvals.length && !d.approvalsError) {
      const note = S.notes.get('approval:' + sid);
      if (note) box.append(el('div', { cls: 'notice warn' }, el('p', { text: note.text })));
      return;
    }
    const section = el('div', { cls: 'section' }, el('h2', { text: 'Approval requests' }));
    if (d.approvalsError) {
      section.append(el('div', { cls: 'notice warn' }, el('p', {
        text: 'Couldn’t refresh approvals (' + d.approvalsError.message + ').'
          + (d.approvalsAt ? ' The list below is from ' + fmtClock(d.approvalsAt) + ' and may be stale.' : ''),
      })));
    }
    const note = S.notes.get('approval:' + sid);
    if (note) section.append(el('div', { cls: 'notice warn' }, el('p', { text: note.text })));
    for (const a of d.approvals) {
      const card = el('div', { cls: 'approval' }, el('h3', { text: a.title || 'Permission requested' }));
      if (a.detail) card.append(el('pre', { text: a.detail }));
      const row = el('div', { cls: 'row-actions' });
      for (const c of a.choices || []) {
        const key = 'approve:' + sid + ':' + a.id;
        const busy = S.busy.has(key);
        row.append(el('button', {
          cls: 'btn', text: c.label || c.id,
          attrs: { type: 'button', disabled: busy || !canApprove || !!d.approvalsError },
          on: { click: () => onApprove(sid, a, c) },
        }));
      }
      card.append(row);
      section.append(card);
    }
    box.append(section);
  }

  function renderTabs(s) {
    const termAvailable = !!(s && s.managed);
    $('tabTerminal').hidden = !termAvailable;
    if (!termAvailable && S.tab === 'terminal') S.tab = 'messages';
    $('tabMessages').setAttribute('aria-selected', S.tab === 'messages' ? 'true' : 'false');
    $('tabTerminal').setAttribute('aria-selected', S.tab === 'terminal' ? 'true' : 'false');
    $('messages').hidden = S.tab !== 'messages';
    $('terminalPanel').hidden = S.tab !== 'terminal';
  }

  function renderMessages(d) {
    const box = $('messages');
    if (d.messages == null) return;
    const sig = JSON.stringify([d.messages.map((m) => [m.id, String(m.text || '').length, m.role]), [...S.expandedMessages]]);
    if (sig === d.msgSig && box.childNodes.length) return;
    const scroller = $('detailScroll');
    const atBottom = !d.msgSig || scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 60;
    d.msgSig = sig;
    if (!d.messages.length) {
      box.replaceChildren(el('p', { cls: 'muted', text: 'No messages yet.' }));
      return;
    }
    const frag = document.createDocumentFragment();
    if (d.messages.length >= MESSAGE_LIMIT) frag.append(el('p', { cls: 'muted small', text: 'Showing the latest ' + MESSAGE_LIMIT + ' messages.' }));
    for (const m of d.messages) {
      const role = ['user', 'assistant', 'tool', 'system'].includes(m.role) ? m.role : 'system';
      const head = el('div', { cls: 'msg-head' }, el('span', { cls: 'role', text: role === 'tool' && m.toolName ? 'Tool' : role }));
      if (m.toolName) head.append(el('span', { cls: 'tag', text: m.toolName }));
      const t = fmtTime(m.timestamp);
      if (t) head.append(el('span', { text: t }));
      const text = String(m.text || '');
      const long = text.length > LONG_MESSAGE && !S.expandedMessages.has(m.id);
      const card = el('article', { cls: 'msg ' + role }, head,
        el('div', { cls: 'msg-body', text: long ? text.slice(0, LONG_MESSAGE) + '…' : text }));
      if (text.length > LONG_MESSAGE) {
        card.append(el('button', {
          cls: 'btn ghost msg-more', text: long ? 'Show full message' : 'Show less', attrs: { type: 'button' },
          on: { click: () => { toggleSet(S.expandedMessages, m.id); renderMessages(S.detail); } },
        }));
      }
      frag.append(card);
    }
    box.replaceChildren(frag);
    if (atBottom) scroller.scrollTop = scroller.scrollHeight;
  }

  function renderTerminal(s, d) {
    if (S.tab !== 'terminal') return;
    const pre = $('terminalText');
    if (!s || !s.managed) { pre.textContent = 'Terminal preview is only available for chats managed by Agent Deck.'; return; }
    if (!d.terminal) { pre.textContent = 'Loading terminal…'; return; }
    pre.textContent = d.terminal.available ? (d.terminal.text || '(empty screen)') : 'Terminal preview unavailable.';
  }

  function renderComposer(sid, s) {
    const form = $('composer');
    const canSend = !!(s && s.managed && s.capabilities && s.capabilities.send);
    form.hidden = !canSend;
    if (!canSend) return;
    const key = 'send:' + sid;
    const busy = S.busy.has(key);
    const pending = S.pending[key];
    const pendBox = $('sendPending');
    if (pending && !busy) {
      let text = '';
      try { text = JSON.parse(pending.sig)[0]; } catch { text = ''; }
      $('sendPendingText').textContent = 'Your last message may not have been delivered'
        + (pending.reason ? ' (' + pending.reason + ')' : '') + '. Check the messages, then retry with the saved request ID or discard it. “'
        + (text.length > 120 ? text.slice(0, 120) + '…' : text) + '”';
      pendBox.hidden = false;
    } else {
      pendBox.hidden = true;
    }
    const hasInterrupt = !!(s.capabilities && s.capabilities.interrupt);
    $('interruptLabel').hidden = !hasInterrupt;
    $('sendBtn').disabled = busy || !!pending;
    $('sendRetry').disabled = busy;
    $('sendBtn').textContent = busy ? 'Sending…' : 'Send';
    const note = S.notes.get(sid);
    const noteEl = $('composerNote');
    noteEl.hidden = !note;
    noteEl.className = 'composer-note' + (note && note.error ? ' error' : '');
    noteEl.textContent = note ? note.text : '';
  }

  function renderBusy() {
    if (S.selectedId && !$('detail').hidden) renderDetail();
  }

  // ---------------------------------------------------------- actions
  async function sendWith(sid, text, interrupt) {
    const key = 'send:' + sid;
    const sig = JSON.stringify([text, interrupt]);
    S.notes.delete(sid);
    const out = await runOp(key, sig, (requestId) => api('POST', sessionPath(sid, '/send'), { text, interrupt, requestId }));
    if (out.skipped) return;
    if (out.conflict) {
      S.notes.set(sid, { text: 'Resolve the undelivered message above first.', error: true });
    } else if (out.ok) {
      // Clear only what was sent: newer typing and other chats stay untouched.
      if (getDraft(sid) === text) setDraft(sid, '');
      if (S.selectedId === sid && $('composerText').value === text) $('composerText').value = '';
      S.notes.set(sid, { text: interrupt ? 'Sent with interrupt enabled.' : 'Sent.', error: false });
      refreshSoon();
    } else if (out.error.status !== 401) {
      S.notes.set(sid, { text: out.error.uncertain ? 'Delivery unconfirmed.' : 'Not sent: ' + out.error.message, error: true });
    }
    if (S.selectedId === sid) renderDetail();
  }

  function onSubmitComposer(ev) {
    ev.preventDefault();
    const sid = S.selectedId;
    const s = sid && getSession(sid);
    if (!s || !s.managed || !s.capabilities || !s.capabilities.send) return;
    const text = $('composerText').value;  // snapshot before any await
    if (!text.trim()) return;
    const interrupt = !!(s.capabilities.interrupt && $('interruptBox').checked);
    setDraft(sid, text);
    sendWith(sid, text, interrupt);
  }

  function onRetrySend() {
    const sid = S.selectedId;
    const pending = sid && S.pending['send:' + sid];
    if (!pending) return;
    let text, interrupt;
    try { [text, interrupt] = JSON.parse(pending.sig); } catch { return; }
    sendWith(sid, text, interrupt);
  }

  function onDiscardSend() {
    const sid = S.selectedId;
    if (!sid) return;
    delete S.pending['send:' + sid];
    savePending();
    S.notes.set(sid, { text: 'Discarded. Your text is still in the box if you want to edit and send it again.', error: false });
    renderDetail();
  }

  async function onStop(s) {
    const key = 'stop:' + s.id;
    if (!S.pending[key] && !window.confirm('Stop the current turn of “' + (s.title || s.id) + '”?')) return;
    S.notes.delete('head:' + s.id);
    const out = await runOp(key, 'stop', (requestId) => api('POST', sessionPath(s.id, '/stop'), { requestId }));
    if (out.skipped || out.conflict) return;
    if (out.ok) S.notes.set('head:' + s.id, { text: 'Stop requested.', error: false });
    else if (out.error.status !== 401) {
      S.notes.set('head:' + s.id, {
        text: out.error.uncertain ? 'Stop may not have been delivered (' + out.error.message + '). A retry uses the saved request ID.' : 'Couldn’t stop: ' + out.error.message,
        error: true,
      });
    }
    refreshSoon();
    renderDetail();
  }

  async function onResume(s) {
    const key = 'resume:' + s.id;
    S.notes.delete('head:' + s.id);
    const out = await runOp(key, 'resume', (requestId) => api('POST', sessionPath(s.id, '/resume'), { requestId }));
    if (out.skipped || out.conflict) return;
    if (out.ok && out.result && out.result.session && out.result.session.id) {
      const ns = out.result.session;
      S.extra.set(ns.id, ns);
      refreshSoon();
      select(ns.id);
      return;
    }
    if (!out.ok && out.error.status !== 401) {
      S.notes.set('head:' + s.id, {
        text: out.error.uncertain ? 'Resume may have started (' + out.error.message + '). Check the list; a retry uses the saved request ID.' : out.error.message,
        error: true,
      });
    }
    renderDetail();
  }

  async function onApprove(sid, approval, choice) {
    const key = 'approve:' + sid + ':' + approval.id;
    S.notes.delete('approval:' + sid);
    const out = await runOp(key, String(choice.id),
      (requestId) => api('POST', sessionPath(sid, '/approvals/' + encodeURIComponent(approval.id)), { choiceId: choice.id, requestId }));
    if (out.skipped) return;
    if (out.conflict) {
      S.notes.set('approval:' + sid, { text: 'An earlier answer to this request may already have been delivered. Refreshing…' });
    } else if (out.ok) {
      S.notes.set('approval:' + sid, { text: 'Answered: ' + (choice.label || choice.id) });
    } else if (out.error.code === 'stale_approval') {
      S.notes.set('approval:' + sid, { text: 'That request is no longer pending — it was answered or changed in the terminal.' });
    } else if (out.error.status !== 401) {
      S.notes.set('approval:' + sid, {
        text: out.error.uncertain ? 'Answer may not have been delivered (' + out.error.message + '). Choosing the same option reuses the saved request ID.' : 'Couldn’t answer: ' + out.error.message,
      });
    }
    if (S.selectedId === sid && S.detail) S.detail.approvals = S.detail.approvals.filter((a) => a.id !== approval.id || !out.ok);
    refreshSoon();
    renderDetail();
  }

  // --------------------------------------------------------- new chat
  const NC = { models: null, settings: null, loading: false };

  async function openNewChat() {
    const dlg = $('newChatDialog');
    $('ncError').hidden = true;
    if (typeof dlg.showModal === 'function') dlg.showModal();
    else dlg.setAttribute('open', '');
    await loadNewChatData(false);
  }

  async function loadNewChatData(refresh) {
    if (NC.loading) return;
    NC.loading = true;
    $('ncRefreshModels').disabled = true;
    $('ncModelNote').textContent = refresh ? 'Asking the installed CLIs for models…' : 'Loading models…';
    const epoch = S.epoch;
    try {
      const [models, settings] = await Promise.allSettled([
        refresh ? api('POST', '/api/v1/models/refresh', {}) : api('GET', '/api/v1/models'),
        api('GET', '/api/v1/settings'),
      ]);
      if (epoch !== S.epoch) return;
      if (models.status === 'fulfilled') NC.models = models.value;
      if (settings.status === 'fulfilled') NC.settings = settings.value.settings || {};
      const errs = [models, settings].filter((r) => r.status === 'rejected').map((r) => r.reason.message);
      fillNewChat();
      $('ncModelNote').textContent = errs.length ? 'Couldn’t load everything: ' + errs.join('; ') : modelNote();
    } finally {
      NC.loading = false;
      $('ncRefreshModels').disabled = false;
    }
  }

  function agentsList() {
    const list = (NC.models && Array.isArray(NC.models.agents)) ? NC.models.agents : [];
    const known = list.filter((a) => a && (a.id === 'claude' || a.id === 'codex'));
    if (known.length) return known;
    return [{ id: 'claude', name: 'Claude Code', available: true, models: [] }, { id: 'codex', name: 'Codex', available: true, models: [] }];
  }

  function modelNote() {
    const a = agentsList().find((x) => x.id === $('ncAgent').value);
    if (!a) return '';
    const parts = [];
    if (a.modelSource) parts.push('Models from ' + a.modelSource);
    if (a.modelRefreshedAt) parts.push('refreshed ' + fmtTime(a.modelRefreshedAt));
    if (a.error) parts.push(a.error);
    return parts.join(' · ');
  }

  function fillNewChat() {
    const settings = NC.settings || {};
    const agentSel = $('ncAgent');
    const prevAgent = agentSel.value || settings.defaultAgent || 'claude';
    agentSel.replaceChildren();
    for (const a of agentsList()) {
      agentSel.append(el('option', {
        text: (a.name || AGENT_LABELS[a.id] || a.id) + (a.available === false ? ' (not available)' : ''),
        attrs: { value: a.id, disabled: a.available === false },
      }));
    }
    agentSel.value = prevAgent;
    if (!agentSel.value && agentSel.options.length) agentSel.selectedIndex = 0;
    fillModels();

    const folderSel = $('ncFolder');
    const prevFolder = folderSel.value;
    const folders = [];
    for (const w of settings.allowedWorkspaces || []) if (typeof w === 'string' && !folders.includes(w)) folders.push(w);
    for (const s of S.sessions) if (s.cwd && !s.isSubagent && !folders.includes(s.cwd)) folders.push(s.cwd);
    folderSel.replaceChildren(...folders.slice(0, 60).map((f) => el('option', { text: f, attrs: { value: f } })),
      el('option', { text: 'Other folder…', attrs: { value: '__manual__' } }));
    if (prevFolder) folderSel.value = prevFolder;
    if (!folderSel.value && folderSel.options.length) folderSel.selectedIndex = 0;
    $('ncFolderManual').hidden = folderSel.value !== '__manual__';
  }

  function fillModels() {
    const settings = NC.settings || {};
    const agentId = $('ncAgent').value;
    const a = agentsList().find((x) => x.id === agentId);
    const sel = $('ncModel');
    const prev = sel.value;
    const def = (settings.defaultModels || {})[agentId];
    const opts = [el('option', { text: def ? 'Default (' + def + ')' : 'Default (CLI chooses)', attrs: { value: '' } })];
    for (const m of (a && a.models) || []) {
      if (!m || typeof m.id !== 'string') continue;
      opts.push(el('option', { text: m.label && m.label !== m.id ? m.label + ' — ' + m.id : m.id, attrs: { value: m.id } }));
    }
    opts.push(el('option', { text: 'Other model ID…', attrs: { value: '__manual__' } }));
    sel.replaceChildren(...opts);
    sel.value = prev;
    if (sel.value !== prev) sel.value = '';
    $('ncModelManual').hidden = sel.value !== '__manual__';
    $('ncModelNote').textContent = modelNote();
  }

  async function onStartChat(ev, force) {
    if (ev) ev.preventDefault();
    const errEl = $('ncError');
    errEl.hidden = true;
    const agent = $('ncAgent').value;
    let model = $('ncModel').value;
    if (model === '__manual__') {
      model = $('ncModelManual').value.trim();
      if (!MODEL_ID_RE.test(model)) { errEl.textContent = 'Enter a valid model ID (letters, digits and . _ : / @ + [ ] -).'; errEl.hidden = false; return; }
    }
    let cwd = $('ncFolder').value;
    if (cwd === '__manual__') cwd = $('ncFolderManual').value.trim();
    if (!cwd || !cwd.startsWith('/') && !cwd.startsWith('~')) { errEl.textContent = 'Choose a folder (absolute path).'; errEl.hidden = false; return; }
    const prompt = $('ncPrompt').value;
    const body = { agent, cwd };
    if (model) body.model = model;
    if (prompt.trim()) body.prompt = prompt;
    const sig = JSON.stringify(body);
    $('ncSubmit').disabled = true;
    $('ncSubmit').textContent = 'Starting…';
    const out = await runOp('start', sig, (requestId) => api('POST', '/api/v1/sessions', { ...body, requestId }), force);
    $('ncSubmit').disabled = false;
    $('ncSubmit').textContent = 'Start chat';
    if (out.skipped) return;
    if (out.conflict) {
      errEl.replaceChildren(
        el('span', { text: 'An earlier start request may already have created a chat. Check the list first. ' }),
        el('button', { cls: 'btn', text: 'Start anyway', attrs: { type: 'button' }, on: { click: () => onStartChat(null, true) } }),
      );
      errEl.hidden = false;
      return;
    }
    if (out.ok && out.result && out.result.session && out.result.session.id) {
      const ns = out.result.session;
      S.extra.set(ns.id, ns);
      $('ncPrompt').value = '';
      $('newChatDialog').close();
      refreshSoon();
      select(ns.id);
      return;
    }
    if (!out.ok && out.error.status !== 401) {
      errEl.textContent = out.error.uncertain
        ? 'The chat may have started (' + out.error.message + '). Check the list; Start again with the same settings to reuse the saved request ID.'
        : out.error.message;
      errEl.hidden = false;
    }
  }

  // ------------------------------------------------------------ wiring
  function wire() {
    $('pairForm').addEventListener('submit', onPair);
    $('offlineRetry').addEventListener('click', boot);
    $('logoutBtn').addEventListener('click', onLogout);
    $('backBtn').addEventListener('click', goBack);
    $('newChatBtn').addEventListener('click', openNewChat);
    $('composer').addEventListener('submit', onSubmitComposer);
    $('sendRetry').addEventListener('click', onRetrySend);
    $('sendDiscard').addEventListener('click', onDiscardSend);
    $('composerText').addEventListener('input', () => { if (S.selectedId) setDraft(S.selectedId, $('composerText').value); });
    $('composerText').addEventListener('keydown', (ev) => {
      if (ev.key === 'Enter' && (ev.metaKey || ev.ctrlKey)) { ev.preventDefault(); $('composer').requestSubmit(); }
    });
    $('tabMessages').addEventListener('click', () => { S.tab = 'messages'; renderDetail(); });
    $('tabTerminal').addEventListener('click', () => {
      S.tab = 'terminal';
      renderDetail();
      if (S.selectedId) refreshDetail(S.selectedId, S.selGen, S.epoch);
    });
    $('searchInput').addEventListener('input', (ev) => { S.filter.q = ev.target.value.trim().toLowerCase(); renderList(true); });
    for (const b of document.querySelectorAll('.segmented button')) {
      b.addEventListener('click', () => {
        S.filter.agent = b.dataset.agent;
        for (const o of document.querySelectorAll('.segmented button')) o.setAttribute('aria-pressed', o === b ? 'true' : 'false');
        renderList(true);
      });
    }
    $('scopeSelect').addEventListener('change', (ev) => { S.filter.scope = ev.target.value; renderList(true); });
    $('ncAgent').addEventListener('change', fillModels);
    $('ncModel').addEventListener('change', () => { $('ncModelManual').hidden = $('ncModel').value !== '__manual__'; });
    $('ncFolder').addEventListener('change', () => { $('ncFolderManual').hidden = $('ncFolder').value !== '__manual__'; });
    $('ncRefreshModels').addEventListener('click', () => loadNewChatData(true));
    $('ncCancel').addEventListener('click', () => $('newChatDialog').close());
    $('newChatForm').addEventListener('submit', (ev) => onStartChat(ev, false));

    window.addEventListener('popstate', (ev) => {
      const sid = ev.state && ev.state.sid;
      if (sid && getSession(sid)) select(sid, { push: false });
      else setDetailVisible(false);
    });
    narrowQuery.addEventListener('change', () => setDetailVisible(isNarrow() ? $('dashView').classList.contains('show-detail') : false));
    document.addEventListener('visibilitychange', () => {
      if (S.stopped) return;
      if (document.visibilityState === 'visible') tick();
      else { clearTimeout(S.timer); S.timer = null; }
      renderSync();
    });
    window.addEventListener('beforeunload', () => { if (S.selectedId) setDraft(S.selectedId, $('composerText').value); });
    // Keep the "last updated … ago" text honest while offline.
    setInterval(() => { if (!S.stopped && S.lastError) renderSync(); }, 15000);
  }

  wire();
  boot();
})();
