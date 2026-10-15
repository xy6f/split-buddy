#!/bin/bash
# 全量 QA suite A-H（payload 全 ASCII，规避 Git Bash 中文转 GBK 假 400）
B=http://localhost:8081/api
H='Content-Type: application/json'
PW='123456'
PASS=0; FAIL=0; FAILED=""
run() { # name expect curl...
  local name=$1 expect=$2; shift 2
  local out; out=$(curl -s -w $'\n%{http_code}' "$@")
  local code=${out##*$'\n'}; local body=${out%$'\n'*}
  local v=PASS; [ "$code" = "$expect" ] || v=FAIL
  if [ $v = PASS ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); FAILED="$FAILED|$name"; fi
  echo "[$v] $name | expect $expect | got $code | ${body:0:220}"
}
jtok()  { echo "$1" | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4; }
idfield(){ echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | grep -o '[0-9]*'; }

TS=$(date +%H%M%S)
A="qa_user_a_$TS"; BOB="qa_user_b_$TS"; CAROL="qa_user_c_$TS"; OUT="qa_outsider_$TS"
echo "===== TS=$TS ====="

# ---------- A 认证 ----------
R=$(curl -s -X POST $B/auth/register -H "$H" -d "{\"username\":\"$A\",\"password\":\"$PW\",\"nickname\":\"Alice\"}")
echo "[INFO] A1 下划线用户名注册 resp: $R"
run A1-register-underscore-expect-200 200 -X POST $B/auth/register -H "$H" -d "{\"username\":\"qa_dup_$TS\",\"password\":\"$PW\",\"nickname\":\"Dup\"}"
run A2-duplicate-register-409 409 -X POST $B/auth/register -H "$H" -d "{\"username\":\"qa_dup_$TS\",\"password\":\"$PW\",\"nickname\":\"Dup2\"}"
run A3-weak-password-400 400 -X POST $B/auth/register -H "$H" -d "{\"username\":\"qa_weak_$TS\",\"password\":\"123\",\"nickname\":\"Weak\"}"
run A3b-short-username-400 400 -X POST $B/auth/register -H "$H" -d "{\"username\":\"ab\",\"password\":\"$PW\",\"nickname\":\"Sh\"}"
run A4-wrong-password-401 401 -X POST $B/auth/login -H "$H" -d "{\"username\":\"$A\",\"password\":\"badpass\"}"
LA=$(curl -s -X POST $B/auth/login -H "$H" -d "{\"username\":\"$A\",\"password\":\"$PW\"}")
run A5-login-ok-200 200 -X POST $B/auth/login -H "$H" -d "{\"username\":\"$A\",\"password\":\"$PW\"}"
run A6-no-token-401 401 $B/ledgers
run A7-forged-token-401 401 $B/ledgers -H "Authorization: Bearer not.a.token"
curl -s -X POST $B/auth/register -H "$H" -d "{\"username\":\"$BOB\",\"password\":\"$PW\",\"nickname\":\"Bob\"}" > /dev/null
curl -s -X POST $B/auth/register -H "$H" -d "{\"username\":\"$CAROL\",\"password\":\"$PW\",\"nickname\":\"Carol\"}" > /dev/null
curl -s -X POST $B/auth/register -H "$H" -d "{\"username\":\"$OUT\",\"password\":\"$PW\",\"nickname\":\"Outsider\"}" > /dev/null
LB=$(curl -s -X POST $B/auth/login -H "$H" -d "{\"username\":\"$BOB\",\"password\":\"$PW\"}")
LC=$(curl -s -X POST $B/auth/login -H "$H" -d "{\"username\":\"$CAROL\",\"password\":\"$PW\"}")
LO=$(curl -s -X POST $B/auth/login -H "$H" -d "{\"username\":\"$OUT\",\"password\":\"$PW\"}")
TA=$(jtok "$LA"); TB=$(jtok "$LB"); TC=$(jtok "$LC"); TO=$(jtok "$LO")
UA=$(idfield "$LA" userId); UB=$(idfield "$LB" userId); UC=$(idfield "$LC" userId); UO=$(idfield "$LO" userId)
AH="Authorization: Bearer $TA"; BH="Authorization: Bearer $TB"; CH="Authorization: Bearer $TC"; OH="Authorization: Bearer $TO"
echo "users A=$UA B=$UB C=$UC OUT=$UO"

# ---------- B 账本与权限 ----------
LR=$(curl -s -X POST $B/ledgers -H "$AH" -H "$H" -d '{"name":"QA Trip"}')
echo "[INFO] B1 create ledger: $LR"
run B1-ledger-create-200 200 -X POST $B/ledgers -H "$AH" -H "$H" -d "{\"name\":\"QA Main $TS\"}"
run B2-outsider-page-403 403 "$B/ledgers/$(idfield "$LR" id)/expenses" -H "$OH"
run B2b-outsider-balances-403 403 "$B/ledgers/$(idfield "$LR" id)/balances" -H "$OH"
run B2c-outsider-expense-403 403 -X POST "$B/ledgers/$(idfield "$LR" id)/expenses" -H "$OH" -H "$H" -d "{\"title\":\"x\",\"amount\":10,\"paidBy\":$UO,\"splits\":[{\"userId\":$UO,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\"}"
run B3-invite-nonexistent-404 404 -X POST "$B/ledgers/$(idfield "$LR" id)/members" -H "$AH" -H "$H" -d '{"username":"qa_nobody_xyz"}'
LID=$(idfield "$LR" id)
run B4-invite-bob-200 200 -X POST "$B/ledgers/$LID/members" -H "$AH" -H "$H" -d "{\"username\":\"$BOB\"}"
run B4b-invite-bob-again-idempotent-200 200 -X POST "$B/ledgers/$LID/members" -H "$AH" -H "$H" -d "{\"username\":\"$BOB\"}"
run B5-invite-self-200 200 -X POST "$B/ledgers/$LID/members" -H "$AH" -H "$H" -d "{\"username\":\"$A\"}"
curl -s -X POST "$B/ledgers/$LID/members" -H "$AH" -H "$H" -d "{\"username\":\"$CAROL\"}" > /dev/null

# ---------- C 记账核心 ----------
CEQ=$(curl -s -X POST "$B/ledgers/$LID/expenses" -H "$AH" -H "$H" -d "{\"title\":\"equal odd\",\"amount\":100.00,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UB,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UC,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\"}")
echo "[INFO] C1 EQUAL 100/3 (payer among): $CEQ"
run C2-amount-sum-mismatch-400 400 -X POST "$B/ledgers/$LID/expenses" -H "$AH" -H "$H" -d "{\"title\":\"bad sum\",\"amount\":100,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"AMOUNT\",\"share\":60},{\"userId\":$UB,\"shareType\":\"AMOUNT\",\"share\":39}],\"expenseDate\":\"2026-10-01\"}"
run C3-empty-splits-400 400 -X POST "$B/ledgers/$LID/expenses" -H "$AH" -H "$H" -d "{\"title\":\"empty\",\"amount\":10,\"paidBy\":$UA,\"splits\":[],\"expenseDate\":\"2026-10-01\"}"
run C4-amount-zero-400 400 -X POST "$B/ledgers/$LID/expenses" -H "$AH" -H "$H" -d "{\"title\":\"zero\",\"amount\":0,\"paidBy\":$UA,\"splits\":[{\"userId\":$UB,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\"}"
run C4b-amount-negative-400 400 -X POST "$B/ledgers/$LID/expenses" -H "$AH" -H "$H" -d "{\"title\":\"neg\",\"amount\":-5,\"paidBy\":$UA,\"splits\":[{\"userId\":$UB,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\"}"
run C5-nonmember-participant-403 403 -X POST "$B/ledgers/$LID/expenses" -H "$AH" -H "$H" -d "{\"title\":\"outsider\",\"amount\":10,\"paidBy\":$UA,\"splits\":[{\"userId\":$UO,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\"}"
# 为 D/E/F 造数
E2R=$(curl -s -X POST "$B/ledgers/$LID/expenses" -H "$BH" -H "$H" -d "{\"title\":\"for update\",\"amount\":60,\"paidBy\":$UB,\"splits\":[{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UB,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-02\"}")
EID=$(idfield "$E2R" id)
curl -s -X POST "$B/ledgers/$LID/expenses" -H "$CH" -H "$H" -d "{\"title\":\"third\",\"amount\":45,\"paidBy\":$UC,\"splits\":[{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UB,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UC,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-03\",\"dueDate\":\"2026-10-05\"}" > /dev/null
echo "expense-for-update EID=$EID"

# ---------- D 并发乐观锁 ----------
UPDBODY="{\"title\":\"upd1\",\"amount\":61,\"paidBy\":$UB,\"splits\":[{\"userId\":$UA,\"shareType\":\"AMOUNT\",\"share\":30},{\"userId\":$UB,\"shareType\":\"AMOUNT\",\"share\":31}],\"expenseDate\":\"2026-10-02\",\"version\":0}"
run D1-put-v0-200 200 -X PUT "$B/expenses/$EID" -H "$BH" -H "$H" -d "$UPDBODY"
run D2-put-stale-v0-409 409 -X PUT "$B/expenses/$EID" -H "$BH" -H "$H" -d "$UPDBODY"
run D3-delete-wrong-version-409 409 -X DELETE "$B/expenses/$EID?version=0" -H "$BH"
run D3b-delete-no-version-400or422 400 -X DELETE "$B/expenses/$EID" -H "$BH"

# ---------- E 结清 ----------
SETR=$(curl -s -X POST $B/ledgers -H "$AH" -H "$H" -d "{\"name\":\"QA Settle $TS\"}")
SL=$(idfield "$SETR" id)
curl -s -X POST "$B/ledgers/$SL/members" -H "$AH" -H "$H" -d "{\"username\":\"$BOB\"}" > /dev/null
curl -s -X POST "$B/ledgers/$SL/members" -H "$AH" -H "$H" -d "{\"username\":\"$CAROL\"}" > /dev/null
curl -s -X POST "$B/ledgers/$SL/expenses" -H "$AH" -H "$H" -d "{\"title\":\"s1\",\"amount\":100,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UB,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UC,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\"}" > /dev/null
run E1-plan-two-creditors-ok 200 "$B/ledgers/$SL/settlement-plan" -H "$AH"
PL=$(curl -s "$B/ledgers/$SL/settlement-plan" -H "$AH"); echo "[INFO] E plan: $PL"
run E2-manual-partial-200 200 -X POST "$B/ledgers/$SL/settlements" -H "$BH" -H "$H" -d "{\"fromUser\":$UB,\"toUser\":$UA,\"amount\":20}"
run E3-manual-overpay-400 400 -X POST "$B/ledgers/$SL/settlements" -H "$BH" -H "$H" -d "{\"fromUser\":$UB,\"toUser\":$UA,\"amount\":999}"
run E4-manual-from-creditor-400 400 -X POST "$B/ledgers/$SL/settlements" -H "$BH" -H "$H" -d "{\"fromUser\":$UB,\"toUser\":$UC,\"amount\":10}"
run E5-manual-self-400 400 -X POST "$B/ledgers/$SL/settlements" -H "$BH" -H "$H" -d "{\"fromUser\":$UB,\"toUser\":$UB,\"amount\":10}"
run E6-execute-no-confirm-400 400 -X POST "$B/ledgers/$SL/settlements/execute-plan" -H "$AH"
run E7-execute-plan-200 200 -X POST "$B/ledgers/$SL/settlements/execute-plan?confirm=true" -H "$AH"
BAL=$(curl -s "$B/ledgers/$SL/balances" -H "$AH"); echo "[INFO] E8 balances after execute: $BAL"
run E9-plan-after-settle-empty-200 200 "$B/ledgers/$SL/settlement-plan" -H "$AH"

# ---------- F 分页 ----------
run F1-page0-200 200 "$B/ledgers/$LID/expenses?page=0" -H "$AH"
run F2-page-huge-200 200 "$B/ledgers/$LID/expenses?page=999999" -H "$AH"
run F3-size-huge-200 200 "$B/ledgers/$LID/expenses?page=1&size=100000" -H "$AH"
run F3b-size0-200 200 "$B/ledgers/$LID/expenses?page=1&size=0" -H "$AH"
run F4-bad-page-type-400 400 "$B/ledgers/$LID/expenses?page=abc" -H "$AH"

# ---------- G 降级（Redis 未启动）：以上全流程能跑通本身即验证；日志里应有 Redis 降级 warn ----------
run G1-list-ledgers-200 200 "$B/ledgers" -H "$AH"

echo "===== SUMMARY pass=$PASS fail=$FAIL ====="
echo "FAILED:$FAILED"
