#!/bin/bash
# 项目一端到端冒烟：注册→登录→账本→成员→支出(均摊)→净额→结清方案→执行→清零
# 用户名带时间戳：每次跑都是干净数据，避免唯一键冲突干扰结果
set -e
B=http://localhost:8081/api
H_JSON='Content-Type: application/json'
TS=$(date +%H%M%S)
A="alice$TS"; BOB="bob$TS"
PW='123456'

r() { curl -s "$@"; }

echo "== 1 register ($A / $BOB) =="
r -X POST $B/auth/register -H "$H_JSON" -d "{\"username\":\"$A\",\"password\":\"$PW\",\"nickname\":\"Alice\"}"
echo
r -X POST $B/auth/register -H "$H_JSON" -d "{\"username\":\"$BOB\",\"password\":\"$PW\",\"nickname\":\"Bob\"}"
echo

echo "== 2 login =="
LA=$(r -X POST $B/auth/login -H "$H_JSON" -d "{\"username\":\"$A\",\"password\":\"$PW\"}")
LB=$(r -X POST $B/auth/login -H "$H_JSON" -d "{\"username\":\"$BOB\",\"password\":\"$PW\"}")
TA=$(echo "$LA" | sed -E 's/.*"token":"***"]*\)".*/\1/')
TB=$(echo "$LB" | sed -E 's/.*"token":"***"]*\)".*/\1/')
UA=$(echo "$LA" | sed -E 's/.*"userId":([0-9]+).*/\1/')
UB=$(echo "$LB" | sed -E 's/.*"userId":([0-9]+).*/\1/')
echo "alice userId=$UA  bob userId=$UB"

echo "== 3 create ledger =="
LG=$(r -X POST $B/ledgers -H "Authorization: Bearer $TA" -H "$H_JSON" -d '{"name":"毕业旅行"}')
echo "$LG"
L=$(echo "$LG" | sed -E 's/.*"id":([0-9]+).*/\1/')

echo "== 4 invite bob =="
r -X POST $B/ledgers/$L/members -H "Authorization: Bearer $TA" -H "$H_JSON" -d "{\"username\":\"$BOB\"}"
echo

echo "== 5 expense 100 by alice, EQUAL split =="
r -X POST $B/ledgers/$L/expenses -H "Authorization: Bearer $TA" -H "$H_JSON" \
  -d "{\"title\":\"hotel\",\"amount\":100.00,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"EQUAL\"},{\"userId\":$UB,\"shareType\":\"EQUAL\"}],\"expenseDate\":\"2026-10-03\"}"
echo

echo "== 6 balances =="
r $B/ledgers/$L/balances -H "Authorization: Bearer $TA"
echo

echo "== 7 settlement plan =="
r $B/ledgers/$L/settlement-plan -H "Authorization: Bearer $TA"
echo

echo "== 8 execute plan =="
r -X POST $B/ledgers/$L/settlements/execute-plan -H "Authorization: Bearer $TA" -H "$H_JSON" -d '{"confirm":true}'
echo

echo "== 9 balances after (期望全 0) =="
r $B/ledgers/$L/balances -H "Authorization: Bearer $TA"
echo
echo "== SMOKE DONE =="
