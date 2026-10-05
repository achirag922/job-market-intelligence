import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ReadinessComponent } from '../api/types';
import { NextSteps } from '../components/NextSteps';
import { Landing } from './Landing';

const auth = vi.fn();
const careerProgress = vi.fn();

vi.mock('../auth/AuthContext', () => ({ useAuth: () => auth() }));
vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return { ...actual, api: { careerProgress: (...a: unknown[]) => careerProgress(...a) } };
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('Landing (V9.15)', () => {
  it('explains JMIP to a visitor with Get Started, Login and Explore Features', () => {
    auth.mockReturnValue({ status: 'signedOut' });
    render(<MemoryRouter><Landing /></MemoryRouter>);
    expect(screen.getByRole('heading', { level: 1 }).textContent).toContain('job search and career growth');
    const hero = screen.getByRole('region', { name: /your job search/i });
    expect(within(hero).getByRole('link', { name: 'Get Started' }).getAttribute('href')).toBe('/signup');
    expect(within(hero).getByRole('link', { name: 'Login' }).getAttribute('href')).toBe('/login');
    expect(within(hero).getByRole('link', { name: 'Explore Features' }).getAttribute('href')).toBe('#features');

    const features = screen.getByRole('region', { name: 'What JMIP does for you' });
    expect(within(features).getAllByRole('listitem')).toHaveLength(6);
    for (const name of ['Personalized job matching', 'Resume intelligence', 'Skill gap & learning', 'Interview preparation',
      'Career analytics', 'Professional portfolio']) {
      expect(within(features).getByRole('heading', { name })).toBeTruthy();
    }
    const steps = within(screen.getByRole('region', { name: 'How it works' })).getAllByRole('listitem');
    expect(steps.map((step) => step.querySelector('strong')?.textContent)).toEqual(['Sign up', 'Onboarding', 'Resume',
      'Personalized jobs', 'Match analysis', 'Skill gap', 'Learning', 'Interview', 'Analytics', 'Portfolio']);
    expect(screen.getByRole('region', { name: 'How JMIP uses your data' })).toBeTruthy();
    expect(screen.getByText(/synthetic/)).toBeTruthy();
  });

  it('offers the dashboard to a signed-in user instead of sign-up', () => {
    auth.mockReturnValue({ status: 'signedIn' });
    render(<MemoryRouter><Landing /></MemoryRouter>);
    expect(screen.getAllByRole('link', { name: 'Go to dashboard' }).length).toBeGreaterThan(0);
    expect(screen.queryByRole('link', { name: 'Get Started' })).toBeNull();
  });
});

function component(key: string, points: number, maxPoints: number, hint?: string): ReadinessComponent {
  return { key, label: key, points, maxPoints, detail: `${key} detail`, hint };
}

describe('NextSteps on the dashboard (V9.15)', () => {
  it('shows only incomplete parts, largest gap first, with the server hint', async () => {
    careerProgress.mockResolvedValue({ readiness: { score: 40, level: 'Building momentum', components: [
      component('profile', 15, 15),
      component('resume', 8, 15, 'List at least 5 skills your resume can show'),
      component('skills', 0, 20, 'Set a career goal to see your skill gap'),
      component('learning', 15, 15),
      component('interviews', 7, 15, 'Complete a practice interview'),
      component('jobSearch', 3, 10, 'Apply to a job that matches you'),
      component('portfolio', 0, 10, 'Create your portfolio'),
    ] } });
    render(<MemoryRouter><NextSteps /></MemoryRouter>);
    const list = await screen.findByRole('list', { name: 'Next steps' });
    const links = within(list).getAllByRole('link');
    expect(links.map((link) => link.querySelector('strong')?.textContent)).toEqual([
      'Improve your skill gap', 'Update your portfolio', 'Practise an interview', 'Upload or update your resume']);
    expect(links[0].getAttribute('href')).toBe('/career-goals');
    expect(within(list).getByText('Set a career goal to see your skill gap')).toBeTruthy();
    expect(within(list).queryByText('Complete your profile')).toBeNull();
    expect(screen.getByRole('link', { name: 'How JMIP uses your data' }).getAttribute('href')).toBe('/welcome#privacy');
  });

  it('says so when everything is complete, and stays out of the way on errors', async () => {
    careerProgress.mockResolvedValue({ readiness: { score: 100, level: 'Job-ready', components: [component('profile', 15, 15)] } });
    render(<MemoryRouter><NextSteps /></MemoryRouter>);
    expect(await screen.findByText('You are all set')).toBeTruthy();
    expect(screen.queryByRole('list', { name: 'Next steps' })).toBeNull();
    cleanup();

    careerProgress.mockRejectedValue(new Error('offline'));
    const { container } = render(<MemoryRouter><NextSteps /></MemoryRouter>);
    await waitFor(() => expect(careerProgress).toHaveBeenCalled());
    expect(container.textContent).toBe('');
  });
});
