import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type { CategoryDemand, JobSummary, PagedResponse } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { Pagination } from '../components/Pagination';
import {
  formatDate,
  formatEmploymentType,
  formatExperience,
  formatLocation,
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

  return (
    <section>
      <h1>Job Explorer</h1>

      <form
        className="filters"
        onSubmit={(event) => {
          event.preventDefault();
          applyFilters(draft);
        }}
      >
        <label>
          Search title
          <input
            type="text"
            value={draft.title}
            placeholder="e.g. engineer"
            onChange={(event) => setDraft({ ...draft, title: event.target.value })}
          />
        </label>
        <label>
          Location
          <input
            type="text"
            value={draft.location}
            placeholder="e.g. India"
            onChange={(event) => setDraft({ ...draft, location: event.target.value })}
          />
        </label>
        <label>
          Company
          <input
            type="text"
            value={draft.company}
            placeholder="e.g. Halcyon"
            onChange={(event) => setDraft({ ...draft, company: event.target.value })}
          />
        </label>
        <label>
          Skill
          <input
            type="text"
            value={draft.skill}
            placeholder="e.g. Java"
            onChange={(event) => setDraft({ ...draft, skill: event.target.value })}
          />
        </label>
        <label>
          Employment type
          <select
            value={draft.employmentType}
            onChange={(event) => setDraft({ ...draft, employmentType: event.target.value })}
          >
            <option value="">Any</option>
            {EMPLOYMENT_TYPES.map((type) => (
              <option key={type} value={type}>
                {formatEmploymentType(type)}
              </option>
            ))}
          </select>
        </label>
        <label>
          Job category
          {/* Options come from the classified data, so the list can never offer a
              category that no posting actually carries. */}
          <select
            value={draft.category}
            onChange={(event) => setDraft({ ...draft, category: event.target.value })}
          >
            <option value="">Any</option>
            {(categories.data ?? []).map((option) => (
              <option key={option.category} value={option.category}>
                {option.category} ({option.jobCount})
              </option>
            ))}
          </select>
        </label>
        <div className="filter-actions">
          <button type="submit">Search</button>
          <button
            type="button"
            onClick={() => {
              const cleared = { title: '', location: '', company: '', skill: '', employmentType: '', category: '' };
              setDraft(cleared);
              applyFilters(cleared);
            }}
          >
            Clear
          </button>
        </div>
      </form>

      <AsyncPanel
        state={jobs}
        isEmpty={(data) => data.content.length === 0}
        empty="No jobs match these filters."
      >
        {(data) => (
          <>
            <table>
              <thead>
                <tr>
                  <th>Title</th>
                  <th>Company</th>
                  <th>Category</th>
                  <th>Location</th>
                  <th>Experience</th>
                  <th>Employment type</th>
                  <th>Posted</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((job) => (
                  <tr key={job.id}>
                    <td>
                      <Link to={`/jobs/${job.id}`}>{job.title}</Link>
                    </td>
                    <td>{job.company.name}</td>
                    <td>{job.category ?? '—'}</td>
                    <td>{formatLocation(job)}</td>
                    <td>{formatExperience(job.experience)}</td>
                    <td>{formatEmploymentType(job.employmentType)}</td>
                    <td>{formatDate(job.postedDate)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
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
    </section>
  );
}
