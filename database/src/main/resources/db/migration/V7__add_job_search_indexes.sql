-- V6.2 job search: indexes for the filters added to /api/jobs.
--
-- Chosen from the predicates the search actually issues, not from the column list.
-- At the current development volume (a few hundred rows) the planner correctly prefers a
-- sequential scan and uses none of these. They are here for the shape of the queries, so
-- the search does not degrade into a full scan per request once the table is large.
--
-- Deliberately not indexed:
--   employment_type  Six values, one of which dominates. An index this unselective is
--                    rarely chosen, and costs every write to maintain.
--   title, description, company name, location text
--                    Searched with LIKE '%term%'. A leading wildcard cannot use a btree
--                    index, so one would be pure overhead. Serving these properly needs
--                    pg_trgm GIN indexes, which is an extension to install and operate;
--                    not justified at this volume.

-- Salary filter and salary ordering. Both always scope to one currency first — a bare
-- amount is refused, because salaries in different currencies do not compare — so the
-- currency leads. Partial, because postings without a stated salary can never match a
-- salary filter and need not be in the index at all.
CREATE INDEX idx_jobs_currency_salary
    ON jobs (currency, salary_min)
    WHERE salary_min IS NOT NULL;

-- Experience filter: a half-open range on the minimum requirement, the same bands the
-- experience distribution reports.
CREATE INDEX idx_jobs_experience_min
    ON jobs (experience_min);
