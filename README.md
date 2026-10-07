# Job Market Intelligence Platform (JMIP)
- [x] V10.9 — Test & quality: API client session/CSRF/401 tests, alert email sender, ETL CSV reader/location/employment-type tests; Sonar High issues fixed (23 duplicated literals, 2 frontend complexity)

Analytics over job postings: job demand, skill demand and trends, companies, locations,
experience requirements and salary, plus job search and filtering.

## Architecture

```
Job files / connectors  ->  ETL (Spring Batch)  ->  PostgreSQL  <-  Spring Boot REST API  <-  nginx + React SPA
                                                                      |-> Anthropic API (optional), SMTP, encrypted resume files
```

The full picture (components, ER diagram, ETL flow, authentication flow, feature modules, data flows,
integrations and architectural decisions) is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Documentation

| Document | Covers |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | system, backend, frontend, data model, ETL, security flow, modules, decisions |
| [docs/PRODUCTION_DEPLOYMENT.md](docs/PRODUCTION_DEPLOYMENT.md) | profiles, environment variables, Docker, database, storage, security, monitoring |
| [docs/CI_CD.md](docs/CI_CD.md) | pipeline stages, secrets, Sonar Quality Gate, releases |
| [docs/BACKUP_AND_RECOVERY.md](docs/BACKUP_AND_RECOVERY.md) | backups and restores |

The ETL and the API are separate applications with separate lifecycles. They share only
the database schema, through the `database` module, so that running an ingestion batch can
never affect API availability.

## Modules

| Module     | What it is                                                              |
|------------|-------------------------------------------------------------------------|
| `database` | Flyway migrations, packaged as a jar so both applications resolve the same schema |
| `backend`  | Spring Boot REST API                                                     |
| `etl`      | Spring Batch ingestion pipeline, plus the raw input data under `etl/data` |
| `frontend` | React + TypeScript dashboard, built with Vite (not a Maven module)        |

## Tech stack

| Layer    | Technology                                                   |
|----------|--------------------------------------------------------------|
| Backend  | Java 17, Spring Boot 3.5, Spring Security, Spring Data JPA, Actuator/Micrometer |
| ETL      | Java 17, Spring Batch 5.2, Spring JDBC, Jackson, Commons CSV |
| Database | PostgreSQL 18, Flyway migrations                             |
| Build    | Maven (multi-module)                                         |
| Tests    | JUnit 5, AssertJ, Testcontainers (real PostgreSQL)           |
| Frontend | React 19, TypeScript, Vite, React Router, Recharts, Vitest   |
| Runtime  | Docker images (JRE 17, nginx), Docker Compose                |

## Prerequisites

- JDK 17
- Maven 3.9+
- PostgreSQL 18 running locally (this project defaults to **port 5433**)
- Node.js 22 and npm (frontend)
- Docker (needed for the integration tests, which start a throwaway PostgreSQL, and for Docker Compose)

## Database setup

Create the database once:

```sql
CREATE DATABASE jmip;
```

## Configuration

All settings have local-friendly defaults and can be overridden with environment variables.

| Variable           | Default     |
|--------------------|-------------|
| `JMIP_DB_HOST`     | `localhost` |
| `JMIP_DB_PORT`     | `5433`      |
| `JMIP_DB_NAME`     | `jmip`      |
| `JMIP_DB_USERNAME` | `postgres`  |
| `JMIP_DB_PASSWORD` | `postgres`  |
| `JMIP_SERVER_PORT` | `8080`      |
| `JMIP_RESUME_DIR`  | `./data/resumes` |
| `JMIP_RESUME_MAX_FILE_SIZE` | `5MB` |
| `JMIP_ETL_JOB`     | `ingestJobPostings` |
| `AI_PROVIDER`      | `anthropic` (or `stub`) |
| `AI_API_KEY`       | *(unset)*   |
| `AI_MODEL`         | `claude-opus-5` |
| `AI_TEMPERATURE`   | *(unset — see below)* |
| `AI_MAX_TOKENS`    | `8192`      |
| `AI_TIMEOUT`       | `30s`       |
| `JMIP_ASSISTANT_MAX_LIMIT` | `25` |
| `JMIP_ASSISTANT_MAX_JOB_RESULTS` | `20` |
| `JMIP_ASSISTANT_MIN_SALARY_SAMPLE` | `5` |
| `JMIP_VERIFICATION_DELIVERY` | `log` (or `smtp` with `JMIP_MAIL_HOST/PORT/USERNAME/PASSWORD/FROM`) |
| `JMIP_ADMIN_EMAILS` | *(unset)*: verified accounts promoted to ADMIN at startup |
| `JMIP_API_DOCS_ENABLED` | `true` locally, `false` in the prod profile |
| `JMIP_CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` |

The complete list, including production-only settings, is in [docs/PRODUCTION_DEPLOYMENT.md](docs/PRODUCTION_DEPLOYMENT.md#environment-variables)
and [.env.example](.env.example).

Sign-up locally: with the default `JMIP_VERIFICATION_DELIVERY=log` the 6-digit verification code is written
to the backend log instead of being emailed (set `smtp` and the `JMIP_MAIL_*` variables to send email).

`AI_API_KEY` has no default and appears in no file in this repository. With it unset the
application starts normally and the assistant reports itself unavailable; nothing else is
affected. `AI_TEMPERATURE` is left unset because the current Claude models removed sampling
controls and reject it with a 400 — set it only for a model that accepts one.

Classification rules — the categories, their keywords and the signal weights — live in
`etl/src/main/resources/application.yml` under `jmip.etl.classification`. They are
configuration, not code: adding a category or retuning a weight needs no recompile.

## Building

```
mvn clean install
```

Whichever application starts first applies the Flyway migrations; both resolve them from
the `database` module, so the schema is identical either way.

## Running the API

```
mvn -pl backend spring-boot:run
```

Health check: `http://localhost:8080/actuator/health`

Alternatively run `JobMarketIntelligenceApplication` from the IDE with the same environment variables.

## API documentation

With the backend running locally (V10.8):

| URL | What |
|---|---|
| `http://localhost:8080/swagger-ui.html` | Swagger UI: every endpoint, grouped (drop-down: all, account & profile, jobs & applications, resume & career growth, market analytics, administration & ETL) |
| `http://localhost:8080/v3/api-docs/all` | OpenAPI 3 JSON for all endpoints (`/v3/api-docs/{group}` per group), e.g. for Postman or client generation |

The document covers request and response schemas, query parameters (filters and `page`/`size`/`sort`),
which routes are public, the session-cookie and CSRF-header security, the shared `ApiError` error shape with the
possible error statuses per route, and examples for sign-up, verification, login and job search.

To try authenticated calls in Swagger UI: `POST /api/auth/signup`, read the code from the backend log,
`POST /api/auth/verify-email`, then `POST /api/auth/login`. The browser keeps the session cookie; copy the
returned `csrfToken` into **Authorize → csrf** for POST, PUT, PATCH and DELETE calls.

API documentation is off in the prod profile (`JMIP_API_DOCS_ENABLED=false`).

## Running the ETL

The input file is a job parameter, so switching datasets needs no code change:

```
java -jar etl/target/etl-0.0.1-SNAPSHOT.jar inputFile=etl/data/raw/synthetic-job-postings-v1.json
```

A `.csv` file is read with Apache Commons CSV instead, chosen by extension. CSV files
without a source column take one from an optional `defaultSource=<name>` parameter.

Re-running the same file is safe: duplicate detection means nothing is loaded twice.

**Connectors (V9.1).** Records come through a connector, and every connector's records then go through
the same pipeline: Connector → Normalize → Validate → Deduplicate → Load (the V8.1/V8.2 processor,
writer and source registry). The connector is the `connector` job parameter, else
`JMIP_ETL_CONNECTOR` (`jmip.etl.connectors.active`), default `file`:

| Connector | Reads | Feed type |
|---|---|---|
| `file` | the `inputFile` JSON or CSV, exactly as before | FILE_JSON / FILE_CSV |
| `sample` | a bundled mock job-board feed (`connectors/sample-postings.json`) with its own record shape, mapped to the common raw record; never touches the network | SAMPLE |

```
java -jar etl/target/etl-0.0.1-SNAPSHOT.jar connector=sample
```

The sample feed's location (`JMIP_ETL_SAMPLE_RESOURCE`, `classpath:` or `file:` only) and its source code
(`JMIP_ETL_SAMPLE_SOURCE`, default `sample-board`) are configurable. Each run records its connector with
its metrics; ETL Monitoring and `/api/etl/runs` show it next to the feed, read, loaded, duplicate and
rejected counts, status and duration. A new connector implements `JobSourceConnector` (a name, a reader
over `RawJobRecord`s and a feed description) and is picked up automatically.

To re-read postings already in the database after the rules or the skill dictionary change,
run the reprocessing job instead — see [Job categories and text processing](#job-categories-and-text-processing-v4).

## Running the frontend

```
cd frontend
npm install
npm run dev
```

Opens on `http://localhost:5173` and talks to the API at `VITE_API_BASE_URL`
(see `frontend/.env.example`). The backend must be running, and its `jmip.cors.allowed-origins`
must include the frontend's origin — `http://localhost:5173` is allowed by default.

The pages and what each one does are listed in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#7-feature-modules).

## Running everything with Docker Compose

```
cp .env.example .env        # then set JMIP_DB_PASSWORD, JMIP_OTP_SECRET (32+ chars), JMIP_RESUME_ENCRYPTION_KEY
docker compose up -d --build
docker compose --profile etl run --rm etl   # load the sample dataset
```

The app is then on `http://localhost:3000` (nginx serving the SPA and proxying `/api`), the API on
`http://localhost:8080`, both with the production profile. See [docs/PRODUCTION_DEPLOYMENT.md](docs/PRODUCTION_DEPLOYMENT.md).

## Testing

```
mvn test
```

Unit tests are plain JUnit. The integration tests start a throwaway PostgreSQL through
Testcontainers, so Docker must be running.

Frontend tests run separately:

```
cd frontend
npm test
```

**No test calls a model provider.** The assistant's tests substitute a scripted `AiClient`,
so the suite is deterministic, needs no API key, costs nothing to run, and can exercise the
cases a real provider cannot be asked for — malformed JSON, an intent outside the enum, a
timeout on demand.

## Project layout

```
database/src/main/resources/db/migration   Flyway migrations, the single source of schema truth
backend/src/main/java/com/jmip
  common/exception                         Global exception handling and the shared ApiError
  ai/                                      The only code that talks to a model provider
  service/assistant/                       V5: intent validation, routing, grounded answers
  resources/prompts/                       Versioned assistant prompts
etl/src/main/java/com/jmip/etl
  raw/                                     Input readers (JSON, CSV) and the RawJobRecord contract
  transform/                               Cleaning, parsing, skill extraction, fingerprinting
  validation/                              Validation rules and rejected-record storage
  load/                                    Chunk writer, reference-data cache, metrics
  batch/                                   Spring Batch jobs, steps and listeners
  reprocess/                               Re-reads stored postings when the rules change
etl/data
  README.md                                Dataset sources, licensing, column dictionary
  raw/                                     Raw job-posting input read by the ETL
```

## Build phases

- [x] Phase 1 — project skeleton, database and Flyway wiring, error handling, health endpoint
- [x] Phase 2 — database schema: companies, locations, skills, jobs, job_skills
- [x] Phase 3 — initial job data: synthetic development dataset and input format
- [x] Phase 4 — Spring Batch ETL: extract, transform, skill extraction, validation, dedupe, load
- [x] Phase 5 — REST API: job search, skills, companies, locations and analytics
- [x] Phase 6 — React frontend: dashboard, job explorer, job details and analytics pages
- [x] Phase 7 — deeper analytics: skill filters and ranking, experience bands, per-company and per-location breakdowns, grouped job titles
- [x] V2 — skill trends: monthly demand snapshots, backfilled from posted dates, and rising/falling detection
- [x] V3 — resume intelligence: PDF upload, text and skill extraction, resume-to-job match and skill gap
- [x] V4 — NLP and job intelligence: description text processing, skill extraction from prose, rule-based job classification with confidence and evidence, and per-category analytics
- [x] V5 — AI job market assistant: natural-language questions answered from the database, with validated intents, reused analytics services, grounded answers and chart metadata
- [x] V6.1 — professional UI: design system with light and dark themes, application shell, shared cards, charts, tables and loading, empty and error states
- [x] V6.2 — advanced job search: cross-field text search, experience and salary filters, named orderings including relevance, and searches kept in the URL
- [x] V6.3 — job recommendations: completed resumes ranked against stored jobs by the existing deterministic skill-match percentage
- [x] V6.4 — career insights: a resume against one category's skill demand and trends, with focus areas, related V6.3 jobs and an optional grounded AI summary
- [x] V6.5 — ETL monitoring: run status, times, duration and counts from the Spring Batch job repository, on an ETL Monitoring page
- [x] V6.6 — Docker Compose: PostgreSQL, backend, frontend (nginx) and ETL containers
- [x] V6.7 — GitHub Actions CI: Maven build and tests, frontend tests and build, Docker image builds on every push and pull request to main
- [x] V6.8 — production profile (`SPRING_PROFILES_ACTIVE=prod`): required settings checked at startup, health details hidden, graceful shutdown; env-only secrets
- [x] V6.9 — security hardening: PDF signature check, rate limits and body caps on upload and assistant, security headers and CSP, strict CORS, no personal data or keys in logs
- [x] V6.10.1 — authentication foundation: users table, bcrypt password hashing, USER role, stateless Spring Security chain (all endpoints still public)
- [x] V6.10.2 — signup, login and logout: HttpOnly SameSite=Strict session cookie (Secure in prod), per-session CSRF token, Log in / Sign up pages and header user menu
- [x] V6.10.3 — API authorization: every /api endpoint needs a signed-in USER (JSON 401 otherwise) except signup, login, logout and /me; frontend route guard
- [x] Auth UI upgrade: dark Sign up / Login / Verify screens; full name on accounts; 6-digit email verification codes (Gmail SMTP, HMAC-stored, 10 min expiry, 5 attempts, 60 s resend cooldown)
- [x] V6.10.4 — resume ownership: each resume belongs to the uploading account; other accounts get 404 on it and on its skills, matches, recommendations, career insights and assistant answers
- [x] V6.10.5 — resume privacy: DELETE /api/resumes/{id} (owner only), configurable retention (off by default), AES-256-GCM encryption of stored files and extracted text
- [x] V6.10.6 — HTTPS deployment: `docker-compose.https.yml` (nginx TLS termination, HTTP→HTTPS 301, HSTS on HTTPS only, backend/DB unpublished), forwarded-proto handling, prod refuses non-Secure/SameSite=None cookies and non-HTTPS CORS origins
- [x] V6.10.7 — dependency scanning: Dependabot (Maven, npm, GitHub Actions) plus a `Dependency scan` workflow where Trivy fails on HIGH/CRITICAL vulnerabilities that have a fix, using a Maven-resolved CycloneDX SBOM and `package-lock.json`
- [x] V6.10.8 — final security review: rate limit and header filters match the decoded path (no %-encoding bypass), nginx drops client X-Forwarded-Host/Prefix and Forwarded, prod refuses log-delivered sign-up codes for non-localhost origins, Spring Boot 3.5.16 plus Tomcat/PostgreSQL/httpcore5 patch overrides (0 fixable HIGH/CRITICAL)
- [x] V7.1 — job alerts foundation: saved job-search alerts per account (keywords, category, location, experience, skill; DAILY/WEEKLY; active/paused), `/api/job-alerts` CRUD + status, owner-scoped 404s, Job Alerts page; no notifications sent yet
- [x] V7.2 — saved jobs & application tracking: bookmark jobs from results and details, SAVED → APPLIED → INTERVIEW → OFFER (plus REJECTED/WITHDRAWN) with application date and private notes, owner-scoped `/api/saved-jobs`, one row per user and job
- [x] V7.3 — advanced resume intelligence: multiple resume versions per account (title, version label, one default), job-specific analysis on the V3 match with data-based suggestions, and version comparison from stored skills
- [x] V7.4 — career goals & skill roadmap: goals per account (role, job category, optional location/experience/skills; ACTIVE/COMPLETED/ARCHIVED), a deterministic roadmap from the default resume, category demand and rising trends (the V6.4 focus-area order), and per-skill progress
- [x] V7.5 — market intelligence: `/api/market/{salary,locations,remote,companies,skills}` with shared category/location/experience/period filters, per-currency salaries, work mode read from posting wording, posting-month series, skill trends from the stored snapshot; Market Intelligence page
- [x] V7.6 — AI career copilot: eight personal intents (missing skills, next skills, target-role skills and demand, job matches, resume improvement, application progress, saved-job priority) routed to existing owner-scoped services, default-resume fallback, v2 prompts, career suggestions and job context in the assistant
- [x] V7.7 — personal career dashboard: `GET /api/dashboard` aggregates resume, skills, recommendations, applications, career goal and target-role market data from the existing owner-scoped services; My Career page
- [x] V7.8 — observability: request summaries with a request id (X-Request-Id, MDC), JSON logs in prod, Actuator metrics behind a metrics account, liveness/readiness/database health groups, ETL run summaries with the execution id, friendly frontend errors and an error boundary
- [x] V7.9 — performance: measured statements per request (QueryCountIntegrationTest); per-request memo for the signed-in user and the market window cuts the dashboard from 38 to 25 SQL statements; other endpoints were already constant-query
- [x] V7.10 — release validation: end-to-end journey test (signup to logout with CSRF, ownership, deletion), migration and cascade checks, production-stack smoke test, dependency rescan, [backup and recovery guide](docs/BACKUP_AND_RECOVERY.md)
- [x] V8.1 — job sources: `job_sources` registry, per-posting source/first seen/last seen/run id/source job id, inactive sources rejected, per-run source counts in ETL monitoring, read-only `/api/job-sources`
- [x] V8.2 — deduplication and data quality: match by source job id, then source URL, then content fingerprint (updating last seen); expiry dates and source status (`expires_at`, `active`, never deleted); expired count in ETL monitoring
- [x] V8.3 — advanced job matching: overall match from skills plus experience, location, work mode and salary (match preferences, `/api/match-preferences`), per-dimension breakdown, recommendations ranked by it
- [x] V8.4 — job alert digests: scheduled daily/weekly emails of new matching active jobs with match score and link, one record per alert and job (no repeats), delivery status with retries, emailed-jobs view on Job Alerts
- [x] V8.5 — application intelligence: status history and follow-up dates (V20), per-application V8.3 match with matched/missing skills, funnel, monthly activity, averages and rankings, upcoming/overdue follow-ups on Saved Jobs
- [x] V8.6 — resume optimization: per-job match, skills, keyword and section analysis with grounded suggestions (no rewriting, no invented skills), and version comparison for a job
- [x] V8.7 — interview preparation: job- and resume-grounded questions (technical, role, resume, behavioral), practice sessions with AI-evaluated answers through the existing provider, graceful AI failure with retry, stored history and summaries, Interview Prep page
- [x] V8.8 — career market trends: role and market demand, share, skills, salary, location and work-mode trends over past posting months (earlier vs recent halves, periods shown), gaps for months without data, and a labelled straight-line estimate only with 6+ months
- [x] V8.9 — admin: ADMIN role (bootstrapped from JMIP_ADMIN_EMAILS, never at signup), admin-only /api/admin (overview, data quality, users, job-source switch), Admin Dashboard for admins
- [x] V9.1 — multi-source connectors: JobSourceConnector with the existing file connector and a mock sample-board connector, selected by job parameter or JMIP_ETL_CONNECTOR, through the one common pipeline; each run records its connector (V22)
- [x] V9.2 — personalized feed: GET /api/jobs/personalized ranks jobs by the V8.3 match plus goal, preferred role and skill, freshness and application-history signals with reasons; preference lists (V23); opt-in usePreferences search; For You page
- [x] V9.3 — smart matching 2.0: required vs optional skills from the posting's wording, career-goal, preferred-role and preferred-skill dimensions, missing important skills and reasons, one engine for every match
- [x] V9.4 — resume builder: built resumes as ordinary resume rows (V24) with sections, versions, duplicate, default and delete, two ATS-friendly templates, server-side PDF export, live preview and job optimisation check
- [x] V9.5 — learning plan: learning items per skill prioritised by the career-goal roadmap, own resources, progress and target dates, completion synced to the roadmap, career impact without editing the resume, and a Learning & Skills page
- [x] V9.6 — interview simulation: mock interviews by type (technical, behavioral, mixed), difficulty and question count, one question at a time with skip and end, communication score and a suggested approach per answer, and a final report with technical/behavioral scores and learning-plan recommendations (V26)
- [x] V9.7 — professional portfolio: a profile built from your resume (builder sections reused), skills and career goals, with section visibility, private/public publishing, a unique changeable slug and a read-only public page at /profile/{slug} (V27)
- [x] V9.8 — advanced user analytics: 7D/30D/90D/1Y/All history of job-search activity, the application funnel, resume-version match and skill-gap trends, interview scores and learning progress, with factual insights and no forecasts
- [x] V9.9 — production reliability: analytics N+1 removed (37 → 24 statements), scheduled jobs run once across instances with job ids, durations and a metric, ETL single-run lock and transient-database retry, AI retry setting and status logging, graceful stop and OOM exit in Docker
- [x] V9.10 — release validation: full test, build, migration and Docker smoke run (27 end-to-end checks); interview answer/re-evaluation AI calls now share the assistant's per-minute rate limit
- [x] V9.11 — UI polish: accessible confirm dialogs (focus trap, Escape, focus restore) instead of browser confirms, toasts, section breadcrumb and page titles, skip link, show-password toggles, dashboard quick actions, two-column KPIs on phones, subtle motion that honours reduced-motion
- [x] V9.12 — onboarding: after sign-up and email verification, Welcome → Career profile → Resume → Preferences → Career goal → personalized dashboard, each skippable and resumable; answers go to the existing match preferences, resume parser and career goals (V28 tracks progress; existing accounts marked complete)
- [x] V9.13 — career progress: a transparent 100-point readiness score (profile, resume, skill gap, learning, interviews, job search, portfolio; each capped), progress toward the target role, one-time achievements with a dated timeline and weekly streaks, all computed server-side from existing data (GET /api/career-progress, page /progress)
- [x] V9.14 — job search workspace: Best-match ordering and per-job match explanations from the existing skill match, not-interested (hidden) jobs left out of search and recommendations, saved searches, remembered last search, recently viewed, application priority, a Job Workspace page (follow-ups, recommended, saved, applied, viewed, hidden) and once-per-date follow-up reminder emails through the alert delivery (V29)
- [x] V9.15 — showcase: a public landing page (/welcome; signed-out visits to / start there) with features, the workflow and how JMIP uses your data; dashboard next steps chosen from what is still incomplete in career readiness; navigation regrouped (Overview, Jobs, Resume & profile, Growth, Market insights; ETL under Admin) with Applications and Profile & Preferences entries
- [x] V9.16 — notification center: job-alert matches, follow-up, interview, learning and achievement notifications derived from existing data (each once, by key), read/unread, mark all read, per-kind preferences, header bell with unread count (V30)
- [x] V9.17 — Settings (/settings): profile name, job preferences and notification preferences (existing cards), public-profile privacy, theme, password change and account deletion (both need the current password; /api/account)
- [x] V9.18 — Help & Guidance: searchable Help & FAQ page (/help, header ? link), ? tooltips explaining key metrics, dismissible first-visit tips on key pages, empty states with next-step links, and error panels with a troubleshooting link
- [x] V10.1 — Cloud readiness: ResumeFileStore storage abstraction (JMIP_RESUME_STORAGE_TYPE, local volume), JMIP_DB_SSL_MODE for managed PostgreSQL, Flyway migration verification test, frontend /healthz health check, production env-var guide in docs/PRODUCTION_DEPLOYMENT.md
- [x] V10.2 — Production database & file storage: named, tuned Hikari pools (backend/ETL, env-configurable), strict forward-only Flyway with connect retries, owner-only resume files with a start-up writability check, managed-backup guidance
- [x] V10.3 — CI/CD: GitHub Actions pipeline with backend build/tests, frontend lint/tests/npm audit/build, Docker image builds, a Compose smoke test of the prod stack, and tag-only GHCR image publishing (no deploy); see docs/CI_CD.md
- [x] V10.4 — Sonar code quality: JaCoCo (backend/ETL) and Vitest V8 (frontend) coverage, sonar-project.properties, a CI sonar job that waits for the Quality Gate (80% new-code coverage, A ratings, hotspots reviewed) and blocks the Docker stages; see docs/CI_CD.md
- [x] V10.5 — Production HTTPS & security hardening: trusted-proxy (load balancer) client addresses in nginx, prod secret-strength checks, public/private endpoint and error-leakage tests, security reference in docs/PRODUCTION_DEPLOYMENT.md
- [x] V10.6 — Monitoring & logging: ETL last-run gauges (success, age, duration, records), pool/ETL metric checks, monitoring and alerting reference in docs/PRODUCTION_DEPLOYMENT.md
- [x] V10.7 — Documentation & architecture: docs/ARCHITECTURE.md (system, backend/frontend, ER diagram, ETL flow, auth flow, modules, data flows, integrations, decisions), README architecture, documentation index, prerequisites and Compose quick start

## API

All list endpoints take `page` and `size` (max 100) and return the same envelope:
`content`, `page`, `size`, `totalElements`, `totalPages`, `first`, `last`.

| Endpoint | Notes |
|---|---|
| `GET /api/jobs` | Filters: `q`, `title`, `location`, `company`, `skill`, `employmentType`, `category`, `experience`, `currency` + `salaryMin`/`salaryMax`, `locationStated`, combined with AND. Order with `order` (`newest`, `oldest`, `relevance`, `salary-high`, `salary-low`, `title`, `company`); the older `sort` still works. See [Job search](#job-search-v62) |
| `GET /api/jobs/salary-currencies` | The currencies salaries are stated in, with the range seen in each — the salary filter's options |
| `GET /api/jobs/{id}` | Full posting including description and source, plus `classification` — the category, its confidence and the signals behind it, or `null` when unclassified |
| `GET /api/skills` | Filter: `name` |
| `GET /api/skills/top` | Most in-demand skills, `limit` 1–100, default 10 |
| `GET /api/companies` | Filter: `name` |
| `GET /api/companies/{id}` | Company plus its posting count |
| `GET /api/locations` | Filter: `country` |
| `GET /api/analytics/overview` | Total jobs, companies, skills and locations |
| `GET /api/analytics/skills` | Skills ranked by demand. Filters: `location`, `fromDate`, `toDate`, `title`. Returns `scope.totalJobsInScope`, the denominator behind every percentage |
| `GET /api/analytics/skills/trends` | Skills gaining or losing demand. `months`, `minJobs`, `direction` (RISING/FALLING/STABLE), `limit`. Measured from the snapshot history |
| `GET /api/analytics/job-categories` | Postings per job category, ranked, as a share of the **classified** postings |
| `GET /api/analytics/category/skills` | Top skills in one category, as a share of that category. Takes `category` and `limit` |
| `GET /api/analytics/category/locations` | Where one category's postings are. Takes `category` and `limit` |
| `GET /api/analytics/category/companies` | Who is hiring for one category. Takes `category` and `limit` |
| `GET /api/analytics/experience` | Distribution across 0–2, 2–5, 5–8, 8+ years, plus "not specified" |
| `GET /api/analytics/locations` | Locations ranked by posting count; remote postings have none and are excluded |
| `GET /api/analytics/locations/{id}/skills` | Top skills in one location, as a share of that location |
| `GET /api/analytics/locations/{id}/titles` | Most common job titles in one location |
| `GET /api/analytics/companies` | Companies ranked by posting count |
| `GET /api/analytics/companies/{id}/skills` | Top skills at one company, as a share of that company |
| `GET /api/analytics/titles` | Most common job titles after grouping, with the skills each role asks for |
| `POST /api/assistant/query` | Ask a natural-language question. Body: `question`, optional `resumeId`, `jobId`, `context`. See [AI assistant](#ai-assistant-v5) |
| `GET /api/assistant/intents` | The questions the assistant can answer |

### Resume intelligence (V3)

Upload a PDF resume, have its skills extracted, and compare them against a job posting.

| Endpoint | Purpose |
|---|---|
| `POST /api/resumes` | Upload a PDF (`multipart/form-data`, part name `file`). Stores it, extracts the text and matches skills, then returns the resume with its status |
| `GET /api/resumes/{id}` | Metadata, processing status and extracted skills |
| `GET /api/resumes/{id}/skills` | The extracted skills alone |
| `GET /api/resumes/{resumeId}/match/{jobId}` | Matched skills, the skill gap, and resume-only skills |
| `GET /api/resumes/{resumeId}/recommendations` | Top skill-overlap jobs for a completed resume. Optional `limit` 1–20, default 10; jobs with no listed skills or no overlapping skill are excluded |
| `GET /api/resumes/{resumeId}/career-insights` | Strong skills, skill gaps, high-demand and rising skills for a target `category` (defaults to the top recommendation's category), focus areas and related recommended jobs. `summary=true` adds a V5 AI description of the same figures |
| `GET /api/etl/runs` | ETL run history from Spring Batch, newest first. Optional `job`, `page` (from 0), `size` 1–50 (default 10) |
| `GET /api/etl/runs/latest` | The most recent ETL run; 404 when none has run. Optional `job` |

**Flow.** `PDF → text extraction (PDFBox) → normalisation → skill matching → stored against
the resume`. Extraction runs inside the upload request, so one call returns a final status;
the `processing_status` column still models the whole lifecycle for when it moves to a queue.
A document that cannot be read is recorded as `FAILED` with a reason rather than lost.

**Skill extraction** uses the existing `skills` table as its vocabulary — no second
dictionary. Text is split into words, and every run of up to N consecutive words is
normalised (lower-cased, punctuation and spacing removed) and compared against skills
normalised the same way. So "Spring Boot", "spring boot" and "springboot" are one skill,
while word boundaries stop `Java` matching inside `JavaScript`, `SQL` inside `PostgreSQL`
and `Git` inside `GitHub`. A trailing "js" is also resolved, so "React.js" finds "React".

**Match score** is deliberately simple and deterministic:

```
match percentage = matched job skills / total job skills * 100
```

A job that lists no skills has no denominator and gets no score — `matchPercentage` is
absent and `matchNote` says why, rather than reporting a misleading zero. The score
compares skills only. It is **not** a probability of being hired, and accounts for nothing
about experience, seniority or anything else.

**Skill gap** is the job's skills minus the resume's: `missingSkills`, with
`resumeOnlySkills` as the reverse. Both sides are rows from the same `skills` table, so the
comparison is by id rather than by string.

### Job recommendations (V6.3)

`GET /api/resumes/{resumeId}/recommendations?limit=10` reuses that exact V3 comparison
for each eligible posting. It returns only jobs that list skills and share at least one with
the completed resume, ordered by `matchPercentage` descending (then job id for a stable
tie). The response includes the job, company, location, category, matched skills and skill
gap. It is a transparent skill-overlap ranking, not a recommendation based on AI, machine
learning, experience, salary, or hiring likelihood.

**Configuration.** Files are written to `jmip.resume.storage.directory`
(`JMIP_RESUME_DIR`, default `./data/resumes` — relative, so nothing assumes a machine's
layout). The size cap is `JMIP_RESUME_MAX_FILE_SIZE` (default 5MB), which also sets the
servlet multipart limit so both reject at the same size. The uploaded filename is never
used to build a path: files are named by the resume's UUID.

**Example flow.**

```
curl -F "file=@resume.pdf;type=application/pdf" http://localhost:8080/api/resumes
# -> {"id":"56aedd0e-...","status":"COMPLETED","skills":[{"name":"Java"},...]}

curl http://localhost:8080/api/resumes/56aedd0e-.../match/133
# -> {"matchPercentage":25.0,"totalJobSkills":4,"matchedSkillCount":1,
#     "missingSkills":[{"name":"Airflow"},{"name":"Snowflake"},{"name":"Spark"}]}
```

### Job categories and text processing (V4)

V4 reads the job description as text rather than as an opaque blob, and uses what it finds
to put each posting into a role category. Everything here is deterministic and rule-based:
**no model, no training, no external service**. The same posting always classifies the same
way, and changing an outcome means changing a rule in `etl/src/main/resources/application.yml`.

**Description processing.** Before anything reads a description it is normalised:
Unicode NFKC, HTML block tags turned into line breaks, remaining tags and entities removed,
words rejoined across soft hyphens, bullet markers regularised, runs of whitespace
collapsed. It deliberately does **not** lower-case or strip punctuation — `Node.js`, `C#`
and `C++` lose their identity that way — and it keeps line structure, because a bulleted
requirements list is information. The stored `description` is never modified; normalisation
happens on read, so it is recomputed rather than duplicated into a second column.

**Skill extraction from prose.** The same `skills` table is still the only vocabulary, but
separators are now flexible: one entry matches "Spring Boot", "spring-boot" and
"SpringBoot". Aliases cover the forms that are not spelling variants at all — `JS` for
JavaScript, `RESTful APIs` for REST API, `Postgres` for PostgreSQL. Word boundaries still
stop `Java` matching inside `JavaScript`, `SQL` inside `PostgreSQL` and `Git` inside
`GitHub`.

**Classification** scores every category from three kinds of signal, weighted because they
are not equally telling:

| Signal | Weight | Why |
|---|---|---|
| Title keyword | 5 | The title is the employer naming the role outright |
| Required skill | 2 | A stack is strong evidence, but stacks overlap between roles |
| Description keyword | 1 | A phrase in prose is the weakest of the three; anything can be mentioned in passing |

At most one title signal counts per category: the keyword list carries synonyms on purpose
("backend engineer" and "back end engineer" both match "Backend Engineer"), and scoring
both would count one piece of evidence twice. The highest-scoring category wins; a posting
that matches nothing is recorded as `Other` rather than forced into the least-bad fit.

**Confidence** combines two things, because either alone misleads:

```
coverage  = min(1, winner score / full evidence score)   # how much evidence there was
dominance = winner score / (winner score + runner-up)    # how clearly it won
confidence = coverage × dominance × 100
```

A posting scoring equally as backend and frontend is genuinely ambiguous however much
evidence it carries, and its confidence says so. This is **not** a probability that the
category is correct — it describes the strength and clarity of the evidence, nothing more.

Every signal that contributed is stored in `job_classification_signals`, so
`GET /api/jobs/{id}` can show *why* a posting got its category instead of asserting it.

**Reprocessing.** Rules and the skill dictionary will change, so postings already in the
database can be re-read without re-ingesting anything:

```
JMIP_ETL_JOB=reprocessJobPostings java -jar etl/target/etl-0.0.1-SNAPSHOT.jar
```

It reads stored titles and descriptions, and rewrites skills, classification and signals.
It is idempotent by construction: the rows it owns are deleted before being rewritten, so a
narrowed rule leaves no residue and repeated runs produce the same state rather than
accumulating. Descriptions themselves are never touched.

**Category names are query parameters, not path segments.** One of them contains a slash —
"QA / Automation Engineer" — and an encoded slash inside a path segment is rejected by the
servlet container with a 400 before any handler sees it. The alternative, relaxing that
check application-wide, trades a security control for a URL shape.

### Job search (V6.2)

Job Explorer searches, filters, orders and pages entirely through `GET /api/jobs` — the
same endpoint as before, extended rather than duplicated.

**Text search (`q`).** Matched, case-insensitively, against title, company, city, state,
country, description and skills. Several words all have to match, each anywhere: `java
spring` finds a posting titled "Java Engineer" that lists Spring Boot. Matching is by
substring, as partial search requires — so `engineer` also matches "engineering", which
every synthetic description contains. Relevance ordering is what keeps that useful: title
matches rank first. `%` and `_` in the input are escaped, so `50%` means fifty percent.

**Filters.** All optional, all combined with AND.

| Parameter | Matches |
|---|---|
| `category`, `skill` | Exact V4 category / exact skill name |
| `location`, `company`, `title` | Substring of city/state/country, company name, title |
| `employmentType` | One of the six schema values |
| `experience` | `0-2`, `2-5`, `5-8`, `8+`, `unspecified` — the bands the experience distribution uses, by minimum requirement, half open |
| `currency` + `salaryMin` / `salaryMax` | Postings in that currency whose stated range overlaps the request |
| `locationStated` | `true`: names a place. `false`: does not |

**Salary needs a currency.** The dataset states salaries in eight currencies with no
exchange rates, so a bare "at least 100,000" would compare rupees with dollars. A bound
without `currency` is a 400, not a guess. Postings without a stated salary never match a
salary filter — unknown is not zero.

**There is no remote filter,** because the data cannot support one. The ETL maps "remote",
"work from home" and "unspecified" all to no location, so a remote role and one that simply
did not say look identical. `locationStated=false` asks the only question the data can
answer — whether a place is named — and the UI labels it that way.

**Orderings.** Named, because the useful ones are not plain columns:

| `order` | Notes |
|---|---|
| `newest` (default), `oldest` | Undated postings last in both directions |
| `relevance` | Needs `q`. Per word: 3 points in the title, 2 in the company, 1 in the description; summed, newest first within a score. A deterministic score, not a model. Without `q` it falls back to newest rather than an arbitrary order |
| `salary-high`, `salary-low` | Needs `currency` — 400 otherwise. Unsalaried postings last |
| `title`, `company` | Alphabetical, case-insensitive |

**The URL is the search.** Every filter, the ordering, the page and the page size live in
the query string and nowhere else — no store, no context. That is what makes a search
survive a refresh, work with the back button, and open unchanged from a shared link.
Defaults are left out, so a plain search stays short:

```
/jobs?q=java&category=Backend+Developer&skill=Spring+Boot&experience=2-5&page=2
```

`page` is one-based in the URL and zero-based to the API. Opening a posting carries the
search with it, so its back link returns to the same results. Links written before V6.2
(`?company=`, `?skill=`, `?title=`, …) still open the search they described.

**Requests.** One search request per change, however many filters it touches. Text waits for
a 350–400ms pause before searching; Enter searches at once. Suggestions for skills and
companies use the existing name-filter endpoints after a pause; the location list is fetched
once, on the first keystroke. A production page load makes three requests — the search, the
category list and the currency list. (A development build shows each twice: React StrictMode
mounts effects twice on purpose, in development only.)

**Indexes.** Migration V7 adds two, chosen from the predicates the search actually issues: a
partial `(currency, salary_min)` index for the salary filter, and `experience_min` for the
experience bands. At the current few hundred rows the planner correctly prefers a sequential
scan and uses neither; they are there for the shape of the queries. `employment_type` is not
indexed — six values, one dominant — and neither are the text fields, whose `LIKE '%…%'`
cannot use a btree index at all.

### AI assistant (V5)

Ask the dataset a question in ordinary words. The answer is written by a language model,
but every figure in it is read from PostgreSQL first — the model never sees the database,
never writes a query, and never chooses what to run.

```
POST /api/assistant/query
{ "question": "What are the top skills for Backend Developer jobs?" }
```

```
{
  "question": "...",
  "answer":   "Among Backend Developer postings, Java and Spring Boot appear most often...",
  "intent":   "SKILL_DEMAND",
  "grounded": true,
  "data":     [ { "skill": "Java", "jobCount": 19, "percentageOfJobs": 100.0, "rank": 1 } ],
  "visualization": {
    "type": "BAR", "title": "Top skills in Backend Developer",
    "xAxis": "Skill", "yAxis": "Job count",
    "points": [ { "label": "Java", "value": 19 } ]
  },
  "context": { "previousIntent": "SKILL_DEMAND", "previousEntities": { "jobCategory": "..." } }
}
```

`grounded` is the field to read first. It is true when `data` came from a database query
and false when the reply is the assistant talking about itself — an unsupported question, a
skill that does not exist, a missing resume, an unconfigured provider. An ungrounded answer
makes no claim about the job market, and the UI says so rather than showing an empty table.

`GET /api/assistant/intents` lists the supported intents.

**The pipeline.**

```
question → model proposes an intent → validation → an existing analytics service
         → PostgreSQL → rows → model describes those rows → answer + chart metadata
```

The model appears twice and controls neither step in between. It proposes a name from a
closed enum and some entity names; everything after that is ordinary Java.

**Supported intents.** `SKILL_DEMAND`, `SKILL_TREND`, `JOB_CATEGORY_DEMAND`,
`COMPANY_DEMAND`, `LOCATION_DEMAND`, `JOB_SEARCH`, `SALARY_ANALYSIS`, `SKILL_GAP`,
`RESUME_MATCH`, `SKILL_COMPARISON`, `CATEGORY_COMPARISON`, `GENERAL_JOB_MARKET`. Anything
else is `UNSUPPORTED`, which asks the user to rephrase rather than guessing.

**Validation.** Nothing the model returns is trusted:

| Check | Effect |
|---|---|
| Intent must be a member of the enum | Anything else, including SQL, is `UNSUPPORTED` |
| Skill, category, company, location must resolve to a stored row | Otherwise refused, naming what was not found |
| Limits | Clamped to `max-limit` (25), or `max-job-results` (20) for postings |
| Periods | Clamped to the 2–36 month window the trend service accepts |
| Required entities | A trend with no skill asks which skill, rather than trending everything |

Resolution also canonicalises: a question saying "kubernetes" queries for the stored
`Kubernetes`, and the answer echoes the dataset's own spelling.

**Why there is no AI-generated SQL.** The model returns a name from an enum. That name
selects one of twelve branches, each of which calls a service that existed before the
assistant did — the same services behind `/api/analytics/*`, `/api/jobs` and `/api/resumes`.
There is no query builder, no table name in the model's output path, and no branch it can
add. Entity values are bound parameters of named JPQL queries, and they have already been
matched against stored rows, so injection has nothing to inject into.

**Visualization** metadata is chosen by the backend from the shape of the data — ranked
rows are `BAR`, a series over time is `LINE`, a distribution that genuinely sums to a whole
is `PIE`, postings are `TABLE`, a one-line fact is `NONE`. The frontend maps each name to a
component it already has; the model never sends markup, code, or a chart type.

**Conversation.** One previous turn is carried, and it travels in the response and back in
the next request — there is no server-side session and no Redis. An entity the new question
does not mention is filled in from the previous one, which is what makes "what about
Bengaluru?" mean "the same thing, in Bengaluru". A question meant to widen the scope will
keep the previous filter; starting a new conversation clears it.

**Salary questions** are answered per currency or not at all. This dataset states salaries
in eight currencies with no exchange rates, so a single average would be arithmetic on
incomparable units. Currencies with fewer than `min-salary-sample` postings are dropped, and
a scope with nothing left is told it has insufficient data rather than given an estimate.

**Resume questions** (`SKILL_GAP`, `RESUME_MATCH`) need a resume id from the V3 upload
endpoint, supplied by the caller. Without one the assistant asks for a resume rather than
answering. The matching itself is V3's, unchanged.

**Provider.** One interface, `AiClient`, with two implementations: `anthropic` (the official
SDK) and `stub` (keyword matching, no key, no network — for tests and local development).
Swapping provider is one class and one property. The key is read from configuration, never
logged, and never sent to the browser; every model call is made by the server.

**Failure handling.** A provider timeout, rate limit, outage, malformed reply or absent key
all produce a short sentence and `grounded: false`, never a stack trace and never provider
detail. If the provider fails *after* the rows are retrieved, the rows and the chart are
returned anyway with a plain description — the numbers are the valuable part.

### Reading the numbers

Analytics responses separate three kinds of figure, and name them consistently:

| Kind | Fields | Meaning |
|---|---|---|
| Raw count | `jobCount`, `total*` | Counted directly from stored rows |
| Percentage | `percentageOf*` | A raw count over a stated total, to one decimal place |
| Derived | `rank`, `title` on title analytics | Computed, not stored. `rank` comes from the ordering; a normalised title is one no posting necessarily carries verbatim |

Two denominators are easy to confuse, so they are named apart. `percentageOfJobs` on
`/analytics/skills` is a share of `scope.totalJobsInScope` — the postings the filters
matched, not the whole database. On `/companies/{id}/skills` and `/locations/{id}/skills`
it is a share of that company's or location's own postings.

Experience bands are half open: `[0,2)`, `[2,5)`, `[5,8)`, `[8,∞)`. A posting asking for
exactly 2 years falls in the 2–5 band. Postings that state no requirement form their own
band rather than being dropped, so the bands always sum to the total.

### Skill trends

Trends read from `skill_demand_snapshot`, one row per skill per month holding that month's
job count and the month's total as its own denominator. The history is **derived** — every
row can be recomputed from `jobs.posted_date` — so it is rebuilt wholesale at startup and
again on a daily schedule. Both are configurable under `jmip.analytics.snapshots`.

Three decisions make the numbers mean something:

- **Per period, not cumulative.** A running total only grows, so every skill would look
  like it was rising.
- **Share, not raw count.** If postings double, every count rises with them. Share only
  moves when the mix genuinely shifts.
- **Pooled halves, not endpoints.** The window is split in two and each half pooled, so a
  single quiet month cannot invent a trend.

Change is reported in **percentage points**, not percent: 10% to 15% is +5 points, which
is also a 50% relative rise, and conflating the two is the usual way these charts mislead.
Changes within ±1 point are reported as `STABLE`, and skills below `minJobs` postings in
the window are left out, because one posting becoming two is noise.

Job titles are grouped by stripping seniority prefixes, parenthetical qualifiers and
trailing level markers. Text after a dash is kept, because "Engineer - Payments" and
"Engineer - Search" are different roles. Every group lists the stored titles it folded in,
so the grouping can be checked rather than trusted.

Errors return a consistent body — `timestamp`, `status`, `error`, `message`, `path`, and
`fieldErrors` when validation failed. Unknown id gives 404; a bad filter, an unsortable
field or an out-of-range page size gives 400.

### Job alerts (V7.1)

Saved job searches for the signed-in account. The filters are the job search's own (`keywords`
is the search's `q`); at least one is required, along with a `name` and a `frequency` of `DAILY`
or `WEEKLY`. An account can keep up to 25 alerts. The owner always comes from the session: a
`userId` in the body is ignored, and another account's alert answers 404 like a missing one.
Since V8.4 each active alert emails a digest at its frequency (see below).

| Method | Path | Result |
| --- | --- | --- |
| `POST` | `/api/job-alerts` | 201, the new alert (active) |
| `GET` | `/api/job-alerts` | 200, your alerts, newest first |
| `GET` | `/api/job-alerts/{id}` | 200, one alert |
| `PUT` | `/api/job-alerts/{id}` | 200, name, filters and frequency replaced |
| `PATCH` | `/api/job-alerts/{id}/status` | 200, body `{"active": false}` pauses, `true` resumes |
| `DELETE` | `/api/job-alerts/{id}` | 204 |

```json
{"name": "Java in Berlin", "keywords": "backend", "category": "Software Engineering",
 "location": "Berlin", "experience": "2-5", "skill": "Java", "frequency": "WEEKLY"}
```

### Saved jobs and applications (V7.2)

Bookmark jobs and track each application. Statuses are `SAVED`, `APPLIED`, `INTERVIEW`,
`OFFER`, `REJECTED` and `WITHDRAWN`; any move is allowed so mistakes can be corrected. The
application date is set when a job first leaves `SAVED` and cleared if it goes back. There is
one record per account and job (saving twice returns the same record), up to 500 per account.
Records, statuses and notes are private: another account's record answers 404.

| Method | Path | Result |
| --- | --- | --- |
| `POST` | `/api/jobs/{jobId}/save` | 201 new, or 200 with the existing record |
| `DELETE` | `/api/jobs/{jobId}/save` | 204, saved or not |
| `GET` | `/api/saved-jobs?status=APPLIED` | 200, your saved jobs (status optional), most recently changed first |
| `PATCH` | `/api/saved-jobs/{id}/status` | 200, body `{"status": "INTERVIEW"}` |
| `PATCH` | `/api/saved-jobs/{id}/notes` | 200, body `{"notes": "..."}` (up to 2000 characters; blank clears) |
| `DELETE` | `/api/saved-jobs/{id}` | 204 |

### Resume versions and job analysis (V7.3)

An account can keep up to 20 resumes. Each has a `title` (initially the file name), an optional
`versionLabel` and an `isDefault` flag; the first upload is the default, and deleting the default
(through the existing `DELETE /api/resumes/{id}`) passes it to the newest remaining resume.

| Method | Path | Result |
| --- | --- | --- |
| `GET` | `/api/resumes` | 200, your resumes, newest first |
| `PATCH` | `/api/resumes/{id}` | 200, body `{"title": "Backend CV", "versionLabel": "v2"}` |
| `PUT` | `/api/resumes/{id}/default` | 200, this resume becomes the default |
| `GET` | `/api/resumes/{resumeId}/analyze-job/{jobId}` | 200, the V3 match plus experience and suggestions |
| `GET` | `/api/resumes/compare?resumeId1=&resumeId2=` | 200, skills added, removed and common, and differing metadata |

The analysis uses the same deterministic match as `/match/{jobId}`; its suggestions come only
from the matched and missing skills and the posting's stated experience, and it makes no claim
about the chance of being hired. The comparison reads the skills extracted at upload. Every
resume id must belong to the signed-in account; anything else is a 404.

### Career goals and skill roadmap (V7.4)

A goal names a `targetRole`, a `targetCategory` (an existing V4 job category), and optionally a
`targetLocation`, a `targetExperience` (`0-2`, `2-5`, `5-8`, `8+`) and up to 20 `targetSkills`
(existing skill names). Up to 20 goals per account; another account's goal is a 404.

| Method | Path | Result |
| --- | --- | --- |
| `POST` | `/api/career-goals` | 201, the new goal (`ACTIVE`) |
| `GET` | `/api/career-goals?status=ACTIVE` | 200, your goals (status optional) |
| `GET` / `PUT` / `DELETE` | `/api/career-goals/{id}` | 200 / 200 / 204 |
| `PATCH` | `/api/career-goals/{id}/status` | 200, body `{"status": "ARCHIVED"}` |
| `GET` | `/api/career-goals/{id}/roadmap?resumeId=` | 200, the roadmap (default resume unless `resumeId` names another of yours) |
| `PUT` | `/api/career-goals/{id}/roadmap/skills/{skillId}` | 200, body `{"status": "IN_PROGRESS"}` |

The roadmap is computed on request. Market skills are the category's top 10 skills from the
category analytics (the same figures as Job Categories); current skills are the resume's. Skills
not yet on the resume are ordered by the career-insights focus-area rule: rising in recent
postings first (biggest rise first), then by demand rank, then skills the user chose. Progress is
the share of roadmap skills on the resume or marked `COMPLETED`. Skills are not split into
difficulty stages because no data says how advanced a skill is.

### Market intelligence (V7.5)

`GET /api/market/salary`, `/locations`, `/remote`, `/companies` and `/skills` all take the same
optional filters: `category` (exact job category), `location` (city, state or country text, as in
the job search), `experience` (`0-2`, `2-5`, `5-8`, `8+`, `unspecified`) and `months` (the last N
posting months of the data, counted back from the newest posting, not from today). Every
response has a `scope`: how many postings it covers, their posting months and the newest posting
date in the data, plus `notes` wherever data is missing or thin.

- **Salary**: averages of the stated minimum and maximum, the lowest and highest, per currency and
  per category and currency; never converted or combined. Figures from fewer than 3 postings are
  marked `reliable: false`. Postings without a salary are left out, not counted as zero.
- **Locations**: postings per location, and how many state no location.
- **Remote**: postings carry no work-mode field, so it is read from the description: "hybrid" means
  HYBRID, otherwise "remote" means REMOTE, otherwise "on-site"/"onsite"/"in office" means ON_SITE,
  and anything else is NOT_STATED.
- **Companies**: postings per company in JMIP's dataset (not a company's total hiring), with a
  monthly series for the top five.
- **Skills**: the most requested skills among the filtered postings. The earlier-vs-recent trend is
  the V6.1 stored skill history (`skill_demand_snapshot`), which covers all postings, so it is
  omitted, with a note, when a category, location or experience filter is set.

Monthly series group postings by `posted_date`, like the skill snapshot; undated postings count in
totals only. Nothing is forecast.

### AI career copilot (V7.6)

`POST /api/assistant/query` also answers questions about the signed-in user's own data, through
the same closed pipeline: question → intent (a fixed list) → validated call to an existing service
→ rows → prose that may only repeat those rows.

| Intent | Example | Service |
| --- | --- | --- |
| `MY_SKILL_GAP` | What skills am I missing for my target role? | V7.4 roadmap of the active goal |
| `NEXT_SKILLS` | What skills should I focus on next? | roadmap, or V6.4 focus areas without a goal |
| `TARGET_ROLE_SKILLS` | Which skills are most requested for my target role? | V7.5 market skills for the goal's category |
| `TARGET_ROLE_DEMAND` | How is demand for my target role changing? | V7.5 postings per posting month |
| `MY_JOB_MATCHES` | Which jobs match my resume? | V6.3 recommendations |
| `RESUME_IMPROVEMENT` | What should I improve in my resume? | V7.3 job analysis (with a job) or V6.4 gaps |
| `APPLICATION_PROGRESS` | Show me my application progress. | V7.2 saved jobs per status |
| `SAVED_JOB_PRIORITY` | Which saved jobs should I prioritize? | open saved jobs by V3 resume match |

"How does my resume compare with this job?" is the existing `RESUME_MATCH` with a `jobId` (Job
Details → Ask the copilot). When no `resumeId` is sent, resume questions use the account's default
resume. None of these intents takes a user from the question or the model: every service reads
the account from the session, and a `resumeId` from the request is ownership-checked as elsewhere.
Rows exclude application notes and posting descriptions, so stored free text never reaches the
model. Prompts are `intent-extraction-v2.txt` and `answer-generation-v2.txt`; the offline `stub`
provider routes the example questions by keyword.

### Personal career dashboard (V7.7)

`GET /api/dashboard` (optional `?goalId=` for one of your goals) returns the **My Career** page in
one response, with a section per feature, each assembled from the service that owns it:

| Section | Source |
| --- | --- |
| `resume` | current resume (the default if processed, else the newest processed), match summary over the V6.3 recommendations, roadmap skills missing for the goal |
| `skills` | resume skills, and roadmap skills in progress / completed (V7.4) |
| `recommendations` | top V6.3 matches, count and average match |
| `applications` | V7.2 counts per status, a SAVED → OFFER funnel by current status, recently updated jobs (no notes) |
| `careerGoal` | the most recently changed active goal (or `goalId`), roadmap progress and highest-priority skills |
| `market` | V7.5 skills, locations, work modes, salaries and companies for the goal's category, or all postings without a goal |

Every section has `available` and, when empty, a `note` saying what is missing. Every personal
figure comes from services that read the account from the session; another account's `goalId` is
a 404.

### Observability (V7.8)

- **Logs.** Every request gets an id (`X-Request-Id`, reused from a proxy when it looks like one)
  that appears on every log line it causes and on the response. One summary line per request
  (`method=… path=… status=… durationMs=…`) is WARN for server errors, INFO when slower than
  `JMIP_SLOW_REQUEST_THRESHOLD` (1s) and DEBUG otherwise. Query strings, headers, cookies and
  bodies are never logged. Under `prod`, backend and ETL log one JSON object per line
  (`JMIP_LOG_FORMAT`: `ecs`, `logstash` or `gelf`). ETL runs log their execution id on every
  line and finish with `etl.run executionId=… status=… durationMs=… read=… rejected=…`.
- **Health.** `/actuator/health` (overall), `/actuator/health/liveness` (the application),
  `/actuator/health/readiness` (application and database) and `/actuator/health/database`. Public;
  status only under `prod`.
- **Metrics.** `/actuator/metrics` needs HTTP Basic with `JMIP_METRICS_USERNAME` /
  `JMIP_METRICS_PASSWORD` (at least 16 characters); with no password it is closed to everyone.
  It includes `http.server.requests` (count, time, status and outcome per endpoint, so errors
  are `outcome:SERVER_ERROR`), JVM, `hikaricp.connections.*` and more. Nothing else in Actuator
  (env, configprops, beans, heapdump) is exposed, and nginx does not proxy `/actuator/metrics`.

```bash
curl -u "metrics:$JMIP_METRICS_PASSWORD" "http://localhost:8080/actuator/metrics/http.server.requests?tag=outcome:SERVER_ERROR"
```

### Job sources (V8.1)

Every posting belongs to a **job source**, keyed by the `source` label its record carries
(`jobs.source`). The ETL registers a source the first time it meets it, typed by the feed
(`FILE_JSON`, `FILE_CSV`; `API` is reserved for future connectors). A database trigger links
postings inserted by any other path, so `jobs.source_id` is never empty.

Each posting records `source_job_id` (the feed's own id: JSON `source_job_id`, CSV
`sourceJobId`/`jobId`/`externalId`/`postingId`, unique per source), `first_seen_at`,
`last_seen_at` and `last_seen_run_id`. A posting met again by a later run keeps its first-seen time
and has its last-seen time and run updated. Records from a source set inactive
(`UPDATE job_sources SET active = false WHERE code = '...'`) are rejected with the reason
`source '...' is inactive`.

| Method | Path | Returns |
|---|---|---|
| GET | `/api/job-sources` | All sources: code, name, type, active, created, last ingested, last run, posting count |
| GET | `/api/job-sources/{id}` | One source with its 10 latest runs (Spring Batch status, times, feed name, loaded, seen again) |

Both are read-only and signed-in only; nothing in the API changes what is ingested. ETL runs
(`/api/etl/runs`, `/api/etl/runs/latest`) now also show `feedName` (file name only, never the path),
`feedType` and `sources` (new and seen-again postings per source). The ETL logs one
`etl.sources` line per run.

### Deduplication and job status (V8.2)

A record is the same posting as an existing job when, in this order, it has the same
**source job id** in the same source, the same **source URL** in the same source, or the same
**content fingerprint** (normalised title, company, location and posted date; a shared title
alone is never a duplicate). A match creates no row: it updates the job's `last_seen_at`,
`last_seen_run_id` and, when the source gives one, `expires_at`, and counts as a duplicate.

Required fields are title, company, description and source; missing optional fields are
fine. A present but unreadable value (date, salary, status) or an expiry date before the
posted date rejects the record with that reason in `etl_rejected_record`.

Feeds may give an expiry date (JSON `expires_at`; CSV `expiresAt`/`expiryDate`/`validThrough`/`closingDate`)
and a status (JSON `status`; CSV `status`/`jobStatus`: open, active, live, true / closed, expired,
filled, inactive, false). At the end of every run, jobs past their expiry date are marked
`active = false`. A posting its source marks closed is marked inactive at once, and becomes active
again if the source lists it as open later. Only these source-given signals count: a posting
missing from one feed is not treated as closed. Jobs are never deleted, and job search is unchanged.
ETL runs report the count as `expired` next to read, valid (processed), loaded, duplicates and rejected.

### Advanced job matching (V8.3)

`GET /api/resumes/{id}/match/{jobId}` and `GET /api/resumes/{id}/recommendations` now include a
`breakdown`: an `overallPercentage` and, per dimension, a `status` (MATCH, PARTIAL, NO_MATCH,
UNAVAILABLE), a `score`, its `weight` and a `detail` saying why. Recommendations carry
`overallMatchPercentage` and are ranked by it (the best skill matches form the candidate pool).

| Dimension | Weight | Rule |
|---|---|---|
| Skills | 60 | Share of the job's listed skills on the resume (the V3 score) |
| Experience | 15 | In range 100; 25 points off per year short of the minimum; above the maximum 75 |
| Location | 10 | Preferred city, or a whole state/country, 100; same country, other city, 50 |
| Work mode | 10 | From the posting's words, with the V7.5 market rule; same 100; hybrid vs other 50 |
| Salary | 5 | Top of the stated range at or above your minimum, same currency only (never converted) |

The overall score is the weighted average of the dimensions that are available. A dimension
is unavailable when you have not set that preference or the posting does not state it, so with
no preferences the overall score is exactly the skill match and the ranking is unchanged. It is a
compatibility measure, not an interview or hiring probability.

Preferences are the signed-in user's own (`GET`/`PUT /api/match-preferences`: `yearsExperience`,
`preferredLocation`, `workMode` REMOTE/HYBRID/ON_SITE, `minSalary` with `salaryCurrency`), set on
the Resume Intelligence page.

### Job alert digests (V8.4)

A scheduled pass in the API (`JMIP_ALERTS_CRON`, hourly by default; never part of the ETL) takes
each **active** alert of a **verified** account whose frequency has come round (daily: 23 hours
since the last pass, weekly: 167). It finds active postings **first seen** since that pass that match
the alert's filters (the Job Explorer's own), scores each with the V8.3 overall match against the
user's current resume and preferences, and emails one plain-text digest: title, company,
location and work mode, match score, and a link to the job in JMIP.

Every job is recorded once per alert in `job_alert_notifications` (unique on alert and job), so it
is never sent twice. Each record has a status (PENDING, SENT with `sent_at`, FAILED); a failed
digest is retried on later passes up to 3 attempts, recording only the error type. Pausing an
alert stops its digests; resuming it starts from that moment. `GET /api/job-alerts/{id}/notifications`
(owner only) lists what an alert recorded, shown under "Emailed jobs" on the Job Alerts page.

Delivery is `JMIP_ALERTS_DELIVERY=log` by default: nothing is emailed, a line is logged without
the recipient. `smtp` uses the `JMIP_MAIL_*` server and credentials; `JMIP_APP_URL` sets the
link base. The email carries no ids, tokens or account data beyond the user's name.

### Application intelligence (V8.5)

From the signed-in user's own tracked jobs only (the account comes from the session; no user id is
accepted). "Applications" are tracked jobs that left Saved.

| Method | Path | Returns |
|---|---|---|
| GET | `/api/applications` | Every tracked job: status, saved/applied dates, notes, follow-up, and the V8.3 match of the current resume (overall %, skill %, matched and missing skills) |
| GET | `/api/applications/insights` | Totals, status counts, funnel (applied, ever interviewed, ever offered; rates from 3 applications), activity by month, average match, top missing skills, companies and roles, upcoming and overdue follow-ups |
| PATCH | `/api/saved-jobs/{id}/follow-up` | Sets `followUpOn` (a date) and an optional `note` (200 characters); no date clears both |

Every status change is recorded in `saved_job_status_events`, so a job rejected after an interview
still counts as interviewed. Existing rows were backfilled from what they recorded: saved and applied
times exactly, a later stage at the row's last update, flagged and left out of the monthly activity.
A figure without enough data is absent with a note saying what it needs. Follow-ups are shown on the
Saved Jobs page; no reminder emails are sent.

### Resume optimization (V8.6)

| Method | Path | Returns |
|---|---|---|
| GET | `/api/resumes/{resumeId}/optimize/{jobId}` | The V8.3 match and breakdown, matched and missing skills, required experience and the gap, the posting's terms present in and missing from the resume, heavily repeated terms, recognised section headings, and suggestions by area (skills, keywords, sections, experience, alignment) |
| GET | `/api/resumes/compare-for-job?resumeId1=&resumeId2=&jobId=` | Two versions against one job: skills added and removed, each version's match and the change, posting terms gained and lost |

Keywords are the posting's own terms (its title, and words its description uses at least twice),
without stopwords or skills, which the skill comparison already covers. Sections are headings on
their own line (Summary, Skills, Experience, Projects, Education, Certifications). Suggestions come only
from the resume and the posting, and each says to use a term only if it truthfully describes the
user's experience; a term repeated ten or more times is flagged, never encouraged. Nothing is
written into the resume, and no AI is used. Without stored resume text, keywords and sections are
marked unavailable. Resumes are owner-checked: another account's answers 404. On Resume
Intelligence, after a match, "7. Optimize resume" shows all of this.

### Interview preparation (V8.7)

| Method | Path | Result |
|---|---|---|
| POST | `/api/interviews` | 201, a new session for `jobId` (and optionally one of your `resumeId`s; default: your current processed resume) with its questions |
| GET | `/api/interviews` | Your sessions, newest first (no questions) |
| GET | `/api/interviews/{id}` | One session with questions, answers and feedback |
| POST | `/api/interviews/{id}/questions/{position}/answer` | Saves `answer` (up to 4000 characters) and evaluates it |
| POST | `/api/interviews/{id}/questions/{position}/evaluate` | Evaluates the saved answer again (at most 3 evaluations per question) |
| POST | `/api/interviews/{id}/complete` | Closes the session with a summary |

**Questions** are generated without AI, from the job and resume data only: technical questions on job
skills the resume shows, an honest question on a job skill it does not show, role questions on the
posting's own terms (V8.6) and experience requirement, a resume question on a skill the resume lists
that the job does not, and two behavioral questions. Up to 8 per session; nothing is invented.

**Evaluation** uses the configured AI provider (`jmip.ai.provider`; the stub in tests) with the
`interview-evaluation-v1` prompt. The model receives only the job's title, company and skills, the
resume's skill names, the question and the answer, marked as data; it returns scores (relevance,
completeness, clarity, technical correctness where it applies, 1 to 5) with short strengths and
improvements as JSON. It runs nothing and sees no database, schema or resume text. A failed call or
an unreadable reply leaves the answer saved with feedback UNAVAILABLE, to try again. The completion
summary is computed from the stored scores, without AI.

Sessions are the signed-in user's own: another account's session or resume answers 404.

### Career market trends (V8.8)

`GET /api/market/trends?category=&months=` (`months` 2 to 36, default 12; `category` is a job category,
i.e. the role; absent means all roles). Built on the V7.5 market queries and filter; the period ends at
the newest posting in the data, not today.

- **History.** Postings per posting month, with the role's share of all postings. A month in the period
  with no postings in JMIP at all is listed in `monthsWithoutData`, drawn as a gap and left out of every
  calculation, never read as zero.
- **Trends.** The covered months are split in two (the later half, rounded up, is recent) and compared:
  counts and salaries by percentage change (under 5% is STABLE), shares in percentage points with the
  skill trends' one-point stable band. Every trend states the two periods it compares, or
  `INSUFFICIENT_DATA` with why (fewer than 5 postings, or fewer than two months).
- **Role insights.** Growing and declining skills (all roles: the stored monthly skill history; one role:
  that role's postings, same rule), the salary midpoint in the currency most postings state (never
  converted; needs 3 postings with a salary in each period), the top locations and the remote, hybrid and
  on-site shares.
- **Estimate.** Only from 6 months with data and 10 postings: an ordinary least-squares line through
  monthly postings (months placed by calendar position, so gaps do not bend it), extended 3 months,
  never below zero, labelled "Estimate, not a prediction", with the slope and R². No probability,
  hiring prediction, model or external data. Otherwise: "Insufficient data".
- If the newest posting is more than two months old, the response says the figures describe the
  market up to then, not today.

On the Market Intelligence page, "Career market trends" has its own role and time-range filters.

### Admin (V8.9)

**Becoming an admin.** Set `JMIP_ADMIN_EMAILS` (comma-separated) and restart: at startup every account with
one of those emails that has verified it is made ADMIN. Signup always creates USER; a `role` in the
request is ignored. Nothing demotes an admin automatically. An admin keeps every USER permission, and a
role change takes effect at the next sign-in.

| Method | Path | Returns |
|---|---|---|
| GET | `/api/admin/overview` | Users (total, verified, admins, new and active in 30 days), jobs (total, active, inactive, new in 7/30 days), latest and recent ETL runs, failed runs in 30 days, job sources, Actuator health statuses, last-7-day activity counts |
| GET | `/api/admin/data-quality` | V8.2 totals over all ingestion runs (read, valid, rejected, loaded, duplicates, expired), jobs now (active, expired by date, closed by source), top rejection reasons, per-source figures |
| GET | `/api/admin/users?q=&role=&verified=&page=&size=` | Accounts, newest first, searchable by name or email |
| GET | `/api/admin/users/{id}` | One account's metadata and how many resumes, saved jobs, applications, alerts, interview sessions and goals it has |
| PATCH | `/api/admin/job-sources/{id}` | `{"active": false}` stops the ETL loading that source (V8.1); stored jobs stay |

Every `/api/admin/**` path requires ADMIN in the security configuration: a USER gets 403, a signed-out
caller 401, and CSRF applies as everywhere. Responses carry counts and account metadata only, never a
password or hash, a session, a verification code, resume contents or a rejected record's raw input.
"Active users" means accounts that saved, uploaded or changed something in the last 30 days, because
sign-ins are not recorded. Accounts cannot be deactivated: the user model has no active flag. There is
no SQL or command execution. The Admin Dashboard page (`/admin`) appears in the menu for admins only.

### Personalized feed (V9.2)

`GET /api/jobs/personalized?limit=` (1 to 50, default 20) ranks the signed-in user's jobs. The score is the
V8.3 match with the current resume and match preferences; on top of it fixed, listed signals order the
feed: the active career goal's category (+10), a preferred role (+8), preferred skills (+3 each, up to +9),
first seen in the last 7 days (+5), and a category the user applied to before (+4). Each job returns its
match, priority, breakdown and reasons such as "Strong skill match (3 of 4 skills)", "Matches target role",
"Preferred location", "Remote preference matched" or "Missing 2 required skills". Jobs already applied to,
inactive jobs, and excluded companies or locations are left out; saved jobs stay, marked. It is a
ranking aid, not a hiring or interview prediction.

Preferences extend the V8.3 `/api/match-preferences` resource with `preferredCategories`, `preferredSkills`,
`excludedCompanies` and `excludedLocations` (up to 20 each). `GET /api/jobs?usePreferences=true` fills an
empty category and location from them and leaves out excluded companies; filters in the request win, and
without the flag search is unchanged. Everything is the signed-in user's own. The For You page shows the feed
with Save and Mark applied, and Job Explorer has a "Use my preferences" option.

### Smart matching 2.0 (V9.3)

The one matching engine (`JobMatchScorer`) now also weighs skills by importance and adds the career goal and
the V9.2 preferences. Recommendations, the personalized feed, resume analysis and optimisation, interview
preparation, application intelligence and alert digests all use it.

- **Required vs optional skills.** Postings carry no required/optional flag, so the posting's own wording
  decides: a listed skill mentioned only in a sentence or a section headed with "nice to have", "a plus",
  "bonus", "preferred", "desirable", "optional" or "advantageous" is optional; every other listed skill,
  including one the text never names, is required. The skill score is the share of required skills the
  resume shows; optional ones are listed but never lower it (when all are optional, all count).
- **New dimensions** (unavailable, and not counted, when their data is missing): career goal (weight 10:
  same category as the active goal, or the share of the job's skills on its roadmap), preferred role
  (5) and preferred skills (5), next to skills (60), experience (15), location (10), work mode (10) and
  salary (5). The overall score is the weighted average of the available dimensions.
- **Explanation.** The breakdown adds `careerGoal`, `role`, `preferredSkills`, `missingRequiredSkills`,
  `optionalSkills` and `reasons` (each dimension's contribution in words). The feed's goal, role and skill
  bonuses moved into the engine; only freshness and application history are still added there.
- Experience still comes only from the years in match preferences; resumes do not state it reliably, so it
  is unavailable rather than guessed. It is a compatibility measure, not a hiring prediction.

### Resume builder (V9.4)

Write a resume section by section (personal information, summary, skills, experience, education, projects,
certifications, achievements and named extra sections), preview it live and export it as a PDF. A built
resume is an ordinary resume row (`source = BUILDER`, migration V24): versions, rename (`PATCH /api/resumes/{id}`),
default (`PUT /api/resumes/{id}/default`) and delete (`DELETE /api/resumes/{id}`) work as for uploads. On every
save its text is regenerated from the sections and its skills extracted by the same matcher uploads use, so
matching, recommendations, analysis, V8.6 optimisation and interview preparation work on it unchanged.
Its sections are stored as JSON, encrypted at rest like extracted text; nothing is ever added or rewritten.

| Method | Path | Result |
|---|---|---|
| POST | `/api/resumes/builder` | 201, a new built resume (`title`, `versionLabel`, optional `content`; blank starts from the account's name) |
| GET | `/api/resumes/{id}/builder` | Its sections |
| PUT | `/api/resumes/{id}/builder` | Saves the sections (validated: names, titles and sizes) |
| POST | `/api/resumes/{id}/duplicate` | 201, a copy ("Copy of …", not the default) |
| GET | `/api/resumes/{id}/builder/pdf` | The PDF, as a download named after the title |

PDFs are drawn server-side with PDFBox in one of two ATS-friendly templates (Classic: serif, centred header,
ruled headings; Modern: sans-serif, left-aligned, coloured headings): selectable single-column text, standard
fonts, A4, entries kept on one page when they fit and headings kept with their first entry. Characters the
standard fonts cannot print (such as emoji) become "?". The document carries only the user's content (its
title is the person's name): no ids, export dates or product names. Only the owner can read, edit, duplicate,
export or delete a built resume. The Resume Builder page lists built resumes and has section navigation, a
live preview, template choice, unsaved-change tracking, validation, PDF export and a check against a saved job
using V8.6 optimisation.

### Learning plan (V9.5)

The **Learning & Skills** page (`/learning`) turns the active career goal's roadmap (V7.4) into a learning plan.

- **Priority skills** are the roadmap's own ranking (market demand plus skills you added to the goal, minus what your resume already shows); the top three are suggested HIGH, the next four MEDIUM, the rest LOW. Without an active goal you can still plan any skill by name.
- **Items** have a skill, topic, priority, status (`NOT_STARTED`, `IN_PROGRESS`, `COMPLETED`), progress 0–100, optional target date and notes. Completing an item sets progress to 100 and marks the skill COMPLETED on the goal's roadmap; reopening moves it back.
- **Resources** are links you add yourself (title, http(s) URL, type `COURSE`, `VIDEO`, `ARTICLE`, `DOCUMENTATION`, `PROJECT` or `OTHER`, notes). JMIP does not suggest or fetch courses.
- **Career impact** shows roadmap coverage, completed skills not yet on your resume and how many of your saved jobs ask for them. Job matches read the resume, so a learned skill counts once you add it there; JMIP never edits the resume for you.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/learning` | Priorities, items with resources, progress and impact |
| POST | `/api/learning/items` | Plan a skill (`skillId` or `skillName`, `topic`, optional `priority`, `targetDate`, `notes`) |
| PUT | `/api/learning/items/{id}` | Edit topic, priority, progress, target date, notes |
| PATCH | `/api/learning/items/{id}/status` | Start, complete or reopen |
| DELETE | `/api/learning/items/{id}` | Remove an item and its resources |
| POST | `/api/learning/items/{id}/resources` | Add a resource |
| PUT / DELETE | `/api/learning/resources/{id}` | Edit or remove a resource |

Everything is the signed-in user's own: no endpoint takes a user id, and another user's items or resources return 404. Tables: `learning_items`, `learning_resources` (migration V25).

### Interview simulation (V9.6)

Interview Preparation (`/interview-prep`) now runs a mock interview on top of the V8.7 sessions.

- **Setup:** a saved job, one of your processed resumes (default: the current one), type `TECHNICAL`, `BEHAVIORAL` or `MIXED`, difficulty `EASY`, `MEDIUM` or `HARD`, and 3–10 questions. Without a type or count the V8.7 mixed set is used. Questions are still generated without AI from the job's skills and terms and the resume's skills; HARD questions also ask for trade-offs and how success is measured.
- **Flow:** one question at a time with a progress bar; submit an answer to get feedback, skip an unanswered question, or end the interview at any time.
- **Evaluation** (prompt `interview-evaluation-v2.txt`): relevance, completeness, clarity, technical correctness where it applies and communication (1–5), strengths, improvements and a suggested approach that describes structure and never writes experience for you. Replies that are not the expected JSON, or scores out of range, are treated as unavailable feedback; the answer is kept.
- **Report** (completed sessions): overall, technical (technical and resume questions) and behavioral (behavioral and role questions) scores, strong areas (4+), weak areas (under 3), topics to prepare (weak, skipped or unanswered) and matching items or priority skills from the V9.5 learning plan. The plan is only read; adding a skill to it is your own action.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/interviews` | Start: `jobId`, optional `resumeId`, `interviewType`, `difficulty`, `questionCount` |
| POST | `/api/interviews/{id}/questions/{position}/skip` | Skip an unanswered question |
| POST | `/api/interviews/{id}/complete` | End the interview; the detail view then has `report` |
| GET | `/api/interviews` | History with job, type, difficulty, date, score and summary |

Sessions stay owner-only (404 for other accounts). The AI sees only the job's title, company and skills, the resume's skill names, the question and the answer, and everything after `DATA:` is treated as data. Migration V26 adds the session type and difficulty and the question's communication score, suggested approach and skip time.

### Professional portfolio (V9.7)

The **Portfolio** page (`/portfolio`) builds a professional profile: display name, headline, about, skills, experience, education, projects, certifications, achievements and links (the V9.4 resume builder's section shapes), plus the target roles of your active career goals when you choose to show them.

- **Import** fills the editor from one of your resumes: a built resume brings all its sections, an uploaded one its skills; skills completed in the V9.5 learning plan are offered separately. Nothing is saved until you save, and the resume's email and phone are never copied.
- **Visibility:** the profile is `PRIVATE` until you publish it, and each section can be hidden. The live preview shows exactly what a visitor would see.
- **Address:** a unique slug (3–50 lowercase letters, digits and hyphens, a few reserved words refused), made from your name on creation and changeable later; the old address then stops working. A taken slug answers 409.
- **Public page:** `/profile/{slug}` (API `GET /api/public/profiles/{slug}`, no sign-in) shows a published profile's visible sections only. It never includes an email, phone, account id, applications, alerts, interviews or learning data; a private or unknown slug is the same 404.

| Method | Path | Purpose |
| --- | --- | --- |
| GET / POST / PUT / DELETE | `/api/portfolio` | Your portfolio (404 until created); create, update, delete |
| PATCH | `/api/portfolio/slug` | Change the public address |
| POST | `/api/portfolio/publish`, `/api/portfolio/unpublish` | Make it public or private |
| GET | `/api/portfolio/preview` | The public view, published or not |
| GET | `/api/portfolio/import?resumeId=` | A draft from one of your resumes |
| GET | `/api/public/profiles/{slug}` | A published profile (public) |

Web addresses must be http or https; control and text-direction characters are removed, and the page renders everything as text. Table `portfolios` (migration V27).

### My analytics (V9.8)

**My Analytics** (`/my-analytics`, API `GET /api/dashboard/analytics?range=7D|30D|90D|1Y|ALL`, default 30D) shows how things changed over time; the V7.7 dashboard still shows where they stand. Everything is the signed-in user's own and computed from data JMIP already keeps; no table was added.

- **Activity:** jobs saved, applied, moved to interview and to offer per day (7D, 30D), week (90D) or month (1Y), from the V8.5 status history.
- **Funnel:** applications started in the range (first applied, interview or offer change) and how many reached an interview and an offer, with conversion rates when there is a base.
- **Resume and skills:** each resume version from the range scored now against all saved jobs with the existing skill match (average match, skills, missing skills), and the skills the current resume lacks most often among the range's saved jobs. Past match scores are not stored, so this is labelled as computed now.
- **Interviews and learning:** completed V9.6 interviews with overall, technical and behavioral scores; V9.5 learning items started and completed, completion rate and roadmap coverage when there is an active goal. Portfolio status and dates (V9.7).
- **Insights** are fixed sentences filled from these figures (application volume against the previous equal period, funnel conversion, match change between resume versions, the most frequent missing skill, interview score first to latest, learning completion). A figure that cannot be computed is absent, never zero or estimated.

### Production reliability (V9.9)

- **Query counts** (`QueryCountIntegrationTest` prints them) now cover the V8/V9 endpoints too. My Analytics issued one query per completed interview and matched the current resume twice: 37 statements, now 24, independent of history size. The other newer endpoints were already bounded and now have ceilings.
- **Scheduled jobs** (job alerts, skill snapshots, resume retention) go through `ScheduledJobRunner`: a PostgreSQL advisory lock per job means a second backend instance skips instead of repeating the work (or the alert emails); a crashed instance releases its lock with its connection. Each run logs `scheduled.job name=… status=COMPLETED|FAILED|SKIPPED durationMs=…` with a `jobId` in every line, records the `jmip.scheduled.job` timer, and never throws at the scheduler. Two scheduler threads; on shutdown a running job may finish (30 s).
- **ETL:** one run at a time (advisory lock `jmip:etl`; a concurrent run fails before reading and can simply be started again). Transient database failures (deadlock, lock timeout, dropped connection) are retried up to 3 attempts with 0.5–5 s backoff; rejections are still skipped and never retried, and any other failure fails the step for a normal restart.
- **AI:** `AI_MAX_RETRIES` (default 2) is passed to the SDK, which retries timeouts, 408/409/429 and 5xx with backoff and never other 4xx; failures log the provider status code, never the request.
- **Docker:** `-XX:+ExitOnOutOfMemoryError` so an out-of-memory JVM restarts instead of limping; backend `stop_grace_period: 40s` (longer than the 20 s graceful shutdown) and `mem_limit: ${JMIP_BACKEND_MEMORY:-1g}`, which the heap percentage follows.
- **Checked, unchanged:** no new index (every new query hits an existing one: status events by user and time, sessions by user, questions by session, learning items by user); no cache (market endpoints measure 3 statements and ~20 ms; user data is per user and changes often); resume processing stays in the upload request (bounded by the upload size limit, and the user needs the result), alert emails already run on the scheduler, AI calls keep their timeout.

### Backup and recovery

What to back up (database, resume files, secrets), how, and how to restore: see
[docs/BACKUP_AND_RECOVERY.md](docs/BACKUP_AND_RECOVERY.md). Keep `JMIP_RESUME_ENCRYPTION_KEY` safe: without it
restored resumes cannot be read.

## Running with Docker (V6.6)

```bash
cp .env.example .env        # set JMIP_DB_PASSWORD (required, no default)
docker compose up -d --build
docker compose --profile etl run --rm etl    # load etl/data/raw/synthetic-job-postings-v1.json
```

Open http://localhost:3000. nginx serves the built frontend and forwards `/api` to the
backend, so the browser talks to one origin. The API is also on http://localhost:8080 and
PostgreSQL on `127.0.0.1:5434`. All ports are configurable in `.env`. Data persists in the
`postgres-data` and `resume-data` volumes; `docker compose down -v` deletes them.
Flyway migrates on backend and ETL startup, as it does outside Docker.

### HTTPS deployment (V6.10.6)

Put `fullchain.pem` and `privkey.pem` (for example from certbot) in a directory on the host and set
in `.env`: `JMIP_PUBLIC_URL=https://your-host`, `JMIP_CORS_ALLOWED_ORIGINS=https://your-host` and
`JMIP_TLS_CERT_DIR=/path/to/that/directory`. Then:

```bash
docker compose -f docker-compose.yml -f docker-compose.https.yml up -d --build
```

nginx listens on 80 and 443 only: port 80 answers ACME challenges from `/var/www/acme` and
redirects everything else to HTTPS (to the standard port 443), and HTTPS responses carry
`Strict-Transport-Security`. The backend and database are no longer published on the host.
Certificates are mounted read-only, never baked into an image.

Behind a load balancer that terminates TLS itself, use `docker-compose.yml` alone: nginx passes on
the balancer's `X-Forwarded-Proto: https`, the backend (`forward-headers-strategy: framework`)
then treats the request as secure, and the redirect and HSTS belong to the balancer.

Under the `prod` profile the backend refuses to start when `JMIP_SESSION_COOKIE_SECURE=false`,
`JMIP_SESSION_COOKIE_SAME_SITE=none`, or `JMIP_CORS_ALLOWED_ORIGINS` lists a plain-HTTP origin other
than localhost. Local development (no `prod` profile, `npm run dev` on port 5173) is unchanged.

### Dependency security scanning (V6.10.7)

`.github/workflows/dependency-scan.yml` runs on pushes and pull requests to `main`, every Monday
and on demand. It fails when a dependency has a **HIGH or CRITICAL** vulnerability for which a
fixed version exists. Maven resolves the real dependency tree into a CycloneDX SBOM
(`target/bom.json`, not committed; test scope excluded), and Trivy scans it along with
`frontend/package-lock.json` (development dependencies included). It is separate from CI, so a
newly published advisory does not stop builds and tests.

To run the same scan locally (Docker required):

```bash
mvn -B -ntp -DskipTests package org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom -DoutputFormat=json -DoutputName=bom
```

```bash
docker run --rm -v "$PWD:/src:ro" -w /src aquasec/trivy:0.67.2 sbom --severity HIGH,CRITICAL --ignore-unfixed /src/target/bom.json
```

`.github/dependabot.yml` opens weekly update PRs (minor and patch grouped, majors separately)
that go through CI and are never merged automatically. For PRs that fix vulnerable versions as
soon as an advisory appears, enable **Dependabot alerts** and **Dependabot security updates** in
the repository's Settings → Code security. A finding that has been reviewed and does not apply
can be listed, with a reason, in a `.trivyignore` file at the repository root.
