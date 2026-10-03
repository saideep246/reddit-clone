#!/usr/bin/env bash
# Verifies DELETE /r/{name}/posts/{postId}: the author, a moderator holding PERM_REMOVE_CONTENT, and the
# community owner can delete (soft-remove) a post; a plain member, a moderator without the bit, an
# anonymous caller and a wrong-community URL cannot. Deleted posts disappear from /new and the detail
# page; a repeat delete is idempotent. Needs the app on $BASE with RATE_LIMIT_REGISTER_CAPACITY raised.
set -uo pipefail
BASE="${BASE:-http://localhost:8081}"
PASSWORD="Sup3rSecret!1"
RUN=$(date +%s | tail -c 6)
PASS_COUNT=0; FAIL_COUNT=0

check() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then PASS_COUNT=$((PASS_COUNT+1)); printf 'PASS  %-62s expected %s, got %s\n' "$desc" "$expected" "$actual"
  else FAIL_COUNT=$((FAIL_COUNT+1)); printf 'FAIL  %-62s expected %s, got %s\n' "$desc" "$expected" "$actual"; fi
}
req() {
  local method="$1" path="$2" data="${3:-}"; shift 3 || true
  local resp
  resp=$(curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" -H 'Content-Type: application/json' ${data:+-d "$data"} "$@")
  HTTP_STATUS=$(echo "$resp" | tail -n1); HTTP_BODY=$(echo "$resp" | sed '$d')
}
register() {
  req POST /api/v1/register "{\"username\":\"dp${RUN}$1\",\"email\":\"dp${RUN}$1@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}
user_id() { req GET "/user/dp${RUN}$1/about" ""; echo "$HTTP_BODY" | jq -r .id; }
submit() {
  req POST "/r/$2/submit" "{\"kind\":\"text\",\"title\":\"$3\",\"body\":\"b\"}" -H "Authorization: Bearer $1" -H "Idempotency-Key: dp-$RUN-$3"
  echo "$HTTP_BODY" | jq -r .id
}
auth() { echo "Authorization: Bearer $1"; }

OWNER=$(register owner); MOD=$(register mod); WEAKMOD=$(register weakmod); AUTHOR=$(register author); OTHER=$(register other)
MOD_ID=$(user_id mod); WEAKMOD_ID=$(user_id weakmod)
C="dpc${RUN}"; C2="dpd${RUN}"
req POST /r "{\"name\":\"$C\",\"description\":\"d\"}" -H "$(auth "$OWNER")"
req POST /r "{\"name\":\"$C2\",\"description\":\"d\"}" -H "$(auth "$OWNER")"
req POST "/r/$C/mod/moderators" "{\"userId\":\"$MOD_ID\",\"permissions\":1}" -H "$(auth "$OWNER")"
req POST "/r/$C/mod/moderators" "{\"userId\":\"$WEAKMOD_ID\",\"permissions\":2}" -H "$(auth "$OWNER")"
for t in AUTHOR OTHER; do req POST "/r/$C/subscribe" "" -H "$(auth "${!t}")"; done

P1=$(submit "$AUTHOR" "$C" p1); P2=$(submit "$AUTHOR" "$C" p2); P3=$(submit "$AUTHOR" "$C" p3); P4=$(submit "$AUTHOR" "$C" p4)

req DELETE "/r/$C/posts/$P1" ""; check "anonymous delete is rejected" 401 "$HTTP_STATUS"
req DELETE "/r/$C/posts/$P1" "" -H "$(auth "$OTHER")"; check "non-author member cannot delete" 403 "$HTTP_STATUS"
req DELETE "/r/$C/posts/$P1" "" -H "$(auth "$WEAKMOD")"; check "moderator without remove permission cannot delete" 403 "$HTTP_STATUS"
req DELETE "/r/$C2/posts/$P1" "" -H "$(auth "$AUTHOR")"; check "wrong community in URL is 404" 404 "$HTTP_STATUS"
req GET "/r/$C/comments/$P1" ""; check "post still visible after rejected attempts" 200 "$HTTP_STATUS"

req DELETE "/r/$C/posts/$P1" "" -H "$(auth "$AUTHOR")"; check "author deletes own post" 200 "$HTTP_STATUS"
req DELETE "/r/$C/posts/$P2" "" -H "$(auth "$MOD")"; check "moderator deletes someone else's post" 200 "$HTTP_STATUS"
req DELETE "/r/$C/posts/$P3" "" -H "$(auth "$OWNER")"; check "owner deletes someone else's post" 200 "$HTTP_STATUS"
req DELETE "/r/$C/posts/$P1" "" -H "$(auth "$AUTHOR")"; check "repeat delete is idempotent" 200 "$HTTP_STATUS"

for p in "$P1" "$P2" "$P3"; do req GET "/r/$C/comments/$p" ""; check "deleted post detail is 404" 404 "$HTTP_STATUS"; done
req GET "/r/$C/new" ""
check "/new lists only the surviving post" "$P4" "$(echo "$HTTP_BODY" | jq -r '[.data.children[].data.id] | join(",")')"

echo; echo "passed=$PASS_COUNT failed=$FAIL_COUNT"; [ "$FAIL_COUNT" = 0 ]
