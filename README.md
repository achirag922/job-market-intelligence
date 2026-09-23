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
