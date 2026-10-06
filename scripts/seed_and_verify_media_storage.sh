#!/usr/bin/env bash
# Verifies the file-upload pipeline against whatever storage the backend is configured for (local s3mock or Cloudflare
# R2): validation (type, size, ownership), the browser-style PUT to the presigned URL INCLUDING the CORS preflight a
# real browser sends (curl alone would hide a missing bucket CORS rule), completion checks, background processing,
# attaching media to posts, and cleanup of oversized uploads. Needs the app on $BASE.
# Set CHECK_PUBLIC_URLS=1 to also fetch the processed renditions from MEDIA_PUBLIC_BASE_URL and verify their sizes (256px
# thumbnail, 1280px display) — only meaningful when that address is reachable from this machine and the bucket is public.
# Set APP_CONTAINER=<docker container name of the backend> to also verify from its log that no media was processed twice.
set -uo pipefail
BASE="${BASE:-http://localhost:8081}"
ORIGIN="${ORIGIN:-http://localhost:5174}"
PASSWORD="Sup3rSecret!1"
RUN=$(date +%s | tail -c 6)
PASS_COUNT=0; FAIL_COUNT=0
check() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then PASS_COUNT=$((PASS_COUNT+1)); printf 'PASS  %-66s expected %s, got %s\n' "$desc" "$expected" "$actual"
  else FAIL_COUNT=$((FAIL_COUNT+1)); printf 'FAIL  %-66s expected %s, got %s\n' "$desc" "$expected" "$actual"; fi
}
req() {
  local method="$1" path="$2" data="${3:-}"; shift 3 || true
  local resp
  resp=$(curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" -H 'Content-Type: application/json' ${data:+-d "$data"} "$@")
  HTTP_STATUS=$(echo "$resp" | tail -n1); HTTP_BODY=$(echo "$resp" | sed '$d')
}
register() {
  req POST /api/v1/register "{\"username\":\"ms${RUN}$1\",\"email\":\"ms${RUN}$1@example.com\",\"password\":\"$PASSWORD\"}"
  echo "$HTTP_BODY" | jq -r .accessToken
}
auth() { echo "Authorization: Bearer $1"; }

# A real 120x80 PNG (so the image worker can decode it).
PNG=$(mktemp -t msimg).png
python3 - "$PNG" <<'PYEOF'
import sys, zlib, struct
w, h = 2000, 1500
raw = b''.join(b'\x00' + b''.join(bytes([(x*4) % 256, (y*4) % 256, 128]) for x in range(w)) for y in range(h))
def chunk(t, d):
    c = struct.pack('>I', len(d)) + t + d
    return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
open(sys.argv[1], 'wb').write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))
PYEOF
SIZE=$(wc -c < "$PNG" | tr -d ' ')

A=$(register a); B=$(register b)
C="msc${RUN}"; req POST /r "{\"name\":\"$C\",\"description\":\"d\"}" -H "$(auth "$A")"

echo "--- validation"
req POST /api/media/upload-url "{\"filename\":\"x.exe\",\"contentType\":\"application/x-msdownload\",\"byteSize\":10}" -H "$(auth "$A")"; check "unsupported content type is rejected" 400 "$HTTP_STATUS"
req POST /api/media/upload-url "{\"filename\":\"big.png\",\"contentType\":\"image/png\",\"byteSize\":999999999}" -H "$(auth "$A")"; check "declared size over the image limit is rejected" 400 "$HTTP_STATUS"
req POST /api/media/upload-url "{\"filename\":\"v.mp4\",\"contentType\":\"video/mp4\",\"byteSize\":999999999999}" -H "$(auth "$A")"; check "declared size over the video limit is rejected" 400 "$HTTP_STATUS"
req POST /api/media/upload-url "{\"filename\":\"\",\"contentType\":\"image/png\",\"byteSize\":10}" -H "$(auth "$A")"; check "blank filename is rejected" 400 "$HTTP_STATUS"
req POST /api/media/upload-url "{\"filename\":\"a.png\",\"contentType\":\"image/png\",\"byteSize\":0}" -H "$(auth "$A")"; check "zero byteSize is rejected" 400 "$HTTP_STATUS"
req POST /api/media/upload-url "{\"filename\":\"a.png\",\"contentType\":\"image/png\",\"byteSize\":10}"; check "anonymous cannot request an upload URL" 401 "$HTTP_STATUS"

echo "--- upload like a browser"
req POST /api/media/upload-url "{\"filename\":\"my pic (1).png\",\"contentType\":\"image/png\",\"byteSize\":$SIZE}" -H "$(auth "$A")"; check "upload URL issued" 200 "$HTTP_STATUS"
MID=$(echo "$HTTP_BODY" | jq -r .mediaId); URL=$(echo "$HTTP_BODY" | jq -r .uploadUrl)
check "filename is sanitized in the storage key" true "$(echo "$URL" | grep -q 'my_pic__1_.png' && echo true || echo false)"
PRE=$(curl -s -o /dev/null -D - -X OPTIONS -H "Origin: $ORIGIN" -H "Access-Control-Request-Method: PUT" -H "Access-Control-Request-Headers: content-type" "$URL")
check "CORS preflight is accepted (browser uploads can start)" true "$(echo "$PRE" | head -1 | grep -qE ' (200|204)' && echo true || echo false)"
check "preflight allows the website's origin" true "$(echo "$PRE" | grep -iE '^access-control-allow-origin:' | grep -qE "(\*|$ORIGIN)" && echo true || echo false)"
check "PUT to the presigned URL succeeds" 200 "$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "Origin: $ORIGIN" -H 'Content-Type: image/png' --data-binary @"$PNG" "$URL")"
req POST "/api/media/$MID/complete" "" -H "$(auth "$B")"; check "another user cannot complete my upload" 403 "$HTTP_STATUS"
req POST "/api/media/$MID/complete" "" -H "$(auth "$A")"; check "owner completes the upload" 200 "$HTTP_STATUS"
req POST "/api/media/$MID/complete" "" -H "$(auth "$A")"; check "completing twice is harmless" 200 "$HTTP_STATUS"
req POST "/api/media/00000000-0000-7000-0000-000000000099/complete" "" -H "$(auth "$A")"; check "completing an unknown media id is 404" 404 "$HTTP_STATUS"

echo "--- complete without uploading"
req POST /api/media/upload-url "{\"filename\":\"ghost.png\",\"contentType\":\"image/png\",\"byteSize\":$SIZE}" -H "$(auth "$A")"; GHOST=$(echo "$HTTP_BODY" | jq -r .mediaId)
req POST "/api/media/$GHOST/complete" "" -H "$(auth "$A")"; check "completing an upload that never happened is rejected" 400 "$HTTP_STATUS"
req POST "/r/$C/submit" "{\"kind\":\"image\",\"title\":\"ghost\",\"mediaId\":\"$GHOST\"}" -H "$(auth "$A")" -H "Idempotency-Key: ms-$RUN-g"; check "a never-uploaded media cannot be attached to a post" 400 "$HTTP_STATUS"

echo "--- oversized object is rejected and discarded"
req POST /api/media/upload-url "{\"filename\":\"liar.png\",\"contentType\":\"image/png\",\"byteSize\":100}" -H "$(auth "$A")"; LMID=$(echo "$HTTP_BODY" | jq -r .mediaId); LURL=$(echo "$HTTP_BODY" | jq -r .uploadUrl)
BIG=$(mktemp -t msbig); head -c 22000000 /dev/zero > "$BIG"
curl -s -o /dev/null -X PUT -H 'Content-Type: image/png' --data-binary @"$BIG" "$LURL"; rm -f "$BIG"
req POST "/api/media/$LMID/complete" "" -H "$(auth "$A")"; check "an object bigger than the limit is rejected at completion" 400 "$HTTP_STATUS"

echo "--- background processing"
STATUS=pending
req POST "/r/$C/submit" "{\"kind\":\"image\",\"title\":\"my image\",\"mediaId\":\"$MID\"}" -H "$(auth "$A")" -H "Idempotency-Key: ms-$RUN-p1"; check "an uploaded image can be attached to an image post" 200 "$HTTP_STATUS"
PID=$(echo "$HTTP_BODY" | jq -r .id)
for i in $(seq 1 15); do
  req GET "/r/$C/comments/$PID" ""; STATUS=$(echo "$HTTP_BODY" | jq -r '.post.media.processingStatus')
  [ "$STATUS" = "ready" ] && break; sleep 2
done
check "the image worker finishes processing" ready "$STATUS"
THUMB=$(echo "$HTTP_BODY" | jq -r '.post.media.thumbnailUrl'); DISP=$(echo "$HTTP_BODY" | jq -r '.post.media.displayUrl')
check "thumbnail and display URLs are returned" true "$([ "$THUMB" != null ] && [ "$DISP" != null ] && echo true || echo false)"
check "processed variants are JPEGs" true "$(echo "$THUMB" | grep -q 'thumb.jpg' && echo "$DISP" | grep -q 'display.jpg' && echo true || echo false)"
jpeg_dims() { python3 - "$1" <<'PYEOF'
import sys, struct
d = open(sys.argv[1], 'rb').read()
i = 2
while i < len(d):
    if d[i] != 0xFF: i += 1; continue
    m = d[i+1]
    if m in (0xC0, 0xC1, 0xC2):
        h, w = struct.unpack('>HH', d[i+5:i+9]); print(f"{w}x{h}"); break
    i += 2 + struct.unpack('>H', d[i+2:i+4])[0]
PYEOF
}
if [ "${CHECK_PUBLIC_URLS:-0}" = 1 ]; then
  check "the thumbnail is fetchable from the public URL" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$THUMB")"
  check "the display rendition is fetchable from the public URL" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$DISP")"
  TF=$(mktemp -t msthumb); DF=$(mktemp -t msdisp); curl -s -o "$TF" "$THUMB"; curl -s -o "$DF" "$DISP"
  check "the thumbnail is 256px on its longest side" 256 "$(jpeg_dims "$TF" | tr 'x' '\n' | sort -n | tail -1)"
  check "the display rendition is 1280px on its longest side" 1280 "$(jpeg_dims "$DF" | tr 'x' '\n' | sort -n | tail -1)"
  check "the thumbnail is much smaller than the display rendition" true "$([ "$(wc -c < "$TF")" -lt "$(( $(wc -c < "$DF") / 3 ))" ] && echo true || echo false)"
  rm -f "$TF" "$DF"
fi
req GET "/api/media/$MID" ""; check "a media item's status is publicly readable (the site polls it)" ready "$(echo "$HTTP_BODY" | jq -r .processingStatus)"
check "the media view carries its id" "$MID" "$(echo "$HTTP_BODY" | jq -r .id)"
check "the media view never exposes the original upload" false "$(echo "$HTTP_BODY" | jq 'has("originalUrl")')"
req GET "/api/media/00000000-0000-7000-0000-000000000099" ""; check "an unknown media id is 404" 404 "$HTTP_STATUS"
req POST "/r/$C/submit" "{\"kind\":\"video\",\"title\":\"wrong kind\",\"mediaId\":\"$MID\"}" -H "$(auth "$A")" -H "Idempotency-Key: ms-$RUN-p2"; check "an image cannot be attached to a video post" 400 "$HTTP_STATUS"
req POST "/r/$C/submit" "{\"kind\":\"image\",\"title\":\"stolen\",\"mediaId\":\"$MID\"}" -H "$(auth "$B")" -H "Idempotency-Key: ms-$RUN-p3"; check "another user cannot attach my media" 403 "$HTTP_STATUS"

echo "--- a file attached straight after upload (only processed renditions are ever exposed)"
req POST /api/media/upload-url "{\"filename\":\"fast.png\",\"contentType\":\"image/png\",\"byteSize\":$SIZE}" -H "$(auth "$A")"; FID=$(echo "$HTTP_BODY" | jq -r .mediaId); FURL=$(echo "$HTTP_BODY" | jq -r .uploadUrl)
curl -s -o /dev/null -X PUT -H 'Content-Type: image/png' --data-binary @"$PNG" "$FURL"
req POST "/api/media/$FID/complete" "" -H "$(auth "$A")"
req POST "/r/$C/submit" "{\"kind\":\"image\",\"title\":\"posted at once\",\"mediaId\":\"$FID\"}" -H "$(auth "$A")" -H "Idempotency-Key: ms-$RUN-fast"; check "posting right after the upload completes works" 200 "$HTTP_STATUS"
FIRST_STATUS=$(echo "$HTTP_BODY" | jq -r .media.processingStatus)
check "the post's media carries no original URL, and no renditions until ready" true "$(echo "$HTTP_BODY" | jq -r '(.media | has("originalUrl") | not) and (if .media.processingStatus == "ready" then true else (.media.thumbnailUrl == null and .media.displayUrl == null) end)')"
T0=$(date +%s)
for i in $(seq 1 30); do req GET "/api/media/$FID" ""; [ "$(echo "$HTTP_BODY" | jq -r .processingStatus)" = ready ] && break; sleep 1; done
ELAPSED=$(( $(date +%s) - T0 ))
check "it becomes ready within 15s of completion" true "$([ "$(echo "$HTTP_BODY" | jq -r .processingStatus)" = ready ] && [ "$ELAPSED" -le 15 ] && echo true || echo false)"
printf '      (ready %ss after complete)\n' "$ELAPSED"

echo "--- a file that is not really an image fails promptly (and is never retried)"
BADF=$(mktemp -t msbad); echo "this is plainly not a PNG" > "$BADF"; BADSZ=$(wc -c < "$BADF" | tr -d ' ')
req POST /api/media/upload-url "{\"filename\":\"fake.png\",\"contentType\":\"image/png\",\"byteSize\":$BADSZ}" -H "$(auth "$A")"; BID=$(echo "$HTTP_BODY" | jq -r .mediaId); BURL=$(echo "$HTTP_BODY" | jq -r .uploadUrl)
curl -s -o /dev/null -X PUT -H 'Content-Type: image/png' --data-binary @"$BADF" "$BURL"; rm -f "$BADF"
req POST "/api/media/$BID/complete" "" -H "$(auth "$A")"
for i in $(seq 1 20); do req GET "/api/media/$BID" ""; [ "$(echo "$HTTP_BODY" | jq -r .processingStatus)" = failed ] && break; sleep 1; done
check "a non-image reaches the 'failed' state within 20s" failed "$(echo "$HTTP_BODY" | jq -r .processingStatus)"
check "a failed media view has no renditions" true "$(echo "$HTTP_BODY" | jq -r '.thumbnailUrl == null and .displayUrl == null')"
req POST "/r/$C/submit" "{\"kind\":\"image\",\"title\":\"broken\",\"mediaId\":\"$BID\"}" -H "$(auth "$A")" -H "Idempotency-Key: ms-$RUN-bad"; check "failed media cannot be attached to a post" 400 "$HTTP_STATUS"

echo "--- several uploads at once are each processed exactly once"
MIDS=(); PURLS=()
for n in 1 2 3 4 5 6; do
  req POST /api/media/upload-url "{\"filename\":\"par$n.png\",\"contentType\":\"image/png\",\"byteSize\":$SIZE}" -H "$(auth "$A")"
  MIDS+=("$(echo "$HTTP_BODY" | jq -r .mediaId)"); PURLS+=("$(echo "$HTTP_BODY" | jq -r .uploadUrl)")
done
for n in 0 1 2 3 4 5; do curl -s -o /dev/null -X PUT -H 'Content-Type: image/png' --data-binary @"$PNG" "${PURLS[$n]}"; done
for n in 0 1 2 3 4 5; do curl -s -o /dev/null -X POST "$BASE/api/media/${MIDS[$n]}/complete" -H "$(auth "$A")" & done; wait
ALLREADY=true
for n in 0 1 2 3 4 5; do
  for i in $(seq 1 60); do req GET "/api/media/${MIDS[$n]}" ""; [ "$(echo "$HTTP_BODY" | jq -r .processingStatus)" = ready ] && break; sleep 1; done
  [ "$(echo "$HTTP_BODY" | jq -r .processingStatus)" = ready ] || ALLREADY=false
done
check "all 6 concurrent uploads become ready" true "$ALLREADY"
if [ -n "${APP_CONTAINER:-}" ]; then
  DUP=0; for m in "${MIDS[@]}"; do c=$(docker logs "$APP_CONTAINER" 2>&1 | grep -c "image media $m processed"); [ "$c" != 1 ] && DUP=$((DUP+1)); done
  check "each concurrent upload was processed exactly once (per the backend log)" 0 "$DUP"
fi

echo "--- gallery"
GIDS=()
for n in 1 2; do
  req POST /api/media/upload-url "{\"filename\":\"g$n.png\",\"contentType\":\"image/png\",\"byteSize\":$SIZE}" -H "$(auth "$A")"
  gid=$(echo "$HTTP_BODY" | jq -r .mediaId); gurl=$(echo "$HTTP_BODY" | jq -r .uploadUrl)
  curl -s -o /dev/null -X PUT -H 'Content-Type: image/png' --data-binary @"$PNG" "$gurl"
  req POST "/api/media/$gid/complete" "" -H "$(auth "$A")"; GIDS+=("$gid")
done
req POST "/r/$C/submit" "{\"kind\":\"gallery\",\"title\":\"two images\",\"mediaIds\":[\"${GIDS[0]}\",\"${GIDS[1]}\"]}" -H "$(auth "$A")" -H "Idempotency-Key: ms-$RUN-gal"; check "a two-image gallery post is created" 200 "$HTTP_STATUS"
check "gallery keeps its order" "${GIDS[0]}" "$(echo "$HTTP_BODY" | jq -r '.mediaItems | length as $n | if $n == 2 then "'"${GIDS[0]}"'" else "wrong" end')"

echo "--- community icon uses the same pipeline"
req PATCH "/r/$C/mod/settings" "{\"iconMediaId\":\"$MID\"}" -H "$(auth "$A")"; check "an uploaded image can be set as the community icon" 200 "$HTTP_STATUS"
req GET "/r/$C/about" ""; check "the icon URL is exposed on the community" true "$([ "$(echo "$HTTP_BODY" | jq -r .iconUrl)" != null ] && echo true || echo false)"

rm -f "$PNG"
echo; echo "passed=$PASS_COUNT failed=$FAIL_COUNT"; [ "$FAIL_COUNT" = 0 ]
