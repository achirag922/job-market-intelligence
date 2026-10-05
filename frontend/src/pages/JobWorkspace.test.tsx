import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { JobSummary, JobWorkspace, PagedResponse } from '../api/types';
import { JobExplorer } from './JobExplorer';
import { JobWorkspacePage } from './JobWorkspace';

const workspace = vi.fn();
const unhideJob = vi.fn();
const deleteSavedSearch = vi.fn();
const jobs = vi.fn();
const jobMatches = vi.fn();
const savedSearches = vi.fn();
const saveSearch = vi.fn();
const hideJob = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      workspace: (...a: unknown[]) => workspace(...a),
      unhideJob: (...a: unknown[]) => unhideJob(...a),
      deleteSavedSearch: (...a: unknown[]) => deleteSavedSearch(...a),
      jobs: (...a: unknown[]) => jobs(...a),
      jobMatches: (...a: unknown[]) => jobMatches(...a),
      savedSearches: (...a: unknown[]) => savedSearches(...a),
      saveSearch: (...a: unknown[]) => saveSearch(...a),
      hideJob: (...a: unknown[]) => hideJob(...a),
      jobCategories: () => Promise.resolve([]),
      salaryCurrencies: () => Promise.resolve([]),
      skills: () => Promise.resolve([]),
      companies: () => Promise.resolve([]),
      locations: () => Promise.resolve([]),
    },
  };
});

function job(id: number, title: string): JobSummary {
  return { id, title, company: { id: 1, name: 'Acme' }, skills: [], category: 'Backend Developer' } as unknown as JobSummary;
}

function page(content: JobSummary[]): PagedResponse<JobSummary> {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: 1, first: true, last: true };
}

const yesterday = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10);

const data: JobWorkspace = {
  recommended: [{ job: job(1, 'Platform Engineer'), matchPercentage: 82, priority: 90, saved: false, reasons: [] }],
  saved: [{ id: 's1', job: job(2, 'Data Engineer'), status: 'SAVED', savedAt: '', updatedAt: '', priority: 'LOW' }],
  applied: [{ id: 's2', job: job(3, 'Backend Engineer'), status: 'APPLIED', savedAt: '', updatedAt: '', priority: 'HIGH',
    followUpOn: yesterday, followUpNote: 'Email the recruiter' }],
  followUps: [{ id: 's2', job: job(3, 'Backend Engineer'), status: 'APPLIED', savedAt: '', updatedAt: '', priority: 'HIGH',
    followUpOn: yesterday, followUpNote: 'Email the recruiter' }],
  recentlyViewed: [{ job: job(4, 'SRE'), viewedAt: '' }],
  hidden: [{ job: job(5, 'Cobol Developer'), hiddenAt: '' }],
  savedSearches: [{ id: 'q1', name: 'Remote Java', filters: { q: 'java', order: 'match' }, createdAt: '' }],
};

function Where() {
  const location = useLocation();
  return <p data-testid="where">{location.pathname + location.search}</p>;
}

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/workspace" element={<JobWorkspacePage />} />
        <Route path="/jobs" element={<><JobExplorer /><Where /></>} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  localStorage.clear();
  workspace.mockResolvedValue(data);
  jobs.mockResolvedValue(page([job(1, 'Java Developer'), job(2, 'Data Engineer')]));
  jobMatches.mockResolvedValue({ matches: { 1: { percentage: 75, matched: ['Java'], missing: ['Docker'] } } });
  savedSearches.mockResolvedValue([]);
  unhideJob.mockResolvedValue(undefined);
  hideJob.mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('Job workspace (V9.14)', () => {
  it('shows follow-ups (overdue first), recommendations, saved, applied, viewed, hidden and saved searches', async () => {
    renderAt('/workspace');
    const followUps = await screen.findByRole('list', { name: 'Follow-ups' });
    expect(within(followUps).getByText(`Overdue · ${yesterday}`)).toBeTruthy();
    expect(within(followUps).getByText('Email the recruiter')).toBeTruthy();
    expect(within(followUps).getByText('high priority')).toBeTruthy();
    expect(within(screen.getByRole('list', { name: 'Recommended jobs' })).getByText('82% match')).toBeTruthy();
    expect(within(screen.getByRole('list', { name: 'Saved jobs' })).getByText('Data Engineer')).toBeTruthy();
    expect(within(screen.getByRole('list', { name: 'Applied jobs' })).getByText('Applied')).toBeTruthy();
    expect(within(screen.getByRole('list', { name: 'Recently viewed' })).getByText('SRE')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Remote Java' }).getAttribute('href')).toBe('/jobs?q=java&order=match');

    fireEvent.click(screen.getByRole('button', { name: 'Unhide Cobol Developer' }));
    await waitFor(() => expect(unhideJob).toHaveBeenCalledWith(5));
    await waitFor(() => expect(workspace).toHaveBeenCalledTimes(2));
  });

  it('shows empty states for a new user', async () => {
    workspace.mockResolvedValue({ recommended: [], saved: [], applied: [], followUps: [], recentlyViewed: [], hidden: [], savedSearches: [] });
    renderAt('/workspace');
    expect(await screen.findByText('No follow-ups set. Add one to a saved job to be reminded.')).toBeTruthy();
    expect(screen.getByText('No recommendations yet')).toBeTruthy();
    expect(screen.getByText('No hidden jobs.')).toBeTruthy();
  });
});

describe('Job Explorer smart search (V9.14)', () => {
  it('leaves out hidden jobs, explains matches, hides a job and saves the search', async () => {
    renderAt('/jobs?q=java');
    expect(await screen.findByText('75% skill match')).toBeTruthy();
    expect(screen.getByText('You have Java')).toBeTruthy();
    expect(screen.getByText('Missing Docker')).toBeTruthy();
    expect(jobs.mock.calls[0][0]).toMatchObject({ q: 'java', excludeHidden: 'true' });
    await waitFor(() => expect(jobMatches).toHaveBeenCalledWith([1, 2]));

    fireEvent.click(screen.getByRole('button', { name: 'Not interested in Java Developer' }));
    await waitFor(() => expect(hideJob).toHaveBeenCalledWith(1));
    await waitFor(() => expect(jobs).toHaveBeenCalledTimes(2));

    saveSearch.mockResolvedValue({ id: 'q9', name: 'Java roles', filters: { q: 'java' }, createdAt: '' });
    fireEvent.click(screen.getByRole('button', { name: 'Save this search' }));
    fireEvent.change(screen.getByLabelText('Name this search'), { target: { value: 'Java roles' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saveSearch).toHaveBeenCalledWith('Java roles', { q: 'java' }));
    expect(await screen.findByRole('link', { name: 'Java roles' })).toBeTruthy();
  });

  it('remembers the last search and reopens it when Job Explorer is opened without one', async () => {
    renderAt('/jobs?category=Backend+Developer&page=2');
    await screen.findByText('Java Developer');
    cleanup();
    renderAt('/jobs');
    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/jobs?category=Backend+Developer'));
    expect(jobs.mock.calls.at(-1)?.[0]).toMatchObject({ category: 'Backend Developer' });
  });
});
