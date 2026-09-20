import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { LocationDemand, PagedResponse } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { DemandBarChart } from '../components/DemandBarChart';
import { Pagination } from '../components/Pagination';
import { useApi } from '../hooks/useApi';

const PAGE_SIZE = 15;

export function LocationAnalytics() {
  const [page, setPage] = useState(0);
  const locations = useApi<PagedResponse<LocationDemand>>(
    () => api.locationDemand(page, PAGE_SIZE),
    [page],
  );

  return (
    <section>
      <h1>Location Analytics</h1>
      <p className="subtitle">
        Postings per location. Remote postings have no location and are not counted here.
      </p>

      <AsyncPanel
        state={locations}
        isEmpty={(data) => data.content.length === 0}
        empty="No located postings yet."
      >
        {(data) => (
          <>
            <div className="card">
              <DemandBarChart
                data={data.content.map((row) => ({
                  label: row.location.displayName,
                  value: row.jobCount,
                }))}
                height={Math.max(260, data.content.length * 28)}
              />
            </div>

            <table>
              <thead>
                <tr>
                  <th>Location</th>
                  <th>City</th>
                  <th>Country</th>
                  <th>Job count</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((row) => (
                  <tr key={row.location.id}>
                    <td>{row.location.displayName}</td>
                    <td>{row.location.city ?? '—'}</td>
                    <td>{row.location.country}</td>
                    <td>{row.jobCount}</td>
                    <td>
                      <Link to={`/jobs?location=${encodeURIComponent(row.location.country)}`}>
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
