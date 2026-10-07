# Changelog

## v1.0.0-rc1 — Release candidate

First release candidate of the Job Market Intelligence Platform. Feature-complete for the planned scope;
no new features will be added before 1.0.0, only fixes found while validating this candidate.

### Included

- **Market intelligence**: Spring Batch ETL (file and sample connectors, validation, deduplication, rejected
  records, run monitoring); job, skill, company, location and category analytics; skill trends; market
  intelligence per role.
- **Job seekers**: sign-up with email verification, onboarding, resume upload/analysis and ATS-friendly builder
  with PDF export, explained matching and personalised recommendations, job workspace and application tracker,
  job alerts, career goals and learning plan, interview practice (AI evaluation optional), personal analytics,
  career progress, public portfolio, notifications, settings, help.
- **Platform**: session security with CSRF, owner-scoped data, rate limits, encrypted resumes, production
  startup checks, health probes, JSON logs, Micrometer metrics (including ETL health), OpenAPI docs,
  Docker images and Compose (HTTP and HTTPS), CI/CD with tests, lint, npm audit, Sonar Quality Gate, Trivy and
  a container smoke test.

### Validation (V10.11 local production simulation, V10.12 audit)

- Tests: backend 686, ETL 183, frontend 261, all passing; 38-step end-to-end API smoke test passing.
- Coverage: backend 95.0% lines / 80.3% branches, ETL 93.6% / 81.7%, frontend 77.5% / 73.0%.
- Dependencies: npm audit 0 vulnerabilities; Trivy HIGH/CRITICAL clean except the documented, time-limited
  CVE-2026-47884 acceptance (`.trivyignore`, guarded by `NoXsltViewTest`).
- Resilience: container crash restart, database outage and recovery, invalid-configuration refusal, ETL
  failure and recovery verified.

### Known issues

- A database outage returns HTTP 500 (generic message) rather than 503.
- HTTP sessions are in memory: one instance, or sticky sessions, until a shared session store is added.
- Flyway warns that PostgreSQL 18.6 is newer than its tested range.
- PDF export substitutes LiberationSans for the standard PDF fonts in the container image.
- Real SMTP delivery and a real AI provider must be verified with production credentials.
- Spring Framework 7 / Spring Boot 4 upgrade pending (removes the CVE-2026-47884 acceptance, which expires 2027-01-06).
- Sonar: 10 High cognitive-complexity findings in established services and about 430 Medium/Low style findings.

### Releasing this candidate

1. Open a pull request `impl-V10` → `main` and wait for CI (tests, Sonar Quality Gate, images, smoke test) and
   the Dependency scan to pass.
2. Merge, then tag the merge commit: `git tag -a v1.0.0-rc1 -m "JMIP 1.0.0 release candidate 1"` and
   `git push origin v1.0.0-rc1`. The CI `publish` job then pushes the images to GHCR (no deployment).
