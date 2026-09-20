import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { SkillAnalytics as SkillAnalyticsData, SkillAnalyticsFilters } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { DemandBarChart } from '../components/DemandBarChart';
import { Pagination } from '../components/Pagination';
import { useApi } from '../hooks/useApi';

const PAGE_SIZE = 15;

const EMPTY_FILTERS: SkillAnalyticsFilters = {
  location: '',
  fromDate: '',
  toDate: '',
  title: '',
};

export function SkillAnalytics() {
  const [applied, setApplied] = useState<SkillAnalyticsFilters>(EMPTY_FILTERS);
  const [draft, setDraft] = useState<SkillAnalyticsFilters>(EMPTY_FILTERS);
  const [page, setPage] = useState(0);

  // A new filter changes which rows exist, so the current page number no longer means
  // anything; go back to the first page rather than showing an empty one.
  useEffect(() => {
    setPage(0);
  }, [applied]);

  const analytics = useApi<SkillAnalyticsData>(
    () => api.skillDemand(applied, page, PAGE_SIZE),
    [applied, page],
  );

  return (
    <section>
      <h1>Skill Analytics</h1>
      <p className="subtitle">
        Skills ranked by how many postings mention them. A posting usually needs several
        skills, so the percentages do not add up to 100.
      </p>

      <form
        className="filters"
        onSubmit={(event) => {
          event.preventDefault();
          setApplied(draft);
        }}
      >
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
          Job title contains
          <input
            type="text"
            value={draft.title}
            placeholder="e.g. engineer"
            onChange={(event) => setDraft({ ...draft, title: event.target.value })}
          />
        </label>
        <label>
          Posted from
          <input
            type="date"
            value={draft.fromDate}
            onChange={(event) => setDraft({ ...draft, fromDate: event.target.value })}
          />
        </label>
        <label>
          Posted to
          <input
            type="date"
            value={draft.toDate}
            onChange={(event) => setDraft({ ...draft, toDate: event.target.value })}
          />
        </label>
        <div className="filter-actions">
          <button type="submit">Apply</button>
          <button
            type="button"
            onClick={() => {
              setDraft(EMPTY_FILTERS);
              setApplied(EMPTY_FILTERS);
            }}
          >
            Clear
          </button>
        </div>
      </form>

      <AsyncPanel
        state={analytics}
        isEmpty={(data) => data.skills.content.length === 0}
        empty="No skills match these filters."
      >
        {(data) => (
          <>
            {/* The denominator is stated explicitly, so a filtered percentage cannot be
                mistaken for a share of the whole database. */}
            <p className="subtitle">
              Percentages are of the <strong>{data.scope.totalJobsInScope.toLocaleString('en-US')}</strong>{' '}
              postings matching these filters.
            </p>

            <div className="card">
              <DemandBarChart
                data={data.skills.content.map((row) => ({ label: row.skill, value: row.jobCount }))}
                height={Math.max(260, data.skills.content.length * 28)}
              />
            </div>

            <table>
              <thead>
                <tr>
                  <th>Rank</th>
                  <th>Skill</th>
                  <th>Category</th>
                  <th>Job count</th>
                  <th>Percentage of jobs</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {data.skills.content.map((row) => (
                  <tr key={row.skillId}>
                    <td>{row.rank}</td>
                    <td>{row.skill}</td>
                    <td>{row.category ?? '—'}</td>
                    <td>{row.jobCount}</td>
                    <td>{row.percentageOfJobs.toFixed(1)}%</td>
                    <td>
                      <Link to={`/jobs?skill=${encodeURIComponent(row.skill)}`}>View jobs</Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            <Pagination
              page={data.skills.page}
              totalPages={data.skills.totalPages}
              totalElements={data.skills.totalElements}
              first={data.skills.first}
              last={data.skills.last}
              onChange={setPage}
            />
          </>
        )}
      </AsyncPanel>
    </section>
  );
}
