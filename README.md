# Job Market Intelligence Platform (JMIP)

Analytics over job postings: job demand, skill demand and trends, companies, locations,
experience requirements and salary, plus job search and filtering.

## Architecture (V1)

```
Job dataset / API  ->  Java ETL  ->  PostgreSQL  ->  Spring Boot REST API  ->  React frontend
```

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
| Backend  | Java 17, Spring Boot 3.5, Spring Data JPA                    |
| ETL      | Java 17, Spring Batch 5.2, Spring JDBC, Jackson, Commons CSV |
| Database | PostgreSQL 18, Flyway migrations                             |
| Build    | Maven (multi-module)                                         |
| Tests    | JUnit 5, AssertJ, Testcontainers (real PostgreSQL)           |
| Frontend | React + TypeScript (added in a later phase)                  |

## Prerequisites

- JDK 17
- Maven 3.9+
- PostgreSQL 18 running locally (this project defaults to **port 5433**)
- Docker (only needed to run the tests, which start a throwaway PostgreSQL)

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

## Running the ETL

The input file is a job parameter, so switching datasets needs no code change:

```
java -jar etl/target/etl-0.0.1-SNAPSHOT.jar inputFile=etl/data/raw/synthetic-job-postings-v1.json
```

A `.csv` file is read with Apache Commons CSV instead, chosen by extension. CSV files
without a source column take one from an optional `defaultSource=<name>` parameter.

Re-running the same file is safe: duplicate detection means nothing is loaded twice.

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

Pages: Dashboard, Job Explorer, Job Details, Job Intelligence, Skill Analytics, Skill Trends,
Company Analytics, Location Analytics, Resume Intelligence, Ask the Data.

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
No notifications are sent yet; the frequency records the user's choice for a later phase.

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
