#!/bin/bash
# QA suite B/C/D/E/F — 全部 payload ASCII
B=http://localhost:8081/api
H='Content-Type: application/json'
PW='123456'
PASS=0; FAIL=0
# run <name> <expect_http> <curl args...>
run() {
  local name=$1 expect=$2; shift 2
  local out; out=$(curl -s -w $'\n%{http_code}' "$@")
  local code=${out##*$'\n'}; local body=${out%$'\n'*}
  local v=PASS; [ "$code" = "$expect" ] || v=FAIL
  [ $v = PASS ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
  echo "[$v] $name | expect $expect | got $code | ${body:0:200}"
}
# field <json> <key> : extract number/string field value
field() {
  echo "$1" | awk -F"\"" "$2"\"" '{print $3}' | head -1
}
numfield() {
  echo "$1" | tr -d ' ' | sed -E "s/.*\"$2\"://" | cut -d',' -f1 | cut -d'}' -f1
}
jtok() { echo "$1" | tr -d ' ' | sed -E 's/.*"token"://' | cut -d',' -f1 | tr -d '"'; }
reg() { curl -s -X POST $B/auth/register -H "$H" -d "{\"username\":\"$1\",\"password\":\"$PW\",\"nickname\":\"$2\"}" > /dev/null; }
loginjson() { curl -s -X POST $B/auth/login -H "$H" -d "{\"username\":\"$1\",\"password\":\"$PW\"}"; }

TS=$(date +%H%M%S)
A="qaalice$TS"; BOB="qabob$TS"; CAROL="qacarol$TS"; OUT="qaout$TS"
reg $A Alice; reg $BOB Bob; reg $CAROL Carol; reg $OUT Out
JA=$(loginjson $A); JB=$(loginjson $BOB); JC=$(loginjson $CAROL); JO=$(loginjson $OUT)
TA=$(jtok "$JA"); TB=$(jtok "$JB"); TC=$(jtok "$JC"); TO=$(jtok "$JO")
UA=$(numfield "$JA" userId); UB=$(numfield "$JB" userId); UC=$(numfield "$JC" userId); UO=$(numfield "$JO" userId)
AH="Authorization: Bearer $TA"; BH="Authorization: Bearer $TB"; CH="Authorization: Bearer $TC"; OH="Authorization: Bearer $TO"
echo "== users: A=$UA B=$UB C=$UC OUT=$UO  (TS=$TS) =="

mkledger() { curl -s -X POST $B/ledgers -H "$AH" -H "$H" -d "{\"name\":\"$1\"}"; }
mkexpense() { # ledger payer title amount  splits-json  extra
  curl -s -X POST $B/ledgers/$1/expenses -H "$2" -H "$H" -d "{\"title\":\"$3\",\"amount\":$4,\"paidBy\":$5,\"splits\":$6,\"expenseDate\":\"2026-10-01\"$7}"
}

echo "=== Suite B: ledger & permission ==="
LG=$(mkledger "QA Ledger B"); L=$(numfield "$LG" id)
echo "[INFO] B1 create ledger -> id=$L : ${LG:0:120}"
run B2 outsider GET balances 403 -X GET $B/ledgers/$L/balances -H "$OH"
run B2b outsider GET expenses 403 -X GET $B/ledgers/$L/expenses -H "$OH"
run B3 invite-nonexistent 404 -X POST $B/ledgers/$L/members -H "$AH" -H "$H" -d '{"username":"nosuchuser_zzz"}'
run B4 invite-bob 200 -X POST $B/ledgers/$L/members -H "$AH" -H "$H" -d "{\"username\":\"$BOB\"}"
run B5 invite-bob-again-idempotent 200 -X POST $B/ledgers/$L/members -H "$AH" -H "$H" -d "{\"username\":\"$BOB\"}"
run B6 invite-self 200 -X POST $B/ledgers/$L/members -H "$AH" -H "$H" -d "{\"username\":\"$A\"}"
run B7 GET nonexistent-ledger 404 -X GET $B/ledgers/99999999/expenses -H "$AH"

echo "=== Suite C: expense core ==="
run C1 EQUAL-100-two 200 -X POST $B/ledgers/$L/expenses -H "$AH" -H "$H" -d "{\"title\":\"hotel\",\"amount\":100.00,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UB,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\"}"
echo "[INFO] C2 EQUAL-100.01-two payer-in-splits:"; curl -s -X POST $B/ledgers/$L/expenses -H "$AH" -H "$H" -d "{\"title\":\"odd\",\"amount\":100.01,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UB,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\"}"; echo
echo "[INFO] C2b EQUAL-10.01 payer-NOT-in-splits (carol pays):"; curl -s -X POST $B/ledgers/$L/expenses -H "$AH" -H "$H" -d "{\"title\":\"odd2\",\"amount\":10.01,\"paidBy\":$UA,\"splits\":[{\"userId\":$UB,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UC,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\"}" > /dev/null # carol not member yet -> C8b class; use alice payer below instead
run C3 AMOUNT-sum-mismatch 400 -X POST $B/ledgers/$L/expenses -H "$AH" -H "$H" -d "{\"title\":\"bad\",\"amount\":100.00,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"AMOUNT\",\"share\":60},{\"userId\":$UB,\"shareType\":\"AMOUNT\",\"share\":30}],\"expenseDate\":\"2026-10-01\"}"
run C4 AMOUNT-valid 200 -X POST $B/ledgers/$L/expenses -H "$AH" -H "$H" -d "{\"title\":\"amt\",\"amount\":100.00,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"AMOUNT\",\"share\":60},{\"userId\":$UB,\"shareType\":\"AMOUNT\",\"share\":40}],\"expenseDate\":\"2026-10-01\"}"
run C5a invite-carol 200 -X POST $B/ledgers/$L/members -H "$AH" -H "$H" -d "{\"username\":\"$CAROL\"}"
echo "[INFO] C5 RATIO-1:2:3-of-100.00 (expect 16.67/33.33/50.00):"; curl -s -X POST $B/ledgers/$L/expenses -H "$AH" -H "$H" -d "{\"title\":\"ratio\",\"amount\":100.00,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"RATIO\",\"share\":1},{\"userId\":$UB,\"shareType\":\"RATIO\",\"share\":2},{\"userId\":$UC,\"shareType\":\"RATIO\",\"share\":3}],\"expenseDate\":\"2026-10-01\"}"; echo
run C6 empty-splits 400 -X POST $B/ledgers/$L/expenses -H "$AH" -H "$H" -d "{\"title\":\"empty\",\"amount\":10.00,\"paidBy\":$UA,\"splits\":[],\"expenseDate\":\"2026-10-01\"}"
run C7 zero-amount 400 -X POST $B/ledgers/$L/expenses -H "$AH" -H "$H" -d "{\"title\":\"zero\",\"amount\":0.00,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\"}"
run C7b negative-amount 400 -X POST $B/ledgers/$L/expenses -H "$AH" -H "$H" -d "{\"title\":\"neg\",\"amount\":-5.00,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\"}"
run C8 nonmember-participant 400 -X POST $B/ledgers/$L/expenses -H "$AH" -H "$H" -d "{\"title\":\"badp\",\"amount\":10.00,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UO,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\"}"
run C8b nonmember-payer 400 -X POST $B/ledgers/$L/expenses -H "$AH" -H "$H" -d "{\"title\":\"badpayer\",\"amount\":10.00,\"paidBy\":$UO,\"splits\":[{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\"}"
echo "[INFO] C9 balances (expect sum 0):"; curl -s $B/ledgers/$L/balances -H "$AH"; echo
echo "[INFO] C10 EQUAL-100.01 three-way payer=bob (expect 33.34 payer /33.33 /33.33):"; curl -s -X POST $B/ledgers/$L/expenses -H "$BH" -H "$H" -d "{\"title\":\"three\",\"amount\":100.01,\"paidBy\":$UB,\"splits\":[{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UB,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UC,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\"}"; echo
run C11 unknown-sharetype 400 -X POST $B/ledgers/$L/expenses -H "$AH" -H "$H" -d "{\"title\":\"st\",\"amount\":10.00,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"WEIRD\",\"share\":0}],\"expenseDate\":\"2026-10-01\"}"
run C12 mixed-sharetype 400 -X POST $B/ledgers/$L/expenses -H "$AH" -H "$H" -d "{\"title\":\"mix\",\"amount\":10.00,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UB,\"shareType\":\"AMOUNT\",\"share\":5}],\"expenseDate\":\"2026-10-01\"}"
run C13 three-decimals 400 -X POST $B/ledgers/$L/expenses -H "$AH" -H "$H" -d "{\"title\":\"d3\",\"amount\":10.005,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\"}"
echo "[INFO] C14 balances after all (expect sum 0):"; curl -s $B/ledgers/$L/balances -H "$AH"; echo

echo "=== Suite D: optimistic lock ==="
E1=$(curl -s -X POST $B/ledgers/$L/expenses -H "$AH" -H "$H" -d "{\"title\":\"lock\",\"amount\":20.00,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UB,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\"}")
EID=$(numfield "$E1" id); V0=$(numfield "$E1" version)
echo "[INFO] D0 created expense id=$EID version=$V0"
upd() { curl -s -w $'\n[%{http_code}]' -X PUT $B/expenses/$EID -H "$AH" -H "$H" -d "{\"title\":\"lock-upd\",\"amount\":$1,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UB,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\",\"version\":$2}"; }
echo "[INFO] D1 PUT with fresh v=$V0 (expect 200):"; upd 21.00 "$V0"; echo
echo "[INFO] D2 PUT again with STALE v=$V0 (expect 409):"; upd 22.00 "$V0"; echo
run D2b-stale-PUT-409 409 -X PUT $B/expenses/$EID -H "$AH" -H "$H" -d "{\"title\":\"stale\",\"amount\":23.00,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UB,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\",\"version\":$V0}"
echo "[INFO] D3 page row after conflict (expect amount 21.00 version 1):"; curl -s "$B/ledgers/$L/expenses?page=1&size=1" -H "$AH" | head -c 300; echo
echo "[INFO] D3b balances sum 0:"; curl -s $B/ledgers/$L/balances -H "$AH"; echo
run D4 DELETE-stale-version 409 -X DELETE "$B/expenses/$EID?version=$V0" -H "$AH"

echo "=== Suite E: settlement ==="
LE=$(numfield "$(mkledger 'QA E2')" id)
run E0a invite-bob-E 200 -X POST $B/ledgers/$LE/members -H "$AH" -H "$H" -d "{\"username\":\"$BOB\"}"
echo "[INFO] E1 two-person: alice pays 30 split alice+bob:"; curl -s -X POST $B/ledgers/$LE/expenses -H "$AH" -H "$H" -d "{\"title\":\"dinner\",\"amount\":30.00,\"paidBy\":$UA,\"splits\":[{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UB,\"shareType\":\"EQUAL\",\"share\":0}],\"expenseDate\":\"2026-10-01\"}" > /dev/null; echo done
echo "[INFO] E1b plan (expect 1 transfer B->A 15):"; curl -s $B/ledgers/$LE/settlement-plan -H "$AH"; echo
run E1c execute-confirm-false-400 400 -X POST "$B/ledgers/$LE/settlements/execute-plan?confirm=false" -H "$AH"
echo "[INFO] E1d execute (expect balances zero):"; curl -s -X POST "$B/ledgers/$LE/settlements/execute-plan?confirm=true" -H "$AH"; echo
echo "[INFO] E1e balances after execute:"; curl -s $B/ledgers/$LE/balances -H "$AH"; echo
echo "[INFO] E1f plan with no debt (expect empty):"; curl -s $B/ledgers/$LE/settlement-plan -H "$AH"; echo

LF=$(numfield "$(mkledger 'QA E3 ring')" id)
run E2a invite-ring-b 200 -X POST $B/ledgers/$LF/members -H "$AH" -H "$H" -d "{\"username\":\"$BOB\"}"
run E2b invite-ring-c 200 -X POST $B/ledgers/$LF/members -H "$AH" -H "$H" -d "{\"username\":\"$CAROL\"}"
mk() { curl -s -X POST $B/ledgers/$LF/expenses -H "$1" -H "$H" -d "{\"title\":\"$2\",\"amount\":$3,\"paidBy\":$4,\"splits\":$5,\"expenseDate\":\"2026-09-30\"}"; }
echo "[INFO] E2 ring: A pays30 [A,B]; B pays20 [B,C]; C pays10 [C,A]"
mk "$AH" r1 30.00 $UA "[{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UB,\"shareType\":\"EQUAL\",\"share\":0}]" >/dev/null
mk "$BH" r2 20.00 $UB "[{\"userId\":$UB,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UC,\"shareType\":\"EQUAL\",\"share\":0}]" >/dev/null
mk "$CH" r3 10.00 $UC "[{\"userId\":$UC,\"shareType\":\"EQUAL\",\"share\":0},{\"userId\":$UA,\"shareType\":\"EQUAL\",\"share\":0}]" >/dev/null
echo "[INFO] E2c balances (expect A=+10 B=-5 C=-5 sum0):"; curl -s $B/ledgers/$LF/balances -H "$AH"; echo
echo "[INFO] E2d plan (expect 2 transfers to A):"; curl -s $B/ledgers/$LF/settlement-plan -H "$AH"; echo
echo "[INFO] E3 manual settle B->A 5.00 then nets:"; curl -s -X POST $B/ledgers/$LF/settlements -H "$BH" -H "$H" -d "{\"fromUser\":$UB,\"toUser\":$UA,\"amount\":5.00}"; echo
echo "[INFO] E3b balances (expect A=+5 B=0 C=-5 sum0):"; curl -s $B/ledgers/$LF/balances -H "$AH"; echo
echo "[INFO] E3c plan (expect 1 transfer C->A 5):"; curl -s $B/ledgers/$LF/settlement-plan -H "$AH"; echo
run E4 manual-wrong-direction-400 400 -X POST $B/ledgers/$LF/settlements -H "$BH" -H "$H" -d "{\"fromUser\":$UA,\"toUser\":$UC,\"amount\":1.00}"
run E4b manual-over-repay-400 400 -X POST $B/ledgers/$LF/settlements -H "$BH" -H "$H" -d "{\"fromUser\":$UC,\"toUser\":$UA,\"amount\":99.00}"
echo "[INFO] E5 execute rest -> all zero:"; curl -s -X POST "$B/ledgers/$LF/settlements/execute-plan?confirm=true" -H "$AH"; echo
echo "[INFO] E5b balances all zero:"; curl -s $B/ledgers/$LF/balances -H "$AH"; echo
LG0=$(numfield "$(mkledger 'QA E6 clean')" id)
echo "[INFO] E6 execute-plan on no-debt ledger (expect empty 200):"; curl -s -w $'\n[%{http_code}]' -X POST "$B/ledgers/$LG0/settlements/execute-plan?confirm=true" -H "$AH"; echo

echo "=== Suite F: paging ==="
run F1 page-0 200 -X GET "$B/ledgers/$L/expenses?page=0&size=10" -H "$AH"
run F2 size-huge 200 -X GET "$B/ledgers/$L/expenses?page=1&size=10000" -H "$AH"
echo "[INFO] F2b clamped size value:"; curl -s "$B/ledgers/$L/expenses?page=1&size=10000" -H "$AH" | sed -E 's/.*"page":([0-9]+),"size":([0-9]+).*/page=\1 size=\2/'; echo
run F3 page-huge 200 -X GET "$B/ledgers/$L/expenses?page=999999&size=10" -H "$AH"
echo "[INFO] F3b records empty:"; curl -s "$B/ledgers/$L/expenses?page=999999&size=10" -H "$AH" | head -c 120; echo
run F4 size-0 200 -X GET "$B/ledgers/$L/expenses?page=1&size=0" -H "$AH"
run F5 page-negative 200 -X GET "$B/ledgers/$L/expenses?page=-1&size=5" -H "$AH"

echo "=== Suite X: malformed json / edge bodies ==="
run X1 malformed-json-400 400 -X POST $B/auth/register -H "$H" -d '{"username":"qa_x1"'
run X2 empty-body-login-400 400 -X POST $B/auth/login -H "$H" -d ''

echo "=== SUMMARY B-C-D-E-F === PASS=$PASS FAIL=$FAIL"
echo "CTX TS=$TS A=$A BOB=$BOB CAROL=$CAROL OUT=$OUT" > qa/ctx.env
echo "UA=$UA UB=$UB UC=$UC UO=$UO" >> qa/ctx.env
echo "L=$L LE=$LE LF=$LF EID=$EID V0=$V0" >> qa/ctx.env
