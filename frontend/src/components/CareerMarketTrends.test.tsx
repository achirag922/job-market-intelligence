import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { MarketTrends } from '../api/types';
import { CareerMarketTrends } from './CareerMarketTrends';

const marketTrends = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return { ...actual, api: { marketTrends: (...args: unknown[]) => marketTrends(...args) } };
});

// Recharts needs a measured container; the chart itself is covered in charts.test.tsx.
vi.mock('./charts', () => ({ MultiLineChartPanel: () => <div data-testid="chart" /> }));

const periods = { earlierFrom: '2026-01-01', earlierTo: '2026-03-01', recentFrom: '2026-05-01', recentTo: '2026-07-01' };

const trends: MarketTrends = {
  category: 'Backend Developer',
  period: { requestedMonths: 12, fromMonth: '2025-08-01', toMonth: '2026-07-01', coveredMonths: 6,
    monthsWithoutData: ['2026-04-01'], latestPostedDate: '2026-07-10', source: 'Postings in JMIP classified as Backend Developer' },
  volume: [{ month: '2026-07-01', postings: 8, allPostings: 10, sharePercentage: 80 }],
  volumeTrend: { direction: 'INCREASING', change: 133.3, unit: 'PERCENT', ...periods, basis: 'Average postings per month with data' },
  shareTrend: { direction: 'INCREASING', change: 17.8, unit: 'PERCENTAGE_POINTS', ...periods },
  skills: { source: 'Postings in this role, by posting month', ...periods,
    growing: [{ skillId: 3, skill: 'Docker', postings: 21, earlierSharePercentage: 0, recentSharePercentage: 100, changeInPercentagePoints: 100, direction: 'INCREASING' }],
    declining: [] },
  salary: { currency: 'EUR', series: [], trend: { direction: 'INSUFFICIENT_DATA', note: 'Fewer than 3 postings state a salary in EUR in one of the two periods.' } },
  locations: [{ locationId: 1, location: 'Berlin, Germany', postings: 30, trend: { direction: 'STABLE', change: 2.1, unit: 'PERCENT', ...periods } }],
  workModes: [],
  workModeTrends: [{ mode: 'REMOTE', trend: { direction: 'DECREASING', change: -12.5, unit: 'PERCENTAGE_POINTS', ...periods } }],
  forecast: { status: 'ESTIMATE', label: 'Estimate, not a prediction: a straight line fitted to past monthly postings.', method: 'OLS',
    basedOnMonths: 6, fromMonth: '2026-01-01', toMonth: '2026-07-01', slopePerMonth: 1, rSquared: 1,
    estimates: [{ month: '2026-08-01', estimatedPostings: 9 }, { month: '2026-09-01', estimatedPostings: 10 }] },
  notes: ['1 month(s) in this period have no postings in JMIP at all; they are shown as gaps and left out of every comparison.'],
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('CareerMarketTrends', () => {
  it('labels historical trends with their periods and keeps the estimate separate', async () => {
    marketTrends.mockResolvedValue(trends);
    render(<CareerMarketTrends categories={[{ category: 'Backend Developer', jobCount: 30, percentageOfJobs: 50, rank: 1 }]} />);

    expect(await screen.findByText(/Historical data: Aug 2025–Jul 2026 · 6 month\(s\) with data/)).toBeTruthy();
    expect(screen.getByText(/have no postings in JMIP at all/)).toBeTruthy();
    expect(screen.getAllByText('Increasing').length).toBeGreaterThan(0);
    expect(screen.getByText('+133.3%', { exact: false })).toBeTruthy();
    expect(screen.getAllByText('(Jan 2026–Mar 2026 vs May 2026–Jul 2026)').length).toBeGreaterThan(0);
    expect(screen.getByText('+17.8 pts', { exact: false })).toBeTruthy();
    expect(screen.getByText('Docker')).toBeTruthy();
    expect(screen.getByText(/Fewer than 3 postings state a salary in EUR/)).toBeTruthy();
    expect(screen.getByText('-12.5 pts', { exact: false })).toBeTruthy();
    expect(screen.getByText('Estimate')).toBeTruthy();
    expect(screen.getByText(/Estimate, not a prediction/)).toBeTruthy();
    expect(screen.getByText(/Aug 2026: ~9 · Sep 2026: ~10/)).toBeTruthy();
    expect(marketTrends).toHaveBeenCalledWith({ category: undefined, months: 12 });
  });

  it('shows Insufficient data instead of an estimate, and refetches for the chosen role and range', async () => {
    marketTrends.mockResolvedValue({
      ...trends,
      forecast: { status: 'INSUFFICIENT_DATA', label: '', method: '', estimates: [],
        note: 'Insufficient data: an estimate needs at least 6 months with data; this selection has 2.' },
    });
    render(<CareerMarketTrends categories={[{ category: 'Backend Developer', jobCount: 30, percentageOfJobs: 50, rank: 1 }]} />);

    expect(await screen.findByText(/an estimate needs at least 6 months/)).toBeTruthy();
    expect(screen.getAllByText('Insufficient data').length).toBeGreaterThan(0);
    expect(screen.queryByText(/Aug 2026: ~9/)).toBeNull();

    fireEvent.change(screen.getByLabelText('Role'), { target: { value: 'Backend Developer' } });
    fireEvent.change(screen.getByLabelText('Time range'), { target: { value: '24' } });
    await waitFor(() => expect(marketTrends).toHaveBeenLastCalledWith({ category: 'Backend Developer', months: 24 }));
  });
});
