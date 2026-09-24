import { useState } from 'react';
import { ApiError, api } from '../api/client';
import type { EtlRun, EtlRunOutcome, PagedResponse } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { Pagination } from '../components/Pagination';
import { Badge, Card, PageHeader, StatCard } from '../components/ui';
import { useApi } from '../hooks/useApi';

const PAGE_SIZE = 10;

const OUTCOME: Record<EtlRunOutcome, { label: string; tone: 'success' | 'danger' | 'brand' | 'warning' }> = {
  SUCCEEDED: { label: 'Succeeded', tone: 'success' },
  FAILED: { label: 'Failed', tone: 'danger' },
  RUNNING: { label: 'Running', tone: 'brand' },
  STOPPED: { label: 'Stopped', tone: 'warning' },
};

/**
 * ETL run history, read from Spring Batch's own job repository.
 *
 * <p>Refreshed on demand rather than streamed: a run takes seconds to minutes, and a
 * button is enough to watch one finish.
 */
export function EtlMonitoring() {
  const [page, setPage] = useState(0);
  const [refresh, setRefresh] = useState(0);

  // "Never run" is an expected state, not a failure, so its 404 becomes an empty panel.
  // Wrapped because AsyncPanel reads bare null data as "nothing returned".
  const latest = useApi<{ run: EtlRun | null }>(
    () =>
      api.latestEtlRun().then(
        (run) => ({ run }),
        (error: unknown) => {
          if (error instanceof ApiError && error.status === 404) {
            return { run: null };
          }
          throw error;
        },
      ),
    [refresh],
  );
  const runs = useApi<PagedResponse<EtlRun>>(() => api.etlRuns(page, PAGE_SIZE), [page, refresh]);

  const reload = () => setRefresh((count) => count + 1);

  return (
    <>
      <PageHeader
        title="ETL Monitoring"
        description="Runs of the ingestion and reprocessing jobs, as recorded by Spring Batch. Times are the ETL server's local time."
        actions={
          <button type="button" onClick={reload}>
            Refresh
          </button>
        }
      />

      <Card title="Latest run">
        <AsyncPanel
          state={latest}
          onRetry={reload}
          skeleton="cards"
          skeletonCount={3}
          isEmpty={(data) => data.run === null}
          emptyTitle="No ETL runs yet"
          empty="Nothing has been ingested into this database. Run the ETL job and its runs will appear here."
        >
          {({ run }) => run && <LatestRun run={run} />}
        </AsyncPanel>
      </Card>

      <Card title="Recent runs" description="Newest first.">
        <AsyncPanel
          state={runs}
          onRetry={reload}
          skeleton="table"
          skeletonCount={5}
          isEmpty={(data) => data.content.length === 0}
          emptyTitle="No run history"
          empty="Spring Batch has not recorded any ETL run yet."
        >
          {(data) => (
            <>
              <div className="table-wrap">
                <table>
                  <caption className="visually-hidden">Recent ETL runs</caption>
                  <thead>
                    <tr>
                      <th scope="col" className="rank-cell">#</th>
                      <th scope="col">Job</th>
                      <th scope="col">Status</th>
                      <th scope="col">Started</th>
                      <th scope="col">Duration</th>
                      <th scope="col" className="tabular">Read</th>
                      <th scope="col" className="tabular">Processed</th>
                      <th scope="col" className="tabular">Loaded</th>
                      <th scope="col" className="tabular">Duplicates</th>
                      <th scope="col" className="tabular">Rejected</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.content.map((run) => (
                      <tr key={run.executionId}>
                        <td className="rank-cell tabular">{run.executionId}</td>
                        <td>{run.jobName}</td>
                        <td>
                          <OutcomeBadge outcome={run.outcome} />
                        </td>
                        <td>{formatDateTime(run.startTime)}</td>
                        <td className="tabular">{formatDuration(run.durationMillis)}</td>
                        <td className="tabular">{formatCount(run.recordsRead)}</td>
                        <td className="tabular">{formatCount(run.recordsProcessed)}</td>
                        <td className="tabular">{formatCount(run.recordsLoaded)}</td>
                        <td className="tabular">{formatCount(run.duplicates)}</td>
                        <td className="tabular">{formatCount(run.rejected)}</td>
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
          )}
        </AsyncPanel>
      </Card>
    </>
  );
}

function LatestRun({ run }: { run: EtlRun }) {
  return (
    <div className="stack" style={{ gap: 16 }}>
      <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
        <OutcomeBadge outcome={run.outcome} />
        <strong>{run.jobName}</strong>
        <span className="muted">Execution #{run.executionId}</span>
        <span className="muted">Started {formatDateTime(run.startTime)}</span>
        <span className="muted">
          {run.endTime ? `Ended ${formatDateTime(run.endTime)}` : run.outcome === 'RUNNING' ? 'Still running' : 'No end time recorded'}
        </span>
      </div>

      {run.outcome === 'FAILED' && run.exitMessage && (
        <p className="status status-error" role="alert" style={{ whiteSpace: 'pre-wrap', margin: 0 }}>
          {run.exitMessage}
        </p>
      )}

      <div className="stat-grid">
        <StatCard label="Duration" value={formatDuration(run.durationMillis)} hint={run.outcome === 'RUNNING' ? 'So far' : undefined} />
        <StatCard label="Records read" value={run.recordsRead} hint="From the input file" />
        <StatCard label="Records processed" value={run.recordsProcessed} hint="Read minus rejected" />
        <StatCard label="Records loaded" value={run.recordsLoaded ?? null} hint={run.recordsLoaded === undefined ? 'Recorded when the run ends' : 'Written to the database'} />
        <StatCard label="Duplicates" value={run.duplicates ?? null} hint="Already loaded, skipped" />
        <StatCard label="Rejected" value={run.rejected} hint="Failed validation" />
      </div>
    </div>
  );
}

function OutcomeBadge({ outcome }: { outcome: EtlRunOutcome }) {
  const { label, tone } = OUTCOME[outcome] ?? OUTCOME.STOPPED;
  return <Badge tone={tone}>{label}</Badge>;
}

function formatCount(value?: number): string {
  return value === undefined || value === null ? '—' : value.toLocaleString();
}

function formatDateTime(value?: string): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

/** Short and exact enough for a run: "850 ms", "12.3 s", "3m 05s", "1h 02m". */
export function formatDuration(millis?: number): string {
  if (millis === undefined || millis === null) {
    return '—';
  }
  if (millis < 1000) {
    return `${millis} ms`;
  }
  const seconds = millis / 1000;
  if (seconds < 60) {
    return `${seconds.toFixed(1)} s`;
  }
  const totalSeconds = Math.floor(seconds);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const rest = totalSeconds % 60;
  return hours > 0
    ? `${hours}h ${String(minutes).padStart(2, '0')}m`
    : `${minutes}m ${String(rest).padStart(2, '0')}s`;
}
