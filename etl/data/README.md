# JMIP input data test

This directory holds the raw job-posting data the ETL reads. Nothing here is written
by the application — these are inputs, checked in as fixtures or downloaded manually.

```
etl/data/
  README.md                            this file
  raw/
    synthetic-job-postings-v1.json     150 synthetic development records
```

---

## ⚠️ The bundled dataset is synthetic

`raw/synthetic-job-postings-v1.json` is **fabricated test data**. It is not scraped,
sampled or derived from any real job board.

**It must never be presented as real market data.** Any figure computed from it — top
skills, salary medians, demand by city, hiring companies — is an artifact of a random
generator and says nothing whatsoever about the actual job market. Every company in it
is invented, and every URL points at the reserved `.invalid` domain (RFC 2606), which
can never resolve.

It exists so the full pipeline — ingestion, skill extraction, search, analytics,
dashboard — can be built and tested end to end before real data is wired in.

---

## Why synthetic, and what the real options are

Real datasets were researched first. None could be obtained automatically *and*
redistributed inside this repository, which is why the development fixture is synthetic.

| Source | Format | Obtainable automatically? | Licensing / usage considerations |
|---|---|---|---|
| [Kaggle — LinkedIn Job Postings 2023–2024](https://www.kaggle.com/datasets/arshkon/linkedin-job-postings) | CSV | No — needs a Kaggle account and API token | Richest option (~124k postings with salary, work type, location, description). Two layers of terms apply: the Kaggle dataset licence *and* LinkedIn's terms, since the data was scraped from LinkedIn. Fine for local private development; **do not commit it to a public repo**. |
| [Kaggle — 1.3M LinkedIn Jobs & Skills 2024](https://www.kaggle.com/datasets/asaniczka/1-3m-linkedin-jobs-and-skills-2024) | CSV | No — Kaggle account required | Includes a separate skills file, useful later for validating skill extraction. Same LinkedIn terms caveat. |
| [Remotive API](https://remotive.com/api/remote-jobs) | JSON | Yes — public, no API key | Carries an explicit legal notice in the response body: link back to Remotive as the source, do not republish its jobs to third-party job sites, do not hammer the endpoint. Usable as a *live* development source; republishing a snapshot in this repo would conflict with those terms. `salary` is free text, and there are no experience fields. |
| [Himalayas](https://himalayas.app/api), [RemoteOK](https://remoteok.com/api), [RemoteJobs.org](https://remotejobs.org/api-access) | JSON | Yes — public, no API key | Same shape of obligation: attribution and link-back expected. All are remote-only, so the location distribution is heavily skewed and useless for location analytics. |
| [Greenhouse](https://docs.greenhouse.io/job-board.html) / Lever public boards | JSON | Yes — public GET endpoints | Clean and unambiguous to use, but per-company: you must enumerate the employers yourself, so coverage is narrow and not market-representative. |
| [USAJOBS API](https://developer.usajobs.gov/) | JSON | No — free API key required | US federal government postings, permissive terms. Genuinely open, but covers only US public-sector roles. |

**No web scraping is used or intended.** Every option above is either a published dataset
or a documented public API. If a scraped source is ever added, its `robots.txt`, terms of
service and licence must be checked and recorded in this file first.

### Swapping in a real dataset

The ETL will read whatever file it is pointed at, so replacing the fixture means
downloading a real dataset into `raw/`, mapping its columns onto the format below, and
pointing the ETL at it. Keep real downloads out of version control — add the specific
filename to `.gitignore` rather than committing licensed third-party data.

---

## File format

UTF-8 encoded JSON, a single array of objects, one object per job posting. Field order
is stable and missing values are explicit `null` rather than omitted keys or empty
strings, so a parser never has to distinguish "absent" from "unknown".

```json
[
  {
    "source": "synthetic-dev",
    "source_url": "https://jobs.example.invalid/postings/SYN-0001",
    "title": "Junior Data Engineer",
    "company_name": "Halcyon Robotics",
    "company_industry": "Robotics",
    "company_website": "https://www.halcyon-robotics.example.invalid",
    "city": "Warsaw",
    "state": "Masovia",
    "country": "Poland",
    "description": "Halcyon Robotics is hiring a junior data engineer to build and operate the pipelines behind our analytics. The team is based in Warsaw.\n\nWhat you will do:\n- ...",
    "employment_type": "FULL_TIME",
    "experience_min": 1,
    "experience_max": 3,
    "salary_min": 110000,
    "salary_max": 170000,
    "currency": "PLN",
    "posted_date": "2026-08-13"
  }
]
```

## Columns

| Field | Type | Nullable | Maps to | Notes |
|---|---|---|---|---|
| `source` | string | no | `jobs.source` | Identifier of the dataset or API the record came from. |
| `source_url` | string | yes | `jobs.source_url` | The posting's URL at the source. Null when the source publishes none. |
| `title` | string | no | `jobs.title` | |
| `company_name` | string | no | `companies.name` | Resolved to a company row; matching is case-insensitive. |
| `company_industry` | string | yes | `companies.industry` | |
| `company_website` | string | yes | `companies.website` | |
| `city` | string | yes | `locations.city` | |
| `state` | string | yes | `locations.state` | Null in countries with no meaningful state level. |
| `country` | string | yes | `locations.country` | Null **only** when the whole location is absent — see below. |
| `description` | string | yes | `jobs.description` | Free text. Skills are extracted from this field. |
| `employment_type` | string | yes | `jobs.employment_type` | One of `FULL_TIME`, `PART_TIME`, `CONTRACT`, `INTERNSHIP`, `TEMPORARY`, `FREELANCE`. Any other source vocabulary must be normalised to these before insert, or the check constraint rejects the row. |
| `experience_min` | integer | yes | `jobs.experience_min` | Years. `>= 0`. |
| `experience_max` | integer | yes | `jobs.experience_max` | Years. Must be `>= experience_min`. |
| `salary_min` | number | yes | `jobs.salary_min` | Annual, in `currency`. May be present with a null `salary_max` for open-ended "from X" postings. |
| `salary_max` | number | yes | `jobs.salary_max` | Annual. Must be `>= salary_min`. |
| `currency` | string | yes | `jobs.currency` | ISO 4217, uppercase. **Required whenever any salary value is present** — the schema rejects a salary with no currency, because it cannot be compared or aggregated. |
| `posted_date` | string | yes | `jobs.posted_date` | `YYYY-MM-DD`. |

There is deliberately **no skills column**. Skills are extracted from `description` in a
later phase and written to `skills` / `job_skills`. Descriptions in the fixture name real
technologies so that extraction has something genuine to find.

### Location handling

`city`, `state` and `country` are either all populated together or all null. All-null
means the posting is remote or its location is unknown, and the ETL maps that to
`jobs.location_id = NULL`. A record with a city but no country is invalid — `country` is
`NOT NULL` on the `locations` table.

---

## Deliberate duplicates

The fixture contains **12 planted duplicates** among its 150 records, so the ETL's
deduplication can actually be tested rather than assumed:

- **6 exact re-ingestions** — identical `source` and `source_url` as an earlier record.
  Caught by the partial unique index on `(source, source_url)`.
- **6 cross-source duplicates** — the same posting under `source: "synthetic-dev-mirror"`
  with a different URL. `(source, source_url)` cannot catch these; only the content
  fingerprint on `(title, company, location, posted_date)` can.

So 150 input records should yield **138 stored jobs**. That number is the assertion to
write against the ingestion step.

## Other shapes the fixture exercises

Roughly, across the 150 records: 18 have no location, 35 have no salary, 25 have no
experience range, 19 have no company industry, 8 have no posted date, 6 have no
employment type, and 45 have no state. 19 have a `salary_min` with no `salary_max`.
Ten countries and eight currencies are represented.

This is intentional — real postings are patchy, and an ETL that only handles fully
populated records will fail on the first real dataset it meets.
