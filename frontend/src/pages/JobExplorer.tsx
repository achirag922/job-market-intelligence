import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type { CategoryDemand, JobSummary, PagedResponse } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { Pagination } from '../components/Pagination';
import { Badge, PageHeader } from '../components/ui';
import { IconSearch } from '../components/icons';
import {
  formatDate,
  formatEmploymentType,
  formatExperience,
  formatLocation,
  formatSalary,
} from '../components/format';
import { useApi } from '../hooks/useApi';

const PAGE_SIZE = 20;

const EMPLOYMENT_TYPES = [
  'FULL_TIME',
  'PART_TIME',
  'CONTRACT',
  'INTERNSHIP',
  'TEMPORARY',
  'FREELANCE',
];

/**
 * Two chips fit on one line at this column width. A third pushed every row to three
 * lines for a skill most readers would open the posting to see anyway, so the rest are
 * summarised as a count.
 */
const SKILLS_SHOWN = 2;

/**
 * Filters live in the URL rather than in component state, so a search can be shared,
 * bookmarked, and survives the back button from a job's detail page.
 */
export function JobExplorer() {
  const [searchParams, setSearchParams] = useSearchParams();

  const title = searchParams.get('title') ?? '';
  const location = searchParams.get('location') ?? '';
  const company = searchParams.get('company') ?? '';
  const skill = searchParams.get('skill') ?? '';
  const employmentType = searchParams.get('employmentType') ?? '';
  const category = searchParams.get('category') ?? '';
  const page = Number(searchParams.get('page') ?? '0');

  // The inputs are uncontrolled between submits so that typing does not fire a request
  // per keystroke.
  const [draft, setDraft] = useState({ title, location, company, skill, employmentType, category });

  useEffect(() => {
    setDraft({ title, location, company, skill, employmentType, category });
  }, [title, location, company, skill, employmentType, category]);

  const categories = useApi<CategoryDemand[]>(() => api.jobCategories(), []);

  const jobs = useApi<PagedResponse<JobSummary>>(
    () => api.jobs({ title, location, company, skill, employmentType, category }, page, PAGE_SIZE),
    [title, location, company, skill, employmentType, category, page],
  );

  const applyFilters = (next: typeof draft, nextPage = 0) => {
    const params: Record<string, string> = {};
    Object.entries(next).forEach(([key, value]) => {
      if (value.trim() !== '') {
        params[key] = value.trim();
      }
    });
    if (nextPage > 0) {
      params.page = String(nextPage);
    }
    setSearchParams(params);
  };

  const activeCount = Object.values({ title, location, company, skill, employmentType, category })
    .filter((value) => value !== '').length;

  return (
    <>
      <PageHeader
        title="Job Explorer"
        description="Search and filter every posting in the dataset. Filters combine with AND and are kept in the URL, so a search can be shared."
      />

      <form
        className="card"
        onSubmit={(event) => {
          event.preventDefault();
          applyFilters(draft);
        }}
      >
        {/* The title search is the primary action, so it gets its own full-width row. */}
        <div className="search-row">
          <span className="search-row-icon" aria-hidden="true">
            <IconSearch size={17} />
          </span>
          <input
            type="search"
            value={draft.title}
            placeholder="Search job titles — e.g. backend engineer"
            aria-label="Search job titles"
            onChange={(event) => setDraft({ ...draft, title: event.target.value })}
          />
          <button type="submit">Search</button>
        </div>

        <div className="filter-row">
          <label className="field">
            Category
            {/* Options come from the classified data, so the list can never offer a
                category that no posting actually carries. */}
            <select
              value={draft.category}
              onChange={(event) => setDraft({ ...draft, category: event.target.value })}
            >
              <option value="">Any category</option>
              {(categories.data ?? []).map((option) => (
                <option key={option.category} value={option.category}>
                  {option.category} ({option.jobCount})
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            Skill
            <input
              type="text"
              value={draft.skill}
              placeholder="e.g. Java"
              onChange={(event) => setDraft({ ...draft, skill: event.target.value })}
            />
          </label>
          <label className="field">
            Location
            <input
              type="text"
              value={draft.location}
              placeholder="e.g. India"
              onChange={(event) => setDraft({ ...draft, location: event.target.value })}
            />
          </label>
          <label className="field">
            Company
            <input
              type="text"
              value={draft.company}
              placeholder="e.g. Acme"
              onChange={(event) => setDraft({ ...draft, company: event.target.value })}
            />
          </label>
          <label className="field">
            Employment type
            <select
              value={draft.employmentType}
              onChange={(event) => setDraft({ ...draft, employmentType: event.target.value })}
            >
              <option value="">Any type</option>
              {EMPLOYMENT_TYPES.map((type) => (
                <option key={type} value={type}>
                  {formatEmploymentType(type)}
                </option>
              ))}
            </select>
          </label>
          <div className="filter-actions">
            <button
              type="button"
              className="ghost"
              disabled={activeCount === 0}
              onClick={() => {
                const cleared = {
                  title: '',
                  location: '',
                  company: '',
                  skill: '',
                  employmentType: '',
                  category: '',
                };
                setDraft(cleared);
                applyFilters(cleared);
              }}
            >
              Clear{activeCount > 0 ? ` (${activeCount})` : ''}
            </button>
          </div>
        </div>
      </form>

      <AsyncPanel
        state={jobs}
        skeleton="table"
        skeletonCount={8}
        isEmpty={(data) => data.content.length === 0}
        emptyTitle="No jobs found"
        empty="No postings match these filters. Try removing one, or clear them all to start again."
      >
        {(data) => (
          <>
            <div className="table-wrap">
              <table className="table-jobs">
                <caption className="visually-hidden">
                  Job postings matching the current filters
                </caption>
                <thead>
                  <tr>
                    {/* Title and company are one fact — the role — so they share a cell
                        rather than spending two columns on a pairing nobody reads apart. */}
                    <th scope="col">Role</th>
                    <th scope="col">Category</th>
                    <th scope="col">Key skills</th>
                    <th scope="col">Location</th>
                    <th scope="col">Experience</th>
                    <th scope="col">Salary</th>
                    <th scope="col">Posted</th>
                  </tr>
                </thead>
                <tbody>
                  {data.content.map((job) => (
                    <tr key={job.id}>
                      <td className="wrap">
                        <Link to={`/jobs/${job.id}`} className="cell-strong">
                          {job.title}
                        </Link>
                        <span className="cell-sub">{job.company.name}</span>
                      </td>
                      <td>
                        {job.category ? (
                          <Badge tone="brand">{job.category}</Badge>
                        ) : (
                          <span className="muted">Unclassified</span>
                        )}
                      </td>
                      <td className="wrap">
                        {job.skills.length === 0 ? (
                          <span className="muted">—</span>
                        ) : (
                          <span className="cell-skills">
                            {job.skills.slice(0, SKILLS_SHOWN).map((item) => (
                              <span key={item.id} className="skill-tag">
                                {item.name}
                              </span>
                            ))}
                            {job.skills.length > SKILLS_SHOWN && (
                              <span className="muted">+{job.skills.length - SKILLS_SHOWN}</span>
                            )}
                          </span>
                        )}
                      </td>
                      <td>{formatLocation(job)}</td>
                      <td>
                        {formatExperience(job.experience)}
                        <span className="cell-sub">{formatEmploymentType(job.employmentType)}</span>
                      </td>
                      <td>{formatSalary(job.salary)}</td>
                      <td>{formatDate(job.postedDate)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              page={data.page}
              totalPages={data.totalPages}
              totalElements={data.totalElements}
              first={data.first}
              last={data.last}
              onChange={(nextPage) => applyFilters(draft, nextPage)}
            />
          </>
        )}
      </AsyncPanel>
    </>
  );
}
