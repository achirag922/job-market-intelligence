import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import type { ApplicationStatus, SavedJob } from '../api/types';
import { SaveJobButton, SavedJobsProvider } from '../saved/SavedJobs';
import { SavedJobs } from './SavedJobs';

const savedJobs = vi.fn();
const saveJob = vi.fn();
const unsaveJob = vi.fn();
const setSavedJobStatus = vi.fn();
const setSavedJobNotes = vi.fn();
const deleteSavedJob = vi.fn();

const setSavedJobFollowUp = vi.fn();
const applications = vi.fn((..._args: unknown[]) => Promise.resolve([] as unknown[]));
const applicationInsights = vi.fn((..._args: unknown[]) => Promise.resolve(EMPTY_INSIGHTS as unknown));

const EMPTY_INSIGHTS = {
  tracked: 0, applications: 0,
  statusCounts: { SAVED: 0, APPLIED: 0, INTERVIEW: 0, OFFER: 0, REJECTED: 0, WITHDRAWN: 0 },
  funnel: { applied: 0, interviewed: 0, offers: 0, minimumForRates: 3, note: 'Conversion rates appear once you have at least 3 applications.' },
  activity: [], activityNote: 'Save jobs from the Job Explorer to see your activity here.',
  scoredApplications: 0, matchNote: 'Move a saved job to Applied to see how your applications match.',
  topMissingSkills: [], topCompanies: [], topRoles: [], upcomingFollowUps: [], overdueFollowUps: [],
};

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      savedJobs: (...args: unknown[]) => savedJobs(...args),
      saveJob: (...args: unknown[]) => saveJob(...args),
      unsaveJob: (...args: unknown[]) => unsaveJob(...args),
      setSavedJobStatus: (...args: unknown[]) => setSavedJobStatus(...args),
      setSavedJobNotes: (...args: unknown[]) => setSavedJobNotes(...args),
      deleteSavedJob: (...args: unknown[]) => deleteSavedJob(...args),
      setSavedJobFollowUp: (...args: unknown[]) => setSavedJobFollowUp(...args),
      applications: (...args: unknown[]) => applications(...args),
      applicationInsights: (...args: unknown[]) => applicationInsights(...args),
    },
  };
});

function saved(id: string, jobId: number, title: string, status: ApplicationStatus = 'SAVED', overrides: Partial<SavedJob> = {}): SavedJob {
  return {
    id,
    job: {
      id: jobId,
      title,
      company: { id: 1, name: 'Acme Systems' },
      location: { id: 1, city: 'Berlin', country: 'Germany' },
      skills: [],
    } as unknown as SavedJob['job'],
    status,
    notes: null,
    savedAt: '2026-09-20T08:00:00Z',
    appliedAt: status === 'SAVED' ? null : '2026-09-22T08:00:00Z',
    updatedAt: '2026-09-22T08:00:00Z',
    ...overrides,
  };
}

function renderWith(ui: React.ReactNode) {
  return render(
    <MemoryRouter>
      <SavedJobsProvider>{ui}</SavedJobsProvider>
    </MemoryRouter>,
  );
}

function card(title: string) {
  return screen.getByRole('link', { name: title }).closest('article')!;
}

const APPLIED_ON = new Date('2026-09-22T08:00:00Z').toLocaleDateString(undefined, { dateStyle: 'medium' });

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.restoreAllMocks();
});

describe('SaveJobButton', () => {
  it('saves and unsaves a job, and shows the saved state', async () => {
    savedJobs.mockResolvedValue([]);
    saveJob.mockResolvedValue(saved('s1', 7, 'Backend Engineer'));
    unsaveJob.mockResolvedValue(undefined);
    renderWith(<SaveJobButton jobId={7} />);

    const button = await screen.findByRole('button', { name: 'Save' });
    await vi.waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(button);

    expect(await screen.findByRole('button', { name: 'Saved' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Saved' }).getAttribute('aria-pressed')).toBe('true');
    expect(saveJob).toHaveBeenCalledWith(7);

    fireEvent.click(screen.getByRole('button', { name: 'Saved' }));
    expect(await screen.findByRole('button', { name: 'Save' })).toBeTruthy();
    expect(unsaveJob).toHaveBeenCalledWith(7);
  });

  it('shows a job that is already saved as saved, from one shared load', async () => {
    savedJobs.mockResolvedValue([saved('s1', 7, 'Backend Engineer')]);
    renderWith(
      <>
        <SaveJobButton jobId={7} />
        <SaveJobButton jobId={8} />
      </>,
    );

    expect(await screen.findByRole('button', { name: 'Saved' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Save' })).toBeTruthy();
    expect(savedJobs).toHaveBeenCalledTimes(1);
  });

  it('renders nothing outside the signed-in shell', () => {
    render(<SaveJobButton jobId={7} />);
    expect(screen.queryByRole('button')).toBeNull();
  });
});

describe('SavedJobs page', () => {
  const list = [
    saved('s1', 1, 'Backend Engineer', 'APPLIED'),
    saved('s2', 2, 'Platform Engineer', 'SAVED'),
    saved('s3', 3, 'Data Engineer', 'INTERVIEW', { notes: 'Panel on Friday' }),
    saved('s4', 4, 'QA Engineer', 'REJECTED'),
  ];

  it('shows the pipeline counts, statuses, dates and notes', async () => {
    savedJobs.mockResolvedValue(list);
    renderWith(<SavedJobs />);

    await screen.findByRole('link', { name: 'Backend Engineer' });
    const pipeline = screen.getByRole('group', { name: 'Application pipeline' });
    expect(within(pipeline).getByRole('button', { name: /1\s*Saved/ })).toBeTruthy();
    expect(within(pipeline).getByRole('button', { name: /1\s*Applied/ })).toBeTruthy();
    expect(within(pipeline).getByRole('button', { name: /1\s*Interview/ })).toBeTruthy();
    expect(within(pipeline).getByRole('button', { name: /0\s*Offer/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /Closed: 1 rejected · 0 withdrawn/ })).toBeTruthy();

    const applied = card('Backend Engineer');
    expect(within(applied).getByText(new RegExp(`Applied ${APPLIED_ON}`))).toBeTruthy();
    expect((within(card('Data Engineer')).getByLabelText('Notes') as HTMLTextAreaElement).value).toBe('Panel on Friday');
    expect(within(card('Platform Engineer')).queryByText(/· Applied/)).toBeNull();
  });

  it('filters by status from the pipeline and from the select', async () => {
    savedJobs.mockResolvedValue(list);
    renderWith(<SavedJobs />);
    await screen.findByRole('link', { name: 'Backend Engineer' });

    fireEvent.click(screen.getByRole('button', { name: /1\s*Interview/ }));
    expect(screen.getAllByRole('article')).toHaveLength(1);
    expect(screen.getByRole('link', { name: 'Data Engineer' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /Closed:/ }));
    expect(screen.getByRole('link', { name: 'QA Engineer' })).toBeTruthy();
    expect(screen.getAllByRole('article')).toHaveLength(1);

    fireEvent.change(screen.getByLabelText('Filter by status'), { target: { value: 'OFFER' } });
    expect(screen.getByText('Nothing at this stage')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Filter by status'), { target: { value: 'ALL' } });
    expect(screen.getAllByRole('article')).toHaveLength(4);
  });

  it('changes the application status', async () => {
    savedJobs.mockResolvedValue([saved('s2', 2, 'Platform Engineer', 'SAVED')]);
    setSavedJobStatus.mockResolvedValue(saved('s2', 2, 'Platform Engineer', 'APPLIED'));
    renderWith(<SavedJobs />);
    await screen.findByRole('link', { name: 'Platform Engineer' });

    fireEvent.change(within(card('Platform Engineer')).getByLabelText('Status'), { target: { value: 'APPLIED' } });

    expect(await within(card('Platform Engineer')).findByText(new RegExp(`Applied ${APPLIED_ON}`))).toBeTruthy();
    expect(setSavedJobStatus).toHaveBeenCalledWith('s2', 'APPLIED');
  });

  it('saves notes, and shows a server error without losing them', async () => {
    savedJobs.mockResolvedValue([saved('s2', 2, 'Platform Engineer')]);
    setSavedJobNotes
      .mockResolvedValueOnce(saved('s2', 2, 'Platform Engineer', 'SAVED', { notes: 'Ask about remote' }))
      .mockRejectedValueOnce(new ApiError(400, 'notes must be at most 2000 characters'));
    renderWith(<SavedJobs />);
    await screen.findByRole('link', { name: 'Platform Engineer' });
    const notes = within(card('Platform Engineer')).getByLabelText('Notes') as HTMLTextAreaElement;

    fireEvent.change(notes, { target: { value: 'Ask about remote' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save notes' }));
    expect(await screen.findByText('Notes saved')).toBeTruthy();
    expect(setSavedJobNotes).toHaveBeenCalledWith('s2', 'Ask about remote');

    fireEvent.change(notes, { target: { value: 'Too long, says the server' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save notes' }));
    expect((await screen.findByRole('alert')).textContent).toContain('at most 2000');
    expect(notes.value).toBe('Too long, says the server');
  });

  it('removes a saved job after confirmation', async () => {
    savedJobs.mockResolvedValue([saved('s2', 2, 'Platform Engineer')]);
    deleteSavedJob.mockResolvedValue(undefined);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderWith(<SavedJobs />);

    fireEvent.click(await screen.findByRole('button', { name: 'Remove' }));

    expect(await screen.findByText('No saved jobs yet')).toBeTruthy();
    expect(deleteSavedJob).toHaveBeenCalledWith('s2');
  });

  it('shows an empty state with a way to find jobs', async () => {
    savedJobs.mockResolvedValue([]);
    renderWith(<SavedJobs />);

    expect(await screen.findByText('No saved jobs yet')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Browse jobs' }).getAttribute('href')).toBe('/jobs');
  });
});

describe('SavedJobs application intelligence (V8.5)', () => {
  const list = [
    saved('s1', 1, 'Backend Engineer', 'OFFER'),
    saved('s2', 2, 'Platform Engineer', 'SAVED'),
  ];

  it('shows the summary, funnel, rankings, follow-ups and each job\'s match', async () => {
    savedJobs.mockResolvedValue(list);
    applicationInsights.mockResolvedValue({
      ...EMPTY_INSIGHTS,
      tracked: 2, applications: 1,
      statusCounts: { ...EMPTY_INSIGHTS.statusCounts, SAVED: 1, OFFER: 1 },
      funnel: { applied: 3, interviewed: 2, offers: 1, interviewRate: 66.7, offerRate: 33.3, minimumForRates: 3 },
      activity: [
        { month: '2026-08', saved: 2, applied: 1, interviews: 0, offers: 0 },
        { month: '2026-09', saved: 1, applied: 2, interviews: 2, offers: 1 },
      ],
      activityNote: undefined,
      averageMatchPercentage: 58, scoredApplications: 3, matchNote: undefined,
      topMissingSkills: [{ name: 'Kubernetes', count: 2 }],
      topCompanies: [{ name: 'Acme Systems', count: 3 }],
      upcomingFollowUps: [{ id: 's9', jobId: 9, jobTitle: 'Follow-up role', companyName: 'Beta', status: 'APPLIED', followUpOn: '2030-01-15', note: 'Email the recruiter' }],
    });
    applications.mockResolvedValue([{
      id: 's1', job: list[0].job, status: 'OFFER', savedAt: list[0].savedAt, overallMatchPercentage: 72.4, skillMatchPercentage: 50,
      matchedSkills: [{ id: 1, name: 'Java' }], missingSkills: [{ id: 2, name: 'Docker' }],
    }]);
    renderWith(<SavedJobs />);

    expect(await screen.findByText('Application intelligence')).toBeTruthy();
    expect(await screen.findByText('67%')).toBeTruthy();
    expect(screen.getByText('58%')).toBeTruthy();
    expect(screen.getByText('Offer rate 33% of applications.')).toBeTruthy();
    expect(screen.getByText('Kubernetes')).toBeTruthy();
    expect(screen.getByText('Email the recruiter')).toBeTruthy();

    const offer = card('Backend Engineer');
    expect(await within(offer).findByText('72% match')).toBeTruthy();
    expect(within(offer).getByText('Docker')).toBeTruthy();
  });

  it('saves a follow-up date for a job', async () => {
    savedJobs.mockResolvedValue(list);
    setSavedJobFollowUp.mockResolvedValue({ ...list[1], followUpOn: '2030-02-01', followUpNote: 'Check status' });
    renderWith(<SavedJobs />);

    const platform = (await screen.findByRole('link', { name: 'Platform Engineer' })).closest('article')!;
    fireEvent.change(within(platform).getByLabelText('Follow up on'), { target: { value: '2030-02-01' } });
    fireEvent.change(within(platform).getByLabelText('Reminder'), { target: { value: 'Check status' } });
    fireEvent.click(within(platform).getByRole('button', { name: 'Save follow-up' }));

    expect(await within(platform).findByText('Follow-up saved')).toBeTruthy();
    expect(setSavedJobFollowUp).toHaveBeenCalledWith('s2', '2030-02-01', 'Check status');
  });
});
