import { useMemo, useState } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type { CategoryDemand, JobSummary, PagedResponse, SalaryRange } from '../api/types';
import { DebouncedInput } from '../components/DebouncedInput';
import { FilterDrawer } from '../components/FilterDrawer';
import { JobFilterPanel } from '../components/JobFilterPanel';
import { Pagination } from '../components/Pagination';
import { Badge, EmptyState, ErrorState, PageHeader, Skeleton } from '../components/ui';
import { IconClose, IconFile, IconSearch } from '../components/icons';
import { formatDate, formatExperience, formatLocation, formatSalary } from '../components/format';
import { useApi } from '../hooks/useApi';
import type { AsyncState } from '../hooks/useApi';
import {
  ORDER_OPTIONS,
  PAGE_SIZES,
  activeFilters,
  cleared,
  formatEmploymentType,
  hasAnyFilter,
  normalise,
  orderUnavailableReason,
  parseSearch,
  resultSummary,
  toApiFilters,
  toSearchParams,
  withFilter,
} from './jobSearchState';
import type { PageSize, SearchState } from './jobSearchState';

/** Enough to show what a role is about without the card becoming a list of skills. */
const SKILLS_SHOWN = 5;

/**
 * Job search: text, filters, ordering and pages, all held in the URL.
 *
 * <p>The URL is the only copy of the search. Filters are parsed from it on every render
 * and written back to it on every change, which is what makes a search survive a refresh,
 * work with the back button, and open unchanged from a shared link — with no store, no
 * context, and nothing to keep in sync.
 */
export function JobExplorer() {
  const [searchParams, setSearchParams] = useSearchParams();
  const location = useLocation();
  const state = useMemo(() => parseSearch(searchParams), [searchParams]);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [drawerDraft, setDrawerDraft] = useState<SearchState>(state);
  // Bumped by "Try again" to re-run a failed search without changing what is asked for.
  const [attempt, setAttempt] = useState(0);

  const categories = useApi<CategoryDemand[]>(() => api.jobCategories(), []);
  const currencies = useApi<SalaryRange[]>(() => api.salaryCurrencies(), []);

  // One request per URL change. Everything a search depends on is in the URL, so a change
  // that touches several filters at once is still a single navigation and a single fetch.
  const apiFilters = useMemo(() => toApiFilters(state.filters), [state.filters]);
  const jobs = useApi<PagedResponse<JobSummary>>(
    () => api.jobs(apiFilters, state.page - 1, state.size, undefined, state.order),
    [searchParams.toString(), attempt],
  );

  const apply = (next: SearchState) => {
    const params = toSearchParams(normalise(next));
    // A late debounce can re-commit the value just submitted. Navigating to the same URL
    // would push a duplicate history entry, so the Back button would appear to do nothing.
    if (params.toString() === searchParams.toString()) {
      return;
    }
    setSearchParams(params);
  };

  const chips = activeFilters(state.filters);
  const filtersActive = hasAnyFilter(state.filters);

  const openDrawer = () => {
    setDrawerDraft(state);
    setDrawerOpen(true);
  };

  return (
    <>
      <PageHeader
        title="Job Explorer"
        description="Search every posting, combine filters, and share the result — the whole search is kept in the page address."
      />

      <section className="card job-search" aria-label="Search and filters">
        <form
          className="search-row"
          role="search"
          onSubmit={(event) => {
            event.preventDefault();
            // Read from the form rather than relying on the input blurring first: mobile
            // Safari does not move focus to a tapped button, so the draft never commits.
            const typed = new FormData(event.currentTarget).get('q');
            apply(withFilter(state, 'q', typeof typed === 'string' ? typed : ''));
          }}
        >
          <span className="search-row-icon" aria-hidden="true">
            <IconSearch size={17} />
          </span>
          <label className="visually-hidden" htmlFor="job-search-q">
            Search jobs
          </label>
          <DebouncedInput
            id="job-search-q"
            name="q"
            type="search"
            placeholder="Search title, company, location, skills or description"
            value={state.filters.q ?? ''}
            onCommit={(value) => apply(withFilter(state, 'q', value))}
          />
          <button type="submit">Search</button>
          <button
            type="button"
            className="mobile-only filters-button"
            aria-haspopup="dialog"
            aria-expanded={drawerOpen}
            onClick={openDrawer}
          >
            Filters
            {chips.length > 0 && <span className="filters-count">{chips.length}</span>}
          </button>
        </form>

        <div className="desktop-only">
          <JobFilterPanel state={state} onChange={apply} categories={categories} currencies={currencies} />
        </div>
      </section>

      {filtersActive && (
        <div className="active-filters" aria-label="Active filters">
          <span className="active-filters-label">Showing jobs matching:</span>
          <ul className="active-filters-list">
            {chips.map((chip) => (
              <li key={chip.key}>
                <span className="filter-chip">
                  <span className="filter-chip-kind">{chip.label}:</span> {chip.value}
                  <button
                    type="button"
                    className="filter-chip-remove"
                    aria-label={`Remove filter ${chip.label}: ${chip.value}`}
                    onClick={() => apply(withFilter(state, chip.key, undefined))}
                  >
                    <IconClose size={12} />
                  </button>
                </span>
              </li>
            ))}
          </ul>
          <button type="button" className="small ghost" onClick={() => apply(cleared(state))}>
            Clear all filters
          </button>
        </div>
      )}

      <div className="results-toolbar">
        <p className="results-summary" role="status" aria-live="polite">
          {jobs.data
            ? // From the response, not the URL: during a refresh the rows on screen are still
              // the previous page's, and the summary has to describe what is shown.
              resultSummary(
                jobs.data.page + 1,
                jobs.data.size,
                jobs.data.content.length,
                jobs.data.totalElements,
              )
            : jobs.loading
              ? 'Searching…'
              : ' '}
        </p>
        <label className="field sort-field">
          <span>Sort by</span>
          <select
            value={state.order}
            onChange={(event) =>
              apply({ ...state, order: event.target.value as SearchState['order'], page: 1 })
            }
          >
            {ORDER_OPTIONS.map((option) => {
              const reason = orderUnavailableReason(option.value, state.filters);
              return (
                <option key={option.value} value={option.value} disabled={reason !== null}>
                  {reason ? `${option.label} — ${reason.toLowerCase()}` : option.label}
                </option>
              );
            })}
          </select>
        </label>
      </div>

      <Results
        jobs={jobs}
        state={state}
        from={location.search}
        filtersActive={filtersActive}
        onClear={() => apply(cleared(state))}
        onRetry={() => setAttempt((count) => count + 1)}
      />

      {jobs.data && jobs.data.totalElements > 0 && (
        <Pagination
          page={jobs.data.page}
          totalPages={jobs.data.totalPages}
          totalElements={jobs.data.totalElements}
          first={jobs.data.first}
          last={jobs.data.last}
          showPageNumbers
          pageSize={state.size}
          pageSizeOptions={PAGE_SIZES}
          onPageSizeChange={(size) => apply({ ...state, size: size as PageSize, page: 1 })}
          onChange={(zeroBased) => {
            apply({ ...state, page: zeroBased + 1 });
            window.scrollTo({ top: 0, behavior: 'smooth' });
          }}
        />
      )}

      <FilterDrawer
        open={drawerOpen}
        title="Filters"
        onClose={() => setDrawerOpen(false)}
        onClearAll={() => setDrawerDraft(cleared(drawerDraft))}
        onApply={() => {
          apply(drawerDraft);
          setDrawerOpen(false);
        }}
      >
        {/* Mounted only while open. A second, hidden copy of the panel would fetch its
            own suggestions on every page load, doubling requests for a drawer nobody
            opened. */}
        {drawerOpen && (
          <JobFilterPanel
            state={drawerDraft}
            onChange={setDrawerDraft}
            categories={categories}
            currencies={currencies}
            mode="draft"
          />
        )}
      </FilterDrawer>
    </>
  );
}

interface ResultsProps {
  jobs: AsyncState<PagedResponse<JobSummary>>;
  state: SearchState;
  from: string;
  filtersActive: boolean;
  onClear: () => void;
  onRetry: () => void;
}

/**
 * The result list, in whichever state the search is in.
 *
 * <p>A refresh keeps the previous results on screen, dimmed, rather than swapping them for
 * a skeleton: the page does not jump on every filter change, and an empty state never
 * flashes up between one set of results and the next. The skeleton is only for the very
 * first load, when there is nothing yet to show.
 */
function Results({ jobs, state, from, filtersActive, onClear, onRetry }: ResultsProps) {
  if (jobs.error) {
    return (
      <div className="card">
        <ErrorState message={jobs.error} onRetry={onRetry} />
      </div>
    );
  }

  if (!jobs.data) {
    return <JobCardSkeletons count={Math.min(state.size, 6)} />;
  }

  if (jobs.data.content.length === 0) {
    return (
      <div className="card">
        <EmptyState
          title="No jobs found matching your filters"
          message={
            filtersActive
              ? 'Try removing a filter, choosing a different location, or broadening your search text.'
              : 'There are no postings in the dataset yet. Run the ETL to load some.'
          }
          action={
            filtersActive ? (
              <button type="button" onClick={onClear}>
                Clear filters
              </button>
            ) : undefined
          }
        />
      </div>
    );
  }

  return (
    <ul className={jobs.loading ? 'job-card-list is-refreshing' : 'job-card-list'} aria-busy={jobs.loading}>
      {jobs.data.content.map((job) => (
        <li key={job.id}>
          <JobCard job={job} from={from} />
        </li>
      ))}
    </ul>
  );
}

/**
 * One posting, with the facts a reader scans for and the two things they might do next.
 *
 * <p>Anything the posting does not state is shown as not stated — never as a zero, a
 * blank that looks like a rendering fault, or an invented figure.
 */
function JobCard({ job, from }: { job: JobSummary; from: string }) {
  // Carried to the details page so its back link returns to this exact search.
  const detailsState = { from };
  const extraSkills = job.skills.length - SKILLS_SHOWN;

  return (
    <article className="job-card" aria-labelledby={`job-${job.id}-title`}>
      <div className="job-card-main">
        <div className="job-card-heading">
          <h2 id={`job-${job.id}-title`} className="job-card-title">
            <Link to={`/jobs/${job.id}`} state={detailsState}>
              {job.title}
            </Link>
          </h2>
          {job.category ? <Badge tone="brand">{job.category}</Badge> : <Badge>Unclassified</Badge>}
        </div>

        <p className="job-card-meta">
          <span>{job.company.name}</span>
          <span aria-hidden="true">·</span>
          <span>{formatLocation(job)}</span>
          {job.employmentType && (
            <>
              <span aria-hidden="true">·</span>
              <span>{formatEmploymentType(job.employmentType)}</span>
            </>
          )}
        </p>

        <dl className="job-card-facts">
          <div>
            <dt>Experience</dt>
            <dd>{formatExperience(job.experience)}</dd>
          </div>
          <div>
            <dt>Salary</dt>
            <dd>{formatSalary(job.salary)}</dd>
          </div>
          <div>
            <dt>Posted</dt>
            <dd>{formatDate(job.postedDate)}</dd>
          </div>
        </dl>

        {job.skills.length > 0 && (
          <ul className="skill-list job-card-skills" aria-label="Key skills">
            {job.skills.slice(0, SKILLS_SHOWN).map((skill) => (
              <li key={skill.id} className="skill-tag">
                {skill.name}
              </li>
            ))}
            {extraSkills > 0 && <li className="muted job-card-more">+{extraSkills} more</li>}
          </ul>
        )}
      </div>

      <div className="job-card-actions">
        <Link className="button-link primary" to={`/jobs/${job.id}`} state={detailsState}>
          View details
        </Link>
        {/* The V3 comparison, unchanged; the job is handed over in the URL. */}
        <Link className="button-link" to={`/resume?jobId=${job.id}`}>
          <IconFile size={15} />
          Compare resume
        </Link>
      </div>
    </article>
  );
}

function JobCardSkeletons({ count }: { count: number }) {
  return (
    <ul className="job-card-list" aria-busy="true" aria-label="Loading jobs">
      {Array.from({ length: count }).map((_, index) => (
        <li key={index}>
          <div className="job-card">
            <div className="job-card-main">
              <Skeleton width="55%" height={18} />
              <div style={{ height: 10 }} />
              <Skeleton width="35%" height={12} />
              <div style={{ height: 14 }} />
              <Skeleton width="80%" height={12} />
            </div>
          </div>
        </li>
      ))}
    </ul>
  );
}
