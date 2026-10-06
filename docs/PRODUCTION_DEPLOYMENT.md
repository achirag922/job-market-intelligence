# Production deployment (V10.1 — cloud readiness)

JMIP is deployable as three containers (frontend nginx, backend API, ETL batch job) plus a managed
PostgreSQL database. Nothing here deploys anything; it is the checklist and reference for doing so.
No Redis, Kafka or Kubernetes is required: the API is stateless apart from the HTTP session, and
scheduled jobs already coordinate through PostgreSQL advisory locks (V9.9).

## Configuration profiles

| Environment | How it is selected | Configuration |
|---|---|---|
| Local development | no profile (IntelliJ / `mvn spring-boot:run`) | `application.yml`: safe local defaults (localhost DB, codes logged, cookie not Secure) |
| Tests | no profile, test classpath | `backend/src/test/resources/application-default.yml` + Testcontainers PostgreSQL |
| Production | `SPRING_PROFILES_ACTIVE=prod` (the Docker image default via Compose) | `application-prod.yml` over `application.yml`: no fallbacks for secrets, JSON logs, graceful shutdown, Secure cookie, forwarded headers |

In `prod`, `RequiredProductionSettings` refuses to start when a required variable is missing, the
session cookie is not Secure, CORS lists a non-HTTPS origin, sign-up codes would be logged on a
public host, or `JMIP_DB_SSL_MODE` is invalid. Errors name variables, never values.

## Environment variables

Secrets must come from the platform's secret store (or an untracked `.env` for Compose), never from
files in the repository. `.env.example` is the template.

### Required in production (backend)

| Variable | Purpose |
|---|---|
| `JMIP_DB_HOST`, `JMIP_DB_NAME`, `JMIP_DB_USERNAME`, `JMIP_DB_PASSWORD` | PostgreSQL connection (**secret**: password) |
| `JMIP_CORS_ALLOWED_ORIGINS` | Exact HTTPS origin(s) of the frontend, comma separated |
| `JMIP_OTP_SECRET` | HMAC key for verification codes, long random string (**secret**) |
| `JMIP_RESUME_ENCRYPTION_KEY` | base64 256-bit key, `openssl rand -base64 32` (**secret**; losing it makes stored resumes unreadable) |
| `JMIP_RESUME_DIR` | Directory for resume files; mount a persistent volume here |
| `JMIP_MAIL_USERNAME`, `JMIP_MAIL_PASSWORD` | SMTP account (**secret**), unless `JMIP_VERIFICATION_DELIVERY=log` on localhost |

### Recommended in production

| Variable | Default | Notes |
|---|---|---|
| `JMIP_DB_SSL_MODE` | `prefer` | `require` or `verify-full` for managed databases (RDS, Cloud SQL, Azure) |
| `JMIP_DB_PORT` | `5432` | |
| `JMIP_DB_POOL_SIZE` / `JMIP_DB_POOL_MIN_IDLE` / `JMIP_DB_CONNECTION_TIMEOUT` | `10` / `2` / `10000` ms | Keep pool × instances (+ ETL pool) below the database connection limit |
| `JMIP_DB_IDLE_TIMEOUT` / `JMIP_DB_MAX_LIFETIME` / `JMIP_DB_KEEPALIVE_TIME` | `600000` / `1500000` / `300000` ms | Max lifetime below the platform's idle-connection cut-off |
| `JMIP_DB_LEAK_DETECTION_THRESHOLD` | `0` (off) | e.g. `60000` to log connections held too long |
| `JMIP_FLYWAY_CONNECT_RETRIES` | `10` | Retries while a managed database is still starting |
| `JMIP_RESUME_MAX_FILE_SIZE` | `5MB` | Upload limit, applied by the servlet container and the upload validation alike |
| `JMIP_APP_URL` | `http://localhost:5173` | Public URL used in emails (alerts, follow-ups) |
| `JMIP_MAIL_HOST` / `JMIP_MAIL_PORT` / `JMIP_MAIL_FROM` | Gmail / 587 / — | SMTP with STARTTLS |
| `JMIP_VERIFICATION_DELIVERY` / `JMIP_ALERTS_DELIVERY` | `smtp` / `log` | Set alerts to `smtp` to send alert and follow-up emails |
| `AI_API_KEY` (**secret**), `AI_PROVIDER`, `AI_MODEL`, `AI_TIMEOUT`, `AI_MAX_RETRIES`, `AI_MAX_TOKENS` | none, `anthropic`, `claude-opus-5`, `30s`, `2`, `8192` | Without a key the AI features report themselves unavailable |
| `JMIP_ADMIN_EMAILS` | — | Verified accounts promoted to admin at start-up |
| `JMIP_METRICS_USERNAME` / `JMIP_METRICS_PASSWORD` (**secret**) | `metrics` / — | No password, no `/actuator/metrics` |
| `JMIP_RESUME_STORAGE_TYPE` | `local` | Storage backend; only `local` (directory/volume) exists |
| `JMIP_RESUME_RETENTION` | `0s` (keep) | e.g. `365d` |
| `JMIP_SESSION_TIMEOUT` / `JMIP_SESSION_COOKIE_SAME_SITE` | `8h` / `strict` | Cookie is always Secure in prod |
| `JMIP_LOG_FORMAT` / `JMIP_LOG_LEVEL` | `ecs` / `INFO` | JSON logs to stdout |
| `JMIP_RATE_LIMIT_*`, `JMIP_PASSWORD_BCRYPT_STRENGTH` | see `application.yml` | |

### Frontend (build time) and Compose

| Variable | Purpose |
|---|---|
| `JMIP_PUBLIC_URL` | Passed as `VITE_API_BASE_URL` when building the frontend image (the public HTTPS origin) |
| `JMIP_TLS_CERT_DIR`, `JMIP_HTTP_PORT`, `JMIP_HTTPS_PORT` | `docker-compose.https.yml` only |

### ETL

Same `JMIP_DB_*` variables (including `JMIP_DB_SSL_MODE`), plus `JMIP_ETL_JOB`, `JMIP_ETL_CONNECTOR`,
`JMIP_ETL_SAMPLE_SOURCE`, `JMIP_ETL_SAMPLE_RESOURCE`. Run it as a scheduled job (cloud scheduler / cron),
not a long-running service; `EtlRunLock` stops overlapping runs.

## Docker images

- `backend/Dockerfile`, `etl/Dockerfile`: multi-stage Maven build, JRE-only runtime, non-root user,
  memory-relative heap with `ExitOnOutOfMemoryError`; the backend has a health check on `/actuator/health`.
- `frontend/Dockerfile`: Vite build served by nginx with security headers; health check on the static
  `/healthz` (no backend call).
- Build: `docker compose build` (add `--profile etl` for the ETL image). Push the images to your
  registry with an immutable tag (e.g. the git SHA).
- Probes: liveness `/actuator/health/liveness`, readiness `/actuator/health/readiness` (includes the
  database), frontend `/healthz`. Shutdown is graceful (20 s), so allow at least 40 s termination grace.

## Database and Flyway

- Use a managed PostgreSQL 18 with automated backups (see `BACKUP_AND_RECOVERY.md`) and `JMIP_DB_SSL_MODE=require`.
- The backend runs Flyway on start-up (`db/migration`, V1…V30 today). `clean` is disabled in prod;
  migrations only move forward. `FlywayMigrationsIntegrationTest` checks that versions are sequential
  and that a fresh database migrates, validates and has nothing pending.
- With several API instances, Flyway's own lock serialises the migration; the first instance migrates.
- Hibernate only validates the schema (`ddl-auto: validate`).
- V10.2 connection pool (prod): named pools `jmip-backend` / `jmip-etl` (also the PostgreSQL `application_name`,
  visible in `pg_stat_activity`), minimum idle 2, connections retired after 25 min and kept alive every 5 min,
  TCP keepalive on. The ETL uses its own pool of 5 (`JMIP_ETL_DB_POOL_SIZE`).
- V10.2 Flyway (prod): validate on migrate, migration naming validated, no out-of-order or baseline, connect retries.
  An edited, missing or misnamed migration stops the start instead of being applied.

## File storage

V10.2: uploads are checked for size (`JMIP_RESUME_MAX_FILE_SIZE`), declared type (PDF only) and the PDF signature
before anything is stored; the stored name is generated from the resume id, never the upload name, and the display
name is sanitised. On Linux the store creates its directory and files owner-only (700/600), the image creates
`/app/data/resumes` as 700, and the backend refuses to start when `JMIP_RESUME_DIR` is not a writable directory.
Stored files are never served: no API endpoint or nginx location exposes the volume, and resume data is returned only to its owner.


Resume files go through `ResumeFileStore` (V10.1). `LocalResumeFileStore` writes to `JMIP_RESUME_DIR`,
which in the cloud must be a persistent volume shared by all API instances (or run one instance).
Files are encrypted before they reach the store. An object-store implementation (S3, GCS, Azure Blob)
can be added as another `ResumeFileStore` selected by `JMIP_RESUME_STORAGE_TYPE`, without changing
upload, validation, encryption or retention logic.

## CORS, security headers, HTTPS

- CORS: exact origins only (wildcards rejected), credentials allowed, HTTPS enforced in prod.
  When the frontend proxies `/api` (same origin), CORS is not used by the browser at all.
- Backend headers: `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, a deny-all CSP,
  `Cross-Origin-Resource-Policy`, HSTS on HTTPS requests, `Cache-Control: no-store` for API responses.
- nginx headers: CSP restricted to `'self'`, `X-Frame-Options`, `Referrer-Policy`, `Permissions-Policy`;
  HSTS is added by `nginx-https.conf`.
- HTTPS: terminate TLS at the load balancer or with `docker-compose.https.yml` (nginx with your
  certificate, HTTP redirected to HTTPS). The backend honours `X-Forwarded-*` (`forward-headers-strategy`),
  so only expose it behind the proxy, never directly to the internet.

## Pre-deployment checklist

1. Create the secrets above in the platform's secret store; never commit them.
2. Provision PostgreSQL 18 with backups and TLS; set `JMIP_DB_SSL_MODE=require`.
3. Provision a persistent volume for `JMIP_RESUME_DIR`; back up the encryption key separately.
4. Build and push the images; build the frontend with `JMIP_PUBLIC_URL` set to the public HTTPS URL.
5. Set `JMIP_CORS_ALLOWED_ORIGINS` and `JMIP_APP_URL` to the public HTTPS URL.
6. Configure SMTP and set `JMIP_VERIFICATION_DELIVERY=smtp` (and `JMIP_ALERTS_DELIVERY=smtp` for emails).
7. Point health checks at the probes above; schedule the ETL job.
