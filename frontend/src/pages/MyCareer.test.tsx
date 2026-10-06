import { cleanup, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import type { PersonalDashboard } from '../api/types';
import { MyCareer } from './MyCareer';

const dashboard = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return { ...actual, api: { dashboard: (...args: unknown[]) => dashboard(...args) } };
});

const scope = { postings: 3, datedPostings: 3, earliestMonth: '2026-08-01', latestMonth: '2026-09-01', latestPostingInData: '2026-09-12' };

const full: PersonalDashboard = {
  resume: {
    available: true,
    current: { id: 'r1', title: 'Main CV', versionLabel: 'v2', isDefault: true, skillCount: 4, uploadedAt: '2026-09-20T08:00:00Z' },
    resumeCount: 2,
    matchSummary: { jobsCompared: 3, topMatchPercentage: 100, averageMatchPercentage: 66.7 },
    missingSkillsForGoal: ['Docker', 'Kubernetes'],
  },
  skills: {
    available: true,
    currentSkills: [{ id: 1, name: 'Java' } as PersonalDashboard['skills']['currentSkills'][number]],
    inProgress: ['Docker'],
    completed: [],
    notStarted: 1,
    roadmapPercentComplete: 33.3,
  },
  recommendations: {
    available: true,
    count: 3,
    averageMatchPercentage: 66.7,
    topJobs: [{ jobId: 3, title: 'Java Developer', company: 'Acme Systems', matchPercentage: 100 }],
  },
  applications: {
    available: true, total: 3, saved: 1, applied: 1, interview: 0, offer: 0, rejected: 1, withdrawn: 0,
    funnel: [{ stage: 'SAVED', jobs: 1 }, { stage: 'APPLIED', jobs: 1 }, { stage: 'INTERVIEW', jobs: 0 }, { stage: 'OFFER', jobs: 0 }],
    recent: [{ savedJobId: 's1', jobId: 1, title: 'Platform Engineer', company: 'Acme Systems', status: 'APPLIED', updatedAt: '2026-09-22T08:00:00Z' }],
  },
  careerGoal: {
    available: true, goalId: 'g1', targetRole: 'Backend Engineer', targetCategory: 'Backend Developer', activeGoals: 1,
    progress: { totalSkills: 3, onResume: 1, completed: 0, inProgress: 1, notStarted: 1, percentComplete: 33.3 },
    topMissingSkills: [
      { priority: 1, skill: 'Docker', reason: 'In 33.3% of Backend Developer postings (rank 2)', status: 'IN_PROGRESS' },
      { priority: 2, skill: 'Kubernetes', reason: 'In 33.3% of Backend Developer postings (rank 3)', status: 'NOT_STARTED' },
    ],
  },
  market: {
    available: true, category: 'Backend Developer', scope,
    topSkills: [{ skillId: 1, skill: 'Java', postings: 3, percentageOfPostings: 100, rank: 1 }],
    topLocations: [{ locationId: 1, location: 'Berlin, Germany', country: 'Germany', postings: 3, percentageOfPostings: 100 }],
    locationNotStated: 0,
    workModes: [{ mode: 'REMOTE', postings: 0, percentageOfPostings: 0 }, { mode: 'NOT_STATED', postings: 3, percentageOfPostings: 100 }],
    salaries: [{ currency: 'EUR', postings: 2, averageMin: 50000, averageMax: 70000, reliable: false }],
    topCompanies: [{ companyId: 1, company: 'Acme Systems', postings: 3, percentageOfPostings: 100 }],
    notes: ['Skill-demand trends are kept across all postings, not per category; see Skill Trends.'],
  },
};

const empty: PersonalDashboard = {
  resume: { available: false, resumeCount: 0, missingSkillsForGoal: [], note: 'Upload a resume in Resume Intelligence to see your skills and matches.' },
  skills: { available: false, currentSkills: [], inProgress: [], completed: [], notStarted: 0, note: 'Create a career goal to track the skills you are developing.' },
  recommendations: { available: false, count: 0, topJobs: [], note: 'Upload a resume to get job recommendations.' },
  applications: {
    available: false, total: 0, saved: 0, applied: 0, interview: 0, offer: 0, rejected: 0, withdrawn: 0,
    funnel: [{ stage: 'SAVED', jobs: 0 }, { stage: 'APPLIED', jobs: 0 }, { stage: 'INTERVIEW', jobs: 0 }, { stage: 'OFFER', jobs: 0 }],
    recent: [], note: 'Save jobs from the Job Explorer to track your applications here.',
  },
  careerGoal: { available: false, activeGoals: 0, topMissingSkills: [], note: 'Create a career goal to see a skill roadmap and your progress.' },
  market: {
    available: false, scope: { postings: 0, datedPostings: 0 }, topSkills: [], topLocations: [], locationNotStated: 0,
    workModes: [], salaries: [], topCompanies: [], notes: ['No active career goal, so this shows all postings.', 'There are no postings in the dataset yet.'],
  },
};

function renderPage() {
  return render(
    <MemoryRouter>
      <MyCareer />
    </MemoryRouter>,
  );
}

function card(title: string | RegExp) {
  return screen.getByRole('heading', { name: title }).closest('.card') as HTMLElement;
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('MyCareer', () => {
  it('shows every section of a full dashboard from one request, with links to the detail pages', async () => {
    dashboard.mockResolvedValue(full);
    renderPage();

    expect(await screen.findByText('Skills on your resume')).toBeTruthy();
    expect(dashboard).toHaveBeenCalledTimes(1);
    expect(screen.getByText('across 3 recommendations')).toBeTruthy();

    const resume = card('Resume');
    expect(within(resume).getByText('Main CV')).toBeTruthy();
    expect(within(resume).getByText('Default')).toBeTruthy();
    expect(within(within(resume).getByLabelText('Skills missing for your goal')).getByText('Kubernetes')).toBeTruthy();
    expect(within(resume).getByRole('link', { name: 'Open' }).getAttribute('href')).toBe('/resume');

    const goal = card('Career goal');
    expect(within(goal).getByText('33% of the roadmap covered')).toBeTruthy();
    expect(within(goal).getByRole('meter', { name: 'Goal progress' })).toBeTruthy();
    const priorities = within(goal).getByLabelText('Highest-priority skills');
    expect(within(priorities).getByText('Docker').closest('li')!.textContent).toContain('In progress');

    expect(within(within(card('Skill progress')).getByLabelText('Skills in progress')).getByText('Docker')).toBeTruthy();
    expect(within(card('Recommended jobs')).getByRole('link', { name: 'Java Developer' }).getAttribute('href')).toBe('/jobs/3');

    const applications = card('Applications');
    expect(within(within(applications).getByLabelText('Recent applications')).getByText('Platform Engineer')).toBeTruthy();
    expect(within(applications).getByText(/1 rejected · 0 withdrawn/)).toBeTruthy();

    const market = card('Market for Backend Developer');
    expect(within(market).getByText(/3 postings · posted Aug 2026 – Sep 2026/)).toBeTruthy();
    expect(within(within(market).getByLabelText('Salaries')).getByText('Too few postings')).toBeTruthy();
    expect(within(market).getByText(/not per category/)).toBeTruthy();
  });

  it('explains every empty section for a new user instead of showing made-up figures', async () => {
    dashboard.mockResolvedValue(empty);
    renderPage();

    expect(await screen.findByText(/Upload a resume in Resume Intelligence/)).toBeTruthy();
    expect(within(card('Career goal')).getByText(/Create a career goal to see a skill roadmap/)).toBeTruthy();
    expect(within(card('Applications')).getByText(/Save jobs from the Job Explorer/)).toBeTruthy();
    expect(within(card('Recommended jobs')).getByText('Upload a resume to get job recommendations.')).toBeTruthy();
    const market = card('Market: all postings');
    expect(within(market).getByText('There are no postings in the dataset yet.')).toBeTruthy();
    expect(within(market).queryByText('Most requested skills')).toBeNull();
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
  });

  it('shows an error with a retry when the dashboard cannot be loaded', async () => {
    dashboard.mockRejectedValue(new ApiError(500, 'Something went wrong on the server'));
    renderPage();

    expect(await screen.findByText(/Something went wrong on the server/)).toBeTruthy();
  });
});
