# JMIP demo guide

A 15–20 minute walkthrough of the implemented platform, plus how to prepare data and what to point out.
Everything below is a real, shipped feature; where a step depends on configuration (email, AI key) it says so.

## 1. Demo data strategy

| Data | Source | Notes |
|---|---|---|
| Job postings | `etl/data/raw/synthetic-job-postings-v1.json`: 150 **synthetic** postings (engineering, data, product and other roles, with companies, locations, salaries, dates) | Loaded by the ETL; also the `sample` connector's bundled mock job-board feed |
| Market analytics | Derived from the postings (skill snapshots rebuilt at startup) | No separate seeding |
| Demo user | A fictional persona created live through sign-up, e.g. "Alex Rivera", `alex.demo@example.com` | Never use real personal data or real resumes |
| Resume | Built live in the **Resume Builder** for the persona, then exported as PDF | Avoids uploading any real document |
| Admin view (optional) | Set `JMIP_ADMIN_EMAILS` to a second demo account | Shows Admin Dashboard and ETL Monitoring |

Prepare once:

```bash
cp .env.example .env               # set JMIP_DB_PASSWORD, JMIP_OTP_SECRET (32+ chars), JMIP_RESUME_ENCRYPTION_KEY
docker compose up -d --build       # app on http://localhost:3000
docker compose --profile etl run --rm etl          # load the 150 synthetic postings
```

- **Verification codes**: with the default `JMIP_VERIFICATION_DELIVERY=log` the 6-digit code is in the backend log
  (`docker compose logs backend | grep -i "verification code"`). For a public demo configure SMTP (`JMIP_MAIL_*`, delivery `smtp`).
- **AI features** (AI Assistant, interview answer evaluation) need `AI_API_KEY`. Without it they say they are
  unavailable and everything else works; questions in Interview Prep are generated without AI either way.
- Reset between demos: `docker compose down -v` removes the database and resume volumes.

## 2. End-to-end demo journey

| # | Step | Where | What to do | What to point out |
|---|---|---|---|---|
| 1 | **Landing & sign-up** | `/welcome` → Sign up | Create the persona's account | Password rules (12–72 chars), show-password toggle, accessible form errors |
| 2 | **OTP verification** | Verify email | Enter the 6-digit code | Codes are stored as an HMAC, expire in 10 minutes, 5 attempts, resend cooldown |
| 3 | **Sign in** | Log in | Sign in | Server session in an HttpOnly, Secure, SameSite cookie; CSRF token held only in memory |
| 4 | **Onboarding** | Profile & Preferences | Profile (target role, years, skills) → Resume → Preferences → Career Goal; skip any step | Steps can be skipped and resumed; the dashboard's "Your next steps" follow what is incomplete |
| 5 | **Resume** | Resume Builder → Resume Intelligence | Build a resume (summary, experience, skills), export the PDF; open Resume Intelligence to see the extracted skills | ATS-friendly PDF export; skills recognised from the skills dictionary; files encrypted at rest |
| 6 | **Personalised jobs** | Recommended for You | Browse the ranked jobs | Every recommendation lists its reasons (skills, experience, location, work mode, salary, goal) |
| 7 | **Matching** | Job Explorer → a job's details; Resume Intelligence "Choose a job" | Sort by match, open a job, compare it with the resume | Deterministic, explained match: skills you have and are missing, weighted signals, no black box |
| 8 | **Applications** | Save a job → Applications; Job Workspace | Move it SAVED → APPLIED → INTERVIEW, add a note, set a priority and a follow-up date | Status history drives analytics; a due follow-up becomes a notification (and an email when SMTP is on) |
| 9 | **Learning** | Career Goals → Learning & Skills | Set the target role, view the skill gap, plan the top missing skills, add a resource, mark progress | The skill gap comes from real postings for that role, not from a fixed list |
| 10 | **Interview** | Interview Prep | Pick the saved job, choose type (technical/behavioural/mixed), difficulty and count, answer a question | Questions are built from the posting and resume; with an AI key each answer is scored 1–5 on several criteria with a suggested approach |
| 11 | **Analytics** | My Analytics; Career Progress | Switch the date range; open the readiness score | Funnel, activity, match trend; a capped readiness score in seven parts, each explained (use the ? tooltips) |
| 12 | **Portfolio** | Portfolio → public link | Build from the resume, choose visible sections, publish, open `/profile/{slug}` in a private window | Private until published; the public page is read-only and shows only chosen sections |

Optional extras, if time allows: the **notification bell** and Notifications page; **Job Alerts**; the **AI
Assistant** ("Which skills are most in demand for data roles?"); **Market Intelligence** and the market analytics
pages; **Settings** (theme, password change, account deletion); **Help & FAQ**; as an admin, **Admin Dashboard**
and **ETL Monitoring** (run history with read/loaded/duplicate/rejected counts).

## 3. Talking points (technical)

- **Separate ETL and API** sharing only a Flyway-owned schema; Spring Batch with skip/retry, deduplication by
  content fingerprint, rejected-record capture and an advisory lock against overlapping runs.
- **Explainable, deterministic intelligence**: matching, recommendations, skill gaps, readiness and interview
  questions are reproducible and work without the AI provider; AI is used only where it adds value and its
  output is grounded in backend data.
- **Security by default**: server sessions + CSRF (no tokens in localStorage), ownership from the session on every
  query, exact-origin CORS, security headers/HSTS, rate limits, PDF signature checks, encrypted resumes,
  production startup checks.
- **Production readiness**: Docker images with health checks, CI (tests, lint, npm audit, Sonar Quality Gate,
  Trivy scan, image builds, Compose smoke test), JSON logs with request ids, Micrometer metrics including ETL health,
  OpenAPI docs.

## 4. Screenshot list

Capture these from a demo run (1440 px wide, light theme, plus one dark-theme and one phone-width shot) into
`docs/screenshots/` with these names, then link them from the README:

| File | Screen |
|---|---|
| `01-landing.png` | Landing page |
| `02-onboarding.png` | Onboarding, profile step |
| `03-dashboard.png` | Dashboard with "Your next steps" |
| `04-recommendations.png` | Recommended for You with reasons |
| `05-match.png` | Job details with the match breakdown |
| `06-applications.png` | Applications pipeline |
| `07-learning.png` | Learning & Skills plan |
| `08-interview.png` | Interview Prep with an evaluated answer |
| `09-analytics.png` | My Analytics |
| `10-progress.png` | Career Progress readiness score |
| `11-portfolio-public.png` | Public portfolio page |
| `12-market.png` | Market Intelligence |
| `13-swagger.png` | Swagger UI (local) |
| `14-mobile.png` | Any page at phone width |

Before publishing screenshots, check that only the fictional persona and synthetic data are visible.
