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

## Running the frontend

```
cd frontend
npm install
npm run dev
```

Opens on `http://localhost:5173` and talks to the API at `VITE_API_BASE_URL`
(see `frontend/.env.example`). The backend must be running, and its `jmip.cors.allowed-origins`
must include the frontend's origin — `http://localhost:5173` is allowed by default.

Pages: Dashboard, Job Explorer, Job Details, Skill Analytics, Skill Trends,
Company Analytics, Location Analytics.

## Testing

```
mvn test
```

Unit tests are plain JUnit. The integration tests start a throwaway PostgreSQL through
Testcontainers, so Docker must be running.

## Project layout

```
database/src/main/resources/db/migration   Flyway migrations, the single source of schema truth
backend/src/main/java/com/jmip
  common/exception                         Global exception handling and the shared ApiError
etl/src/main/java/com/jmip/etl
  raw/                                     Input readers (JSON, CSV) and the RawJobRecord contract
  transform/                               Cleaning, parsing, skill extraction, fingerprinting
  validation/                              Validation rules and rejected-record storage
  load/                                    Chunk writer, reference-data cache, metrics
  batch/                                   Spring Batch job, step and listeners
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

## API

All list endpoints take `page` and `size` (max 100) and return the same envelope:
`content`, `page`, `size`, `totalElements`, `totalPages`, `first`, `last`.

| Endpoint | Notes |
|---|---|
| `GET /api/jobs` | Filters: `title`, `location`, `company`, `skill`, `employmentType`, combined with AND. Sort: `postedDate`, `title`, `salaryMin`, `salaryMax`, `createdAt`. Default is newest first with undated postings last |
| `GET /api/jobs/{id}` | Full posting including description and source |
| `GET /api/skills` | Filter: `name` |
| `GET /api/skills/top` | Most in-demand skills, `limit` 1–100, default 10 |
| `GET /api/companies` | Filter: `name` |
| `GET /api/companies/{id}` | Company plus its posting count |
| `GET /api/locations` | Filter: `country` |
| `GET /api/analytics/overview` | Total jobs, companies, skills and locations |
| `GET /api/analytics/skills` | Skills ranked by demand. Filters: `location`, `fromDate`, `toDate`, `title`. Returns `scope.totalJobsInScope`, the denominator behind every percentage |
| `GET /api/analytics/skills/trends` | Skills gaining or losing demand. `months`, `minJobs`, `direction` (RISING/FALLING/STABLE), `limit`. Measured from the snapshot history |
| `GET /api/analytics/experience` | Distribution across 0–2, 2–5, 5–8, 8+ years, plus "not specified" |
| `GET /api/analytics/locations` | Locations ranked by posting count; remote postings have none and are excluded |
| `GET /api/analytics/locations/{id}/skills` | Top skills in one location, as a share of that location |
| `GET /api/analytics/locations/{id}/titles` | Most common job titles in one location |
| `GET /api/analytics/companies` | Companies ranked by posting count |
| `GET /api/analytics/companies/{id}/skills` | Top skills at one company, as a share of that company |
| `GET /api/analytics/titles` | Most common job titles after grouping, with the skills each role asks for |

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
