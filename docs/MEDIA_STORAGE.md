# Media storage (Cloudflare R2)

How images and videos are uploaded, stored, processed and served. This describes the code as it is; where something
is *not* implemented it says so (see [Known future improvements](#17-known-future-improvements)).

Code lives in `src/main/java/com/redditclone/media/` (backend) and in `frontend/src/` (`lib/mediaApi.ts`,
`hooks/useMediaUpload.ts`, `hooks/useGalleryUpload.ts`, `hooks/useLiveMedia.ts`, `components/PostMedia.tsx`).

## 1. Architecture overview

```
 Browser ──(1) POST /api/media/upload-url ──▶ Backend ──▶ media row (status "pending") + presigned PUT URL
    │
    ├──(2) PUT file straight to the presigned URL ─────────────────────────────▶  R2 bucket (original object)
    │
    ├──(3) POST /api/media/{id}/complete ──▶ Backend: HEAD the object, status "uploaded", publish MediaUploadedEvent
    │                                              │ (after commit)
    │                                              ▼
    │                                   Image/Video worker: download original ─▶ make renditions ─▶ upload them ─▶ "ready"
    │
    └──(4) GET /api/media/{id} (poll while not ready) · load <img>/<video> from MEDIA_PUBLIC_BASE_URL (public R2 URL)
```

The backend never receives the file bytes from the browser: it only issues a short-lived upload permission, then
reads the original back from storage to process it.

## 2. Why Cloudflare R2

S3-compatible object storage with no egress (download) fees, which matters for an image/video-heavy product, and a
public-bucket / custom-domain option for serving finished files. Locally the same code runs against a fake S3
(`adobe/s3mock`, see §12), so R2 is a deployment choice, not a code dependency.

## 3. Why the AWS S3 SDK

R2 implements the S3 API, so the standard AWS SDK v2 (`software.amazon.awssdk:s3`) works against it with only an
endpoint override (`StorageConfig`): path-style access, region `auto`, static credentials. Two settings are specific to
R2: `requestChecksumCalculation` / `responseChecksumValidation` are set to `WHEN_REQUIRED` (since SDK 2.30 the default
adds CRC32 checksums and chunked trailers that R2 does not handle reliably).

## 4. Presigned upload flow

`POST /api/media/upload-url` (`MediaService.requestUploadUrl`), authenticated:

1. Validates `contentType` (JPEG, PNG, WebP → image; MP4, QuickTime, WebM and GIF → video) and the *declared*
   `byteSize` against `app.media.max-image-bytes` (20 MB) / `max-video-bytes` (200 MB).
2. Stores a `media` row, status `pending`, with the storage key `u/<ownerId>/<mediaId>-<sanitized filename>`.
3. Returns the media id and a presigned **PUT** URL valid for **10 minutes** (`UPLOAD_URL_EXPIRY`). The signature covers
   the `Content-Type`, so the browser must send the same one.

The presigner uses `app.storage.public-endpoint` (defaults to `app.storage.endpoint`). It exists because in Docker the
backend reaches the fake S3 as `http://s3mock:9090` while the browser must use `http://localhost:9000`.

A presigned URL cannot enforce a maximum size, so the real size is checked at completion (§6).

## 5. Browser → R2 direct upload

`frontend/src/lib/mediaApi.ts` `uploadFileDirectly` does a plain `fetch(PUT)` to the presigned URL — no `Authorization`
header, no app API client. Because that is a cross-origin request with a custom header, the browser first sends a CORS
**preflight**, so the bucket must have a CORS rule (§10). If the request cannot be read (offline, or CORS blocks it) the
upload hook shows a specific message (`describeUploadError`) instead of a generic failure. No storage credential ever
reaches the frontend; the presigned URL is the only thing it receives.

`POST /api/media/{id}/complete` then (`completeUpload`): checks ownership, `HEAD`s the object (it must exist, and its real
size must be within the limit — an oversized object is deleted), sets `uploaded`, and publishes a `MediaUploadedEvent`.

## 6. Media database lifecycle

Table `media` (migrations V7, V13, V14, V25). `processing_status`:

```
pending ──complete──▶ uploaded ──claimed──▶ processing ──▶ ready
   │                      ▲                     │
   │                      └──── retry (<5) ─────┤──▶ failed   (retries exhausted, or unprocessable)
   └── never completed for 24h ──▶ row deleted (reaper, §15)
```

- `ready`: `thumbnail_key`, `display_key`, `width`, `height` (and `duration_seconds` for video) are set.
- `failed`: `error_message` records why. A file that is not a decodable image, or exceeds the pixel limit (§7), fails
  immediately; other errors are retried up to 5 times (`attempt_count`).
- Media can be attached to a post / community icon while `uploaded`, `processing` or `ready` (`requireOwnedAndUsable`);
  `pending` and `failed` media cannot.

## 7. Processing lifecycle

**Images** (`ImageProcessingWorker`): download the original → read dimensions from the header and reject anything above
`app.media.max-image-pixels` (default 40 million, ≈120 MB decoded) → decode **once** → cut the **1280px** display rendition
→ cut the **256px** thumbnail from that smaller image → upload both JPEGs in parallel → mark `ready`.
Keys: `<originalKey>-display.jpg`, `<originalKey>-thumb.jpg`. The original is only ever read by this worker.

**Videos and GIFs** (`VideoProcessingWorker`): `ffmpeg` (installed in the backend Docker image) transcodes to H.264/AAC MP4
(max width 1280), extracts a poster frame, and `ffprobe` reads dimensions and duration. Keys: `-display.mp4`, `-thumb.jpg`.
Work runs on a bounded 2-thread pool; ffmpeg is not available on a bare dev machine unless installed.

## 8. Event-driven processing and the recovery worker

**Normal path:** `completeUpload` publishes `MediaUploadedEvent` inside its transaction. A `@TransactionalEventListener`
(AFTER_COMMIT) in each worker starts a pass immediately on its own thread, so processing begins as soon as the upload
completes instead of waiting for a poll.

**Recovery path:** a scheduled pass (every 30 s, ShedLock-protected) claims only media that has sat in `uploaded` for at
least 30 seconds (`created_at` age filter) — i.e. whose event was missed, e.g. by a restart or crash between commit and
processing. It is not a second normal path. A separate job (`MediaReaperJob`, §15) returns media stuck in `processing`
for 10 minutes to `uploaded`, after which the recovery pass picks it up.

**No duplicate processing:** claiming is one atomic statement — `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED)
RETURNING …` (`MediaService.claimUploadedBatch`) — so one media row is handed to exactly one worker, across threads and
across app instances.

**Memory:** inside an instance the image worker also serializes its passes with a lock, so at most one image is decoded
at a time (the recovery pass is skipped if a pass is running), and the original's bytes are released before resizing.

## 9. 256px vs 1280px renditions

| View | Rendition used | Why |
| --- | --- | --- |
| Feeds, cards, search/profile lists, gallery preview on a card | **256px thumbnail** | A feed holds many images; a thumbnail is a few KB instead of tens/hundreds |
| Post detail page, gallery strip on the detail page | **1280px display** | Full-size viewing |
| Community icon | thumbnail | small circle |
| Community banner | display | wide |

Chosen in `PostMedia.tsx` (`fullBody` is true only on the detail page). The original upload is **never** exposed through
the API (`MediaView` has no original URL). Until a file is `ready` the UI shows a "processing" placeholder; `failed`
shows "couldn't be processed".

## 10. R2 CORS

CORS is **infrastructure configuration set once on the bucket**, not managed by the application. In the Cloudflare
dashboard: R2 → bucket → Settings → CORS policy:

```json
[{"AllowedOrigins": ["https://your-site-origin"], "AllowedMethods": ["PUT","GET","HEAD"],
  "AllowedHeaders": ["*"], "ExposeHeaders": ["ETag"], "MaxAgeSeconds": 3600}]
```

`app.storage.configure-cors` (env `STORAGE_CONFIGURE_CORS`) is **false by default and must stay false in production**. If set to
true, a *remote* bucket with **no** CORS policy gets one for `app.cors.allowed-origins`; an existing policy is never
overwritten. It needs a bucket-admin token. It does nothing against the local fake S3, which accepts any origin.

## 11. Public media URL

Only processed renditions are served publicly. `MEDIA_PUBLIC_BASE_URL` (`app.media.public-base-url`) is prefixed to the stored
keys to build `thumbnailUrl` / `displayUrl`. For R2 enable public access on the bucket and use the `r2.dev` address (or a custom
domain); that address **does not include the bucket name**. For the fake S3 it is `http://localhost:9000/reddit-clone-media`.
It must contain no credentials. See §14 for the caveat that a public bucket exposes all objects by key.

## 12. Local fake S3 (s3mock) vs R2

| | s3mock (default) | R2 |
| --- | --- | --- |
| Selected by | no env file | `docker compose --env-file .env.r2 up -d` |
| Endpoint | `http://s3mock:9090` (backend), `http://localhost:9000` (browser) | `https://<account>.r2.cloudflarestorage.com` for both |
| Bucket | created at startup if missing | must already exist; never created or changed by the app |
| CORS | not needed | set by hand on the bucket |
| Startup checks | warn only | fail fast on a misconfiguration (below) |

`MediaBucketBootstrap` treats an endpoint containing `localhost`, `127.0.0.1` or `s3mock` as local. For a remote bucket it only
*reads*: it fails startup if the bucket cannot be accessed or if `MEDIA_PUBLIC_BASE_URL` still points at local storage. It needs no
bucket-admin permission and changes nothing. `STORAGE_FAIL_FAST=false` downgrades those failures to a logged error.

## 13. Environment variables

| Variable | Default | Meaning |
| --- | --- | --- |
| `STORAGE_ENDPOINT` | `http://localhost:9000` | S3 API endpoint the backend uses |
| `STORAGE_PUBLIC_ENDPOINT` | = endpoint | Endpoint placed in presigned URLs (what the browser must reach) |
| `STORAGE_BUCKET` | `reddit-clone-media` | Bucket name |
| `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY` | `test` | Credentials (backend only; `test` works for s3mock) |
| `MEDIA_PUBLIC_BASE_URL` | `http://localhost:9000/reddit-clone-media` | Public address of processed files |
| `STORAGE_CONFIGURE_CORS` | `false` | Opt-in dev convenience, see §10 |
| `STORAGE_FAIL_FAST` | `true` | Refuse to start against a broken remote bucket |
| `MEDIA_MAX_IMAGE_BYTES` / `MEDIA_MAX_VIDEO_BYTES` | 20 MB / 200 MB | Upload limits |
| `MEDIA_MAX_IMAGE_PIXELS` | `40000000` | Decode limit (width × height) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5174` | Website origin(s) for the API (and the opt-in bucket CORS) |

`.env.example` documents these with placeholders. Real values go in an ignored file such as `.env.r2`.

## 14. Security considerations

- R2 credentials are used only by the backend (`StorageConfig`); the frontend only ever sees presigned URLs (10-minute life,
  scoped to one key and the declared `Content-Type`).
- `.env`/`.env.*` are git-ignored (except `.env.example`, placeholders only); `MEDIA_PUBLIC_BASE_URL` holds no secret.
- The server does not trust the declared content type: images are sniffed and decoded by `ImageIO`; non-images fail.
- Sizes are re-checked from the stored object at completion; images are pixel-limited before decoding (decompression bombs).
- Ownership is enforced at completion and when attaching media; keys are namespaced per owner.
- `GET /api/media/{id}` is public (like the media URLs) and returns only status and rendition URLs; there is no rate limit on it.
- **Caveat:** with a *public* bucket, every object is reachable by its key — including the original upload (up to 20 MB, may
  contain EXIF). The application never links to it, and keys contain unguessable ids, but it is not private. Keeping originals
  private requires a separate bucket (or a custom domain with path rules) — see future improvements.

## 15. Cleanup / reaper behavior

`MediaReaperJob` (every 5 minutes, ShedLock):

1. **Abandoned uploads:** rows still `pending` after 24 h were never completed. Removed in **bounded batches** (100 rows per
   statement, at most 10 batches per run): a single `DELETE … FOR UPDATE SKIP LOCKED … RETURNING r2_key` removes the rows, *then*
   their objects are deleted outside any transaction. A failed object delete is logged and leaves only an orphaned object. Rows
   are deleted first so a late `complete` can never meet a row whose object was just removed. Safe to run repeatedly.
2. **Stuck processing:** rows in `processing` for more than 10 minutes go back to `uploaded` (or `failed` after the retry limit).

Oversized uploads are deleted at completion time; their row remains `pending` and is reaped later.

## 16. Scalability rationale

- File bytes never pass through the backend on upload; downloads are served from the bucket/CDN, not the app.
- Feeds load 256px thumbnails; only the detail page loads 1280px.
- Processing is claimed atomically (no double work, scales to several instances) and bounded per instance (one image decode at
  a time; 2 ffmpeg jobs).
- Status polling is cheap and bounded: one primary-key lookup per poll, ≤ 1.5 s then 4 s intervals, paused while the tab is
  hidden, stopped as soon as the file is `ready`/`failed`, and abandoned after 5 minutes (`useLiveMedia`).
- Cleanup is batched and never holds a long transaction.

## 17. Known future improvements

- **Push-based media status** (server notifies the page when processing finishes) to remove polling entirely.
- **Stronger orphan cleanup:** objects can be orphaned when an object delete fails, when a post/media is deleted, or when the
  derived `-thumb`/`-display` files outlive a failed row. A periodic bucket-vs-database reconciliation would close this.
- **Private originals:** store originals in a private bucket and serve renditions from a separate public bucket/custom domain.
- **CDN / cache strategy:** put a custom domain with caching in front of the public bucket; set long-lived `Cache-Control` on
  renditions (immutable keys); consider `srcset` for responsive images.
- **Further worker scaling:** a dedicated worker process, queue-based dispatch, and a memory-aware limit on concurrent video jobs.
- **Resumable/multipart uploads** for large videos, and a rate limit on `GET /api/media/{id}`.
