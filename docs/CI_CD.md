# CI/CD pipeline (V10.3)

One GitHub Actions workflow, [`.github/workflows/ci.yml`](../.github/workflows/ci.yml), using only the
project's existing tools (Maven, npm, Docker Compose). It builds, tests, checks and packages JMIP; it
**never deploys**. Dependabot (`.github/dependabot.yml`) keeps Maven, npm and Actions dependencies current.

## Triggers

| Event | What runs |
|---|---|
| Pull request to `main` | backend, frontend, docker, smoke |
| Push to `main` | backend, frontend, docker, smoke |
| Manual (`workflow_dispatch`) | backend, frontend, docker, smoke |
| Tag `v*` (e.g. `v10.3.0`) | all of the above, then publish images to GHCR |

A newer push to the same branch cancels the run in progress. The default token is read-only.

## Stages

| Job | Steps | Fails when |
|---|---|---|
| `backend` | `mvn package -DskipTests`, then `mvn verify` (unit + Testcontainers integration tests for database, backend, ETL) | compilation or any test fails; surefire reports are uploaded on failure |
| `frontend` | `npm ci`, `npm run lint` (oxlint), `npm test` (Vitest), `npm audit --omit=dev --audit-level=high`, `npm run build` (tsc + Vite) | lint errors, a failing test, a high/critical runtime vulnerability, type or build errors |
| `docker` | builds the backend, ETL and frontend images (Buildx, GitHub cache) | any image fails to build; runs only after `backend` and `frontend` pass |
| `smoke` | validates both Compose files, starts the stack with the `prod` profile (`docker compose up --wait`), checks readiness, `/healthz`, the nginx proxy, the SPA, security headers and that the API rejects anonymous calls, then removes everything | a container is unhealthy, Flyway fails on the fresh database, or a check fails; container logs are printed on failure |
| `publish` | tag `v*` only: pushes `ghcr.io/<owner>/jmip-{backend,etl,frontend}:<tag>` and `:sha-<commit>` | the `release` environment is not approved, or `JMIP_PUBLIC_URL` is not set |

Security checks already supported by the project: npm audit (frontend), Dependabot alerts and update PRs
(Maven, npm, Actions), and the pipeline's read-only token. Enable GitHub secret scanning and push protection
in the repository settings. No extra scanners are added.

## Secrets and variables

The pipeline needs **no stored secrets**:

- Tests use Testcontainers PostgreSQL and the stub AI client, so no database password or `AI_API_KEY`.
- The smoke test generates throwaway database, OTP and encryption values per run (masked in logs).
- Publishing to GHCR uses the automatic `GITHUB_TOKEN` (`packages: write` only in that job).

| Name | Type | Where | Needed for |
|---|---|---|---|
| `GITHUB_TOKEN` | automatic secret | provided by Actions | publishing images |
| `JMIP_PUBLIC_URL` | repository **variable** (not secret) | Settings → Secrets and variables → Actions → Variables | frontend image build on release tags |
| `release` | environment | Settings → Environments (add required reviewers) | gating the publish job |

Production runtime secrets (`JMIP_DB_PASSWORD`, `JMIP_OTP_SECRET`, `JMIP_RESUME_ENCRYPTION_KEY`, mail and
AI keys, metrics password) are **not** CI secrets: they belong to the deployment platform's secret store
(see `PRODUCTION_DEPLOYMENT.md`) and are never baked into images. When a deploy stage is added later, its
credentials (for example a cloud deploy role via OIDC) go in the `release` environment's secrets.

## Running the stages locally

```bash
mvn -B -ntp verify                      # backend (Docker must be running for Testcontainers)
cd frontend && npm ci && npm run lint && npm test && npm audit --omit=dev --audit-level=high && npm run build
docker compose --profile etl build      # images
docker compose up -d --build --wait     # smoke stack (needs .env), then: docker compose down -v
```

## Releasing (no deployment)

1. Merge to `main` with a green pipeline.
2. Set the `JMIP_PUBLIC_URL` variable and configure the `release` environment once.
3. `git tag v10.3.0 && git push origin v10.3.0` — the images are pushed to GHCR after the smoke test passes.
