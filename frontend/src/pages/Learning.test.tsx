import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { LearningPlan } from '../api/types';
import { Learning } from './Learning';

const learningPlan = vi.fn();
const createLearningItem = vi.fn();
const setLearningItemStatus = vi.fn();
const addLearningResource = vi.fn();
const deleteLearningResource = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      learningPlan: (...args: unknown[]) => learningPlan(...args),
      createLearningItem: (...args: unknown[]) => createLearningItem(...args),
      setLearningItemStatus: (...args: unknown[]) => setLearningItemStatus(...args),
      addLearningResource: (...args: unknown[]) => addLearningResource(...args),
      deleteLearningResource: (...args: unknown[]) => deleteLearningResource(...args),
    },
  };
});

function plan(overrides: Partial<LearningPlan> = {}): LearningPlan {
  return {
    goalId: 'g1',
    goalRole: 'Backend Developer',
    priorities: [
      { rank: 1, skillId: 2, skill: 'Docker', reason: 'In 100% of Backend Developer postings', suggestedPriority: 'HIGH',
        roadmapStatus: 'IN_PROGRESS', itemId: 'i1' },
      { rank: 2, skillId: 3, skill: 'Kubernetes', reason: 'In 33.3% of Backend Developer postings', suggestedPriority: 'HIGH' },
    ],
    items: [
      { id: 'i1', goalId: 'g1', skillId: 2, skill: 'Docker', topic: 'Containers basics', priority: 'HIGH', status: 'NOT_STARTED',
        progress: 40, targetDate: '2026-12-01', createdAt: '2026-09-29T08:00:00Z', updatedAt: '2026-09-29T08:00:00Z',
        roadmapStatus: 'NOT_STARTED',
        resources: [{ id: 'r1', title: 'Docker docs', url: 'https://docs.docker.com/', type: 'DOCUMENTATION', createdAt: '2026-09-29T08:00:00Z' }] },
    ],
    progress: { items: 1, notStarted: 1, inProgress: 0, completed: 0, averageProgress: 40, completedSkills: [] },
    impact: { goalRole: 'Backend Developer', roadmapSkills: 3, roadmapCompleted: 1, roadmapPercentComplete: 33.3,
      completedNotOnResume: [], savedJobDemand: [], note: 'Job matches read your resume.' },
    ...overrides,
  };
}

describe('Learning', () => {
  beforeEach(() => {
    learningPlan.mockResolvedValue(plan());
    createLearningItem.mockResolvedValue({});
    setLearningItemStatus.mockResolvedValue({});
    addLearningResource.mockResolvedValue({});
    deleteLearningResource.mockResolvedValue(undefined);
  });
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('shows priority skills, the roadmap item with its progress, target date and resources', async () => {
    render(<Learning />);
    expect(await screen.findByText('In 33.3% of Backend Developer postings')).toBeTruthy();
    expect(screen.getByText('Planned')).toBeTruthy();
    expect(screen.getByRole('meter', { name: 'Progress on Docker' }).getAttribute('aria-valuenow')).toBe('40');
    expect(screen.getByText(/target 2026-12-01/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Docker docs' }).getAttribute('href')).toBe('https://docs.docker.com/');
    expect(screen.getByText(/1 of 3 skills covered/)).toBeTruthy();
  });

  it('plans a priority skill, starts an item and adds a resource', async () => {
    render(<Learning />);
    fireEvent.click(await screen.findByRole('button', { name: 'Plan Kubernetes' }));
    expect(createLearningItem).toHaveBeenCalledWith({ skillId: 3, topic: 'Learn Kubernetes' });

    fireEvent.click(screen.getByRole('button', { name: 'Start' }));
    expect(setLearningItemStatus).toHaveBeenCalledWith('i1', 'IN_PROGRESS');

    const form = screen.getByRole('form', { name: 'Add a resource for Docker' });
    fireEvent.change(form.querySelector('input[type="text"]')!, { target: { value: 'Compose guide' } });
    fireEvent.change(form.querySelector('input[type="url"]')!, { target: { value: 'https://docs.docker.com/compose/' } });
    fireEvent.submit(form);
    expect(addLearningResource).toHaveBeenCalledWith('i1', { title: 'Compose guide', url: 'https://docs.docker.com/compose/', type: 'COURSE' });
  });

  it('explains a missing career goal and lists completed skills', async () => {
    learningPlan.mockResolvedValue(plan({
      goalId: undefined, goalRole: undefined, priorities: [], items: [],
      note: 'Set an active career goal to see which skills to learn first.',
      progress: { items: 0, notStarted: 0, inProgress: 0, completed: 0, completedSkills: ['Rust'] },
      impact: { completedNotOnResume: ['Rust'] },
    }));
    render(<Learning />);
    expect(await screen.findByText(/Set an active career goal/)).toBeTruthy();
    expect(screen.getByText('Nothing planned yet')).toBeTruthy();
    expect(screen.getByText(/Completed but not on your resume yet: Rust/)).toBeTruthy();
  });
});
