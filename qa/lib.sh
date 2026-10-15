B=http://localhost:8081/api
H='Content-Type: application/json'
PASS=0; FAIL=0
run() {
  local name=$1 expect=$2; shift 2
  local out; out=$(curl -s -w '\n%{http_code}' "$@")
  local body=${out%$'\n'*}; local code=${out##*$'\n'}
  local verdict=PASS
  [ "$code" = "$expect" ] || verdict=FAIL
  if [ "$verdict" = PASS ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi
  echo "[$verdict] $name | expect HTTP $expect | got $code | ${body:0:160}"
}
