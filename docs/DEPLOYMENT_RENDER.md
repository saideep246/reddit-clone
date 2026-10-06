# Deploying the API to Render (Neon + R2 + managed Redis)

The backend runs from the repo's `Dockerfile` with `SPRING_PROFILES_ACTIVE=prod`. `render.yaml` describes the service;
secrets are entered in the Render dashboard (`sync: false`), never committed.

## How production differs from local

| Concern | Local (no profile) | Production (`prod` profile, `application-prod.yml`) |
|---|---|---|
| Missing config | falls back to localhost / s3mock / dev secret | **startup fails** ("Could not resolve placeholder 'X'") |
| Port | `8081` | `PORT` (Render injects it) |
| Postgres | PgBouncer :6432 / direct :5434 | Neon pooled URL (app) + Neon direct URL (Flyway) |
| Redis | `localhost:6380` | `REDIS_URL` (`redis://` or `rediss://`) |
| Storage | s3mock | Cloudflare R2 (`STORAGE_*`, `MEDIA_PUBLIC_BASE_URL`) |
| Refresh cookie | `Secure=false; SameSite=Strict` | `Secure=true; SameSite=None` (cross-site SPA) |
| Client IP (rate limits) | socket address | `X-Forwarded-For` via `server.forward-headers-strategy=native` |

Schema is owned by Flyway (V1–V35); Hibernate stays `ddl-auto=validate`. Against the existing Neon database Flyway
validates the history and reports "up to date, no migration necessary". Nothing in the app drops or recreates schema.

## Environment variables (Render dashboard)

| Variable | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `SPRING_DATASOURCE_URL` | Neon **pooled** host (`…-pooler…`): `jdbc:postgresql://<host>/neondb?sslmode=require&stringtype=unspecified` |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | Neon role credentials |
| `SPRING_FLYWAY_URL` | Neon **direct** host: `jdbc:postgresql://<host>/neondb?sslmode=require` |
| `SPRING_FLYWAY_USER` / `_PASSWORD` | Neon role credentials |
| `REDIS_URL` | managed Redis connection URL (use `rediss://` if TLS) |
| `JWT_SECRET` | ≥ 32 random bytes (`openssl rand -base64 48`); changing it logs everyone out |
| `AUTH_COOKIE_SAME_SITE` | `None` (default in prod profile); use `Lax`/`Strict` once SPA and API share a parent domain |
| `STORAGE_ENDPOINT` | `https://<account-id>.r2.cloudflarestorage.com` |
| `STORAGE_BUCKET` | bucket name |
| `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` | R2 API token (Object Read & Write) |
| `MEDIA_PUBLIC_BASE_URL` | public r2.dev / custom-domain URL, no bucket name, no credentials |
| `CORS_ALLOWED_ORIGINS` | exact origin(s) of the frontend, comma-separated (e.g. `https://<project>.pages.dev`) |
| `STORAGE_CONFIGURE_CORS` / `STORAGE_FAIL_FAST` | `false` / `true` |

Optional: `ADMIN_BOOTSTRAP_USERNAME`, rate-limit overrides (`RATE_LIMIT_*`), `MEDIA_MAX_*`.

## Render service settings

Docker runtime · region **Singapore** · health check path `/actuator/health` · no disk · auto-deploy off until the
first release is verified · instance with ≥ 512 MB (JVM heap is 70% of RAM; image decoding and ffmpeg need headroom —
prefer 2 GB if video uploads matter).

## One-time manual steps

1. R2 bucket CORS must allow the production frontend origin (`PUT, GET, HEAD`, `AllowedHeaders: *`, `ExposeHeaders: ETag`).
2. Create the Redis instance and put its URL into `REDIS_URL`.
3. Build the frontend with `VITE_API_BASE_URL=https://<your-render-service>.onrender.com` (the WebSocket URL derives from it).
4. After the first deploy, set `CORS_ALLOWED_ORIGINS` to the real frontend URL and redeploy.

## Known limits

- Rate limiting needs the proxy's `X-Forwarded-For` to be trusted; Tomcat only trusts private-range proxies by default.
  After deploying, confirm two different clients do not share one rate-limit key.
- Neon's pooler runs in transaction mode. The app only uses transaction-scoped features (`pg_advisory_xact_lock`,
  ShedLock rows), but local testing used PgBouncer in *session* mode, so before or during the first deployment, test the pooled URL with a real register and login smoke test.
- `SameSite=None` cookies are treated as third-party by some browsers (Safari/ITP). A custom domain for both frontend and
  API (e.g. `app.example.com` + `api.example.com`) avoids this and allows `Lax`.
