# Job Market Intelligence Platform (JMIP)

Analytics over job postings: job demand, skill demand and trends, companies, locations,
experience requirements and salary, plus job search and filtering.

## Architecture (V1)

```
Job dataset / API  ->  Java ETL  ->  PostgreSQL  ->  Spring Boot REST API  ->  React frontend
```

The ETL runs inside this application under a dedicated Spring profile and shares the
entities and Flyway-managed schema with the API.

## Tech stack

| Layer    | Technology                                      |
|----------|-------------------------------------------------|
| Backend  | Java 17, Spring Boot 3.5, Spring Data JPA        |
| Database | PostgreSQL 18, Flyway migrations                 |
| Build    | Maven                                            |
| Tests    | JUnit 5, Testcontainers (real PostgreSQL)        |
| Frontend | React + TypeScript (added in a later phase)      |

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

## Running

```
mvn spring-boot:run
```

Health check: `http://localhost:8080/actuator/health`

## Testing

```
mvn test
```

## Project layout

```
src/main/java/com/jmip
  common/exception   Global exception handling and the shared ApiError response
src/main/resources
  db/migration       Flyway migrations (schema is owned by Flyway, never by Hibernate)
```

## Build phases

- [x] Phase 1 — project skeleton, database and Flyway wiring, error handling, health endpoint
- [ ] Phase 2 — domain model and schema (job, company, location, skill)
- [ ] Phase 3 — ETL ingestion
- [ ] Phase 4 — job search and filtering API
- [ ] Phase 5 — skill, location and company analytics APIs
- [ ] Phase 6 — React dashboard
