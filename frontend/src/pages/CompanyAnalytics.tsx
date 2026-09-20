import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { CompanyDemand, PagedResponse } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { DemandBarChart } from '../components/DemandBarChart';
import { Pagination } from '../components/Pagination';
import { useApi } from '../hooks/useApi';

const PAGE_SIZE = 15;

export function CompanyAnalytics() {
  const [page, setPage] = useState(0);
  const companies = useApi<PagedResponse<CompanyDemand>>(
    () => api.companyDemand(page, PAGE_SIZE),
    [page],
  );

  return (
    <section>
      <h1>Company Analytics</h1>
      <p className="subtitle">Companies ranked by how many postings they have.</p>

      <AsyncPanel
        state={companies}
        isEmpty={(data) => data.content.length === 0}
        empty="No companies yet."
      >
        {(data) => (
          <>
            <div className="card">
              <DemandBarChart
                data={data.content.map((row) => ({
                  label: row.company.name,
                  value: row.jobCount,
                }))}
                height={Math.max(260, data.content.length * 28)}
              />
            </div>

            <table>
              <thead>
                <tr>
                  <th>Company</th>
                  <th>Industry</th>
                  <th>Job count</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((row) => (
                  <tr key={row.company.id}>
                    <td>{row.company.name}</td>
                    <td>{row.company.industry ?? '—'}</td>
                    <td>{row.jobCount}</td>
                    <td>
                      <Link to={`/jobs?company=${encodeURIComponent(row.company.name)}`}>
                        View jobs
                      </Link>
                    </td>
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
              onChange={setPage}
            />
          </>
        )}
      </AsyncPanel>
    </section>
  );
}
