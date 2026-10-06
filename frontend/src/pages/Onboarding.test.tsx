import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { OnboardingStatus } from '../api/types';
import { OnboardingPrompt } from '../components/OnboardingPrompt';
import { Onboarding, experienceBand } from './Onboarding';

const onboarding = vi.fn();
const saveOnboardingProfile = vi.fn();
const saveOnboardingPreferences = vi.fn();
const skipOnboarding = vi.fn();
const completeOnboarding = vi.fn();
const resumes = vi.fn();
const uploadResume = vi.fn();
const setDefaultResume = vi.fn();
const jobCategories = vi.fn();
const createCareerGoal = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      onboarding: (...a: unknown[]) => onboarding(...a),
      saveOnboardingProfile: (...a: unknown[]) => saveOnboardingProfile(...a),
      saveOnboardingPreferences: (...a: unknown[]) => saveOnboardingPreferences(...a),
      skipOnboarding: (...a: unknown[]) => skipOnboarding(...a),
      completeOnboarding: (...a: unknown[]) => completeOnboarding(...a),
      resumes: (...a: unknown[]) => resumes(...a),
      uploadResume: (...a: unknown[]) => uploadResume(...a),
      setDefaultResume: (...a: unknown[]) => setDefaultResume(...a),
      jobCategories: (...a: unknown[]) => jobCategories(...a),
      createCareerGoal: (...a: unknown[]) => createCareerGoal(...a),
    },
  };
});

vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({ user: { fullName: 'Alex Rivera' } }) }));

function status(overrides: Partial<OnboardingStatus> = {}, steps: Partial<OnboardingStatus['steps']> = {}): OnboardingStatus {
  const all = { profile: false, resume: false, preferences: false, careerGoal: false, ...steps };
  const order: [keyof typeof all, OnboardingStatus['nextStep']][] =
    [['profile', 'PROFILE'], ['resume', 'RESUME'], ['preferences', 'PREFERENCES'], ['careerGoal', 'CAREER_GOAL']];
  return {
    status: 'PENDING', steps: all, completedSteps: Object.values(all).filter(Boolean).length, totalSteps: 4,
    nextStep: order.find(([key]) => !all[key])?.[1] ?? 'DONE', profile: {}, preferences: {}, ...overrides,
  };
}

function renderAt(element: React.ReactNode, path = '/onboarding') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/onboarding" element={element} />
        <Route path="/" element={<><OnboardingPrompt /><p>Home page</p></>} />
        <Route path="/my-career" element={<p>My career page</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  resumes.mockResolvedValue([]);
  jobCategories.mockResolvedValue([{ category: 'Backend Developer', jobCount: 20, percentageOfJobs: 14, rank: 1 },
    { category: 'Data Engineer', jobCount: 10, percentageOfJobs: 7, rank: 2 }]);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('Onboarding (V9.12)', () => {
  it('walks a new user through profile, resume, preferences and goal, validating each step, to the dashboard', async () => {
    onboarding.mockResolvedValueOnce(status());
    renderAt(<Onboarding />);
    fireEvent.click(await screen.findByRole('button', { name: 'Get started' }));
    expect(screen.getByRole('heading', { name: 'Your career profile' })).toBeTruthy();

    // Validation before any request.
    fireEvent.click(screen.getByRole('button', { name: 'Save and continue' }));
    expect(screen.getByText('Enter the role you are aiming for.')).toBeTruthy();
    expect(screen.getByText('Enter your years of experience.')).toBeTruthy();
    expect(screen.getByText('Add at least one skill or interest.')).toBeTruthy();
    expect(saveOnboardingProfile).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('Target job or role'), { target: { value: 'Backend Engineer' } });
    fireEvent.change(screen.getByLabelText('Years of experience'), { target: { value: '4' } });
    fireEvent.change(screen.getByLabelText('Key skills and interests (comma-separated)'), { target: { value: 'Java, Docker, Java' } });
    const afterProfile = status({ profile: { targetRole: 'Backend Engineer', yearsExperience: 4, skills: ['Java', 'Docker'] } }, { profile: true });
    saveOnboardingProfile.mockResolvedValue(afterProfile);
    fireEvent.click(screen.getByRole('button', { name: 'Save and continue' }));
    expect(await screen.findByRole('heading', { name: 'Your resume' })).toBeTruthy();
    expect(saveOnboardingProfile).toHaveBeenCalledWith({ targetRole: 'Backend Engineer', yearsExperience: 4, skills: ['Java', 'Docker'] });
    const progress = screen.getByRole('list', { name: 'Onboarding progress' });
    expect(within(progress).getAllByRole('listitem')[0].textContent).toBe('Profile✓');
    expect(within(progress).getAllByRole('listitem')[1].getAttribute('aria-current')).toBe('step');

    // Resume: the existing upload; Continue unlocks once a processed resume exists.
    expect(screen.getByRole('button', { name: 'Continue' })).toHaveProperty('disabled', true);
    uploadResume.mockResolvedValue({ id: 'r1', fileName: 'cv.pdf', status: 'COMPLETED', skills: [{ id: 1, name: 'Java' }, { id: 2, name: 'Docker' }] });
    const afterResume = status({ profile: afterProfile.profile }, { profile: true, resume: true });
    onboarding.mockResolvedValue(afterResume);
    fireEvent.change(screen.getByLabelText('Upload a resume (PDF)'), { target: { files: [new File(['%PDF'], 'cv.pdf', { type: 'application/pdf' })] } });
    expect(await screen.findByText('Skills found: Java, Docker.')).toBeTruthy();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Continue' })).toHaveProperty('disabled', false));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));

    // Preferences: at least one, then saved.
    expect(await screen.findByRole('heading', { name: 'Job preferences' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Save and continue' }));
    expect(screen.getByText('Choose at least one preference, or skip this step.')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Work mode'), { target: { value: 'REMOTE' } });
    fireEvent.click(await screen.findByLabelText('Backend Developer'));
    const afterPreferences = status({ profile: afterProfile.profile, preferences: { workMode: 'REMOTE', preferredCategories: ['Backend Developer'] } },
      { profile: true, resume: true, preferences: true });
    saveOnboardingPreferences.mockResolvedValue(afterPreferences);
    fireEvent.click(screen.getByRole('button', { name: 'Save and continue' }));
    expect(await screen.findByRole('heading', { name: 'Career goal' })).toBeTruthy();
    expect(saveOnboardingPreferences).toHaveBeenCalledWith({ preferredLocation: undefined, workMode: 'REMOTE', minSalary: undefined,
      salaryCurrency: undefined, preferredCategories: ['Backend Developer'] });

    // Goal: prefilled from the profile and preferences, created through the existing career-goal call.
    expect(screen.getByLabelText('Target role')).toHaveProperty('value', 'Backend Engineer');
    expect(screen.getByLabelText('Experience')).toHaveProperty('value', '2-5');
    await waitFor(() => expect(screen.getByLabelText('Job category')).toHaveProperty('value', 'Backend Developer'));
    createCareerGoal.mockResolvedValue({ id: 'g1' });
    onboarding.mockResolvedValue({ ...afterPreferences, steps: { ...afterPreferences.steps, careerGoal: true }, completedSteps: 4, nextStep: 'DONE' });
    fireEvent.click(screen.getByRole('button', { name: 'Create goal and continue' }));
    expect(await screen.findByRole('heading', { name: 'All set!' })).toBeTruthy();
    expect(createCareerGoal).toHaveBeenCalledWith({ targetRole: 'Backend Engineer', targetCategory: 'Backend Developer',
      targetExperience: '2-5', targetLocation: undefined, targetSkills: [] });

    completeOnboarding.mockResolvedValue({ ...afterPreferences, status: 'COMPLETED' });
    fireEvent.click(screen.getByRole('button', { name: 'Go to my dashboard' }));
    expect(await screen.findByText('My career page')).toBeTruthy();
    expect(completeOnboarding).toHaveBeenCalled();
  });

  it('can be skipped, and later resumes at the first unfinished step with earlier answers kept', async () => {
    onboarding.mockResolvedValueOnce(status());
    skipOnboarding.mockResolvedValue(status({ status: 'SKIPPED' }));
    renderAt(<Onboarding />);
    onboarding.mockResolvedValue(status({ status: 'SKIPPED' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Skip for now' }));
    expect(await screen.findByText('Home page')).toBeTruthy();
    expect(skipOnboarding).toHaveBeenCalled();
    expect(await screen.findByText('Finish setting up JMIP')).toBeTruthy();
    cleanup();

    onboarding.mockResolvedValue(status({ status: 'SKIPPED', preferences: { preferredLocation: 'Berlin' } }, { profile: true, resume: true }));
    renderAt(<Onboarding />);
    expect(await screen.findByRole('heading', { name: 'Job preferences' })).toBeTruthy();
    expect(screen.getByLabelText('Preferred location')).toHaveProperty('value', 'Berlin');
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(screen.getByRole('heading', { name: 'Your resume' })).toBeTruthy();
  });

  it('shows the server message when a step cannot be saved', async () => {
    onboarding.mockResolvedValue(status({}, {}));
    const { ApiError } = await import('../api/client');
    saveOnboardingProfile.mockRejectedValue(new ApiError(400, 'skills: at most 20 skills'));
    renderAt(<Onboarding />);
    fireEvent.click(await screen.findByRole('button', { name: 'Get started' }));
    fireEvent.change(screen.getByLabelText('Target job or role'), { target: { value: 'Engineer' } });
    fireEvent.change(screen.getByLabelText('Years of experience'), { target: { value: '2' } });
    fireEvent.change(screen.getByLabelText('Key skills and interests (comma-separated)'), { target: { value: 'Java' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save and continue' }));
    expect((await screen.findByRole('alert')).textContent).toContain('at most 20 skills');
    expect(screen.getByRole('heading', { name: 'Your career profile' })).toBeTruthy();
  });

  it('maps years of experience to the goal experience bands', () => {
    expect([experienceBand(undefined), experienceBand(1), experienceBand(2), experienceBand(7), experienceBand(12)])
      .toEqual(['', '0-2', '2-5', '5-8', '8+']);
  });
});

describe('OnboardingPrompt on the dashboard', () => {
  it('sends a new account to onboarding', async () => {
    onboarding.mockResolvedValue(status());
    renderAt(<p>Onboarding page</p>, '/');
    expect(await screen.findByText('Onboarding page')).toBeTruthy();
  });

  it('shows nothing for a completed onboarding, or when the status cannot be read', async () => {
    onboarding.mockResolvedValue(status({ status: 'COMPLETED' }, { profile: true }));
    renderAt(<p>Onboarding page</p>, '/');
    expect(await screen.findByText('Home page')).toBeTruthy();
    await waitFor(() => expect(onboarding).toHaveBeenCalled());
    expect(screen.queryByText('Finish setting up JMIP')).toBeNull();
    cleanup();

    onboarding.mockRejectedValue(new Error('offline'));
    renderAt(<p>Onboarding page</p>, '/');
    expect(await screen.findByText('Home page')).toBeTruthy();
    expect(screen.queryByText('Onboarding page')).toBeNull();
  });

  it('offers a skipped onboarding with its progress', async () => {
    onboarding.mockResolvedValue(status({ status: 'SKIPPED' }, { profile: true, resume: true }));
    renderAt(<p>Onboarding page</p>, '/');
    expect(await screen.findByText(/2 of 4 steps done/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Continue setup' }).getAttribute('href')).toBe('/onboarding');
  });
});
