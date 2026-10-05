import { MemoryRouter } from 'react-router-dom';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { UserAnalytics } from '../api/types';
import { MyAnalytics } from './MyAnalytics';

const userAnalytics = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return { ...actual, api: { userAnalytics: (...args: unknown[]) => userAnalytics(...args) } };
});

function analytics(overrides: Partial<UserAnalytics> = {}): UserAnalytics {
  return {
    range: '30D', from: '2026-09-01', to: '2026-09-30', bucket: 'DAY',
    kpis: { jobsSaved: 2, applications: 2, interviews: 1, offers: 0, averageMatch: 83.3, interviewsCompleted: 2,
      averageInterviewScore: 2.8, learningCompleted: 1 },
    activity: [{ label: '2026-09-29', saved: 1, applied: 1, interviews: 0, offers: 0 }],
    funnel: { applied: 2, interviewed: 1, offers: 0, applyToInterviewRate: 50, interviewToOfferRate: 0 },
    statusBreakdown: { SAVED: 0, APPLIED: 1, INTERVIEW: 1, OFFER: 0, REJECTED: 0, WITHDRAWN: 0 },
    resumeTrend: [{ title: 'CV v2', date: '2026-09-25', skills: 2, averageMatch: 83.3, missingSkills: 1, jobsCompared: 3 }],
    missingSkills: [{ skill: 'Kubernetes', jobs: 1 }],
    interviews: { completed: 2, averageScore: 2.8, firstScore: 2, latestScore: 3.5,
      sessions: [{ date: '2026-09-10', jobTitle: 'Engineer', overall: 2, technical: 2 }] },
    learning: { items: 3, completed: 1, inProgress: 1, notStarted: 1, startedInRange: 2, completedInRange: 1,
      completionRate: 33.3, activity: [{ label: '2026-09-29', started: 1, completed: 0 }] },
    portfolio: { exists: true, visibility: 'PUBLIC', updatedAt: '2026-09-29T10:00:00Z', publishedAt: '2026-09-28T10:00:00Z' },
    insights: ['You applied to 2 jobs in this period, up from 1 in the period before.'],
    notes: ['Job search activity is measured by the jobs you saved and their status changes; searches themselves are not recorded.'],
    ...overrides,
  };
}

beforeEach(() => userAnalytics.mockResolvedValue(analytics()));

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('MyAnalytics', () => {
  it('shows KPIs, insights, the funnel, learning and portfolio for the default 30-day range', async () => {
    render(<MemoryRouter><MyAnalytics /></MemoryRouter>);
    expect(await screen.findByText('You applied to 2 jobs in this period, up from 1 in the period before.')).toBeTruthy();
    expect(userAnalytics).toHaveBeenCalledWith('30D');
    expect(screen.getByText('83.3%')).toBeTruthy();
    expect(screen.getByText('2.8/5')).toBeTruthy();
    expect(screen.getByText(/Applied → interview: 50.0% · Interview → offer: 0.0%/)).toBeTruthy();
    expect(screen.getByText(/1 completed · 1 in progress · 1 not started · 33.3% complete/)).toBeTruthy();
    expect(screen.getByText(/Public · last updated 2026-09-29 · published 2026-09-28/)).toBeTruthy();
    expect(screen.getByRole('list', { name: 'About these analytics' })).toBeTruthy();
  });

  it('reloads for another time range', async () => {
    render(<MemoryRouter><MyAnalytics /></MemoryRouter>);
    await screen.findByText('83.3%');
    fireEvent.click(screen.getByRole('button', { name: '90D' }));
    await waitFor(() => expect(userAnalytics).toHaveBeenCalledWith('90D'));
    expect(screen.getByRole('button', { name: '90D' }).getAttribute('aria-pressed')).toBe('true');
  }, 15000);

  it('shows empty states and dashes when there is not enough data', async () => {
    userAnalytics.mockResolvedValue(analytics({
      kpis: { jobsSaved: 0, applications: 0, interviews: 0, offers: 0, interviewsCompleted: 0, learningCompleted: 0 },
      activity: [], funnel: { applied: 0, interviewed: 0, offers: 0 }, resumeTrend: [], missingSkills: [],
      interviews: { completed: 0, sessions: [] },
      learning: { items: 0, completed: 0, inProgress: 0, notStarted: 0, startedInRange: 0, completedInRange: 0, activity: [] },
      portfolio: { exists: false }, insights: [],
    }));
    render(<MemoryRouter><MyAnalytics /></MemoryRouter>);
    expect(await screen.findByText('No activity in this range')).toBeTruthy();
    expect(screen.getByText('No applications in this range')).toBeTruthy();
    expect(screen.getByText('No resume versions in this range')).toBeTruthy();
    expect(screen.getByText('No completed interviews in this range')).toBeTruthy();
    expect(screen.getByText('No learning items yet')).toBeTruthy();
    expect(screen.getByText('You have not created a portfolio yet.')).toBeTruthy();
    expect(screen.queryByRole('list', { name: 'Insights' })).toBeNull();
  });
});
