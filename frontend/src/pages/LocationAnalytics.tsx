import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { LocationDemand, PagedResponse } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { BarChartPanel } from '../components/charts';
import { Pagination } from '../components/Pagination';
import { BarCell, Card, EmptyState, PageHeader, StatCard } from '../components/ui';
import { IconSearch } from '../components/icons';
import { useApi } from '../hooks/useApi';

const PAGE_SIZE = 15;

/**
 * Where the postings are.
 *
 * <p>The location endpoint returns a ranked page with no name filter, so the search box
 * here narrows the page that was already fetched rather than asking the server for a
 * filtered one. That keeps it to one request and avoids inventing a backend parameter —
 * and the label says "on this page" so the scope is never misread.
 */
export function LocationAnalytics() {
  const [page, setPage] = useState(0);
  const [query, setQuery] = useState('');

  const locations = useApi<PagedResponse<LocationDemand>>(
    () => api.locationDemand(page, PAGE_SIZE),
    [page],
  );

  const filtered = useMemo(() => {
    const rows = locations.data?.content ?? [];
    const needle = query.trim().toLowerCase();
    if (needle === '') {
      return rows;
    }
    return rows.filter((row) => row.location.displayName.toLowerCase().includes(needle));
  }, [locations.data, query]);

  return (
    <>
      <PageHeader
        title="Location Analytics"
        description="Postings per location. Remote postings have no location and are not counted here, so the shares total less than 100%."
      />

      <AsyncPanel
        state={locations}
        skeleton="cards"
        skeletonCount={3}
        isEmpty={(data) => data.content.length === 0}
        emptyTitle="No located postings"
        empty="No posting in the dataset carries a location yet."
      >
        {(data) => {
          const topCount = filtered[0]?.jobCount ?? 0;
          const leader = page === 0 ? data.content[0] : undefined;
          const countries = new Set(data.content.map((row) => row.location.country));
          return (
            <>
              <div className="stat-grid">
                <StatCard label="Locations" value={data.totalElements} hint="With at least one posting" />
                <StatCard
                  label="Top location"
                  value={leader?.location.displayName ?? null}
                  hint={leader ? `${leader.jobCount} postings` : 'See page 1'}
                />
                <StatCard label="Countries" value={countries.size} hint="On this page" />
              </div>

              <Card
                title="Postings by location"
                description="Ranked within this page of results."
                actions={
                  <div className="search-inline">
                    <span className="search-row-icon" aria-hidden="true">
                      <IconSearch size={15} />
                    </span>
                    <input
                      type="search"
                      value={query}
                      placeholder="Filter this page"
                      aria-label="Filter locations on this page"
                      onChange={(event) => setQuery(event.target.value)}
                    />
                  </div>
                }
              >
                {filtered.length === 0 ? (
                  <EmptyState
                    title="No match on this page"
                    message={`Nothing on page ${page + 1} matches "${query}". Try another page, or clear the filter.`}
                  />
                ) : (
                  <BarChartPanel
                    data={filtered.map((row) => ({
                      label: row.location.displayName,
                      value: row.jobCount,
                    }))}
                    valueLabel="Postings"
                  />
                )}
              </Card>

              {filtered.length > 0 && (
                <div className="table-wrap">
                  <table>
                    <caption className="visually-hidden">Locations ranked by posting count</caption>
                    <thead>
                      <tr>
                        <th scope="col" className="rank-cell">
                          #
                        </th>
                        <th scope="col">Location</th>
                        <th scope="col">Country</th>
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
                      {filtered.map((row) => (
                        <tr key={row.location.id}>
                          <td className="rank-cell">{row.rank}</td>
                          <td className="cell-strong">{row.location.displayName}</td>
                          <td className="muted">{row.location.country}</td>
                          <td className="bar-cell">
                            <BarCell value={row.jobCount} max={topCount} />
                          </td>
                          <td className="numeric">{row.percentageOfJobs.toFixed(1)}%</td>
                          <td>
                            <Link to={`/jobs?location=${encodeURIComponent(row.location.country)}`}>
                              View jobs
                            </Link>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

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
