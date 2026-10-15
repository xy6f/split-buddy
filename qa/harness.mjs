// QA harness — 全量 API 测试（ASCII payloads only，避免 Git Bash GBK 假错误）
// 用法: node qa/harness.mjs [tag]   输出 qa/results-<tag>.txt 与 qa/state-<tag>.json
import { writeFileSync } from 'node:fs';

const TAG = process.argv[2] || 'pre';
const B = 'http://localhost:8081/api';
const PW = '123456';
const lines = [];
let pass = 0, fail = 0;

async function req(name, expect, method, path, opt = {}, check) {
  const headers = { 'Content-Type': 'application/json' };
  if (opt.token) headers.Authorization = 'Bearer ' + opt.token;
  let status = 0, text = '', json = null;
  try {
    const res = await fetch(B + path, {
      method, headers,
      body: opt.raw !== undefined ? opt.raw
            : opt.body !== undefined ? JSON.stringify(opt.body) : undefined,
    });
    status = res.status;
    text = await res.text();
    try { json = JSON.parse(text); } catch { /* keep null */ }
  } catch (e) { text = 'FETCH-EXC ' + e.message; }
  let verdict = (status === expect) ? 'PASS' : 'FAIL';
  if (verdict === 'PASS' && check) {
    try { check(json); } catch (e) { verdict = 'FAIL'; text += ' | CHECK: ' + e.message; }
  }
  if (verdict === 'PASS') pass++; else fail++;
  lines.push(`[${verdict}] ${name} | expect ${expect} | got ${status} | ${text.slice(0, 220)}`);
  return json;
}
function assertEq(a, b, msg) { if (a !== b) throw new Error(`${msg}: got ${a} want ${b}`); }

// helpers
function sumBalances(json) {
  const s = json.data.reduce((acc, r) => acc + Math.round(r.net * 100), 0);
  if (s !== 0) throw new Error('SUM(balances) != 0: sum(cents)=' + s);
}
async function register(u, nick) {
  return fetch(B + '/auth/register', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: PW, nickname: nick }) }).then(r => r.json());
}
async function login(u) {
  const r = await fetch(B + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: u, password: PW }) });
  return r.json();
}
const split = (userId, shareType = 'EQUAL', share = 0) => ({ userId, shareType, share });

const ts = Date.now().toString(36);
const U = {
  alice: `qa_a_${ts}`, bob: `qa_b_${ts}`, carol: `qa_c_${ts}`,
  out: `qa_out${ts}`, under: `qa_under_${ts}`,
};
const state = { users: {} };
for (const k of ['alice', 'bob', 'carol', 'out', 'under']) {
  await register(U[k], k);
  const j = await login(U[k]);
  if (!j.data) throw new Error('login failed ' + k + ' ' + JSON.stringify(j));
  state.users[k] = { name: U[k], token: j.data.token, id: j.data.userId };
}
const T = k => state.users[k].token;
const ID = k => state.users[k].id;

// ================= A 认证 =================
await req('A1 register ascii', 200, 'POST', '/auth/register', { body: { username: 'qa_d' + ts, password: PW, nickname: 'dup1' } }, j => assertEq(j.code, 0, 'code'));
await req('A2 duplicate register', 409, 'POST', '/auth/register', { body: { username: 'qa_d' + ts, password: PW, nickname: 'dup2' } }, j => assertEq(j.code, 40901, 'code'));
await req('A3 weak password', 400, 'POST', '/auth/register', { body: { username: 'qa_w' + ts, password: '123', nickname: 'w' } });
await req('A4 login wrong password', 401, 'POST', '/auth/login', { body: { username: U.alice, password: 'wrongpw' } });
await req('A5 register underscore username', 200, 'POST', '/auth/register', { body: { username: U.under, password: PW, nickname: 'under' } }, j => assertEq(j.code, 40901, 'code(已注册过=幂等409)')); // 已注册 → 409 契约（重复），重点是无 500
await req('A5b register NEW underscore username', 200, 'POST', '/auth/register', { body: { username: `qa_${ts}_x`, password: PW, nickname: 'x1' } }, j => assertEq(j.code, 0, 'code'));
await req('A6 login underscore username', 200, 'POST', '/auth/login', { body: { username: `qa_${ts}_x`, password: PW } }, j => assertEq(j.code, 0, 'code'));
await req('A7 access without token', 401, 'GET', '/ledgers');
await req('A8 forged token', 401, 'GET', '/ledgers', { token: 'a.b.c' });
await req('A8b token wrong signature', 401, 'GET', '/ledgers', { token: T('alice').slice(0, -2) + 'xx' });

// ================= B 账本与权限 =================
const L1 = (await req('B1 create ledger', 200, 'POST', '/ledgers', { token: T('alice'), body: { name: 'QA-B1' } })).data.id;
state.L1 = L1;
await req('B2 outsider GET balances', 403, 'GET', `/ledgers/${L1}/balances`, { token: T('out') });
await req('B2b outsider GET expenses', 403, 'GET', `/ledgers/${L1}/expenses`, { token: T('out') });
await req('B3 invite nonexistent', 404, 'POST', `/ledgers/${L1}/members`, { token: T('alice'), body: { username: 'nosuchuser_zzz' } });
await req('B4 invite bob', 200, 'POST', `/ledgers/${L1}/members`, { token: T('alice'), body: { username: U.bob } });
await req('B5 invite bob dup idempotent', 200, 'POST', `/ledgers/${L1}/members`, { token: T('alice'), body: { username: U.bob } });
await req('B6 invite self', 200, 'POST', `/ledgers/${L1}/members`, { token: T('alice'), body: { username: U.alice } });
await req('B6b invite underscore user', 200, 'POST', `/ledgers/${L1}/members`, { token: T('alice'), body: { username: U.under } }, j => assertEq(j.code, 0, 'code'));
await req('B7 GET nonexistent ledger', 404, 'GET', '/ledgers/99999999/expenses', { token: T('alice') });
await req('B8 outsider create expense', 403, 'POST', `/ledgers/${L1}/expenses`, { token: T('out'), body: { title: 'x', amount: 10, paidBy: ID('out'), splits: [split(ID('out'))], expenseDate: '2026-10-01' } });

// ================= C 记账核心 =================
await req('C1 EQUAL 100 two-way', 200, 'POST', `/ledgers/${L1}/expenses`,
  { token: T('alice'), body: { title: 'hotel', amount: 100.00, paidBy: ID('alice'), splits: [split(ID('alice')), split(ID('bob'))], expenseDate: '2026-10-01' } },
  j => { const c = j.data.splits.map(s => Math.round(s.amount * 100)).sort((a, b) => a - b); assertEq(c[0], 5000, 'split0'); assertEq(c[1], 5000, 'split1'); assertEq(j.data.version, 0, 'version'); });
const eC2 = await req('C2 EQUAL 100.01 remainder to payer', 200, 'POST', `/ledgers/${L1}/expenses`,
  { token: T('alice'), body: { title: 'odd', amount: 100.01, paidBy: ID('alice'), splits: [split(ID('alice')), split(ID('bob'))], expenseDate: '2026-10-01' } });
{ const m = {}; eC2.data.splits.forEach(s => m[s.userId] = Math.round(s.amount * 100));
  assertEq(m[ID('alice')], 5001, 'payer remainder'); assertEq(m[ID('bob')], 5000, 'bob'); }
lines.push('[INFO] C2 remainder check: ' + JSON.stringify(eC2.data.splits));
await req('C2a invite carol (before C2b uses her as participant)', 200, 'POST', `/ledgers/${L1}/members`, { token: T('alice'), body: { username: U.carol } });
const eC2b = await req('C2b EQUAL 10.01 payer-not-in-splits (payer extra row 0.01)', 200, 'POST', `/ledgers/${L1}/expenses`,
  { token: T('alice'), body: { title: 'odd2', amount: 10.01, paidBy: ID('alice'), splits: [split(ID('bob')), split(ID('carol'))], expenseDate: '2026-10-01' } },
  j => { const m = {}; j.data.splits.forEach(s => m[s.userId] = Math.round(s.amount * 100));
    assertEq(m[ID('alice')], 1, 'payer remainder row'); assertEq(m[ID('bob')], 500, 'bob'); assertEq(m[ID('carol')], 500, 'carol'); });
await req('C3 AMOUNT sum mismatch', 400, 'POST', `/ledgers/${L1}/expenses`,
  { token: T('alice'), body: { title: 'bad', amount: 100.00, paidBy: ID('alice'), splits: [split(ID('alice'), 'AMOUNT', 60), split(ID('bob'), 'AMOUNT', 30)], expenseDate: '2026-10-01' } });
await req('C4 AMOUNT valid', 200, 'POST', `/ledgers/${L1}/expenses`,
  { token: T('alice'), body: { title: 'amt', amount: 100.00, paidBy: ID('alice'), splits: [split(ID('alice'), 'AMOUNT', 60), split(ID('bob'), 'AMOUNT', 40)], expenseDate: '2026-10-01' } });
await req('C5 invite carol', 200, 'POST', `/ledgers/${L1}/members`, { token: T('alice'), body: { username: U.carol } });
const eC5 = await req('C5b RATIO 1:2:3 of 100.00', 200, 'POST', `/ledgers/${L1}/expenses`,
  { token: T('alice'), body: { title: 'ratio', amount: 100.00, paidBy: ID('alice'), splits: [split(ID('alice'), 'RATIO', 1), split(ID('bob'), 'RATIO', 2), split(ID('carol'), 'RATIO', 3)], expenseDate: '2026-10-01' } });
{ const c = eC5.data.splits.map(s => Math.round(s.amount * 100)); const sum = c.reduce((a, b) => a + b, 0);
  assertEq(sum, 10000, 'RATIO sum'); }
lines.push('[INFO] C5 ratio splits: ' + JSON.stringify(eC5.data.splits));
await req('C6 empty splits', 400, 'POST', `/ledgers/${L1}/expenses`,
  { token: T('alice'), body: { title: 'empty', amount: 10.00, paidBy: ID('alice'), splits: [], expenseDate: '2026-10-01' } });
await req('C7 zero amount', 400, 'POST', `/ledgers/${L1}/expenses`,
  { token: T('alice'), body: { title: 'zero', amount: 0.00, paidBy: ID('alice'), splits: [split(ID('alice'))], expenseDate: '2026-10-01' } });
await req('C7b negative amount', 400, 'POST', `/ledgers/${L1}/expenses`,
  { token: T('alice'), body: { title: 'neg', amount: -5.00, paidBy: ID('alice'), splits: [split(ID('alice'))], expenseDate: '2026-10-01' } });
await req('C8 nonmember participant', 400, 'POST', `/ledgers/${L1}/expenses`,
  { token: T('alice'), body: { title: 'badp', amount: 10.00, paidBy: ID('alice'), splits: [split(ID('alice')), split(ID('out'))], expenseDate: '2026-10-01' } });
await req('C8b nonmember payer', 400, 'POST', `/ledgers/${L1}/expenses`,
  { token: T('alice'), body: { title: 'badpayer', amount: 10.00, paidBy: ID('out'), splits: [split(ID('alice'))], expenseDate: '2026-10-01' } });
await req('C9 balances sum=0', 200, 'GET', `/ledgers/${L1}/balances`, { token: T('alice') }, sumBalances);
const eC10 = await req('C10 EQUAL 100.01 three-way payer=bob', 200, 'POST', `/ledgers/${L1}/expenses`,
  { token: T('bob'), body: { title: 'three', amount: 100.01, paidBy: ID('bob'), splits: [split(ID('alice')), split(ID('bob')), split(ID('carol'))], expenseDate: '2026-10-01' } });
{ const m = {}; eC10.data.splits.forEach(s => m[s.userId] = Math.round(s.amount * 100));
  assertEq(m[ID('bob')], 3335, 'payer gets remainder(10001/3=3333 rem2)'); }
lines.push('[INFO] C10 splits: ' + JSON.stringify(eC10.data.splits));
await req('C11 unknown sharetype', 400, 'POST', `/ledgers/${L1}/expenses`,
  { token: T('alice'), body: { title: 'st', amount: 10.00, paidBy: ID('alice'), splits: [split(ID('alice'), 'WEIRD', 0)], expenseDate: '2026-10-01' } });
await req('C12 mixed sharetype', 400, 'POST', `/ledgers/${L1}/expenses`,
  { token: T('alice'), body: { title: 'mix', amount: 10.00, paidBy: ID('alice'), splits: [split(ID('alice')), split(ID('bob'), 'AMOUNT', 5)], expenseDate: '2026-10-01' } });
await req('C13 three decimals', 400, 'POST', `/ledgers/${L1}/expenses`,
  { token: T('alice'), body: { title: 'd3', amount: 10.005, paidBy: ID('alice'), splits: [split(ID('alice'))], expenseDate: '2026-10-01' } });
// 到期日过期支出（提醒调度用）：dueDate=昨天，未结清
await req('C15 dueDate overdue unsettled', 200, 'POST', `/ledgers/${L1}/expenses`,
  { token: T('alice'), body: { title: 'due', amount: 33.00, paidBy: ID('alice'), splits: [split(ID('bob'))], expenseDate: '2026-10-01', dueDate: '2026-10-02' } });
await req('C16 balances sum=0 again', 200, 'GET', `/ledgers/${L1}/balances`, { token: T('alice') }, sumBalances);

// ================= D 乐观锁 =================
const dExp = await req('D0 create expense for lock test', 200, 'POST', `/ledgers/${L1}/expenses`,
  { token: T('alice'), body: { title: 'lock', amount: 20.00, paidBy: ID('alice'), splits: [split(ID('alice')), split(ID('bob'))], expenseDate: '2026-10-01' } });
const EID = dExp.data.id; state.EID = EID;
const updBody = (amount, version, paidBy) => ({ title: 'lock-upd', amount, paidBy, splits: [split(paidBy), split(ID('bob'))], expenseDate: '2026-10-01', version });
await req('D1 PUT fresh version', 200, 'PUT', `/expenses/${EID}`, { token: T('alice'), body: updBody(21.00, 0, ID('alice')) });
await req('D2 PUT stale version', 409, 'PUT', `/expenses/${EID}`, { token: T('alice'), body: updBody(22.00, 0, ID('alice')) });
const pg = await req('D3 page shows version=1 amount=21', 200, 'GET', `/ledgers/${L1}/expenses?page=1&size=100`, { token: T('alice') });
{ const row = pg.data.records.find(r => r.id === EID);
  assertEq(row.amount, 21.00, 'amount after 1 update'); assertEq(row.version, 1, 'version after update'); }
await req('D3b balances sum=0 after conflict', 200, 'GET', `/ledgers/${L1}/balances`, { token: T('alice') }, sumBalances);
await req('D4 member-not-payer PUT (bob not payer/owner)', 403, 'PUT', `/expenses/${EID}`, { token: T('bob'), body: updBody(21.00, 1, ID('bob')) });
await req('D5 DELETE stale version', 409, 'DELETE', `/expenses/${EID}?version=0`, { token: T('alice') });

// ================= E 结清 =================
const L2 = (await req('E0 create ledger 2', 200, 'POST', '/ledgers', { token: T('alice'), body: { name: 'QA-E2' } })).data.id;
state.L2 = L2;
await req('E0b invite bob to L2', 200, 'POST', `/ledgers/${L2}/members`, { token: T('alice'), body: { username: U.bob } });
await req('E1 pair debt alice pays 30 [alice,bob]', 200, 'POST', `/ledgers/${L2}/expenses`,
  { token: T('alice'), body: { title: 'dinner', amount: 30.00, paidBy: ID('alice'), splits: [split(ID('alice')), split(ID('bob'))], expenseDate: '2026-10-01' } });
const p2 = await req('E1b plan two-person (expect 1: bob->alice 15)', 200, 'GET', `/ledgers/${L2}/settlement-plan`, { token: T('alice') });
assertEq(p2.data.totalTransfers, 1, 'two-person transfers');
await req('E1c execute confirm=false', 400, 'POST', `/ledgers/${L2}/settlements/execute-plan?confirm=false`, { token: T('alice') });
await req('E1d execute plan', 200, 'POST', `/ledgers/${L2}/settlements/execute-plan?confirm=true`, { token: T('alice') });
await req('E1e balances zero after execute', 200, 'GET', `/ledgers/${L2}/balances`, { token: T('alice') },
  j => { for (const r of j.data) assertEq(Math.round(r.net * 100), 0, 'net after execute u=' + r.userId); });
const p2b = await req('E1f plan with no debt', 200, 'GET', `/ledgers/${L2}/settlement-plan`, { token: T('alice') });
assertEq(p2b.data.totalTransfers, 0, 'empty plan');

const L3 = (await req('E2 create ring ledger', 200, 'POST', '/ledgers', { token: T('alice'), body: { name: 'QA-E3-ring' } })).data.id;
state.L3 = L3;
await req('E2b invite bob', 200, 'POST', `/ledgers/${L3}/members`, { token: T('alice'), body: { username: U.bob } });
await req('E2c invite carol', 200, 'POST', `/ledgers/${L3}/members`, { token: T('alice'), body: { username: U.carol } });
// 环: alice pays30 [alice,bob] -> bob欠alice15; bob pays20 [bob,carol] -> carol欠bob10; carol pays10 [carol,alice] -> alice欠carol5
await req('E2d ring expense r1', 200, 'POST', `/ledgers/${L3}/expenses`, { token: T('alice'), body: { title: 'r1', amount: 30.00, paidBy: ID('alice'), splits: [split(ID('alice')), split(ID('bob'))], expenseDate: '2026-09-30' } });
await req('E2e ring expense r2', 200, 'POST', `/ledgers/${L3}/expenses`, { token: T('bob'), body: { title: 'r2', amount: 20.00, paidBy: ID('bob'), splits: [split(ID('bob')), split(ID('carol'))], expenseDate: '2026-09-30' } });
await req('E2f ring expense r3', 200, 'POST', `/ledgers/${L3}/expenses`, { token: T('carol'), body: { title: 'r3', amount: 10.00, paidBy: ID('carol'), splits: [split(ID('carol')), split(ID('alice'))], expenseDate: '2026-09-30' } });
const bl3 = await req('E2g ring balances sum0', 200, 'GET', `/ledgers/${L3}/balances`, { token: T('alice') }, sumBalances);
{ const m = {}; bl3.data.forEach(r => m[r.userId] = Math.round(r.net * 100));
  assertEq(m[ID('alice')], 1000, 'alice net'); assertEq(m[ID('bob')], -500, 'bob net'); assertEq(m[ID('carol')], -500, 'carol net'); }
lines.push('[INFO] E2 ring nets: ' + JSON.stringify(bl3.data));
const p3 = await req('E2h ring plan (expect 2 transfers)', 200, 'GET', `/ledgers/${L3}/settlement-plan`, { token: T('alice') });
assertEq(p3.data.totalTransfers, 2, 'ring transfers');
await req('E3 manual bob->alice 5.00 (net change)', 200, 'POST', `/ledgers/${L3}/settlements`, { token: T('bob'), body: { fromUser: ID('bob'), toUser: ID('alice'), amount: 5.00 } });
const bl3b = await req('E3b balances after manual', 200, 'GET', `/ledgers/${L3}/balances`, { token: T('alice') }, sumBalances);
{ const m = {}; bl3b.data.forEach(r => m[r.userId] = Math.round(r.net * 100));
  assertEq(m[ID('alice')], 500, 'alice +5'); assertEq(m[ID('bob')], 0, 'bob 0'); }
await req('E4 manual wrong direction (alice not debtor)', 400, 'POST', `/ledgers/${L3}/settlements`, { token: T('alice'), body: { fromUser: ID('alice'), toUser: ID('carol'), amount: 1.00 } });
await req('E4b manual over-repay', 400, 'POST', `/ledgers/${L3}/settlements`, { token: T('carol'), body: { fromUser: ID('carol'), toUser: ID('alice'), amount: 99.00 } });
await req('E5 execute rest', 200, 'POST', `/ledgers/${L3}/settlements/execute-plan?confirm=true`, { token: T('alice') });
await req('E5b all nets zero', 200, 'GET', `/ledgers/${L3}/balances`, { token: T('alice') },
  j => { for (const r of j.data) assertEq(Math.round(r.net * 100), 0, 'net zero u=' + r.userId); });
const L4 = (await req('E6 create clean ledger', 200, 'POST', '/ledgers', { token: T('alice'), body: { name: 'QA-E6' } })).data.id;
const p4 = await req('E6b empty plan', 200, 'GET', `/ledgers/${L4}/settlement-plan`, { token: T('alice') });
assertEq(p4.data.totalTransfers, 0, 'no-debt plan empty');
await req('E6c execute on no-debt', 200, 'POST', `/ledgers/${L4}/settlements/execute-plan?confirm=true`, { token: T('alice') });

// ================= F 分页 =================
await req('F1 page=0 clamp', 200, 'GET', `/ledgers/${L1}/expenses?page=0&size=10`, { token: T('alice') }, j => assertEq(j.data.page, 1, 'page clamped to 1'));
await req('F2 size=10000 clamp', 200, 'GET', `/ledgers/${L1}/expenses?page=1&size=10000`, { token: T('alice') }, j => assertEq(j.data.size, 100, 'size clamped to 100'));
await req('F3 page huge empty', 200, 'GET', `/ledgers/${L1}/expenses?page=999999&size=10`, { token: T('alice') }, j => assertEq(j.data.records.length, 0, 'records empty'));
await req('F4 size=0 clamp', 200, 'GET', `/ledgers/${L1}/expenses?page=1&size=0`, { token: T('alice') }, j => assertEq(j.data.size, 1, 'size>=1'));
await req('F5 page=-1 clamp', 200, 'GET', `/ledgers/${L1}/expenses?page=-1&size=5`, { token: T('alice') });

// ================= X 输入错误（曾经的 50000 通道） =================
await req('X1 malformed JSON body', 400, 'POST', '/auth/register', { raw: '{"username":"qa_x1"' });
await req('X2 empty body', 400, 'POST', '/auth/login', { raw: '' });
await req('X3 DELETE missing version param', 400, 'DELETE', `/expenses/${EID}`);
await req('X4 version non-numeric', 400, 'DELETE', `/expenses/${EID}?version=abc`);

// ================= R 多轮随机操作 + 净额恒等式 =================
{
  let rnd = 12345;
  const rand = () => { rnd = (rnd * 1103515245 + 12345) & 0x7fffffff; return rnd / 0x7fffffff; };
  const members = ['alice', 'bob', 'carol'];
  const expenses = [];
  for (let round = 1; round <= 20; round++) {
    const op = rand();
    let stepName;
    if (op < 0.45 || expenses.length === 0) {
      const payer = members[Math.floor(rand() * 3)];
      const amount = (Math.floor(rand() * 100000) + 1) / 100; // 0.01~1000.00 奇数分级
      const n = 2 + Math.floor(rand() * 2);
      const ids = [ID(payer)];
      while (ids.length < n) { const c = ID(members[Math.floor(rand() * 3)]); if (!ids.includes(c)) ids.push(c); }
      const splits = n === 3 && rand() < 0.4
        ? ids.map((u, i) => ({ userId: u, shareType: 'AMOUNT', share: Math.floor(amount * 100 / n) / 100 }))
        : ids.map(u => split(u));
      if (n === 3 && splits[0].shareType === 'AMOUNT') {
        const each = Math.floor(amount * 100 / n) / 100;
        const sum = each * n;
        splits[0] = { userId: splits[0].userId, shareType: 'AMOUNT', share: Math.round((amount - sum) * 100 + each * 100) / 100 };
      }
      stepName = `R${round} create EQUAL/AMOUNT ${amount}`;
      const j = await req(stepName, 200, 'POST', `/ledgers/${L1}/expenses`, { token: T(payer), body: { title: 'r' + round, amount, paidBy: ID(payer), splits, expenseDate: '2026-10-01' } });
      if (j && j.data) expenses.push({ id: j.data.id, v: j.data.version, payer });
    } else if (op < 0.65 && expenses.length) {
      const e = expenses[Math.floor(rand() * expenses.length)];
      const amt = Math.round((rand() * 50000)) / 100 + 0.01;
      const paidBy = e.payer;
      stepName = `R${round} update ${e.id}`;
      await req(stepName, 200, 'PUT', `/expenses/${e.id}`, { token: T(paidBy), body: { title: 'upd' + round, amount: amt, paidBy: ID(paidBy), splits: [split(ID(paidBy)), ...[ID('bob'), ID('carol')].filter(u => u !== ID(paidBy)).slice(0, 1).map(u => split(u))], expenseDate: '2026-10-01', version: e.v } });
      e.v += 1;
    } else if (op < 0.8 && expenses.length > 3) {
      const e = expenses[Math.floor(rand() * expenses.length)];
      stepName = `R${round} delete ${e.id}`;
      await req(stepName, 200, 'DELETE', `/expenses/${e.id}?version=${e.v}`, { token: T(e.payer) });
      expenses.splice(expenses.indexOf(e), 1);
    } else {
      // manual settle: find debtor & creditor in L1
      const b = await req(`R${round} balances read`, 200, 'GET', `/ledgers/${L1}/balances`, { token: T('alice') });
      const debtors = b.data.filter(r => r.net < 0), creditors = b.data.filter(r => r.net > 0);
      if (debtors.length && creditors.length) {
        const d = debtors[0], c = creditors[0];
        const amount = Math.min(-d.net, c.net).toFixed(2);
        const who = Object.keys(state.users).find(k => state.users[k].id === d.userId);
        stepName = `R${round} manual ${d.userId}->${c.userId} ${amount}`;
        await req(stepName, 200, 'POST', `/ledgers/${L1}/settlements`, { token: T(who), body: { fromUser: d.userId, toUser: c.userId, amount: parseFloat(amount) } });
      } else { stepName = `R${round} no-debt skip`; lines.push(`[INFO] ${stepName}`); }
    }
    await req(`R${round} INVARIANT sum(balances)=0`, 200, 'GET', `/ledgers/${L1}/balances`, { token: T('alice') }, sumBalances);
  }
}

// 汇总
lines.push(`SUMMARY tag=${TAG} PASS=${pass} FAIL=${fail}`);
writeFileSync(`qa/results-${TAG}.txt`, lines.join('\n') + '\n');
writeFileSync(`qa/state-${TAG}.json`, JSON.stringify(state, null, 2));
console.log(lines.join('\n'));
