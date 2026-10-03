#!/usr/bin/env bash
# Verifies: community settings (PATCH /r/{n}/mod/settings), post title/link edit + edit history for posts
# and comments, and user blocking (reply/chat/feed effects, follow severing). Needs the app on $BASE with
# RATE_LIMIT_REGISTER_CAPACITY raised.
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
  req POST /api/v1/register "{\"username\":\"ba${RUN}$1\",\"email\":\"ba${RUN}$1@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}
auth() { echo "Authorization: Bearer $1"; }
submit() {
  req POST "/r/$2/submit" "$3" -H "Authorization: Bearer $1" -H "Idempotency-Key: ba-$RUN-$4"
  echo "$HTTP_BODY" | jq -r .id
}

OWNER=$(register owner); AUTHOR=$(register author); OTHER=$(register other); WEAK=$(register weak)
WEAK_ID=$(curl -s "$BASE/user/ba${RUN}weak/about" | jq -r .id)
C="bac${RUN}"
req POST /r "{\"name\":\"$C\",\"description\":\"d\"}" -H "$(auth "$OWNER")"
req POST "/r/$C/mod/moderators" "{\"userId\":\"$WEAK_ID\",\"permissions\":1}" -H "$(auth "$OWNER")"

echo "--- community settings"
req PATCH "/r/$C/mod/settings" '{"description":"new desc"}' -H "$(auth "$OWNER")"; check "owner updates description" 200 "$HTTP_STATUS"
req GET "/r/$C/about" ""; check "description persisted" "new desc" "$(echo "$HTTP_BODY" | jq -r .description)"
req PATCH "/r/$C/mod/settings" '{"description":"hack"}' -H "$(auth "$OTHER")"; check "non-moderator cannot update settings" 403 "$HTTP_STATUS"
req PATCH "/r/$C/mod/settings" '{"description":"hack"}' -H "$(auth "$WEAK")"; check "moderator without manage-rules bit cannot" 403 "$HTTP_STATUS"
req PATCH "/r/$C/mod/settings" "{\"iconMediaId\":\"00000000-0000-0000-0000-000000000001\"}" -H "$(auth "$OWNER")"; check "unknown icon media id is 404" 404 "$HTTP_STATUS"
req PATCH "/r/$C/mod/settings" "{\"description\":\"$(printf 'x%.0s' $(seq 1 501))\"}" -H "$(auth "$OWNER")"; check "overlong description rejected" 400 "$HTTP_STATUS"
req PATCH "/r/$C/mod/settings" '{"description":"d2"}'; check "anonymous cannot update settings" 401 "$HTTP_STATUS"

echo "--- post edit + history"
TP=$(submit "$AUTHOR" "$C" '{"kind":"text","title":"orig title","body":"orig body"}' t1)
LP=$(submit "$AUTHOR" "$C" '{"kind":"link","title":"link title","url":"https://example.com/a"}' l1)
req PATCH "/r/$C/posts/$TP" '{"title":"new title"}' -H "$(auth "$AUTHOR")"; check "author edits title within window" 200 "$HTTP_STATUS"
check "title changed" "new title" "$(echo "$HTTP_BODY" | jq -r .title)"
req PATCH "/r/$C/posts/$TP" '{"body":"new body"}' -H "$(auth "$AUTHOR")"; check "author edits body" 200 "$HTTP_STATUS"
req PATCH "/r/$C/posts/$LP" '{"url":"https://example.com/b"}' -H "$(auth "$AUTHOR")"; check "author edits link url" 200 "$HTTP_STATUS"
req PATCH "/r/$C/posts/$LP" '{"url":"javascript:alert(1)"}' -H "$(auth "$AUTHOR")"; check "non-http url rejected" 400 "$HTTP_STATUS"
req PATCH "/r/$C/posts/$TP" '{"url":"https://example.com"}' -H "$(auth "$AUTHOR")"; check "url on a text post rejected" 400 "$HTTP_STATUS"
req PATCH "/r/$C/posts/$LP" '{"body":"x"}' -H "$(auth "$AUTHOR")"; check "body on a link post rejected" 400 "$HTTP_STATUS"
req PATCH "/r/$C/posts/$TP" '{}' -H "$(auth "$AUTHOR")"; check "empty edit rejected" 400 "$HTTP_STATUS"
req PATCH "/r/$C/posts/$TP" '{"title":"   "}' -H "$(auth "$AUTHOR")"; check "blank title rejected" 400 "$HTTP_STATUS"
req PATCH "/r/$C/posts/$TP" '{"title":"hijack"}' -H "$(auth "$OTHER")"; check "non-author cannot edit" 403 "$HTTP_STATUS"
req GET "/r/$C/posts/$TP/history" "" -H "$(auth "$AUTHOR")"; check "author reads history" 200 "$HTTP_STATUS"
check "history has 2 revisions, newest first" "orig body" "$(echo "$HTTP_BODY" | jq -r '.[0].body')"
check "oldest revision keeps original title" "orig title" "$(echo "$HTTP_BODY" | jq -r '.[1].title')"
req GET "/r/$C/posts/$TP/history" "" -H "$(auth "$WEAK")"; check "moderator reads history" 200 "$HTTP_STATUS"
req GET "/r/$C/posts/$TP/history" "" -H "$(auth "$OTHER")"; check "other user cannot read history" 403 "$HTTP_STATUS"
req GET "/r/$C/posts/$TP/history" ""; check "anonymous cannot read history" 401 "$HTTP_STATUS"

echo "--- comment history"
req POST /api/comment "{\"postId\":\"$TP\",\"body\":\"first\"}" -H "$(auth "$OTHER")"; CM=$(echo "$HTTP_BODY" | jq -r .id)
req PATCH "/api/comment/$CM" '{"body":"second"}' -H "$(auth "$OTHER")"; check "author edits comment" 200 "$HTTP_STATUS"
req GET "/api/comment/$CM/history" "" -H "$(auth "$OTHER")"; check "comment author reads history" "first" "$(echo "$HTTP_BODY" | jq -r '.[0].body')"
req GET "/api/comment/$CM/history" "" -H "$(auth "$WEAK")"; check "moderator reads comment history" 200 "$HTTP_STATUS"
req GET "/api/comment/$CM/history" "" -H "$(auth "$AUTHOR")"; check "unrelated user cannot read comment history" 403 "$HTTP_STATUS"

echo "--- blocking"
req POST "/user/ba${RUN}author/follow" "" -H "$(auth "$OTHER")"
req POST "/user/ba${RUN}other/follow" "" -H "$(auth "$AUTHOR")"
req POST "/user/ba${RUN}author/block" "" -H "$(auth "$AUTHOR")"; check "cannot block yourself" 400 "$HTTP_STATUS"
req POST "/user/ba${RUN}other/block" "" -H "$(auth "$AUTHOR")"; check "author blocks other" 200 "$HTTP_STATUS"
req POST "/user/ba${RUN}other/block" "" -H "$(auth "$AUTHOR")"; check "repeat block is idempotent (changed=false)" false "$(echo "$HTTP_BODY" | jq -r .changed)"
req GET "/user/ba${RUN}other/block" "" -H "$(auth "$AUTHOR")"; check "block status true" true "$(echo "$HTTP_BODY" | jq -r .isBlocked)"
req GET "/api/blocked" "" -H "$(auth "$AUTHOR")"; check "blocked list has other" "ba${RUN}other" "$(echo "$HTTP_BODY" | jq -r '.[0].username')"
req GET "/user/ba${RUN}author/follow" "" -H "$(auth "$OTHER")"; check "other's follow of author was severed" false "$(echo "$HTTP_BODY" | jq -r .isFollowing)"
req GET "/user/ba${RUN}other/follow" "" -H "$(auth "$AUTHOR")"; check "author's follow of other was severed" false "$(echo "$HTTP_BODY" | jq -r .isFollowing)"
req POST /api/comment "{\"postId\":\"$TP\",\"body\":\"blocked reply\"}" -H "$(auth "$OTHER")"; check "blocked user cannot comment on blocker's post" 403 "$HTTP_STATUS"
req POST /api/comment "{\"postId\":\"$TP\",\"body\":\"ok\"}" -H "$(auth "$WEAK")"; AC=$(echo "$HTTP_BODY" | jq -r .id)
req POST /api/comment "{\"postId\":\"$TP\",\"body\":\"ok\"}" -H "$(auth "$AUTHOR")"; AUTH_C=$(echo "$HTTP_BODY" | jq -r .id)
req POST /api/comment "{\"postId\":\"$TP\",\"parentId\":\"$AUTH_C\",\"body\":\"nope\"}" -H "$(auth "$OTHER")"; check "blocked user cannot reply to blocker's comment" 403 "$HTTP_STATUS"
WP=$(submit "$WEAK" "$C" '{"kind":"text","title":"weak post","body":"b"}' w1)
req POST /api/comment "{\"postId\":\"$WP\",\"body\":\"hi\"}" -H "$(auth "$WEAK")"; WC=$(echo "$HTTP_BODY" | jq -r .id)
req POST /api/comment "{\"postId\":\"$WP\",\"parentId\":\"$WC\",\"body\":\"fine\"}" -H "$(auth "$OTHER")"; check "blocked user can still reply elsewhere" 200 "$HTTP_STATUS"
req POST /api/chat/rooms "{\"participantUsernames\":[\"ba${RUN}author\"]}" -H "$(auth "$OTHER")"; check "blocked user cannot open a chat with blocker" 403 "$HTTP_STATUS"
OP=$(submit "$OTHER" "$C" '{"kind":"text","title":"other post","body":"b"}' o1)
req GET "/r/$C/new" "" -H "$(auth "$AUTHOR")"
check "blocker's feed hides blocked user's posts" "false" "$(echo "$HTTP_BODY" | jq -r --arg id "$OP" '[.data.children[].data.id] | index($id) != null')"
req GET "/r/$C/new" "" -H "$(auth "$WEAK")"
check "other viewers still see the post" "true" "$(echo "$HTTP_BODY" | jq -r --arg id "$OP" '[.data.children[].data.id] | index($id) != null')"
req DELETE "/user/ba${RUN}other/block" "" -H "$(auth "$AUTHOR")"; check "author unblocks" 200 "$HTTP_STATUS"
req POST /api/comment "{\"postId\":\"$TP\",\"body\":\"after unblock\"}" -H "$(auth "$OTHER")"; check "unblocked user can comment again" 200 "$HTTP_STATUS"
req POST "/user/nobody${RUN}/block" "" -H "$(auth "$AUTHOR")"; check "blocking unknown user is 404" 404 "$HTTP_STATUS"
req POST "/user/ba${RUN}other/block" ""; check "anonymous cannot block" 401 "$HTTP_STATUS"

echo; echo "passed=$PASS_COUNT failed=$FAIL_COUNT"; [ "$FAIL_COUNT" = 0 ]
