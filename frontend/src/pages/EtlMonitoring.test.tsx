import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import type { EtlRun, PagedResponse } from '../api/types';
import { EtlMonitoring, formatDuration } from './EtlMonitoring';

const latestEtlRun = vi.fn();
const etlRuns = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      latestEtlRun: (...args: unknown[]) => latestEtlRun(...args),
      etlRuns: (...args: unknown[]) => etlRuns(...args),
    },
  };
});

function run(overrides: Partial<EtlRun> = {}): EtlRun {
  return {
    executionId: 7,
    jobName: 'ingestJobPostings',
    outcome: 'SUCCEEDED',
    batchStatus: 'COMPLETED',
    startTime: '2026-09-24T10:00:00',
    endTime: '2026-09-24T10:00:12',
    durationMillis: 12_000,
    recordsRead: 1250,
    recordsProcessed: 1247,
    recordsWritten: 1247,
    recordsLoaded: 1200,
    duplicates: 47,
    rejected: 3,
    ...overrides,
  };
}

function page(content: EtlRun[]): PagedResponse<EtlRun> {
  return { content, page: 0, size: 10, totalElements: content.length, totalPages: content.length ? 1 : 0, first: true, last: true };
}

describe('EtlMonitoring', () => {
  beforeEach(() => {
    latestEtlRun.mockReset();
    etlRuns.mockReset();
    latestEtlRun.mockResolvedValue(run());
    etlRuns.mockResolvedValue(
      page([
        run(),
        run({ executionId: 6, outcome: 'FAILED', batchStatus: 'FAILED', recordsLoaded: undefined, duplicates: undefined }),
        run({ executionId: 5, outcome: 'RUNNING', batchStatus: 'STARTED', endTime: undefined }),
      ]),
    );
  });

  afterEach(cleanup);

  it('shows the latest run with its KPI cards', async () => {
    render(<EtlMonitoring />);

    expect(await screen.findByText('Execution #7')).toBeInTheDocument();
    // Some labels also head table columns, so pick the one inside a KPI card.
    const stat = (label: string) =>
      screen.getAllByText(label).map((element) => element.closest('.stat-card')).find(Boolean) as HTMLElement;
    expect(within(stat('Records read')).getByText('1,250')).toBeInTheDocument();
    expect(within(stat('Records processed')).getByText('1,247')).toBeInTheDocument();
    expect(within(stat('Records loaded')).getByText('1,200')).toBeInTheDocument();
    expect(within(stat('Duplicates')).getByText('47')).toBeInTheDocument();
    expect(within(stat('Rejected')).getByText('3')).toBeInTheDocument();
    expect(within(stat('Duration')).getByText('12.0 s')).toBeInTheDocument();
  });

  it('lists recent runs with distinct succeeded, failed and running states', async () => {
    render(<EtlMonitoring />);

    const table = await screen.findByRole('table', { name: 'Recent ETL runs' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(3);
    expect(within(rows[0]).getByText('Succeeded')).toBeInTheDocument();
    expect(within(rows[1]).getByText('Failed')).toBeInTheDocument();
    // Counters Spring Batch cannot know are unknown, not zero.
    expect(within(rows[1]).getAllByText('—')).toHaveLength(2);
    expect(within(rows[2]).getByText('Running')).toBeInTheDocument();
    expect(etlRuns).toHaveBeenCalledWith(0, 10);
  });

  it('shows the failure message of a failed latest run', async () => {
    latestEtlRun.mockResolvedValue(run({ outcome: 'FAILED', batchStatus: 'FAILED', exitMessage: 'Input file not found' }));
    render(<EtlMonitoring />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Input file not found');
  });

  it('treats a missing latest run and an empty history as empty states', async () => {
    latestEtlRun.mockRejectedValue(new ApiError(404, 'No ETL run has been recorded yet'));
    etlRuns.mockResolvedValue(page([]));
    render(<EtlMonitoring />);

    expect(await screen.findByText('No ETL runs yet')).toBeInTheDocument();
    expect(await screen.findByText('No run history')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows API errors and retries on refresh', async () => {
    latestEtlRun.mockRejectedValueOnce(new ApiError(0, 'Cannot reach the API.'));
    etlRuns.mockRejectedValueOnce(new ApiError(500, 'Server error'));
    render(<EtlMonitoring />);

    const alerts = await screen.findAllByRole('alert');
    expect(alerts.map((alert) => alert.textContent).join(' ')).toMatch(/Cannot reach the API\.[\s\S]*Server error/);

    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await screen.findByText('Execution #7')).toBeInTheDocument();
    expect(latestEtlRun).toHaveBeenCalledTimes(2);
    expect(etlRuns).toHaveBeenCalledTimes(2);
  });
});

describe('formatDuration', () => {
  it('formats short and long runs', () => {
    expect(formatDuration(undefined)).toBe('—');
    expect(formatDuration(850)).toBe('850 ms');
    expect(formatDuration(12_340)).toBe('12.3 s');
    expect(formatDuration(185_000)).toBe('3m 05s');
    expect(formatDuration(3_720_000)).toBe('1h 02m');
  });
});
