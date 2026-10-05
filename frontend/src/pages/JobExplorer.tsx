import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, Navigate, useLocation, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type { CategoryDemand, JobSummary, MatchHint, PagedResponse, SalaryRange, SavedSearch } from '../api/types';
import { useToast } from '../components/feedback';
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
import { SaveJobButton, useSavedJobs } from '../saved/SavedJobs';

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
/** V9.14: the last search is remembered in this browser, so returning to Job Explorer continues it. */
const LAST_SEARCH_KEY = 'jmip:lastJobSearch';

function readLastSearch(): string | null {
  try {
    return localStorage.getItem(LAST_SEARCH_KEY);
  } catch {
    return null;
  }
}

function rememberSearch(query: string) {
  try {
    if (query) localStorage.setItem(LAST_SEARCH_KEY, query);
    else localStorage.removeItem(LAST_SEARCH_KEY);
  } catch {
    // Storage can be unavailable (private windows); remembering is only a convenience.
  }
}

/**
 * Opens the remembered search when Job Explorer is first opened without one, before any request is
 * made. Only on arrival: clearing the filters later leaves them cleared.
 */
export function JobExplorer() {
  const location = useLocation();
  const [remembered] = useState(() => (location.search === '' ? readLastSearch() : null));
  const arrived = useRef(false);
  if (remembered && !arrived.current && location.search === '') {
    return <Navigate to={{ pathname: location.pathname, search: `?${remembered}` }} replace />;
  }
  arrived.current = true;
  return <JobExplorerView />;
}

function JobExplorerView() {
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
  // V9.2: opt in to fill empty filters from your preferences; plain search is unchanged.
  const [usePreferences, setUsePreferences] = useState(false);
  const jobs = useApi<PagedResponse<JobSummary>>(
    () => api.jobs(usePreferences ? { ...apiFilters, usePreferences: 'true' } : { ...apiFilters, excludeHidden: 'true' },
      state.page - 1, state.size, undefined, state.order),
    [searchParams.toString(), attempt, usePreferences],
  );
  const toast = useToast();

  useEffect(() => {
    // The page number is not part of a search worth coming back to.
    const remembered = new URLSearchParams(searchParams);
    remembered.delete('page');
    rememberSearch(remembered.toString());
  }, [searchParams]);

  // V9.14: why each job on the page matches the current resume (the existing skill match).
  const [matches, setMatches] = useState<Record<string, MatchHint>>({});
  const pageIds = jobs.data?.content.map((job) => job.id).join(',') ?? '';
  useEffect(() => {
    if (!pageIds || !api.jobMatches) return;
    let active = true;
    api.jobMatches(pageIds.split(',').map(Number)).then((result) => active && setMatches(result.matches), () => undefined);
    return () => {
      active = false;
    };
  }, [pageIds]);

  // V9.14: saved searches, run again in one click.
  const [searches, setSearches] = useState<SavedSearch[]>([]);
  const [naming, setNaming] = useState(false);
  const [searchName, setSearchName] = useState('');
  useEffect(() => {
    api.savedSearches?.().then(setSearches, () => setSearches([]));
  }, []);
  const saveSearch = async () => {
    const filters = Object.fromEntries([...searchParams.entries()].filter(([key]) => key !== 'page'));
    try {
      const created = await api.saveSearch(searchName.trim(), filters);
      setSearches((list) => [created, ...list]);
      setNaming(false);
      setSearchName('');
      toast(`Saved search “${created.name}”.`);
    } catch (cause) {
      toast(cause instanceof Error ? cause.message : 'The search could not be saved.', 'error');
    }
  };
  const hide = async (job: JobSummary) => {
    try {
      await api.hideJob(job.id);
      toast(`Hidden “${job.title}”. Find it in your workspace to undo.`);
      setAttempt((count) => count + 1);
    } catch (cause) {
      toast(cause instanceof Error ? cause.message : 'The job could not be hidden.', 'error');
    }
  };

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
        <label className="row small" style={{ gap: 6, marginBottom: 8 }}>
          <input type="checkbox" checked={usePreferences} onChange={(event) => setUsePreferences(event.target.checked)} />
          Use my preferences (fills an empty role and location, leaves out excluded companies)
        </label>
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

      <div className="saved-search-bar" aria-label="Saved searches">
        {searches.map((saved) => (
          <Link key={saved.id} className="quick-action" to={`/jobs?${new URLSearchParams(saved.filters).toString()}`}>
            {saved.name}
          </Link>
        ))}
        {naming ? (
          <form className="row" style={{ gap: 6 }} onSubmit={(event) => { event.preventDefault(); if (searchName.trim()) void saveSearch(); }}>
            <label className="visually-hidden" htmlFor="saved-search-name">Name this search</label>
            <input id="saved-search-name" type="text" maxLength={80} value={searchName} placeholder="Name this search"
              onChange={(event) => setSearchName(event.target.value)} autoFocus />
            <button type="submit" className="small" disabled={!searchName.trim()}>Save</button>
            <button type="button" className="small ghost" onClick={() => setNaming(false)}>Cancel</button>
          </form>
        ) : (
          <button type="button" className="small ghost" onClick={() => setNaming(true)} disabled={!filtersActive && state.order === 'newest'}>
            Save this search
          </button>
        )}
      </div>

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
        matches={matches}
        onHide={hide}
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
  matches: Record<string, MatchHint>;
  onHide: (job: JobSummary) => void;
}

/**
 * The result list, in whichever state the search is in.
 *
 * <p>A refresh keeps the previous results on screen, dimmed, rather than swapping them for
 * a skeleton: the page does not jump on every filter change, and an empty state never
 * flashes up between one set of results and the next. The skeleton is only for the very
 * first load, when there is nothing yet to show.
 */
function Results({ jobs, state, from, filtersActive, onClear, onRetry, matches, onHide }: ResultsProps) {
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
          <JobCard job={job} from={from} match={matches[job.id]} onHide={() => onHide(job)} />
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
const STATUS_BADGE: Record<string, string> = {
  SAVED: 'Saved', APPLIED: 'Applied', INTERVIEW: 'Interview', OFFER: 'Offer', REJECTED: 'Rejected', WITHDRAWN: 'Withdrawn',
};

function JobCard({ job, from, match, onHide }: { job: JobSummary; from: string; match?: MatchHint; onHide: () => void }) {
  const tracked = useSavedJobs()?.savedFor(job.id);
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
          {tracked && (
            <Badge tone={tracked.status === 'SAVED' ? 'neutral' : 'success'}>{STATUS_BADGE[tracked.status] ?? tracked.status}</Badge>
          )}
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

        {match?.percentage !== undefined && match.percentage !== null && (
          <p className="job-card-match" aria-label="Match with your resume">
            <Badge tone={match.percentage >= 70 ? 'success' : match.percentage >= 40 ? 'warning' : 'neutral'}>
              {Math.round(match.percentage)}% skill match
            </Badge>
            {match.matched.length > 0 && <span>You have {match.matched.join(', ')}</span>}
            {match.missing.length > 0 && <span className="muted">Missing {match.missing.join(', ')}</span>}
          </p>
        )}

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
        <SaveJobButton jobId={job.id} />
        <button type="button" className="small ghost" onClick={onHide} aria-label={`Not interested in ${job.title}`}>
          Not interested
        </button>
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
