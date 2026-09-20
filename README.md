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

Pages: Dashboard, Job Explorer, Job Details, Skill Analytics, Company Analytics,
Location Analytics.

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
| `GET /api/analytics/skills` | Skills ranked by demand, with each one's share of postings |
| `GET /api/analytics/locations` | Locations ranked by posting count; remote postings have none and are excluded |
| `GET /api/analytics/companies` | Companies ranked by posting count |

Errors return a consistent body — `timestamp`, `status`, `error`, `message`, `path`, and
`fieldErrors` when validation failed. Unknown id gives 404; a bad filter, an unsortable
field or an out-of-range page size gives 400.
