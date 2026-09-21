import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { SkillAnalytics as SkillAnalyticsData, SkillAnalyticsFilters } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { BarChartPanel } from '../components/charts';
import { Pagination } from '../components/Pagination';
import { BarCell, Card, PageHeader, StatCard } from '../components/ui';
import { useApi } from '../hooks/useApi';

const PAGE_SIZE = 15;

const EMPTY_FILTERS: SkillAnalyticsFilters = {
  location: '',
  fromDate: '',
  toDate: '',
  title: '',
};

/**
 * Skills ranked by demand, within whatever scope the filters define.
 *
 * <p>The scope is stated above the results and again beside the table, because every
 * percentage on this page is a share of it. A filtered percentage that looks like a share
 * of the whole database is the easiest number on the site to misread.
 */
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

  const hasFilters = Object.values(applied).some((value) => value !== '');

  return (
    <>
      <PageHeader
        title="Skill Intelligence"
        description="Skills ranked by how many postings mention them. A posting usually needs several skills, so the percentages do not add up to 100."
      />

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
            className="ghost"
            disabled={!hasFilters}
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
        skeleton="cards"
        skeletonCount={3}
        isEmpty={(data) => data.skills.content.length === 0}
        emptyTitle="No skills in this scope"
        empty="No skills match these filters. Try widening the date range or clearing the location."
      >
        {(data) => {
          const rows = data.skills.content;
          const topCount = rows[0]?.jobCount ?? 0;
          return (
            <>
              <div className="stat-grid">
                <StatCard
                  label="Postings in scope"
                  value={data.scope.totalJobsInScope}
                  hint={hasFilters ? 'Matching these filters' : 'Every posting'}
                />
                <StatCard
                  label="Distinct skills"
                  value={data.skills.totalElements}
                  hint="Mentioned at least once"
                />
                <StatCard
                  label="Most in demand"
                  value={rows[0]?.skill ?? null}
                  hint={rows[0] ? `${rows[0].percentageOfJobs.toFixed(1)}% of postings in scope` : undefined}
                />
              </div>

              <Card
                title="Skill demand"
                description={`Share of the ${data.scope.totalJobsInScope.toLocaleString('en-US')} postings matching these filters.`}
              >
                <BarChartPanel
                  data={rows.map((row) => ({ label: row.skill, value: row.jobCount }))}
                  valueLabel="Postings"
                />
              </Card>

              <div className="table-wrap">
                <table>
                  <caption className="visually-hidden">Skills ranked by demand</caption>
                  <thead>
                    <tr>
                      <th scope="col" className="rank-cell">
                        #
                      </th>
                      <th scope="col">Skill</th>
                      <th scope="col">Category</th>
                      <th scope="col">Postings</th>
                      <th scope="col" className="numeric">
                        Share of scope
                      </th>
                      <th scope="col">
                        <span className="visually-hidden">Actions</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.skillId}>
                        <td className="rank-cell">{row.rank}</td>
                        <td className="cell-strong">{row.skill}</td>
                        <td className="muted">{row.category ?? '—'}</td>
                        {/* The bar makes the ranking a shape; the number keeps it exact. */}
                        <td className="bar-cell">
                          <BarCell value={row.jobCount} max={topCount} />
                        </td>
                        <td className="numeric">{row.percentageOfJobs.toFixed(1)}%</td>
                        <td>
                          <Link to={`/jobs?skill=${encodeURIComponent(row.skill)}`}>View jobs</Link>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <Pagination
                page={data.skills.page}
                totalPages={data.skills.totalPages}
                totalElements={data.skills.totalElements}
                first={data.skills.first}
                last={data.skills.last}
                onChange={setPage}
              />
            </>
          );
        }}
      </AsyncPanel>
    </>
  );
}
