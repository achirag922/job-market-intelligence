import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AdminDataQuality, AdminOverview } from '../api/types';
import { RequireAdmin } from '../auth/RequireAdmin';
import { AdminDashboard } from './AdminDashboard';

const adminOverview = vi.fn();
const adminDataQuality = vi.fn();
const adminUsers = vi.fn();
const adminUser = vi.fn();
const setJobSourceActive = vi.fn();
let role: 'USER' | 'ADMIN' = 'ADMIN';

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      adminOverview: (...args: unknown[]) => adminOverview(...args),
      adminDataQuality: (...args: unknown[]) => adminDataQuality(...args),
      adminUsers: (...args: unknown[]) => adminUsers(...args),
      adminUser: (...args: unknown[]) => adminUser(...args),
      setJobSourceActive: (...args: unknown[]) => setJobSourceActive(...args),
    },
  };
});

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ status: 'signedIn', user: { id: 'u1', email: 'admin@example.test', role, emailVerified: true, createdAt: '' } }),
}));

const overview: AdminOverview = {
  users: { total: 3, verified: 2, admins: 1, newLast30Days: 3, activeLast30Days: 1, activeDefinition: 'Accounts that saved something' },
  jobs: { total: 120, active: 110, inactive: 10, firstSeenLast7Days: 12, firstSeenLast30Days: 40 },
  etl: {
    latest: { executionId: 9, jobName: 'ingestJobPostings', outcome: 'FAILED', batchStatus: 'FAILED', exitMessage: 'Input file not found',
      recordsRead: 3, recordsProcessed: 2, recordsWritten: 0, rejected: 1 },
    recent: [], failedLast30Days: 1,
  },
  sources: [{ id: 5, code: 'board-b', name: 'board-b', sourceType: 'FILE_CSV', active: true, createdAt: '', jobCount: 40 }],
  health: { status: 'UP', components: { db: 'UP' } },
  activity: { days: 7, signups: 3, resumesUploaded: 1, jobsSaved: 4, applications: 2, interviewSessions: 1, jobsEmailedInAlerts: 5 },
};

const quality: AdminDataQuality = {
  totals: { ingestionRuns: 2, recordsRead: 13, validRecords: 10, rejected: 3, loaded: 5, duplicates: 2, expired: 1 },
  current: { jobs: 120, active: 110, inactive: 10, expiredByDate: 8, closedBySource: 2 },
  topRejectionReasons: [{ reason: 'missing description', count: 2 }],
  sources: [{ sourceId: 5, code: 'board-b', name: 'board-b', sourceType: 'FILE_CSV', active: true, jobs: 40, activeJobs: 38, inactiveJobs: 2, loaded: 41, seenAgain: 7 }],
  notes: ['Rejected records are counted by reason only; their raw input is never shown.'],
};

beforeEach(() => {
  role = 'ADMIN';
  adminOverview.mockResolvedValue(overview);
  adminDataQuality.mockResolvedValue(quality);
  adminUsers.mockResolvedValue({
    content: [{ id: 'a1', email: 'alice@example.test', fullName: 'Alice', role: 'USER', emailVerified: true, createdAt: '2026-09-01T10:00:00Z', updatedAt: '' }],
    page: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true,
  });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('AdminDashboard', () => {
  it('shows platform figures, the failed ETL run, health, data quality and users', async () => {
    render(<RequireAdmin><AdminDashboard /></RequireAdmin>);

    expect(await screen.findByText('Input file not found')).toBeTruthy();
    expect(screen.getByText('Overall UP')).toBeTruthy();
    expect(screen.getByText('db: UP')).toBeTruthy();
    expect(await screen.findByText('missing description')).toBeTruthy();
    expect(screen.getByText(/raw input is never shown/)).toBeTruthy();
    expect(await screen.findByText('alice@example.test')).toBeTruthy();
  });

  it('switches a job source off and opens an account without showing sensitive data', async () => {
    setJobSourceActive.mockResolvedValue({ ...overview.sources[0], active: false });
    adminUser.mockResolvedValue({ account: { id: 'a1', email: 'alice@example.test', role: 'USER', emailVerified: true, createdAt: '', updatedAt: '' },
      resumes: 1, savedJobs: 2, applications: 1, jobAlerts: 1, interviewSessions: 0, careerGoals: 0,
      note: 'Accounts cannot be deactivated: the user model has no active flag.' });
    render(<RequireAdmin><AdminDashboard /></RequireAdmin>);

    fireEvent.click(await screen.findByRole('button', { name: 'Disable board-b' }));
    await waitFor(() => expect(setJobSourceActive).toHaveBeenCalledWith(5, false));
    await waitFor(() => expect(adminOverview).toHaveBeenCalledTimes(2));

    fireEvent.click(await screen.findByRole('button', { name: 'Details for alice@example.test' }));
    const details = await screen.findByRole('region', { name: 'Account details' });
    expect(within(details).getByText(/1 resume\(s\) · 2 saved job\(s\) · 1 application\(s\)/)).toBeTruthy();
    expect(within(details).getByText(/cannot be deactivated/)).toBeTruthy();
  });

  it('filters users by role and verification', async () => {
    render(<RequireAdmin><AdminDashboard /></RequireAdmin>);
    await screen.findByText('alice@example.test');

    fireEvent.change(screen.getByLabelText('Role'), { target: { value: 'ADMIN' } });
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'false' } });
    await waitFor(() => expect(adminUsers).toHaveBeenLastCalledWith({ q: undefined, role: 'ADMIN', verified: 'false', page: 0, size: 20 }));
  });

  it('shows a USER an "Admins only" message and calls no admin API', () => {
    role = 'USER';
    render(<RequireAdmin><AdminDashboard /></RequireAdmin>);

    expect(screen.getByText('Admins only')).toBeTruthy();
    expect(adminOverview).not.toHaveBeenCalled();
    expect(adminUsers).not.toHaveBeenCalled();
  });
});
