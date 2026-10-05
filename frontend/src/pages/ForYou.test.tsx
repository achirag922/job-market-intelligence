import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PersonalizedFeed } from '../api/types';
import { ForYou } from './ForYou';

const personalizedJobs = vi.fn();
const saveJob = vi.fn();
const setSavedJobStatus = vi.fn();
const matchPreferences = vi.fn();
const saveMatchPreferences = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      personalizedJobs: (...args: unknown[]) => personalizedJobs(...args),
      saveJob: (...args: unknown[]) => saveJob(...args),
      setSavedJobStatus: (...args: unknown[]) => setSavedJobStatus(...args),
      matchPreferences: (...args: unknown[]) => matchPreferences(...args),
      saveMatchPreferences: (...args: unknown[]) => saveMatchPreferences(...args),
    },
  };
});

vi.mock('../saved/SavedJobs', () => ({
  useSavedJobs: () => ({ replace: vi.fn() }),
  SaveJobButton: ({ jobId }: { jobId: number }) => <button type="button">Save {jobId}</button>,
}));

const feed: PersonalizedFeed = {
  jobs: [{
    job: { id: 1, title: 'Backend Developer', company: { id: 1, name: 'Acme' }, location: { id: 1, city: 'Berlin', country: 'Germany' }, skills: [] } as never,
    matchPercentage: 62.5, priority: 84.5, saved: false,
    reasons: [
      { text: 'Matches your career goal: Backend Developer', kind: 'POSITIVE', points: 10 },
      { text: 'Remote preference matched', kind: 'POSITIVE' },
      { text: 'Missing 1 required skill', kind: 'NEGATIVE' },
    ],
  }],
  context: { resume: true, careerGoal: 'Backend Developer', preferredRoles: [], preferredSkills: [], candidates: 5, excludedApplied: 1, excludedByPreference: 0 },
};

beforeEach(() => {
  personalizedJobs.mockResolvedValue(feed);
  matchPreferences.mockResolvedValue({ preferredCategories: ['Data Analyst'], excludedCompanies: ['Evil Corp'] });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('ForYou', () => {
  it('shows recommended jobs with their match, priority and reasons', async () => {
    render(<MemoryRouter><ForYou /></MemoryRouter>);

    expect(await screen.findByRole('link', { name: 'Backend Developer' })).toBeTruthy();
    expect(screen.getByText('63%')).toBeTruthy();
    expect(screen.getByText('Priority 84.5')).toBeTruthy();
    expect(screen.getByText('Matches your career goal: Backend Developer (+10)')).toBeTruthy();
    expect(screen.getByText('Missing 1 required skill')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Save 1' })).toBeTruthy();
  });

  it('loads and saves the personalization lists with the other preferences', async () => {
    saveMatchPreferences.mockResolvedValue({ preferredCategories: ['Data Analyst', 'Backend Developer'] });
    render(<MemoryRouter><ForYou /></MemoryRouter>);

    await waitFor(() => expect((screen.getByLabelText('Preferred roles') as HTMLInputElement).value).toBe('Data Analyst'));
    expect((screen.getByLabelText('Exclude companies') as HTMLInputElement).value).toBe('Evil Corp');
    fireEvent.change(screen.getByLabelText('Preferred roles'), { target: { value: 'Data Analyst, Backend Developer' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save preferences' }));

    await waitFor(() => expect(saveMatchPreferences).toHaveBeenCalledWith(expect.objectContaining({
      preferredCategories: ['Data Analyst', 'Backend Developer'], excludedCompanies: ['Evil Corp'] })));
    await waitFor(() => expect(personalizedJobs).toHaveBeenCalledTimes(2));
  });

  it('marks a job applied by saving it and moving it to Applied', async () => {
    saveJob.mockResolvedValue({ id: 's1' });
    setSavedJobStatus.mockResolvedValue({ id: 's1', status: 'APPLIED' });
    render(<MemoryRouter><ForYou /></MemoryRouter>);

    fireEvent.click(await screen.findByRole('button', { name: 'Mark applied' }));
    await waitFor(() => expect(setSavedJobStatus).toHaveBeenCalledWith('s1', 'APPLIED'));
    expect(saveJob).toHaveBeenCalledWith(1);
  });
});
