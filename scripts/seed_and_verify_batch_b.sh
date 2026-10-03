#!/usr/bin/env bash
# Verifies batch B: comment search, ban expiry (validation, lazy expiry, sweep job), mod notes, mod-log
# filters, sticky/distinguished comments, crossposts, polls, drafts and scheduled posts. Takes ~100s because it
# waits for the two background jobs (ban expiry sweep every 60s, scheduled-post publisher every 30s).
# Needs the app on $BASE with RATE_LIMIT_REGISTER_CAPACITY raised.
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
  req POST /api/v1/register "{\"username\":\"bb${RUN}$1\",\"email\":\"bb${RUN}$1@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}
uid() { curl -s "$BASE/user/bb${RUN}$1/about" | jq -r .id; }
auth() { echo "Authorization: Bearer $1"; }
submit() {
  req POST "/r/$2/submit" "$3" -H "Authorization: Bearer $1" -H "Idempotency-Key: bb-$RUN-$4"
  echo "$HTTP_BODY" | jq -r .id
}
comment() { req POST /api/comment "$2" -H "Authorization: Bearer $1"; echo "$HTTP_BODY" | jq -r .id; }
iso_in() { python3 -c "import datetime,sys;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(seconds=int(sys.argv[1]))).strftime('%Y-%m-%dT%H:%M:%S.000Z'))" "$1"; }

OWNER=$(register owner); MOD=$(register mod); WEAK=$(register weak); AUTHOR=$(register author); OTHER=$(register other); OUT=$(register outsider)
MOD_ID=$(uid mod); WEAK_ID=$(uid weak); OTHER_ID=$(uid other); AUTHOR_ID=$(uid author); OWNER_ID=$(uid owner)
C="bbc${RUN}"; C2="bbd${RUN}"; PRIV="bbp${RUN}"
for n in $C $C2; do req POST /r "{\"name\":\"$n\",\"description\":\"d\"}" -H "$(auth "$OWNER")"; done
req POST /r "{\"name\":\"$PRIV\",\"description\":\"d\",\"type\":\"private\"}" -H "$(auth "$OWNER")"
req POST "/r/$C/mod/moderators" "{\"userId\":\"$MOD_ID\",\"permissions\":2147483647}" -H "$(auth "$OWNER")"
req POST "/r/$C/mod/moderators" "{\"userId\":\"$WEAK_ID\",\"permissions\":1}" -H "$(auth "$OWNER")"

echo "--- comment search"
P1=$(submit "$AUTHOR" "$C" '{"kind":"text","title":"search host","body":"b"}' s1)
P2=$(submit "$AUTHOR" "$C2" '{"kind":"text","title":"other community host","body":"b"}' s2)
PP=$(submit "$OWNER" "$PRIV" '{"kind":"text","title":"private host","body":"b"}' s3)
K1=$(comment "$OTHER" "{\"postId\":\"$P1\",\"body\":\"zebra${RUN}foo unique needle\"}")
K2=$(comment "$OTHER" "{\"postId\":\"$P2\",\"body\":\"zebra${RUN}foo elsewhere\"}")
comment "$OWNER" "{\"postId\":\"$PP\",\"body\":\"zebra${RUN}foo secret\"}" >/dev/null
req GET "/r/$C/search/comments?q=zebra${RUN}foo" ""; check "community-scoped search finds only that community's comment" "$K1" "$(echo "$HTTP_BODY" | jq -r '[.[].id] | join(",")')"
check "result carries post title and community" "search host|$C" "$(echo "$HTTP_BODY" | jq -r '.[0] | "\(.postTitle)|\(.communityName)"')"
req GET "/r/all/search/comments?q=zebra${RUN}foo" ""; check "sitewide search finds both public comments, not the private one" 2 "$(echo "$HTTP_BODY" | jq 'length')"
req GET "/r/all/search/comments?q=zebra${RUN}foo" "" -H "$(auth "$OWNER")"; check "private-community member sees the private hit too" 3 "$(echo "$HTTP_BODY" | jq 'length')"
req GET "/r/$PRIV/search/comments?q=zebra${RUN}foo" "" -H "$(auth "$OUT")"; check "searching a private community as outsider is 403" 403 "$HTTP_STATUS"
req GET "/r/$C/search/comments?q=" ""; check "blank query returns empty list" 0 "$(echo "$HTTP_BODY" | jq 'length')"
req POST "/r/$C/mod/remove/comment/$K1" '{"reason":"x"}' -H "$(auth "$OWNER")"
req GET "/r/$C/search/comments?q=zebra${RUN}foo" ""; check "removed comment no longer searchable" 0 "$(echo "$HTTP_BODY" | jq 'length')"
K3=$(comment "$OTHER" "{\"postId\":\"$P1\",\"body\":\"quokka${RUN}bar blocked word\"}")
req POST "/user/bb${RUN}other/block" "" -H "$(auth "$AUTHOR")"
req GET "/r/$C/search/comments?q=quokka${RUN}bar" "" -H "$(auth "$AUTHOR")"; check "blocker doesn't see blocked user's comments in search" 0 "$(echo "$HTTP_BODY" | jq 'length')"
req GET "/r/$C/search/comments?q=quokka${RUN}bar" ""; check "anonymous still sees it" 1 "$(echo "$HTTP_BODY" | jq 'length')"
req DELETE "/user/bb${RUN}other/block" "" -H "$(auth "$AUTHOR")"

echo "--- ban duration / expiry"
req POST "/r/$C/mod/ban" "{\"userId\":\"$OTHER_ID\",\"reason\":\"past\",\"expiresAt\":\"$(iso_in -60)\"}" -H "$(auth "$OWNER")"; check "ban expiring in the past is rejected" 400 "$HTTP_STATUS"
req POST "/r/$C/mod/ban" "{\"userId\":\"$OTHER_ID\",\"reason\":\"short\",\"expiresAt\":\"$(iso_in 4)\"}" -H "$(auth "$OWNER")"; check "temporary ban issued" 200 "$HTTP_STATUS"
req POST /api/comment "{\"postId\":\"$P1\",\"body\":\"banned try\"}" -H "$(auth "$OTHER")"; check "banned user cannot comment" 403 "$HTTP_STATUS"
req GET "/r/$C/mod/bans" "" -H "$(auth "$OWNER")"; check "active ban is listed" 1 "$(echo "$HTTP_BODY" | jq 'length')"
sleep 5
req POST /api/comment "{\"postId\":\"$P1\",\"body\":\"after expiry\"}" -H "$(auth "$OTHER")"; check "user can comment once the ban expired" 200 "$HTTP_STATUS"
req GET "/r/$C/mod/bans" "" -H "$(auth "$OWNER")"; check "expired ban is no longer listed" 0 "$(echo "$HTTP_BODY" | jq 'length')"

echo "--- mod notes"
req POST "/r/$C/mod/notes" "{\"userId\":\"$OTHER_ID\",\"note\":\"warned once for spam\"}" -H "$(auth "$MOD")"; check "moderator adds a note" 200 "$HTTP_STATUS"
NOTE=$(echo "$HTTP_BODY" | jq -r .id)
check "note records its author" "bb${RUN}mod" "$(echo "$HTTP_BODY" | jq -r .authorUsername)"
req GET "/r/$C/mod/notes?userId=$OTHER_ID" "" -H "$(auth "$WEAK")"; check "another moderator can read notes" 1 "$(echo "$HTTP_BODY" | jq 'length')"
req GET "/r/$C/mod/notes?userId=$OTHER_ID" "" -H "$(auth "$OTHER")"; check "the subject (non-mod) cannot read notes" 403 "$HTTP_STATUS"
req POST "/r/$C/mod/notes" "{\"userId\":\"$OTHER_ID\",\"note\":\"x\"}" -H "$(auth "$AUTHOR")"; check "non-moderator cannot add notes" 403 "$HTTP_STATUS"
req POST "/r/$C/mod/notes" "{\"userId\":\"$OTHER_ID\",\"note\":\"   \"}" -H "$(auth "$MOD")"; check "blank note rejected" 400 "$HTTP_STATUS"
req DELETE "/r/$C/mod/notes/$NOTE" "" -H "$(auth "$WEAK")"; check "non-author moderator without manage-mods bit cannot delete" 403 "$HTTP_STATUS"
req DELETE "/r/$C/mod/notes/$NOTE" "" -H "$(auth "$MOD")"; check "note author deletes it" 200 "$HTTP_STATUS"
req GET "/r/$C/mod/notes?userId=$OTHER_ID" "" -H "$(auth "$OWNER")"; check "note is gone" 0 "$(echo "$HTTP_BODY" | jq 'length')"

echo "--- mod log filters"
req GET "/r/$C/mod/actions" "" -H "$(auth "$OWNER")"; ALL=$(echo "$HTTP_BODY" | jq 'length'); check "unfiltered log has entries" true "$([ "$ALL" -ge 2 ] && echo true || echo false)"
req GET "/r/$C/mod/actions?action=ban" "" -H "$(auth "$OWNER")"; check "filter by action" "ban" "$(echo "$HTTP_BODY" | jq -r '[.[].action] | unique | join(",")')"
req GET "/r/$C/mod/actions?targetType=comment" "" -H "$(auth "$OWNER")"; check "filter by target type" "comment" "$(echo "$HTTP_BODY" | jq -r '[.[].targetType] | unique | join(",")')"
req GET "/r/$C/mod/actions?actorId=$OWNER_ID" "" -H "$(auth "$OWNER")"; check "filter by acting moderator (username attached)" "bb${RUN}owner" "$(echo "$HTTP_BODY" | jq -r '[.[].actorUsername] | unique | join(",")')"
req GET "/r/$C/mod/actions?targetId=$K1" "" -H "$(auth "$OWNER")"; check "filter by target id" 1 "$(echo "$HTTP_BODY" | jq 'length')"
FIRST_TS=$(curl -s "$BASE/r/$C/mod/actions" -H "$(auth "$OWNER")" | jq -r '.[0].createdAt')
req GET "/r/$C/mod/actions?before=$FIRST_TS" "" -H "$(auth "$OWNER")"; check "before= pages to older entries" $((ALL-1)) "$(echo "$HTTP_BODY" | jq 'length')"
req GET "/r/$C/mod/actions" "" -H "$(auth "$OTHER")"; check "non-moderator cannot read the log" 403 "$HTTP_STATUS"

echo "--- sticky / distinguished comments"
SP=$(submit "$AUTHOR" "$C" '{"kind":"text","title":"sticky host","body":"b"}' st1)
A1=$(comment "$OTHER" "{\"postId\":\"$SP\",\"body\":\"first\"}"); A2=$(comment "$OTHER" "{\"postId\":\"$SP\",\"body\":\"second\"}")
A3=$(comment "$OTHER" "{\"postId\":\"$SP\",\"body\":\"third\"}"); REPLY=$(comment "$OTHER" "{\"postId\":\"$SP\",\"parentId\":\"$A1\",\"body\":\"reply\"}")
req POST "/r/$C/mod/comments/$A3/sticky" "" -H "$(auth "$MOD")"; check "moderator stickies a comment" 200 "$HTTP_STATUS"
req GET "/r/$C/comments/$SP?sort=old" ""; check "sticky comment leads the thread even under sort=old" "$A3" "$(echo "$HTTP_BODY" | jq -r '.comments.data.children[0].data.id')"
check "sticky flag is exposed" true "$(echo "$HTTP_BODY" | jq -r '.comments.data.children[0].data.sticky')"
check "sticky comment is not duplicated" 3 "$(echo "$HTTP_BODY" | jq '.comments.data.children | length')"
req POST "/r/$C/mod/comments/$REPLY/sticky" "" -H "$(auth "$MOD")"; check "cannot sticky a reply" 400 "$HTTP_STATUS"
req POST "/r/$C/mod/comments/$A2/sticky" "" -H "$(auth "$MOD")"
req POST "/r/$C/mod/comments/$A1/sticky" "" -H "$(auth "$MOD")"; check "third sticky exceeds the cap of 2" 400 "$HTTP_STATUS"
req POST "/r/$C/mod/comments/$A1/sticky" "" -H "$(auth "$WEAK")"; check "moderator without manage-posts bit cannot sticky" 403 "$HTTP_STATUS"
req POST "/r/$C/mod/comments/$A1/sticky" "" -H "$(auth "$OTHER")"; check "regular user cannot sticky" 403 "$HTTP_STATUS"
req DELETE "/r/$C/mod/comments/$A3/sticky" "" -H "$(auth "$MOD")"; check "moderator unstickies" 200 "$HTTP_STATUS"
MC=$(comment "$MOD" "{\"postId\":\"$SP\",\"body\":\"mod voice\"}")
req POST "/r/$C/mod/comments/$MC/distinguish" "" -H "$(auth "$MOD")"; check "moderator distinguishes own comment" 200 "$HTTP_STATUS"
req POST "/r/$C/mod/comments/$A1/distinguish" "" -H "$(auth "$MOD")"; check "cannot distinguish someone else's comment" 403 "$HTTP_STATUS"
req POST "/r/$C/mod/comments/$MC/distinguish" "" -H "$(auth "$OTHER")"; check "non-moderator cannot distinguish" 403 "$HTTP_STATUS"
req GET "/r/$C/comments/$SP" ""; check "distinguished flag is exposed" moderator "$(echo "$HTTP_BODY" | jq -r --arg id "$MC" '.comments.data.children[] | select(.data.id==$id) | .data.distinguished')"

echo "--- crossposts"
OP=$(submit "$AUTHOR" "$C" '{"kind":"link","title":"original link","url":"https://example.com/x"}' x1)
req POST "/r/$C2/submit" "{\"kind\":\"crosspost\",\"title\":\"look at this\",\"crosspostOf\":\"$OP\"}" -H "$(auth "$OTHER")" -H "Idempotency-Key: bb-$RUN-x2"; check "user crossposts into another community" 200 "$HTTP_STATUS"
XP=$(echo "$HTTP_BODY" | jq -r .id)
check "crosspost embeds the original's title and community" "original link|$C" "$(echo "$HTTP_BODY" | jq -r '"\(.crosspostParent.title)|\(.crosspostParent.communityName)"')"
req POST "/r/$C2/submit" "{\"kind\":\"crosspost\",\"title\":\"chain\",\"crosspostOf\":\"$XP\"}" -H "$(auth "$OTHER")" -H "Idempotency-Key: bb-$RUN-x3"
check "crosspost of a crosspost resolves to the original" "$OP" "$(echo "$HTTP_BODY" | jq -r .crosspostOf)"
req GET "/r/$C2/new" ""; check "crosspost shows up in the feed with its parent" "original link" "$(echo "$HTTP_BODY" | jq -r --arg id "$XP" '.data.children[] | select(.data.id==$id) | .data.crosspostParent.title')"
req POST "/r/$C2/submit" "{\"kind\":\"crosspost\",\"title\":\"x\",\"crosspostOf\":\"$PP\"}" -H "$(auth "$OWNER")" -H "Idempotency-Key: bb-$RUN-x4"; check "cannot crosspost from a private community" 400 "$HTTP_STATUS"
req POST "/r/$C2/submit" "{\"kind\":\"crosspost\",\"title\":\"x\"}" -H "$(auth "$OTHER")" -H "Idempotency-Key: bb-$RUN-x5"; check "crosspost without crosspostOf is 400" 400 "$HTTP_STATUS"
req POST "/r/$C2/submit" "{\"kind\":\"text\",\"title\":\"x\",\"body\":\"b\",\"crosspostOf\":\"$OP\"}" -H "$(auth "$OTHER")" -H "Idempotency-Key: bb-$RUN-x6"; check "crosspostOf on a non-crosspost is 400" 400 "$HTTP_STATUS"
req DELETE "/r/$C/posts/$OP" "" -H "$(auth "$AUTHOR")"
req GET "/r/$C2/new" ""; check "deleted original shows as unavailable" false "$(echo "$HTTP_BODY" | jq -r --arg id "$XP" '.data.children[] | select(.data.id==$id) | .data.crosspostParent.available')"

echo "--- polls"
req POST "/r/$C/submit" '{"kind":"poll","title":"best pet","pollOptions":["cat","dog","fish"],"pollDays":2}' -H "$(auth "$AUTHOR")" -H "Idempotency-Key: bb-$RUN-p1"; check "create a poll" 200 "$HTTP_STATUS"
POLL=$(echo "$HTTP_BODY" | jq -r .id); OPT1=$(echo "$HTTP_BODY" | jq -r '.poll.options[0].id'); OPT2=$(echo "$HTTP_BODY" | jq -r '.poll.options[1].id')
check "poll has 3 options and 0 votes" "3|0" "$(echo "$HTTP_BODY" | jq -r '"\(.poll.options | length)|\(.poll.totalVotes)"')"
req POST "/r/$C/submit" '{"kind":"poll","title":"one option","pollOptions":["only"]}' -H "$(auth "$AUTHOR")" -H "Idempotency-Key: bb-$RUN-p2"; check "poll needs at least 2 options" 400 "$HTTP_STATUS"
req POST "/r/$C/submit" '{"kind":"poll","title":"too many","pollOptions":["1","2","3","4","5","6","7"]}' -H "$(auth "$AUTHOR")" -H "Idempotency-Key: bb-$RUN-p3"; check "poll allows at most 6 options" 400 "$HTTP_STATUS"
req POST "/r/$C/submit" '{"kind":"poll","title":"long","pollOptions":["a","b"],"pollDays":9}' -H "$(auth "$AUTHOR")" -H "Idempotency-Key: bb-$RUN-p4"; check "poll lasts at most 7 days" 400 "$HTTP_STATUS"
req POST "/r/$C/submit" '{"kind":"text","title":"t","body":"b","pollOptions":["a","b"]}' -H "$(auth "$AUTHOR")" -H "Idempotency-Key: bb-$RUN-p5"; check "pollOptions on a non-poll is 400" 400 "$HTTP_STATUS"
req POST "/r/$C/posts/$POLL/poll/vote" "{\"optionId\":\"$OPT1\"}" -H "$(auth "$OTHER")"; check "user votes in the poll" 200 "$HTTP_STATUS"
check "vote is counted and remembered" "1|$OPT1" "$(echo "$HTTP_BODY" | jq -r '"\(.totalVotes)|\(.myOptionId)"')"
req POST "/r/$C/posts/$POLL/poll/vote" "{\"optionId\":\"$OPT2\"}" -H "$(auth "$OTHER")"; check "cannot vote twice" 400 "$HTTP_STATUS"
req POST "/r/$C/posts/$POLL/poll/vote" "{\"optionId\":\"$OPT2\"}" -H "$(auth "$AUTHOR")"; check "a second user votes" 200 "$HTTP_STATUS"
req POST "/r/$C/posts/$POLL/poll/vote" "{\"optionId\":\"$(uuidgen | tr A-Z a-z)\"}" -H "$(auth "$OUT")"; check "option from nowhere rejected" 400 "$HTTP_STATUS"
req POST "/r/$C/posts/$POLL/poll/vote" "{\"optionId\":\"$OPT1\"}"; check "anonymous cannot vote" 401 "$HTTP_STATUS"
req GET "/r/$C/posts/$POLL/poll" "" -H "$(auth "$OTHER")"; check "viewer's own choice via GET" "$OPT1" "$(echo "$HTTP_BODY" | jq -r .myOptionId)"
req GET "/r/$C/posts/$POLL/poll" ""; check "anonymous sees totals with no personal choice" "2|null" "$(echo "$HTTP_BODY" | jq -r '"\(.totalVotes)|\(.myOptionId)"')"
req GET "/r/$C/new" ""; check "poll counts ride along in the feed" 2 "$(echo "$HTTP_BODY" | jq -r --arg id "$POLL" '.data.children[] | select(.data.id==$id) | .data.poll.totalVotes')"
req POST "/r/$C/posts/$P1/poll/vote" "{\"optionId\":\"$OPT1\"}" -H "$(auth "$OTHER")"; check "voting on a non-poll post is 404" 404 "$HTTP_STATUS"

echo "--- drafts"
req POST /api/drafts '{"communityName":"'$C'","payload":{"kind":"text","title":"wip","body":"half"}}' -H "$(auth "$AUTHOR")"; check "create a draft" 200 "$HTTP_STATUS"
D=$(echo "$HTTP_BODY" | jq -r .id)
req PUT "/api/drafts/$D" '{"communityName":"'$C'","payload":{"kind":"text","title":"wip 2","body":"more"}}' -H "$(auth "$AUTHOR")"; check "update the draft" "wip 2" "$(echo "$HTTP_BODY" | jq -r .payload.title)"
req GET /api/drafts "" -H "$(auth "$AUTHOR")"; check "list shows the draft" 1 "$(echo "$HTTP_BODY" | jq 'length')"
req GET /api/drafts "" -H "$(auth "$OTHER")"; check "drafts are private to their owner" 0 "$(echo "$HTTP_BODY" | jq 'length')"
req PUT "/api/drafts/$D" '{"payload":{"kind":"text"}}' -H "$(auth "$OTHER")"; check "cannot update someone else's draft" 404 "$HTTP_STATUS"
req DELETE "/api/drafts/$D" "" -H "$(auth "$OTHER")"; check "cannot delete someone else's draft" 404 "$HTTP_STATUS"
req POST /api/drafts '{"payload":"not an object"}' -H "$(auth "$AUTHOR")"; check "non-object payload rejected" 400 "$HTTP_STATUS"
req POST /api/drafts '{"payload":{"a":1}}'; check "anonymous cannot create drafts" 401 "$HTTP_STATUS"
req DELETE "/api/drafts/$D" "" -H "$(auth "$AUTHOR")"; check "owner deletes the draft" 200 "$HTTP_STATUS"

echo "--- scheduled posts"
req POST "/r/$C/schedule" "{\"post\":{\"kind\":\"text\",\"title\":\"too soon\",\"body\":\"b\"},\"publishAt\":\"$(iso_in 10)\"}" -H "$(auth "$AUTHOR")"; check "less than a minute ahead is rejected" 400 "$HTTP_STATUS"
req POST "/r/$C/schedule" "{\"post\":{\"kind\":\"text\",\"title\":\"too far\",\"body\":\"b\"},\"publishAt\":\"$(iso_in 3200000)\"}" -H "$(auth "$AUTHOR")"; check "more than 30 days ahead is rejected" 400 "$HTTP_STATUS"
req POST "/r/$C/schedule" "{\"post\":{\"kind\":\"text\",\"title\":\"\",\"body\":\"b\"},\"publishAt\":\"$(iso_in 120)\"}" -H "$(auth "$AUTHOR")"; check "invalid post body rejected at schedule time" 400 "$HTTP_STATUS"
req POST "/r/$C/schedule" "{\"post\":{\"kind\":\"text\",\"title\":\"later\",\"body\":\"b\"},\"publishAt\":\"$(iso_in 62)\"}" -H "$(auth "$AUTHOR")"; check "schedule a post ~1 minute out" 200 "$HTTP_STATUS"
SCH=$(echo "$HTTP_BODY" | jq -r .id)
check "status starts pending" pending "$(echo "$HTTP_BODY" | jq -r .status)"
req POST "/r/$C/schedule" "{\"post\":{\"kind\":\"text\",\"title\":\"cancel me\",\"body\":\"b\"},\"publishAt\":\"$(iso_in 300)\"}" -H "$(auth "$AUTHOR")"; SCH2=$(echo "$HTTP_BODY" | jq -r .id)
req DELETE "/api/scheduled/$SCH2" "" -H "$(auth "$OTHER")"; check "cannot cancel someone else's scheduled post" 404 "$HTTP_STATUS"
req DELETE "/api/scheduled/$SCH2" "" -H "$(auth "$AUTHOR")"; check "author cancels a scheduled post" 200 "$HTTP_STATUS"
req GET /api/scheduled "" -H "$(auth "$OTHER")"; check "scheduled list is private" 0 "$(echo "$HTTP_BODY" | jq 'length')"
req POST "/r/$C/mod/ban" "{\"userId\":\"$OTHER_ID\",\"reason\":\"sweep test\",\"expiresAt\":\"$(iso_in 3)\"}" -H "$(auth "$OWNER")"

echo "(waiting ~100s for the schedule publisher and ban-expiry sweep...)"
sleep 100
req GET /api/scheduled "" -H "$(auth "$AUTHOR")"
check "due post was published by the job" published "$(echo "$HTTP_BODY" | jq -r --arg id "$SCH" '.[] | select(.id==$id) | .status')"
PUBLISHED=$(echo "$HTTP_BODY" | jq -r --arg id "$SCH" '.[] | select(.id==$id) | .postId')
req GET "/r/$C/comments/$PUBLISHED" ""; check "published post exists with its title" later "$(echo "$HTTP_BODY" | jq -r .post.title)"
check "cancelled one stayed cancelled" cancelled "$(echo "$HTTP_BODY" >/dev/null; curl -s "$BASE/api/scheduled" -H "$(auth "$AUTHOR")" | jq -r --arg id "$SCH2" '.[] | select(.id==$id) | .status')"
req GET "/r/$C/mod/actions?action=unban_expired" "" -H "$(auth "$OWNER")"; check "sweep job logged the expired ban" "unban_expired" "$(echo "$HTTP_BODY" | jq -r '[.[].action] | unique | join(",")')"

echo; echo "passed=$PASS_COUNT failed=$FAIL_COUNT"; [ "$FAIL_COUNT" = 0 ]
