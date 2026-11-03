/*
 * Money Transfer – Live Demo.
 * Plain JavaScript, no build step. Every call goes to the UI service:
 *   /proxy/<service>/<path>   one call to one service, answered exactly as the service answered
 *   /api/ui/burst             the same call N times, released at the same instant by the server
 */
'use strict';

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

const SEED_IDS = ['ACC-1001', 'ACC-2002', 'ACC-EMPTY', 'ACC-FROZEN', 'ACC-USD-1'];
const DEST = 'ACC-1001';               // destination for demo transfers: active, INR
const state = { services: [], accounts: [], hideDemo: true, last: null, log: [] };

// ---------------------------------------------------------------- small helpers

function esc(v) {
  return String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function rand(n = 6) {
  const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
  let s = '';
  const bytes = new Uint8Array(n);
  crypto.getRandomValues(bytes);
  for (const b of bytes) s += chars[b % chars.length];
  return s;
}
function newKey() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return `${rand(8)}-${rand(4)}-${rand(4)}-${rand(4)}-${rand(12)}`;
}
function money(v, currency = '') {
  if (v === null || v === undefined || v === '') return '–';
  const n = Number(v);
  if (Number.isNaN(n)) return esc(v);
  const s = n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return currency === 'INR' || currency === '' ? `₹${s}` : `${s} ${esc(currency)}`;
}
function time(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? esc(iso) : d.toLocaleTimeString([], { hour12: false });
}
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => t.classList.remove('show'), 2600);
}
function errCode(res) { return res?.body?.error?.code ?? ''; }
function errMessage(res) { return res?.body?.error?.message ?? ''; }
function transferIdOf(res) {
  return res?.body?.transferId ?? res?.body?.error?.details?.[0]?.transferId ?? null;
}
function json(v) { return `<pre class="json">${esc(JSON.stringify(v, null, 2))}</pre>`; }

function httpBadge(status) {
  const cls = status >= 200 && status < 300 ? 'good' : status >= 400 && status < 500 ? 'warn' : 'bad';
  return `<span class="badge ${cls}">HTTP ${status || '—'}</span>`;
}
function stateBadge(s) {
  if (!s) return '';
  const good = ['COMPLETED', 'SENT', 'CAPTURED', 'ACTIVE', 'UP', 'RESERVED', 'HELD', 'TRANSFER'];
  const warn = ['REJECTED', 'RELEASED', 'FROZEN', 'COMPENSATING', 'PENDING', 'REVERSAL'];
  const bad = ['FAILED', 'DOWN', 'CLOSED'];
  const cls = good.includes(s) ? 'good' : warn.includes(s) ? 'warn' : bad.includes(s) ? 'bad' : 'info';
  return `<span class="badge ${cls}">${esc(s)}</span>`;
}
function checks(items) {
  return `<ul class="checks">${items.map(c =>
    `<li class="${c.ok === null ? 'info' : c.ok ? 'pass' : 'fail'}">${c.text}</li>`).join('')}</ul>`;
}
function verdict(items, passText, failText) {
  const graded = items.filter(c => c.ok !== null);
  const ok = graded.length > 0 && graded.every(c => c.ok);
  return `<div class="verdict ${ok ? 'pass' : 'fail'}">${ok ? passText : failText}</div>`;
}
function answerLine(res) {
  const parts = [httpBadge(res.status), `<span class="hint">${res.ms} ms</span>`];
  if (res.headers?.replayed === 'true') parts.push('<span class="badge info">Idempotent-Replayed: true</span>');
  if (errCode(res)) parts.push(`<code>${esc(errCode(res))}</code>`);
  return `<div class="inline" style="flex-wrap:wrap">${parts.join(' ')}</div>`;
}
async function busy(button, fn) {
  const was = button.textContent;
  button.disabled = true;
  button.textContent = 'Working…';
  try { return await fn(); }
  catch (e) { console.error(e); toast(e.message || String(e)); }
  finally { button.disabled = false; button.textContent = was; }
}

// ---------------------------------------------------------------- calls

async function api(service, method, path, { body, headers = {}, quiet = false } = {}) {
  const requestId = `ui-${rand(8)}`;
  const h = { 'X-Request-ID': requestId, ...headers };
  let payload;
  if (body !== undefined) {
    h['Content-Type'] = 'application/json';
    payload = typeof body === 'string' ? body : JSON.stringify(body);
  }
  const t0 = performance.now();
  let status = 0, text = '', resHeaders = {};
  try {
    const r = await fetch(`/proxy/${service}${path}`, { method, headers: h, body: payload });
    status = r.status;
    text = await r.text();
    resHeaders = { replayed: r.headers.get('Idempotent-Replayed'), requestId: r.headers.get('X-Request-ID') };
  } catch (e) {
    text = JSON.stringify({ error: { code: 'UI_UNREACHABLE', message: `The UI service did not answer: ${e.message}` } });
  }
  let parsed = null;
  try { parsed = text ? JSON.parse(text) : null; } catch { parsed = text; }
  const res = {
    service, method, path, status, ok: status >= 200 && status < 300, body: parsed,
    headers: resHeaders, ms: Math.round(performance.now() - t0), requestId, requestBody: payload, at: new Date(),
  };
  if (!quiet) addLog(res);
  return res;
}

/** The same call N times at the same instant (fired by the UI service, not the browser). */
async function burst(service, method, path, bodies, headers = {}) {
  const requestId = `ui-${rand(8)}`;
  const t0 = performance.now();
  const r = await fetch('/api/ui/burst', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Request-ID': requestId },
    body: JSON.stringify({ service, method, path, headers, bodies }),
  });
  const ms = Math.round(performance.now() - t0);
  const list = await r.json();
  if (!Array.isArray(list)) throw new Error(list?.error?.message || 'burst failed');
  const results = list.map(a => ({
    status: a.status, ok: a.status >= 200 && a.status < 300, body: a.body,
    headers: { replayed: a.headers?.['Idempotent-Replayed'] ?? null, requestId: a.headers?.['X-Request-ID'] ?? null },
  }));
  addLog({
    service, method, path: `${path}  (burst ×${bodies.length})`, status: summarise(results.map(x => x.status)),
    ms, requestId: `${requestId}-1…${bodies.length}`, requestBody: JSON.stringify(bodies[0]),
    body: results, at: new Date(), burst: true,
  });
  return results;
}
function summarise(statuses) {
  const counts = {};
  statuses.forEach(s => { counts[s] = (counts[s] || 0) + 1; });
  return Object.entries(counts).map(([s, n]) => `${n}× ${s}`).join(', ');
}

// Common operations
async function openAccount(balance, label, currency = 'INR') {
  const id = `UI-${label}-${rand(5).toUpperCase()}`;
  const r = await api('account', 'POST', '/api/v1/accounts', {
    body: { accountId: id, holderName: `Demo ${label}`, currency, initialBalance: balance },
    quiet: false,
  });
  if (!r.ok) throw new Error(`Could not open a demo account: HTTP ${r.status} ${errCode(r)} ${errMessage(r)}`);
  return id;
}
async function getAccount(id) {
  const r = await api('account', 'GET', `/api/v1/accounts/${encodeURIComponent(id)}`);
  return r.ok ? r.body : null;
}
function sendTransfer(source, destination, amount, key, currency = 'INR') {
  const headers = key ? { 'Idempotency-Key': key } : {};
  return api('transfer', 'POST', '/api/v1/transfers', {
    body: { sourceAccountId: source, destinationAccountId: destination, amount, currency }, headers,
  });
}

// ---------------------------------------------------------------- request log

function addLog(entry) {
  state.log.unshift(entry);
  if (state.log.length > 300) state.log.length = 300;
  $('#log-count').textContent = state.log.length;
  if ($('#tab-log').classList.contains('active')) renderLog();
}
function renderLog() {
  const rows = state.log.map((e, i) => {
    const st = typeof e.status === 'number' ? httpBadge(e.status) : `<span class="badge info">${esc(e.status)}</span>`;
    return `<tr class="clickable" data-i="${i}"><td class="mono">${e.at.toLocaleTimeString([], { hour12: false })}</td>
      <td>${esc(e.service)}</td><td class="mono">${esc(e.method)} ${esc(e.path)}</td><td>${st}</td>
      <td class="num">${e.ms}</td><td class="mono">${esc(e.requestId)}</td></tr>`;
  }).join('');
  $('#log-table tbody').innerHTML = rows || '<tr><td colspan="6" class="empty">No calls yet.</td></tr>';
}
$('#log-table').addEventListener('click', ev => {
  const tr = ev.target.closest('tr.clickable');
  if (!tr) return;
  const next = tr.nextElementSibling;
  if (next && next.classList.contains('detail')) { next.remove(); return; }
  const e = state.log[Number(tr.dataset.i)];
  let reqBody = e.requestBody;
  try { reqBody = reqBody ? JSON.stringify(JSON.parse(reqBody), null, 2) : '(no body)'; } catch { /* keep text */ }
  const row = document.createElement('tr');
  row.className = 'detail';
  row.innerHTML = `<td colspan="6"><div class="grid two">
      <div><b>Request</b><pre class="json">${esc(reqBody)}</pre></div>
      <div><b>Answer</b>${json(e.body)}</div></div></td>`;
  tr.after(row);
});
$('#log-clear').addEventListener('click', () => { state.log = []; $('#log-count').textContent = 0; renderLog(); });

// ---------------------------------------------------------------- tabs

function showTab(name) {
  $$('.tabs button').forEach(b => b.classList.toggle('active', b.dataset.tab === name));
  $$('.tab').forEach(t => t.classList.toggle('active', t.id === `tab-${name}`));
  if (name === 'overview') { loadAccounts(); renderHealthTable(); }
  if (name === 'c3') loadFaults();
  if (name === 'resilience') loadProvider();
  if (name === 'log') renderLog();
  try { localStorage.setItem('mt-tab', name); } catch { /* private mode */ }
}
$$('.tabs button').forEach(b => b.addEventListener('click', () => showTab(b.dataset.tab)));

// ---------------------------------------------------------------- health

const PORTS = { transfer: 8082, account: 8081, ledger: 8083, limits: 8084, notification: 8085 };
async function checkHealth() {
  const results = await Promise.all(state.services.map(async s => {
    const r = await api(s.name, 'GET', '/health', { quiet: true });
    return { ...s, res: r, up: r.ok && r.body?.status === 'UP' };
  }));
  state.health = results;
  $('#health-strip').innerHTML = ['<span class="chip"><span class="dot up"></span>ui</span>']
    .concat(results.map(h => `<span class="chip" title="${esc(h.url)}"><span class="dot ${h.up ? 'up' : 'down'}"></span>${esc(h.name)}</span>`))
    .join('');
  return results;
}
async function renderHealthTable() {
  const results = await checkHealth();
  $('#health-table tbody').innerHTML = results.map(h => {
    const db = h.res.body?.checks?.database;
    const why = h.up ? '' : `<div class="hint">${esc(errCode(h.res) || '')} ${esc(errMessage(h.res) || '')}</div>`;
    return `<tr><td><b>${esc(h.name)}</b></td><td class="mono">${esc(h.url)}</td>
      <td>${stateBadge(h.up ? 'UP' : 'DOWN')}${why}</td><td>${db ? `database ${stateBadge(db)}` : ''}</td></tr>`;
  }).join('');
}
$('#health-refresh').addEventListener('click', renderHealthTable);

// ---------------------------------------------------------------- accounts

async function loadAccounts() {
  const r = await api('account', 'GET', '/api/v1/accounts', { quiet: true });
  if (!r.ok) {
    $('#accounts').innerHTML = `<p class="empty">Could not load accounts: ${answerLine(r)} ${esc(errMessage(r))}</p>`;
    return;
  }
  state.accounts = Array.isArray(r.body) ? r.body : [];
  $('#account-ids').innerHTML = state.accounts.map(a => `<option value="${esc(a.accountId)}">`).join('');
  renderAccounts();
}
function renderAccounts() {
  const list = state.accounts
    .filter(a => !state.hideDemo || !a.accountId.startsWith('UI-'))
    .sort((a, b) => (SEED_IDS.indexOf(a.accountId) + 1 || 99) - (SEED_IDS.indexOf(b.accountId) + 1 || 99));
  const hidden = state.accounts.length - list.length;
  const rows = list.map(a => {
    const toggle = a.status === 'ACTIVE' ? 'FROZEN' : a.status === 'FROZEN' ? 'ACTIVE' : null;
    return `<tr><td class="mono"><b>${esc(a.accountId)}</b></td><td>${esc(a.holderName)}</td><td>${esc(a.currency)}</td>
      <td class="num">${money(a.balance, a.currency)}</td><td class="num">${money(a.available, a.currency)}</td>
      <td class="num">${money(a.onHold, a.currency)}</td><td>${stateBadge(a.status)}</td>
      <td><span class="inline"><button class="ghost small" data-view="${esc(a.accountId)}">Details</button>
      ${toggle ? `<button class="ghost small" data-status="${toggle}" data-id="${esc(a.accountId)}">${toggle === 'FROZEN' ? 'Freeze' : 'Unfreeze'}</button>` : ''}</span></td></tr>`;
  }).join('');
  $('#accounts').innerHTML = `
    <label class="check"><input type="checkbox" id="hide-demo" ${state.hideDemo ? 'checked' : ''}>
      Hide accounts opened by the demos (UI-…)${hidden ? ` · ${hidden} hidden` : ''}</label>
    <div class="scroll"><table class="table"><thead><tr><th>Account</th><th>Holder</th><th>Cur.</th>
      <th class="num">Balance</th><th class="num">Available</th><th class="num">On hold</th><th>Status</th><th></th></tr></thead>
      <tbody>${rows || '<tr><td colspan="8" class="empty">No accounts.</td></tr>'}</tbody></table></div>`;
}
$('#accounts').addEventListener('change', ev => {
  if (ev.target.id === 'hide-demo') { state.hideDemo = ev.target.checked; renderAccounts(); }
});
$('#accounts').addEventListener('click', async ev => {
  const view = ev.target.closest('[data-view]');
  if (view) { showAccount(view.dataset.view); return; }
  const st = ev.target.closest('[data-status]');
  if (st) {
    const r = await api('account', 'PATCH', `/api/v1/accounts/${encodeURIComponent(st.dataset.id)}/status`, { body: { status: st.dataset.status } });
    toast(r.ok ? `${st.dataset.id} is now ${st.dataset.status}` : `Refused: ${errCode(r)}`);
    loadAccounts();
  }
});
$('#accounts-refresh').addEventListener('click', loadAccounts);
$('#account-detail-close').addEventListener('click', () => { $('#account-detail-card').hidden = true; });

async function showAccount(id) {
  const enc = encodeURIComponent(id);
  const [acc, holds, limits, entries, notes] = await Promise.all([
    api('account', 'GET', `/api/v1/accounts/${enc}`),
    api('account', 'GET', `/api/v1/accounts/${enc}/holds`),
    api('limits', 'GET', `/api/v1/limits/${enc}`),
    api('ledger', 'GET', `/api/v1/journals?accountId=${enc}&limit=20`),
    api('notification', 'GET', `/api/v1/notifications?accountId=${enc}&limit=20`),
  ]);
  const a = acc.body || {};
  const holdRows = (Array.isArray(holds.body) ? holds.body : []).slice(0, 20).map(h =>
    `<tr><td class="mono">${esc(String(h.transferId).slice(0, 13))}…</td><td>${esc(h.destinationAccountId)}</td>
     <td class="num">${money(h.amount, h.currency)}</td><td>${stateBadge(h.status)}</td><td>${time(h.updatedAt)}</td></tr>`).join('');
  const entryRows = (Array.isArray(entries.body) ? entries.body : []).map(e =>
    `<tr><td>${stateBadge(e.type)}</td><td>${esc(e.direction)}</td><td class="num">${money(e.amount, e.currency)}</td>
     <td class="mono">${esc(String(e.transferId).slice(0, 13))}…</td><td>${time(e.postedAt)}</td></tr>`).join('');
  const noteRows = (Array.isArray(notes.body) ? notes.body : []).map(n =>
    `<tr><td>${esc(n.channel)}</td><td>${esc(n.message)}</td><td>${stateBadge(n.status)}</td><td class="num">${esc(n.attempts)}</td></tr>`).join('');
  const l = limits.ok ? limits.body : null;
  $('#account-detail-title').textContent = `Account ${id}`;
  $('#account-detail').innerHTML = `
    <div class="grid four">
      <div class="card service-card"><h3>Account <span class="sv">Account service</span></h3>
        ${acc.ok ? `<dl class="kv"><dt>Holder</dt><dd>${esc(a.holderName)}</dd><dt>Status</dt><dd>${stateBadge(a.status)}</dd>
        <dt>Balance</dt><dd>${money(a.balance, a.currency)}</dd><dt>Available</dt><dd>${money(a.available, a.currency)}</dd>
        <dt>On hold</dt><dd>${money(a.onHold, a.currency)}</dd></dl>` : answerLine(acc)}</div>
      <div class="card service-card"><h3>Limits today <span class="sv">Limits service</span></h3>
        ${l ? `<dl class="kv"><dt>Daily limit</dt><dd>${money(l.dailyLimit)}${l.personal ? ' (personal)' : ''}</dd>
        <dt>Per transfer</dt><dd>${money(l.perTransferLimit)}</dd><dt>Used today</dt><dd>${money(l.usedToday)}</dd>
        <dt>Remaining</dt><dd>${money(l.remaining)}</dd><dt>Last minute</dt><dd>${esc(l.recentTransfers)} of ${esc(l.velocityMaxTransfers)} transfers</dd></dl>` : answerLine(limits)}</div>
    </div>
    <h3>Holds (Account service)</h3>
    <div class="scroll"><table class="table"><thead><tr><th>Transfer</th><th>To</th><th class="num">Amount</th><th>Status</th><th>Updated</th></tr></thead>
      <tbody>${holdRows || '<tr><td colspan="5" class="empty">None.</td></tr>'}</tbody></table></div>
    <h3 style="margin-top:14px">Ledger lines (Ledger service)</h3>
    <div class="scroll"><table class="table"><thead><tr><th>Journal</th><th>Side</th><th class="num">Amount</th><th>Transfer</th><th>Posted</th></tr></thead>
      <tbody>${entryRows || '<tr><td colspan="5" class="empty">None.</td></tr>'}</tbody></table></div>
    <h3 style="margin-top:14px">Notifications (Notification service)</h3>
    <div class="scroll"><table class="table"><thead><tr><th>Channel</th><th>Message</th><th>Status</th><th class="num">Attempts</th></tr></thead>
      <tbody>${noteRows || '<tr><td colspan="4" class="empty">None.</td></tr>'}</tbody></table></div>`;
  $('#account-detail-card').hidden = false;
  $('#account-detail-card').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

$('#open-form').addEventListener('submit', ev => {
  ev.preventDefault();
  const f = ev.target;
  busy($('button[type=submit]', f), async () => {
    const body = {
      accountId: f.accountId.value.trim(), holderName: f.holderName.value.trim(),
      currency: f.currency.value.trim().toUpperCase(), initialBalance: amountValue(f.initialBalance.value),
    };
    const r = await api('account', 'POST', '/api/v1/accounts', { body });
    $('#open-result').innerHTML = `<div style="margin-top:10px">${answerLine(r)} ${r.ok ? `Opened <b>${esc(body.accountId)}</b>.` : esc(errMessage(r))}</div>`;
    if (r.ok) { f.accountId.value = `ACC-${rand(4).toUpperCase()}`; loadAccounts(); }
  });
});

// ---------------------------------------------------------------- transfer + trace

const STEP_TEXT = {
  'LIMIT_RESERVATION|EXECUTE': 'Limits: reserve the amount against today\'s limit',
  'FUNDS_HOLD|EXECUTE': 'Account: set the money aside (hold)',
  'LEDGER_JOURNAL|EXECUTE': 'Ledger: post the balanced journal',
  'CAPTURE|EXECUTE': 'Account: move the money (capture)',
  'NOTIFICATION|EXECUTE': 'Notification: send the SMS (background)',
  'LIMIT_RESERVATION|COMPENSATE': 'Undo: release the limit reservation',
  'FUNDS_HOLD|COMPENSATE': 'Undo: release the hold',
  'LEDGER_JOURNAL|COMPENSATE': 'Undo: post a reversal journal',
};
function timeline(steps) {
  if (!Array.isArray(steps) || steps.length === 0) return '<p class="empty">No steps recorded.</p>';
  return `<ol class="timeline">${steps.map(s => {
    const cls = s.action === 'COMPENSATE' ? 'undo' : s.outcome === 'SUCCEEDED' ? 'ok' : 'no';
    const label = STEP_TEXT[`${s.step}|${s.action}`] || `${s.step} ${s.action}`;
    const badge = s.outcome === 'SUCCEEDED' ? 'good' : s.outcome === 'REJECTED' ? 'warn' : 'bad';
    return `<li class="${cls}"><b>${esc(label)}</b> <span class="badge ${badge}">${esc(s.outcome)}</span>
      <span class="when">${time(s.at)}</span>${s.detail ? `<div class="detail">${esc(s.detail)}</div>` : ''}</li>`;
  }).join('')}</ol>`;
}

async function collectTrace(id) {
  const enc = encodeURIComponent(id);
  const [transfer, steps, reservation, hold, journals, notes] = await Promise.all([
    api('transfer', 'GET', `/api/v1/transfers/${enc}`),
    api('transfer', 'GET', `/api/v1/transfers/${enc}/saga-steps`),
    api('limits', 'GET', `/api/v1/limit-reservations/${enc}`),
    api('account', 'GET', `/api/v1/holds/${enc}`),
    api('ledger', 'GET', `/api/v1/journals/${enc}`),
    api('notification', 'GET', `/api/v1/notifications?transferId=${enc}`),
  ]);
  return { id, transfer, steps, reservation, hold, journals, notes };
}

function renderTrace(t) {
  const tr = t.transfer.ok ? t.transfer.body : null;
  const r = t.reservation.ok ? t.reservation.body : null;
  const h = t.hold.ok ? t.hold.body : null;
  const js = t.journals.ok && Array.isArray(t.journals.body) ? t.journals.body : [];
  const ns = t.notes.ok && Array.isArray(t.notes.body) ? t.notes.body : [];
  const none = res => res.status === 404 ? '<p class="empty">Nothing stored for this transfer.</p>' : answerLine(res);
  const journalHtml = js.length ? js.map(j => `<div style="margin-bottom:8px">${stateBadge(j.type)} <span class="mono">${esc(j.journalId)}</span>
      <table class="table"><tbody>${(j.entries || []).map(e => `<tr><td>${esc(e.direction)}</td><td class="mono">${esc(e.accountId)}</td>
      <td class="num">${money(e.amount, j.currency)}</td></tr>`).join('')}</tbody></table></div>`).join('') : none(t.journals);
  const noteHtml = ns.length ? ns.map(n => `<dl class="kv"><dt>Status</dt><dd>${stateBadge(n.status)}</dd><dt>Attempts</dt><dd>${esc(n.attempts)}</dd>
      <dt>Message</dt><dd>${esc(n.message)}</dd>${n.lastError ? `<dt>Last error</dt><dd>${esc(n.lastError)}</dd>` : ''}</dl>`).join('')
    : (!t.notes.ok ? answerLine(t.notes)
      : tr && tr.status !== 'COMPLETED' && ['REJECTED', 'FAILED'].includes(tr.status)
        ? '<p class="empty">No SMS: only completed transfers are notified.</p>'
        : '<p class="empty">Not sent yet (it is sent in the background after completion).</p>');
  return `
    <div class="inline" style="flex-wrap:wrap;margin-bottom:10px">
      <span class="mono">${esc(t.id)}</span> ${tr ? stateBadge(tr.status) : answerLine(t.transfer)}
      ${tr ? `<span>${esc(tr.sourceAccountId)} → ${esc(tr.destinationAccountId)} · <b>${money(tr.amount, tr.currency)}</b></span>
      <span class="hint">SMS: ${esc(tr.notificationStatus)}</span>` : ''}
      ${tr?.failure ? `<code>${esc(tr.failure.code)}</code> <span class="hint">${esc(tr.failure.message)}</span>` : ''}
      <button class="ghost small" data-retrace="${esc(t.id)}">Refresh</button>
    </div>
    <div class="grid two">
      <div class="card"><h3>Saga steps <span class="sv hint">Transfer service · saga_steps</span></h3>${t.steps.ok ? timeline(t.steps.body) : answerLine(t.steps)}</div>
      <div class="grid four" style="margin:0">
        <div class="card service-card"><h3>Limit reservation <span class="sv">Limits</span></h3>
          ${r ? `<dl class="kv"><dt>Status</dt><dd>${stateBadge(r.status)}</dd><dt>Amount</dt><dd>${money(r.amount, r.currency)}</dd>
          <dt>Used today</dt><dd>${money(r.usedToday)} of ${money(r.dailyLimit)}</dd></dl>` : none(t.reservation)}</div>
        <div class="card service-card"><h3>Hold <span class="sv">Account</span></h3>
          ${h ? `<dl class="kv"><dt>Status</dt><dd>${stateBadge(h.status)}</dd><dt>Amount</dt><dd>${money(h.amount, h.currency)}</dd>
          <dt>From</dt><dd>${esc(h.sourceAccountId)}</dd></dl>` : none(t.hold)}</div>
        <div class="card service-card"><h3>Journal <span class="sv">Ledger</span></h3>${journalHtml}</div>
        <div class="card service-card"><h3>SMS <span class="sv">Notification</span></h3>${noteHtml}</div>
      </div>
    </div>`;
}

async function trace(id, target = '#trace') {
  if (!id) return;
  $('#trace-id').value = id;
  const t = await collectTrace(id);
  $(target).innerHTML = renderTrace(t);
  $(target).classList.remove('empty');
  return t;
}
document.addEventListener('click', ev => {
  const b = ev.target.closest('[data-retrace]');
  if (!b) return;
  const host = b.closest('#trace, #c3-result, #res-result');
  if (host?.id === 'trace') trace(b.dataset.retrace);
  else if (host) collectTrace(b.dataset.retrace).then(t => { b.closest('.trace-host').innerHTML = renderTrace(t); });
});
$('#trace-go').addEventListener('click', () => trace($('#trace-id').value.trim()));

/** A typed amount as a JSON number when it is one (keeps "10.005" exact), else as typed so the API can refuse it. */
function amountValue(v) {
  const t = String(v).trim();
  return /^-?\d+(\.\d+)?$/.test(t) ? Number(t) : t;
}
function transferBody(f) {
  return {
    sourceAccountId: f.sourceAccountId.value.trim(), destinationAccountId: f.destinationAccountId.value.trim(),
    amount: amountValue(f.amount.value), currency: f.currency.value.trim().toUpperCase(),
  };
}
async function doTransfer(body, key, again) {
  const r = await api('transfer', 'POST', '/api/v1/transfers', { body, headers: { 'Idempotency-Key': key } });
  state.last = { body, key };
  const tr = r.body && r.body.transferId ? r.body : null;
  const id = transferIdOf(r);
  $('#transfer-result').classList.remove('empty');
  $('#transfer-result').innerHTML = `
    ${again ? '<p class="hint">Sent again with the same key.</p>' : ''}
    ${answerLine(r)}
    <dl class="kv" style="margin-top:10px">
      <dt>Idempotency-Key</dt><dd class="mono">${esc(key)}</dd>
      ${id ? `<dt>transferId</dt><dd class="mono">${esc(id)}</dd>` : ''}
      ${tr ? `<dt>Status</dt><dd>${stateBadge(tr.status)}</dd><dt>SMS</dt><dd>${esc(tr.notificationStatus)}</dd>` : ''}
      ${errCode(r) ? `<dt>Refused</dt><dd><code>${esc(errCode(r))}</code> ${esc(errMessage(r))}</dd>` : ''}
    </dl>
    <details><summary>Full answer</summary>${json(r.body)}</details>`;
  $('#transfer-form').key.value = newKey();
  if (id) {
    await trace(id);
    setTimeout(() => { if ($('#trace-id').value === id) trace(id); }, 2500);   // pick up the background SMS
  }
  loadAccounts();
}
$('#transfer-form').addEventListener('submit', ev => {
  ev.preventDefault();
  const f = ev.target;
  busy($('button[type=submit]', f), () => doTransfer(transferBody(f), f.key.value.trim(), false));
});
$('#send-again').addEventListener('click', ev => {
  if (!state.last) { toast('Send a transfer first.'); return; }
  busy(ev.target, () => doTransfer(state.last.body, state.last.key, true));
});
$('#new-key').addEventListener('click', () => { $('#transfer-form').key.value = newKey(); });

// ---------------------------------------------------------------- Challenge 1

function squares(results, okCodes) {
  return `<div class="squares">${results.map((r, i) => {
    const cls = okCodes.includes(r.status) ? 'good' : r.status >= 400 && r.status < 500 ? 'bad' : 'warn';
    return `<span class="sq ${cls}" title="#${i + 1}: HTTP ${r.status} ${esc(errCode(r))}">${r.status}</span>`;
  }).join('')}</div>`;
}
function counts(items) {
  return `<div class="counts">${items.map(([n, label]) => `<div class="count"><b>${n}</b><span>${label}</span></div>`).join('')}</div>`;
}

$('#c1-form').addEventListener('submit', ev => {
  ev.preventDefault();
  const f = ev.target;
  busy($('button[type=submit]', f), async () => {
    const mode = f.mode.value;
    const capacity = Number(f.capacity.value), amount = Number(f.amount.value), n = Math.min(100, Number(f.count.value));
    const expected = Math.min(n, Math.floor(capacity / amount));
    const out = $('#c1-result');
    out.classList.remove('empty');
    out.innerHTML = '<p class="hint">Preparing…</p>';

    if (mode === 'account') {
      const id = await openAccount(capacity, 'C1');
      out.innerHTML = `<p class="hint">Opened <b>${esc(id)}</b> with ${money(capacity)}. Firing ${n} holds of ${money(amount)} at once…</p>`;
      const bodies = Array.from({ length: n }, (_, i) => ({
        transferId: `c1-${rand(10)}-${i + 1}`, sourceAccountId: id, destinationAccountId: DEST, amount, currency: 'INR',
      }));
      const results = await burst('account', 'POST', '/api/v1/holds', bodies);
      const ok = results.filter(r => r.status === 201).length;
      const refused = results.filter(r => r.status === 409).length;
      const other = n - ok - refused;
      const after = await getAccount(id);
      const items = [
        { ok: ok === expected, text: `Exactly ${expected} of ${n} holds succeeded (201): <b>${ok}</b>` },
        { ok: refused === n - expected, text: `The other ${n - expected} were refused with 409 <code>INSUFFICIENT_FUNDS</code>: <b>${refused}</b>` },
        { ok: after && Number(after.available) >= 0, text: `Available never went below zero: <b>${after ? money(after.available) : '?'}</b>` },
        { ok: after && Math.abs(Number(after.onHold) - ok * amount) < 0.005, text: `Money on hold equals the successful holds: ${after ? money(after.onHold) : '?'} = ${ok} × ${money(amount)}` },
        { ok: after && Math.abs(Number(after.balance) - capacity) < 0.005, text: `Balance unchanged until a capture: ${after ? money(after.balance) : '?'}` },
      ];
      if (other) items.push({ ok: false, text: `${other} answered something else (see the squares)` });
      out.innerHTML = `
        <p>Account <b class="mono">${esc(id)}</b>: ${money(capacity)}, ${n} holds of ${money(amount)} released at the same instant.</p>
        ${counts([[ok, 'succeeded (201)'], [refused, 'refused (409)'], [other, 'other']])}
        ${squares(results, [201])}
        ${checks(items)}
        ${verdict(items, `Not over-allocated: exactly ${expected} succeeded.`,
          ok > expected ? `Over-allocated: ${ok} succeeded where only ${expected} should. Is Account running with HOLD_STRATEGY=naive?` : 'Unexpected result; see the checks.')}
        <div class="actions"><button class="secondary" id="c1-cleanup" data-id="${esc(id)}">Release these holds</button></div>
        <p class="hint">To show the broken version, start the Account service with <code>HOLD_STRATEGY=naive</code> (read, then write) and run this again: far more than ${expected} succeed.</p>`;
      $('#c1-cleanup').addEventListener('click', e2 => busy(e2.target, async () => {
        const held = bodies.filter((b, i) => results[i].status === 201).map(b => b.transferId);
        for (const t of held) await api('account', 'POST', `/api/v1/holds/${encodeURIComponent(t)}/release`);
        const a = await getAccount(id);
        toast(`Released ${held.length} holds; available is ${money(a?.available)} again`);
      }));
    } else {
      const id = `UI-C1L-${rand(5).toUpperCase()}`;
      const set = await api('limits', 'PUT', `/api/v1/limits/${id}`, { body: { dailyLimit: capacity, perTransferLimit: Math.min(capacity, amount) } });
      if (!set.ok) throw new Error(`Could not set the limit: ${errCode(set)} ${errMessage(set)}`);
      out.innerHTML = `<p class="hint">Gave <b>${esc(id)}</b> a daily limit of ${money(capacity)}. Firing ${n} reservations of ${money(amount)} at once…</p>`;
      const bodies = Array.from({ length: n }, (_, i) => ({ transferId: `c1l-${rand(10)}-${i + 1}`, accountId: id, amount, currency: 'INR' }));
      const results = await burst('limits', 'POST', '/api/v1/limit-reservations', bodies);
      const ok = results.filter(r => r.status === 201).length;
      const limit = results.filter(r => errCode(r) === 'LIMIT_EXCEEDED').length;
      const velocity = results.filter(r => errCode(r) === 'VELOCITY_LIMIT').length;
      const other = n - ok - limit - velocity;
      const after = await api('limits', 'GET', `/api/v1/limits/${id}`);
      const used = after.ok ? Number(after.body.usedToday) : NaN;
      const items = [
        { ok: ok === expected, text: `Exactly ${expected} of ${n} reservations succeeded (201): <b>${ok}</b>` },
        { ok: limit + velocity === n - expected, text: `The rest were refused with 422 <code>LIMIT_EXCEEDED</code>: <b>${limit}</b>${velocity ? `, <code>VELOCITY_LIMIT</code>: ${velocity}` : ''}` },
        { ok: used <= capacity, text: `Used today never went over the limit: <b>${money(used)}</b> of ${money(capacity)}` },
      ];
      out.innerHTML = `
        <p>Account <b class="mono">${esc(id)}</b>: daily limit ${money(capacity)}, ${n} reservations of ${money(amount)} released at the same instant.</p>
        ${counts([[ok, 'reserved (201)'], [limit, 'over the limit (422)'], [velocity, 'velocity (422)'], [other, 'other']])}
        ${squares(results, [201])}
        ${checks(items)}
        ${verdict(items, `Not over-allocated: the limit was never exceeded.`, 'Unexpected result; see the checks.')}`;
    }
  });
});

// ---------------------------------------------------------------- Challenge 2

const C2 = {
  async retry(out) {
    const src = await openAccount(1000, 'C2A');
    const key = newKey();
    const first = await sendTransfer(src, DEST, 100, key);
    const second = await sendTransfer(src, DEST, 100, key);
    const after = await getAccount(src);
    const items = [
      { ok: first.status === 201, text: `First request: ${httpBadge(first.status)} ${stateBadge(first.body?.status)}` },
      { ok: second.status === first.status, text: `Repeat got the same answer: ${httpBadge(second.status)}` },
      { ok: second.headers.replayed === 'true', text: `Repeat marked <code>Idempotent-Replayed: true</code>` },
      { ok: transferIdOf(first) && transferIdOf(first) === transferIdOf(second), text: `Same transferId both times: <span class="mono">${esc(transferIdOf(second))}</span>` },
      { ok: after && Number(after.balance) === 900, text: `Money moved once: balance ${money(1000)} → <b>${money(after?.balance)}</b> (expected ${money(900)})` },
    ];
    out.innerHTML = checks(items) + verdict(items, 'Safe to repeat: the retry did not send money twice.', 'Unexpected result; see the checks.');
  },
  async parallel(out) {
    const n = Math.max(2, Math.min(50, Number($('#c2-n').value) || 10));
    const src = await openAccount(1000, 'C2B');
    const key = newKey();
    const body = { sourceAccountId: src, destinationAccountId: DEST, amount: 100, currency: 'INR' };
    const results = await burst('transfer', 'POST', '/api/v1/transfers', Array(n).fill(body), { 'Idempotency-Key': key });
    const ids = new Set(results.map(transferIdOf).filter(Boolean));
    const created = results.filter(r => r.status === 201 && r.headers.replayed !== 'true').length;
    const replayed = results.filter(r => r.headers.replayed === 'true').length;
    const inProgress = results.filter(r => errCode(r) === 'REQUEST_IN_PROGRESS').length;
    await sleep(300);
    const after = await getAccount(src);
    const items = [
      { ok: ids.size === 1, text: `One transfer created for ${n} requests: <b>${ids.size}</b> distinct transferId` },
      { ok: created === 1, text: `Exactly one request did the work: <b>${created}</b>` },
      { ok: replayed + inProgress === n - 1, text: `The other ${n - 1}: <b>${replayed}</b> got the stored answer (replayed), <b>${inProgress}</b> got 409 <code>REQUEST_IN_PROGRESS</code>` },
      { ok: after && Number(after.balance) === 900, text: `Money moved once: balance <b>${money(after?.balance)}</b> (expected ${money(900)})` },
    ];
    out.innerHTML = counts([[created, 'did the work'], [replayed, 'replayed'], [inProgress, 'in progress (409)'], [n - created - replayed - inProgress, 'other']])
      + squares(results, [201]) + checks(items) + verdict(items, `${n} identical requests, one transfer.`, 'Unexpected result; see the checks.');
  },
  async mismatch(out) {
    const src = await openAccount(1000, 'C2C');
    const key = newKey();
    const first = await sendTransfer(src, DEST, 100, key);
    const second = await sendTransfer(src, DEST, 200, key);
    const after = await getAccount(src);
    const items = [
      { ok: first.status === 201, text: `₹100 with the key: ${httpBadge(first.status)}` },
      { ok: second.status === 422 && errCode(second) === 'IDEMPOTENCY_KEY_REUSED', text: `₹200 with the same key: ${httpBadge(second.status)} <code>${esc(errCode(second))}</code>` },
      { ok: after && Number(after.balance) === 900, text: `Only the first moved money: balance <b>${money(after?.balance)}</b>` },
    ];
    out.innerHTML = checks(items) + verdict(items, 'A reused key with a different body is refused.', 'Unexpected result; see the checks.');
  },
  async nokey(out) {
    const src = await openAccount(1000, 'C2D');
    const r = await sendTransfer(src, DEST, 100, null);
    const after = await getAccount(src);
    const items = [
      { ok: r.status === 400 && errCode(r) === 'MISSING_IDEMPOTENCY_KEY', text: `No key: ${httpBadge(r.status)} <code>${esc(errCode(r))}</code>` },
      { ok: after && Number(after.balance) === 1000, text: `Nothing moved: balance <b>${money(after?.balance)}</b>` },
    ];
    out.innerHTML = checks(items) + verdict(items, 'Refused before anything happened.', 'Unexpected result; see the checks.');
  },
};
$$('[data-c2]').forEach(b => b.addEventListener('click', () => busy(b, async () => {
  const out = $(`#c2-${b.dataset.c2}`);
  out.innerHTML = '<p class="hint">Running…</p>';
  await C2[b.dataset.c2](out);
})));

// ---------------------------------------------------------------- Challenge 3C

async function loadFaults() {
  const r = await api('ledger', 'GET', '/api/v1/admin/faults', { quiet: true });
  $('#faults').innerHTML = r.ok
    ? `<dl class="kv"><dt>Journal posts</dt><dd>${r.body.failPosts ? '<span class="badge bad">FAIL with 503</span>' : '<span class="badge good">normal</span>'}</dd>
       <dt>Delay per post</dt><dd>${esc(r.body.postDelayMs)} ms</dd></dl>`
    : `<p class="empty">Ledger fault switch not reachable. ${answerLine(r)}</p>`;
  return r;
}
function setFaults(failPosts) {
  return api('ledger', 'PUT', '/api/v1/admin/faults', { body: { failPosts, postDelayMs: 0 } });
}
$('#faults-refresh').addEventListener('click', loadFaults);
$('#faults-on').addEventListener('click', ev => busy(ev.target, async () => { await setFaults(true); await loadFaults(); toast('Ledger posts now fail with 503'); }));
$('#faults-off').addEventListener('click', ev => busy(ev.target, async () => { await setFaults(false); await loadFaults(); toast('Ledger back to normal'); }));

$('#c3-run').addEventListener('click', ev => busy(ev.target, async () => {
  const out = $('#c3-result');
  out.classList.remove('empty');
  out.innerHTML = '<p class="hint">Opening a fresh account…</p>';
  const src = await openAccount(1000, 'C3');
  const before = await getAccount(src);
  const on = await setFaults(true);
  if (!on.ok) throw new Error(`Could not switch the Ledger fault on: ${errCode(on)} ${errMessage(on)}`);
  await loadFaults();
  out.innerHTML = '<p class="hint">Ledger failing. Sending ₹300; Transfer will retry step 3, then undo…</p>';
  const r = await sendTransfer(src, DEST, 300, newKey());
  if ($('#c3-auto-off').checked) { await setFaults(false); await loadFaults(); }
  const id = transferIdOf(r);
  const t = id ? await collectTrace(id) : null;
  const after = await getAccount(src);
  const limitsAfter = await api('limits', 'GET', `/api/v1/limits/${encodeURIComponent(src)}`);
  const steps = t?.steps.ok && Array.isArray(t.steps.body) ? t.steps.body : [];
  const undoOrder = steps.filter(s => s.action === 'COMPENSATE').map(s => s.step);
  const js = t?.journals.ok && Array.isArray(t.journals.body) ? t.journals.body : [];
  const items = [
    { ok: r.status === 503 && errCode(r) === 'LEDGER_UNAVAILABLE', text: `Answer: ${httpBadge(r.status)} <code>${esc(errCode(r))}</code>` },
    { ok: t?.transfer.body?.status === 'FAILED', text: `Transfer ended ${stateBadge(t?.transfer.body?.status)}` },
    { ok: steps.some(s => s.step === 'LEDGER_JOURNAL' && s.outcome === 'UNAVAILABLE'), text: 'Step 3 (Ledger) recorded as UNAVAILABLE after its retries' },
    { ok: undoOrder.join(',') === 'FUNDS_HOLD,LIMIT_RESERVATION', text: `Undone in reverse order: ${undoOrder.length ? undoOrder.map(esc).join(' → ') : 'nothing'} (expected FUNDS_HOLD → LIMIT_RESERVATION)` },
    { ok: t?.hold.body?.status === 'RELEASED', text: `Hold ${stateBadge(t?.hold.body?.status)} (Account service)` },
    { ok: t?.reservation.body?.status === 'RELEASED', text: `Limit reservation ${stateBadge(t?.reservation.body?.status)} (Limits service)` },
    { ok: js.length === 0, text: `No journal left in the Ledger: ${js.length} found` },
    { ok: after && before && Number(after.available) === Number(before.available) && Number(after.balance) === Number(before.balance),
      text: `Account back where it started: available ${money(before?.available)} → <b>${money(after?.available)}</b>, balance ${money(after?.balance)}` },
    { ok: limitsAfter.ok && Number(limitsAfter.body.usedToday) === 0, text: `Daily usage back to <b>${money(limitsAfter.body?.usedToday)}</b>` },
  ];
  out.innerHTML = `${answerLine(r)}${checks(items)}${verdict(items, 'Compensated: every step before the failure was undone, nothing is left reserved.', 'Unexpected result; see the checks.')}
    ${t ? `<h3 style="margin-top:16px">Trace</h3><div class="trace-host">${renderTrace(t)}</div>` : ''}`;
}));

// ---------------------------------------------------------------- slow / failing dependency

const PROVIDER = {
  ok: { delayMs: 0, failRate: 0, failNext: 0 },
  failnext: { delayMs: 0, failRate: 0, failNext: 2 },
  flaky: { delayMs: 0, failRate: 0.5, failNext: 0 },
  down: { delayMs: 0, failRate: 1, failNext: 0 },
  slow: { delayMs: 5000, failRate: 0, failNext: 0 },
};
async function loadProvider() {
  const r = await api('notification', 'GET', '/api/v1/admin/provider', { quiet: true });
  $('#provider').innerHTML = r.ok
    ? `<dl class="kv"><dt>Delay per send</dt><dd>${esc(r.body.delayMs)} ms</dd><dt>Random failure rate</dt><dd>${Math.round(r.body.failRate * 100)} %</dd>
       <dt>Next sends that fail</dt><dd>${esc(r.body.failNext)}</dd></dl>`
    : `<p class="empty">Provider switch not reachable. ${answerLine(r)}</p>`;
}
$('#provider-refresh').addEventListener('click', loadProvider);
$$('[data-provider]').forEach(b => b.addEventListener('click', () => busy(b, async () => {
  const r = await api('notification', 'PUT', '/api/v1/admin/provider', { body: PROVIDER[b.dataset.provider] });
  toast(r.ok ? `Provider: ${b.textContent}` : `Refused: ${errCode(r)}`);
  loadProvider();
})));

$('#res-run').addEventListener('click', ev => busy(ev.target, async () => {
  const out = $('#res-result');
  out.classList.remove('empty');
  const src = await openAccount(1000, 'RES');
  const r = await sendTransfer(src, DEST, 50, newKey());
  const id = transferIdOf(r);
  if (!id) { out.innerHTML = answerLine(r) + json(r.body); return; }
  const answered = r.ms;
  const started = Date.now();
  let tr = null, notes = [];
  while (Date.now() - started < 20000) {
    const [t, n] = await Promise.all([
      api('transfer', 'GET', `/api/v1/transfers/${id}`, { quiet: true }),
      api('notification', 'GET', `/api/v1/notifications?transferId=${id}`, { quiet: true }),
    ]);
    tr = t.ok ? t.body : null;
    notes = n.ok && Array.isArray(n.body) ? n.body : [];
    const hist = notes[0]?.history || [];
    out.innerHTML = `${answerLine(r)}
      <p>Transfer ${stateBadge(tr?.status)} answered in <b>${answered} ms</b>. SMS on the transfer: ${stateBadge(tr?.notificationStatus)}
        <span class="hint">· watching ${Math.round((Date.now() - started) / 1000)} s</span></p>
      <table class="table"><thead><tr><th>Attempt</th><th>Outcome</th><th>Error</th><th class="num">ms</th><th>At</th></tr></thead>
        <tbody>${hist.map(a => `<tr><td>${esc(a.attemptNo)}</td><td>${stateBadge(a.outcome)}</td><td>${esc(a.error || '')}</td>
          <td class="num">${esc(a.durationMs)}</td><td>${time(a.at)}</td></tr>`).join('') || '<tr><td colspan="5" class="empty">No attempt yet.</td></tr>'}</tbody></table>`;
    if (tr && tr.notificationStatus !== 'PENDING') break;
    await sleep(1000);
  }
  const hist = notes[0]?.history || [];
  const items = [
    { ok: tr?.status === 'COMPLETED', text: `The transfer is COMPLETED whatever the provider did: ${stateBadge(tr?.status)}` },
    { ok: notes.length <= 1, text: `At most one notification for the transfer: <b>${notes.length}</b>` },
    { ok: hist.filter(a => a.outcome === 'SENT').length <= 1, text: `Never sent twice: <b>${hist.filter(a => a.outcome === 'SENT').length}</b> successful attempt(s)` },
    { ok: null, text: `Provider attempts: ${hist.map(a => esc(a.outcome)).join(' → ') || 'none'}; final SMS status on the transfer: ${esc(tr?.notificationStatus)}` },
  ];
  out.insertAdjacentHTML('beforeend', checks(items) + verdict(items, 'The failing dependency never broke the transfer.', 'Unexpected result; see the checks.'));
}));

// ---------------------------------------------------------------- business rules

const RULES = [
  { rule: 'BR1', name: 'Amount of 0', expect: [422, 'BR1_AMOUNT_NOT_POSITIVE'],
    run: () => sendTransfer('ACC-1001', 'ACC-2002', 0, newKey()) },
  { rule: 'BR1', name: 'Above the ₹1,00,000 cap', expect: [422, 'BR1_AMOUNT_ABOVE_CAP'],
    run: () => sendTransfer('ACC-1001', 'ACC-2002', 100000.01, newKey()) },
  { rule: 'BR1', name: 'More than 2 decimals (10.005)', expect: [422, 'BR1_TOO_MANY_DECIMALS'],
    run: () => sendTransfer('ACC-1001', 'ACC-2002', 10.005, newKey()) },
  { rule: 'BR4', name: 'Same source and destination', expect: [422, 'BR4_SAME_ACCOUNT'],
    run: () => sendTransfer('ACC-1001', 'ACC-1001', 10, newKey()) },
  { rule: 'BR2', name: 'Not enough money (ACC-EMPTY)', expect: [409, 'INSUFFICIENT_FUNDS'],
    run: () => sendTransfer('ACC-EMPTY', DEST, 10, newKey()) },
  { rule: 'BR4', name: 'Frozen account (ACC-FROZEN)', expect: [422, 'ACCOUNT_NOT_ACTIVE'],
    run: () => sendTransfer('ACC-FROZEN', DEST, 10, newKey()) },
  { rule: 'BR4', name: 'Different currency (USD → INR account)', expect: [422, 'CURRENCY_MISMATCH'],
    run: () => sendTransfer('ACC-USD-1', DEST, 10, newKey(), 'USD') },
  { rule: 'BR4', name: 'Unknown account', expect: [404, 'ACCOUNT_NOT_FOUND'],
    run: () => sendTransfer('ACC-NOPE', DEST, 10, newKey()) },
  { rule: 'BR3', name: 'Daily limit: ₹1,500 then ₹1,000 against a ₹2,000 limit', expect: [422, 'LIMIT_EXCEEDED'],
    run: async () => {
      const src = await openAccount(5000, 'BR3');
      await api('limits', 'PUT', `/api/v1/limits/${src}`, { body: { dailyLimit: 2000, perTransferLimit: 1500 } });
      const first = await sendTransfer(src, DEST, 1500, newKey());
      if (first.status !== 201) return first;
      return sendTransfer(src, DEST, 1000, newKey());
    } },
  { rule: 'BR1', name: 'Above the account\'s own per-transfer limit (₹1,500)', expect: [422, 'LIMIT_EXCEEDED'],
    run: async () => {
      const src = await openAccount(5000, 'BR1L');
      await api('limits', 'PUT', `/api/v1/limits/${src}`, { body: { dailyLimit: 2000, perTransferLimit: 1500 } });
      return sendTransfer(src, DEST, 1600, newKey());
    } },
  { rule: 'F1', name: '11th transfer within a minute (default limit 10)', expect: [422, 'VELOCITY_LIMIT'],
    run: async () => {
      const src = await openAccount(10000, 'F1');
      for (let i = 0; i < 10; i++) {
        const r = await sendTransfer(src, DEST, 1, newKey());
        if (r.status !== 201) return r;
      }
      return sendTransfer(src, DEST, 1, newKey());
    } },
  { rule: 'A2', name: 'Close an account that still has money', expect: [409, 'ACCOUNT_NOT_EMPTY'],
    run: async () => {
      const id = await openAccount(100, 'A2');
      return api('account', 'PATCH', `/api/v1/accounts/${id}/status`, { body: { status: 'CLOSED' } });
    } },
  { rule: 'BR5', name: 'Unbalanced journal straight to the Ledger (debit 100, credit 90)', expect: [422, 'UNBALANCED_JOURNAL'],
    run: () => api('ledger', 'POST', '/api/v1/journals', { body: {
      transferId: `ui-br5-${rand(10)}`, currency: 'INR',
      entries: [{ accountId: 'ACC-1001', direction: 'DEBIT', amount: 100 }, { accountId: 'ACC-2002', direction: 'CREDIT', amount: 90 }],
    } }) },
  { rule: 'Idem.', name: 'Transfer without an Idempotency-Key', expect: [400, 'MISSING_IDEMPOTENCY_KEY'],
    run: () => sendTransfer('ACC-1001', 'ACC-2002', 10, null) },
];
function renderRules() {
  $('#rules-table tbody').innerHTML = RULES.map((r, i) => `
    <tr id="rule-${i}"><td><b>${esc(r.rule)}</b></td><td>${esc(r.name)}</td>
      <td>${httpBadge(r.expect[0])} <code>${esc(r.expect[1])}</code></td><td class="actual"><span class="hint">not run</span></td>
      <td class="mark"></td><td><button class="ghost small" data-rule="${i}">Run</button></td></tr>
    <tr class="rule-detail" id="rule-detail-${i}" hidden><td colspan="6"></td></tr>`).join('');
}
async function runRule(i) {
  const r = RULES[i];
  const row = $(`#rule-${i}`);
  $('.actual', row).innerHTML = '<span class="hint">running…</span>';
  $('.mark', row).innerHTML = '';
  let res;
  try { res = await r.run(); } catch (e) { $('.actual', row).innerHTML = `<span class="badge bad">${esc(e.message)}</span>`; return false; }
  const pass = res.status === r.expect[0] && errCode(res) === r.expect[1];
  $('.actual', row).innerHTML = `${httpBadge(res.status)} <code>${esc(errCode(res) || (res.ok ? 'accepted' : ''))}</code>`;
  $('.mark', row).innerHTML = pass ? '<span class="badge good">✓ pass</span>' : '<span class="badge bad">✗ fail</span>';
  const detail = $(`#rule-detail-${i} td`);
  detail.innerHTML = `<p class="hint">${esc(errMessage(res))}</p>${json(res.body)}`;
  return pass;
}
$('#rules-table').addEventListener('click', ev => {
  const b = ev.target.closest('[data-rule]');
  if (b) { busy(b, () => runRule(Number(b.dataset.rule))); return; }
  const tr = ev.target.closest('tr[id^="rule-"]:not(.rule-detail)');
  if (tr) { const d = $(`#rule-detail-${tr.id.split('-')[1]}`); if (d && d.querySelector('td').innerHTML) d.hidden = !d.hidden; }
});
$('#rules-all').addEventListener('click', ev => busy(ev.target, async () => {
  let passed = 0;
  for (let i = 0; i < RULES.length; i++) if (await runRule(i)) passed++;
  toast(`${passed} of ${RULES.length} rules behaved as expected`);
}));

// ---------------------------------------------------------------- start

(async function start() {
  $('#transfer-form').key.value = newKey();
  $('#open-form').accountId.value = `ACC-${rand(4).toUpperCase()}`;
  renderRules();
  renderLog();
  try {
    const r = await fetch('/api/ui/services');
    state.services = await r.json();
  } catch {
    state.services = Object.entries(PORTS).map(([name, port]) => ({ name, url: `http://localhost:${port}` }));
  }
  let tab = 'overview';
  try { tab = localStorage.getItem('mt-tab') || 'overview'; } catch { /* private mode */ }
  showTab($(`.tabs button[data-tab="${tab}"]`) ? tab : 'overview');
  checkHealth();
  setInterval(() => { if (!document.hidden) checkHealth(); }, 15000);
})();
