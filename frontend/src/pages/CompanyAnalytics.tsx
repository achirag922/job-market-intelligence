import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { CompanyDemand, PagedResponse } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { BarChartPanel } from '../components/charts';
import { Pagination } from '../components/Pagination';
import { BarCell, Card, PageHeader, StatCard } from '../components/ui';
import { useApi } from '../hooks/useApi';

const PAGE_SIZE = 15;

/** Employers ranked by how many postings they have in the dataset. */
export function CompanyAnalytics() {
  const [page, setPage] = useState(0);
  const companies = useApi<PagedResponse<CompanyDemand>>(
    () => api.companyDemand(page, PAGE_SIZE),
    [page],
  );

  return (
    <>
      <PageHeader
        title="Company Analytics"
        description="Employers ranked by how many postings they have. Percentages are a share of every posting in the dataset."
      />

      <AsyncPanel
        state={companies}
        skeleton="cards"
        skeletonCount={3}
        isEmpty={(data) => data.content.length === 0}
        emptyTitle="No companies yet"
        empty="No companies have been ingested. Run the ETL to load postings."
      >
        {(data) => {
          const rows = data.content;
          const topCount = rows[0]?.jobCount ?? 0;
          // Only meaningful on the first page, where the leader actually is the leader.
          const leader = page === 0 ? rows[0] : undefined;
          return (
            <>
              <div className="stat-grid">
                <StatCard label="Companies hiring" value={data.totalElements} hint="With at least one posting" />
                <StatCard
                  label="Top employer"
                  value={leader?.company.name ?? null}
                  hint={leader ? `${leader.jobCount} postings` : 'See page 1'}
                />
                <StatCard
                  label="Industries represented"
                  value={new Set(rows.map((row) => row.company.industry).filter(Boolean)).size}
                  hint="On this page"
                />
              </div>

              <Card title="Postings by company" description="Ranked within this page of results.">
                <BarChartPanel
                  data={rows.map((row) => ({ label: row.company.name, value: row.jobCount }))}
                  valueLabel="Postings"
                />
              </Card>

              <div className="table-wrap">
                <table>
                  <caption className="visually-hidden">Companies ranked by posting count</caption>
                  <thead>
                    <tr>
                      <th scope="col" className="rank-cell">
                        #
                      </th>
                      <th scope="col">Company</th>
                      <th scope="col">Industry</th>
                      <th scope="col">Postings</th>
                      <th scope="col" className="numeric">
                        Share
                      </th>
                      <th scope="col">
                        <span className="visually-hidden">Actions</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.company.id}>
                        <td className="rank-cell">{row.rank}</td>
                        <td className="cell-strong">{row.company.name}</td>
                        <td className="muted">{row.company.industry ?? '—'}</td>
                        <td className="bar-cell">
                          <BarCell value={row.jobCount} max={topCount} />
                        </td>
                        <td className="numeric">{row.percentageOfJobs.toFixed(1)}%</td>
                        <td>
                          <Link to={`/jobs?company=${encodeURIComponent(row.company.name)}`}>
                            View jobs
                          </Link>
                        </td>
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
                onChange={setPage}
              />
            </>
          );
        }}
      </AsyncPanel>
    </>
  );
}
