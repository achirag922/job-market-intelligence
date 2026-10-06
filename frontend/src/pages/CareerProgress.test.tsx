import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CareerProgress } from '../api/types';
import { CareerProgressPage } from './CareerProgress';

const careerProgress = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return { ...actual, api: { careerProgress: (...args: unknown[]) => careerProgress(...args) } };
});

const full: CareerProgress = {
  readiness: {
    score: 73, level: 'Strong progress', components: [
      { key: 'profile', label: 'Profile', points: 15, maxPoints: 15, detail: '3 of 3: experience and skills, job preferences, an active career goal' },
      { key: 'resume', label: 'Resume', points: 11, maxPoints: 15, detail: 'Processed resume with 2 recognised skills', hint: 'List at least 5 skills your resume can show' },
    ],
  },
  progress: {
    target: { role: 'Backend Engineer', category: 'Backend Developer', percentComplete: 66.7, skillsTotal: 3, skillsOnResume: 2,
      skillsCompleted: 0, skillsInProgress: 0, nextSkills: ['Kubernetes'] },
    learning: { items: 2, completed: 1, inProgress: 1, averageProgress: 70 },
    interviews: { completed: 2, averageScore: 3.8, latestScore: 3 },
    applications: { saved: 2, applied: 1, interviewing: 0, offers: 0 },
  },
  achievements: [
    { key: 'career-goal', title: 'Career goal set', description: 'You chose a role to work towards.', category: 'Career', achieved: true, achievedOn: '2026-09-15' },
    { key: 'first-practice', title: 'First interview practice', description: 'You completed a practice interview.', category: 'Interviews', achieved: true, achievedOn: '2026-09-28' },
    { key: 'learning-five', title: 'Learning milestone', description: 'Five learning items completed.', category: 'Learning', achieved: false, progress: '1 of 5 completed' },
  ],
  nextMilestones: [
    { key: 'learning-five', title: 'Learning milestone', description: 'Five learning items completed.', category: 'Learning', achieved: false, progress: '1 of 5 completed' },
  ],
  streaks: [
    { key: 'learning', label: 'Learning', currentWeeks: 2, longestWeeks: 3, activeThisWeek: true, lastActiveOn: '2026-10-05' },
    { key: 'interviews', label: 'Interview practice', currentWeeks: 0, longestWeeks: 0, activeThisWeek: false },
  ],
  notes: ['The score adds up the seven components shown.'],
};

function renderPage() {
  render(<MemoryRouter><CareerProgressPage /></MemoryRouter>);
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('CareerProgressPage (V9.13)', () => {
  it('shows the score with its components, streaks, progress, milestones, achievements and a dated timeline', async () => {
    careerProgress.mockResolvedValue(full);
    renderPage();
    expect(await screen.findByRole('img', { name: 'Career readiness 73 out of 100: Strong progress' })).toBeTruthy();
    const components = screen.getByRole('list', { name: 'Score components' });
    expect(within(components).getByText('11 / 15')).toBeTruthy();
    expect(within(components).getByText(/Next: List at least 5 skills/)).toBeTruthy();
    expect(screen.getByRole('progressbar', { name: 'Resume points' }).getAttribute('aria-valuenow')).toBe('11');

    expect(screen.getByText('2 weeks')).toBeTruthy();
    expect(screen.getByText(/Active this week · longest 3/)).toBeTruthy();
    expect(screen.getByText('No streak yet')).toBeTruthy();

    expect(screen.getByText(/67% of roadmap skills covered/)).toBeTruthy();
    expect(screen.getByText('Next to learn: Kubernetes')).toBeTruthy();
    expect(screen.getByText('Average 3.8/5 · latest 3.0/5')).toBeTruthy();

    const achievements = screen.getByRole('list', { name: 'Achievements' });
    expect(within(achievements).getByRole('listitem', { name: 'Career goal set: earned' })).toBeTruthy();
    expect(within(achievements).getByRole('listitem', { name: 'Learning milestone: not yet earned' }).textContent).toContain('1 of 5 completed');
    expect(screen.getByText('2 of 3 earned. Each is earned once.')).toBeTruthy();
    const timeline = screen.getByRole('list', { name: 'Milestone timeline' });
    expect(within(timeline).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      expect.stringContaining('Career goal set'), expect.stringContaining('First interview practice')]);
  });

  it('guides a user with no data yet', async () => {
    careerProgress.mockResolvedValue({
      ...full,
      readiness: { score: 0, level: 'Getting started', components: full.readiness.components.map((c) => ({ ...c, points: 0, hint: 'Do this first' })) },
      progress: { learning: { items: 0, completed: 0, inProgress: 0 }, interviews: { completed: 0 },
        applications: { saved: 0, applied: 0, interviewing: 0, offers: 0 } },
      achievements: full.achievements.map((a) => ({ ...a, achieved: false, achievedOn: undefined, progress: 'Not yet' })),
      streaks: [],
    });
    renderPage();
    expect(await screen.findByRole('img', { name: 'Career readiness 0 out of 100: Getting started' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Set a career goal' }).getAttribute('href')).toBe('/career-goals');
    expect(screen.getByRole('link', { name: 'Plan a skill' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Start a practice interview' })).toBeTruthy();
    expect(screen.getByText('Your milestones will appear here as you reach them.')).toBeTruthy();
  });

  it('shows an error with a retry', async () => {
    careerProgress.mockRejectedValueOnce(new Error('offline')).mockResolvedValue(full);
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: /retry|try again/i }));
    expect(await screen.findByRole('img', { name: /Career readiness 73/ })).toBeTruthy();
  });
});
