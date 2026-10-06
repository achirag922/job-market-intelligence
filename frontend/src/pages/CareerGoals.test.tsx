import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import type { CareerGoal, Roadmap } from '../api/types';
import { CareerGoals, parseSkills } from './CareerGoals';

const careerGoals = vi.fn();
const jobCategories = vi.fn();
const createCareerGoal = vi.fn();
const updateCareerGoal = vi.fn();
const setCareerGoalStatus = vi.fn();
const deleteCareerGoal = vi.fn();
const careerGoalRoadmap = vi.fn();
const setRoadmapSkillStatus = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      careerGoals: (...args: unknown[]) => careerGoals(...args),
      jobCategories: (...args: unknown[]) => jobCategories(...args),
      createCareerGoal: (...args: unknown[]) => createCareerGoal(...args),
      updateCareerGoal: (...args: unknown[]) => updateCareerGoal(...args),
      setCareerGoalStatus: (...args: unknown[]) => setCareerGoalStatus(...args),
      deleteCareerGoal: (...args: unknown[]) => deleteCareerGoal(...args),
      careerGoalRoadmap: (...args: unknown[]) => careerGoalRoadmap(...args),
      setRoadmapSkillStatus: (...args: unknown[]) => setRoadmapSkillStatus(...args),
    },
  };
});

function goal(overrides: Partial<CareerGoal> = {}): CareerGoal {
  return {
    id: 'g1',
    targetRole: 'Backend Engineer',
    targetCategory: 'Backend Developer',
    targetLocation: 'Berlin',
    targetExperience: '2-5',
    targetSkills: [{ id: 5, name: 'Go' }],
    status: 'ACTIVE',
    createdAt: '2026-09-25T08:00:00Z',
    updatedAt: '2026-09-25T08:00:00Z',
    ...overrides,
  } as CareerGoal;
}

function roadmap(overrides: Partial<Roadmap> = {}): Roadmap {
  return {
    goalId: 'g1',
    targetRole: 'Backend Engineer',
    targetCategory: 'Backend Developer',
    basedOnResume: { id: 'r1', title: 'Main CV' },
    currentSkills: [{ id: 1, name: 'Java' }],
    marketSkills: [],
    coveredSkills: [{ id: 1, name: 'Java' }],
    roadmap: [
      { priority: 1, skillId: 2, skill: 'Docker', source: 'MARKET_DEMAND', percentageOfJobs: 33.3, demandRank: 2,
        reason: 'In 33.3% of Backend Developer postings (rank 2)', status: 'NOT_STARTED' },
      { priority: 2, skillId: 5, skill: 'Go', source: 'YOUR_CHOICE', reason: 'You added this skill to the goal',
        status: 'NOT_STARTED' },
    ],
    progress: { totalSkills: 3, onResume: 1, completed: 0, inProgress: 0, notStarted: 2, percentComplete: 33.3 },
    staged: false,
    note: 'Skills are listed by priority rather than in difficulty stages.',
    ...overrides,
  } as Roadmap;
}

function form() {
  return screen.getByRole('form');
}

describe('CareerGoals', () => {
  beforeEach(() => {
    jobCategories.mockResolvedValue([{ category: 'Backend Developer', jobCount: 3 }]);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    vi.restoreAllMocks();
  });

  it('creates a goal and opens its roadmap', async () => {
    careerGoals.mockResolvedValue([]);
    createCareerGoal.mockResolvedValue(goal());
    careerGoalRoadmap.mockResolvedValue(roadmap());
    render(<CareerGoals />);
    await screen.findByText('No goals yet');
    await screen.findByRole('option', { name: 'Backend Developer' });

    fireEvent.change(within(form()).getByLabelText('Target role'), { target: { value: 'Backend Engineer' } });
    fireEvent.change(within(form()).getByLabelText('Job category'), { target: { value: 'Backend Developer' } });
    fireEvent.change(within(form()).getByLabelText(/Skills you want to develop/), { target: { value: 'Go, , go, Kubernetes' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create goal' }));

    expect(await screen.findByRole('region', { name: 'Roadmap for Backend Engineer' })).toBeTruthy();
    expect(createCareerGoal).toHaveBeenCalledWith(expect.objectContaining({
      targetRole: 'Backend Engineer', targetCategory: 'Backend Developer', targetSkills: ['Go', 'go', 'Kubernetes'],
    }));
  });

  it('shows the roadmap: progress, covered skills and prioritised skills with reasons', async () => {
    careerGoals.mockResolvedValue([goal()]);
    careerGoalRoadmap.mockResolvedValue(roadmap());
    render(<CareerGoals />);

    fireEvent.click(await screen.findByRole('button', { name: 'View roadmap' }));

    const panel = await screen.findByRole('region', { name: 'Roadmap for Backend Engineer' });
    expect(within(panel).getByText('33% of the roadmap covered')).toBeTruthy();
    expect(within(panel).getByText(/measured against “Main CV”/)).toBeTruthy();
    expect(within(within(panel).getByLabelText('Covered skills')).getByText('Java')).toBeTruthy();
    const rows = within(panel).getAllByRole('row').slice(1);
    expect(within(rows[0]).getByText('Docker')).toBeTruthy();
    expect(within(rows[0]).getByText(/33.3% of Backend Developer postings/)).toBeTruthy();
    expect(within(rows[1]).getByText('Your choice')).toBeTruthy();
    expect(within(panel).getByText(/by priority rather than in difficulty stages/)).toBeTruthy();
  });

  it('records progress on a skill and reloads the roadmap', async () => {
    careerGoals.mockResolvedValue([goal()]);
    careerGoalRoadmap
      .mockResolvedValueOnce(roadmap())
      .mockResolvedValueOnce(roadmap({
        roadmap: [{ ...roadmap().roadmap[0], status: 'COMPLETED' }, roadmap().roadmap[1]],
        progress: { totalSkills: 3, onResume: 1, completed: 1, inProgress: 0, notStarted: 1, percentComplete: 66.7 },
      }));
    setRoadmapSkillStatus.mockResolvedValue({ status: 'COMPLETED' });
    render(<CareerGoals />);
    fireEvent.click(await screen.findByRole('button', { name: 'View roadmap' }));

    fireEvent.change(await screen.findByLabelText('Progress on Docker'), { target: { value: 'COMPLETED' } });

    expect(await screen.findByText('67% of the roadmap covered')).toBeTruthy();
    expect(setRoadmapSkillStatus).toHaveBeenCalledWith('g1', 2, 'COMPLETED');
  });

  it('edits a goal and shows server validation messages', async () => {
    careerGoals.mockResolvedValue([goal()]);
    updateCareerGoal
      .mockRejectedValueOnce(new ApiError(400, "Unknown skill 'Cobol'; choose a skill that appears in job postings"))
      .mockResolvedValueOnce(goal({ targetRole: 'Senior Backend Engineer' }));
    render(<CareerGoals />);
    fireEvent.click(await screen.findByRole('button', { name: 'Edit' }));

    expect((within(form()).getByLabelText(/Skills you want to develop/) as HTMLInputElement).value).toBe('Go');
    fireEvent.change(within(form()).getByLabelText(/Skills you want to develop/), { target: { value: 'Cobol' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect((await screen.findByRole('alert')).textContent).toContain("Unknown skill 'Cobol'");

    fireEvent.change(within(form()).getByLabelText('Target role'), { target: { value: 'Senior Backend Engineer' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Senior Backend Engineer')).toBeTruthy();
  });

  it('archives, filters and deletes goals', async () => {
    careerGoals.mockResolvedValue([goal(), goal({ id: 'g2', targetRole: 'Data Engineer', status: 'COMPLETED' })]);
    setCareerGoalStatus.mockResolvedValue(goal({ status: 'ARCHIVED' }));
    deleteCareerGoal.mockResolvedValue(undefined);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    render(<CareerGoals />);

    await screen.findByText('Backend Engineer');
    expect(screen.queryByText('Data Engineer')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Archive' }));
    expect(await screen.findByText('No goals here')).toBeTruthy();
    expect(setCareerGoalStatus).toHaveBeenCalledWith('g1', 'ARCHIVED');

    fireEvent.change(screen.getByLabelText('Filter goals'), { target: { value: 'ALL' } });
    expect(screen.getByText('Data Engineer')).toBeTruthy();
    fireEvent.click(within(screen.getByText('Data Engineer').closest('li')!).getByRole('button', { name: 'Delete' }));
    await vi.waitFor(() => expect(screen.queryByText('Data Engineer')).toBeNull());
    expect(deleteCareerGoal).toHaveBeenCalledWith('g2');
  });
});

describe('parseSkills', () => {
  it('trims, drops blanks and repeats', () => {
    expect(parseSkills(' Go, ,Kubernetes, Go ')).toEqual(['Go', 'Kubernetes']);
    expect(parseSkills('')).toEqual([]);
  });
});
