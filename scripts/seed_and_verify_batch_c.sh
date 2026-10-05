#!/usr/bin/env bash
# Verifies the Reddit-style card backend: repost (crosspost) counts on posts, the community icon field, and the
# short-share-link resolver GET /api/p/{postId}. Needs the app on $BASE with RATE_LIMIT_REGISTER_CAPACITY raised.
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
  req POST /api/v1/register "{\"username\":\"bc${RUN}$1\",\"email\":\"bc${RUN}$1@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}
auth() { echo "Authorization: Bearer $1"; }
submit() {
  req POST "/r/$2/submit" "$3" -H "Authorization: Bearer $1" -H "Idempotency-Key: bc-$RUN-$4"
  echo "$HTTP_BODY" | jq -r .id
}

OWNER=$(register owner); AUTHOR=$(register author); OTHER=$(register other); OUT=$(register out)
C="bcc${RUN}"; C2="bcd${RUN}"; PRIV="bcp${RUN}"
for n in $C $C2; do req POST /r "{\"name\":\"$n\",\"description\":\"d\"}" -H "$(auth "$OWNER")"; done
req POST /r "{\"name\":\"$PRIV\",\"description\":\"d\",\"type\":\"private\"}" -H "$(auth "$OWNER")"

echo "--- repost counts"
P=$(submit "$AUTHOR" "$C" '{"kind":"text","title":"original","body":"b"}' o1)
req GET "/r/$C/new" ""; check "fresh post has 0 reposts" 0 "$(echo "$HTTP_BODY" | jq -r --arg id "$P" '.data.children[] | select(.data.id==$id) | .data.crosspostCount')"
X1=$(submit "$OTHER" "$C2" "{\"kind\":\"crosspost\",\"title\":\"one\",\"crosspostOf\":\"$P\"}" x1)
X2=$(submit "$OWNER" "$C2" "{\"kind\":\"crosspost\",\"title\":\"two\",\"crosspostOf\":\"$P\"}" x2)
req GET "/r/$C/new" ""; check "two crossposts -> repost count 2" 2 "$(echo "$HTTP_BODY" | jq -r --arg id "$P" '.data.children[] | select(.data.id==$id) | .data.crosspostCount')"
req GET "/r/$C/comments/$P" ""; check "thread payload carries the count too" 2 "$(echo "$HTTP_BODY" | jq -r .post.crosspostCount)"
XX=$(submit "$AUTHOR" "$C2" "{\"kind\":\"crosspost\",\"title\":\"of a crosspost\",\"crosspostOf\":\"$X1\"}" x3)
req GET "/r/$C/new" ""; check "a crosspost of a crosspost counts against the original" 3 "$(echo "$HTTP_BODY" | jq -r --arg id "$P" '.data.children[] | select(.data.id==$id) | .data.crosspostCount')"
req GET "/r/$C2/new" ""; check "a crosspost itself shows 0 reposts" 0 "$(echo "$HTTP_BODY" | jq -r --arg id "$X1" '.data.children[] | select(.data.id==$id) | .data.crosspostCount')"
req DELETE "/r/$C2/posts/$X2" "" -H "$(auth "$OWNER")"
req GET "/r/$C/new" ""; check "a deleted crosspost no longer counts" 2 "$(echo "$HTTP_BODY" | jq -r --arg id "$P" '.data.children[] | select(.data.id==$id) | .data.crosspostCount')"
check "community icon field is null when none is set" null "$(echo "$HTTP_BODY" | jq -r '.data.children[0].data.communityIconUrl')"

echo "--- share-link resolver"
req GET "/api/p/$P" ""; check "short link resolves to the community" "$C" "$(echo "$HTTP_BODY" | jq -r .communityName)"
req GET "/api/p/00000000-0000-7000-0000-000000000099" ""; check "unknown id is 404" 404 "$HTTP_STATUS"
req GET "/api/p/not-a-uuid" ""; check "malformed id is 400" 400 "$HTTP_STATUS"
PP=$(submit "$OWNER" "$PRIV" '{"kind":"text","title":"secret","body":"b"}' pr1)
req GET "/api/p/$PP" ""; check "private community post is 404 to anonymous" 404 "$HTTP_STATUS"
req GET "/api/p/$PP" "" -H "$(auth "$OUT")"; check "private community post is 404 to a non-member" 404 "$HTTP_STATUS"
req GET "/api/p/$PP" "" -H "$(auth "$OWNER")"; check "private community post resolves for a member" "$PRIV" "$(echo "$HTTP_BODY" | jq -r .communityName)"
req POST "/r/$C/mod/remove/post/$P" '{"reason":"x"}' -H "$(auth "$OWNER")"
req GET "/api/p/$P" ""; check "removed post is 404" 404 "$HTTP_STATUS"
D=$(submit "$AUTHOR" "$C" '{"kind":"text","title":"to delete","body":"b"}' d1)
req DELETE "/r/$C/posts/$D" "" -H "$(auth "$AUTHOR")"
req GET "/api/p/$D" ""; check "author-deleted post still resolves (tombstone page)" "$C" "$(echo "$HTTP_BODY" | jq -r .communityName)"

echo "--- search: partial words, phrases, odd input"
W="mangosteen${RUN}"
SP=$(submit "$AUTHOR" "$C" "{\"kind\":\"text\",\"title\":\"$W fruit review\",\"body\":\"tasty purple stuff\"}" sr1)
SC=$(curl -s -X POST "$BASE/api/comment" -H 'Content-Type: application/json' -H "$(auth "$OTHER")" -d "{\"postId\":\"$SP\",\"body\":\"pomegranate$RUN is better\"}" | jq -r .id)
n_posts() { curl -s "$BASE/r/all/search?q=$1" | jq --arg id "$SP" '[.data.children[].data.id] | index($id) != null'; }
n_comments() { curl -s "$BASE/r/all/search/comments?q=$1" | jq --arg id "$SC" '[.[].id] | index($id) != null'; }
check "full word finds the post" true "$(n_posts "$W")"
check "a partial word finds the post (prefix)" true "$(n_posts "mangost")"
check "a short partial word finds the post" true "$(n_posts "${W:0:5}")"
check "full word + partial word (all terms must match)" true "$(n_posts "$W%20tas")"
check "a partial word that matches nothing finds nothing" false "$(n_posts "zzqx")"
check "partial + unrelated word does not match" false "$(n_posts "mangost%20zzqx")"
check "quoted phrase still works" true "$(n_posts "%22fruit%20review%22")"
check "stemmed full word still works (reviews -> review)" true "$(n_posts "$W%20reviews")"
check "partial word finds the comment" true "$(n_comments "pomegra")"
check "community-scoped search also does prefix" true "$(curl -s "$BASE/r/$C/search?q=mangost" | jq --arg id "$SP" '[.data.children[].data.id] | index($id) != null')"
for odd in "%27" "%22unterminated" "c%2B%2B" "%25" "%5C" "r%2F" "%26%26%7C%7C" "%28%29"; do
  req GET "/r/all/search?q=$odd" ""; check "odd input '$odd' doesn't error (posts)" 200 "$HTTP_STATUS"
  req GET "/r/all/search/comments?q=$odd" ""; check "odd input '$odd' doesn't error (comments)" 200 "$HTTP_STATUS"
done

echo; echo "passed=$PASS_COUNT failed=$FAIL_COUNT"; [ "$FAIL_COUNT" = 0 ]
