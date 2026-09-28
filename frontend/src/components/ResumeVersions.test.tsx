import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Resume, ResumeJobAnalysis } from '../api/types';
import { JobAnalysisPanel, ResumeVersions, versionName } from './ResumeVersions';

const resumes = vi.fn();
const updateResume = vi.fn();
const setDefaultResume = vi.fn();
const deleteResume = vi.fn();
const compareResumes = vi.fn();
const analyzeResumeJob = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      resumes: (...args: unknown[]) => resumes(...args),
      updateResume: (...args: unknown[]) => updateResume(...args),
      setDefaultResume: (...args: unknown[]) => setDefaultResume(...args),
      deleteResume: (...args: unknown[]) => deleteResume(...args),
      compareResumes: (...args: unknown[]) => compareResumes(...args),
      analyzeResumeJob: (...args: unknown[]) => analyzeResumeJob(...args),
    },
  };
});

function resume(id: string, title: string, overrides: Partial<Resume> = {}): Resume {
  return {
    id,
    fileName: `${title}.pdf`,
    fileSizeBytes: 1000,
    status: 'COMPLETED',
    skills: [{ id: 1, name: 'Java', category: 'LANGUAGE' }],
    uploadedAt: '2026-09-20T08:00:00Z',
    title,
    isDefault: false,
    updatedAt: '2026-09-20T08:00:00Z',
    ...overrides,
  } as Resume;
}

const BACKEND = resume('r1', 'Backend CV', { isDefault: true, versionLabel: 'v2' });
const PLATFORM = resume('r2', 'Platform CV');

function renderVersions(props: Partial<Parameters<typeof ResumeVersions>[0]> = {}) {
  const handlers = { onSelect: vi.fn(), onLoaded: vi.fn(), onDeleted: vi.fn() };
  render(<ResumeVersions refreshKey={0} {...handlers} {...props} />);
  return handlers;
}

function item(title: string) {
  return screen.getByText(title, { selector: 'strong' }).closest('li')!;
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.restoreAllMocks();
});

describe('ResumeVersions', () => {
  it('lists versions with labels, the default and the one in use, and selects one', async () => {
    resumes.mockResolvedValue([BACKEND, PLATFORM]);
    const handlers = renderVersions({ selectedId: 'r2' });

    await screen.findByText('Backend CV', { selector: 'strong' });
    expect(handlers.onLoaded).toHaveBeenCalledWith([BACKEND, PLATFORM]);
    expect(within(item('Backend CV')).getByText('v2')).toBeTruthy();
    expect(within(item('Backend CV')).getByText('Default')).toBeTruthy();
    expect(within(item('Platform CV')).getByText('In use')).toBeTruthy();
    expect((within(item('Platform CV')).getByRole('button', { name: 'Use' }) as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(within(item('Backend CV')).getByRole('button', { name: 'Use' }));
    expect(handlers.onSelect).toHaveBeenCalledWith(BACKEND);
  });

  it('renames and relabels a version', async () => {
    resumes.mockResolvedValue([PLATFORM]);
    updateResume.mockResolvedValue({ ...PLATFORM, title: 'Platform focus', versionLabel: 'v3' });
    renderVersions();

    fireEvent.click(within(await screen.findByText('Platform CV', { selector: 'strong' }).then((el) => el.closest('li')!)).getByRole('button', { name: 'Rename' }));
    const form = screen.getByRole('form', { name: 'Rename resume' });
    fireEvent.change(within(form).getByLabelText('Title'), { target: { value: 'Platform focus' } });
    fireEvent.change(within(form).getByLabelText('Version label'), { target: { value: 'v3' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Platform focus')).toBeTruthy();
    expect(screen.getByText('v3')).toBeTruthy();
    expect(updateResume).toHaveBeenCalledWith('r2', 'Platform focus', 'v3');
  });

  it('moves the default to another version', async () => {
    resumes.mockResolvedValue([BACKEND, PLATFORM]);
    setDefaultResume.mockResolvedValue({ ...PLATFORM, isDefault: true });
    renderVersions();

    fireEvent.click(within((await screen.findByText('Platform CV', { selector: 'strong' })).closest('li')!).getByRole('button', { name: 'Set default' }));

    expect(await within(item('Platform CV')).findByText('Default')).toBeTruthy();
    expect(within(item('Backend CV')).queryByText('Default')).toBeNull();
    expect(setDefaultResume).toHaveBeenCalledWith('r2');
  });

  it('deletes a version after confirmation and reloads the list', async () => {
    resumes.mockResolvedValueOnce([BACKEND, PLATFORM]).mockResolvedValueOnce([{ ...PLATFORM, isDefault: true }]);
    deleteResume.mockResolvedValue(undefined);
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const handlers = renderVersions();

    fireEvent.click(within((await screen.findByText('Backend CV', { selector: 'strong' })).closest('li')!).getByRole('button', { name: 'Delete' }));

    await vi.waitFor(() => expect(screen.queryByText('Backend CV', { selector: 'strong' })).toBeNull());
    expect(deleteResume).toHaveBeenCalledWith('r1');
    expect(handlers.onDeleted).toHaveBeenCalledWith('r1');
    expect(within(item('Platform CV')).getByText('Default')).toBeTruthy();
  });

  it('compares two versions: skills added, removed, shared, and metadata that differs', async () => {
    resumes.mockResolvedValue([BACKEND, PLATFORM]);
    compareResumes.mockResolvedValue({
      first: { id: 'r1', title: 'Backend CV', versionLabel: 'v2', fileName: 'Backend CV.pdf', isDefault: true, skillCount: 2,
        uploadedAt: '2026-09-20T08:00:00Z', updatedAt: '2026-09-20T08:00:00Z' },
      second: { id: 'r2', title: 'Platform CV', fileName: 'Platform CV.pdf', isDefault: false, skillCount: 3,
        uploadedAt: '2026-09-21T08:00:00Z', updatedAt: '2026-09-21T08:00:00Z' },
      skillsAdded: [{ id: 3, name: 'Kubernetes' }],
      skillsRemoved: [{ id: 2, name: 'Docker' }],
      commonSkills: [{ id: 1, name: 'Java' }],
      differentFields: ['title', 'skillCount'],
    });
    renderVersions();
    await screen.findByText('Backend CV', { selector: 'strong' });

    fireEvent.change(screen.getByLabelText('From'), { target: { value: 'r1' } });
    fireEvent.change(screen.getByLabelText('To'), { target: { value: 'r2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Compare' }));

    const result = await screen.findByLabelText('Comparison result');
    expect(compareResumes).toHaveBeenCalledWith('r1', 'r2');
    expect(within(within(result).getByLabelText('Skills added')).getByText('Kubernetes')).toBeTruthy();
    expect(within(within(result).getByLabelText('Skills removed')).getByText('Docker')).toBeTruthy();
    expect(within(within(result).getByLabelText('Common skills')).getByText('Java')).toBeTruthy();
    expect(within(result).getByRole('row', { name: /Skills found 2 3/ })).toBeTruthy();
  });

  it('does not offer a comparison with the same version on both sides', async () => {
    resumes.mockResolvedValue([BACKEND, PLATFORM]);
    renderVersions();
    await screen.findByText('Backend CV', { selector: 'strong' });

    fireEvent.change(screen.getByLabelText('From'), { target: { value: 'r1' } });
    fireEvent.change(screen.getByLabelText('To'), { target: { value: 'r1' } });
    expect((screen.getByRole('button', { name: 'Compare' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('names a version by title and label', () => {
    expect(versionName(BACKEND)).toBe('Backend CV · v2');
    expect(versionName(PLATFORM)).toBe('Platform CV');
  });
});

describe('JobAnalysisPanel', () => {
  const analysis: ResumeJobAnalysis = {
    resumeId: 'r1',
    resumeTitle: 'Backend CV',
    jobId: 1,
    jobTitle: 'Platform Engineer',
    companyName: 'Acme Systems',
    matchPercentage: 66.7,
    totalJobSkills: 3,
    matchedSkillCount: 2,
    missingSkillCount: 1,
    matchedSkills: [{ id: 1, name: 'Java' }, { id: 2, name: 'Docker' }],
    missingSkills: [{ id: 3, name: 'Kubernetes' }],
    otherResumeSkills: [],
    experience: { required: { min: 3, max: 5 }, note: 'The posting asks for 3–5 years.' },
    suggestions: ['The posting lists 1 skill your resume does not mention: Kubernetes.'],
    disclaimer: 'It is not a prediction of whether you will be invited to interview or hired.',
  } as ResumeJobAnalysis;

  it('loads the analysis on request and shows the summary, skills, experience and suggestions', async () => {
    analyzeResumeJob.mockResolvedValue(analysis);
    render(<JobAnalysisPanel resumeId="r1" jobId={1} />);

    fireEvent.click(screen.getByRole('button', { name: 'Show detailed analysis' }));

    expect(await screen.findByText(/2 of 3 required skills found \(67%\)/)).toBeTruthy();
    expect(analyzeResumeJob).toHaveBeenCalledWith('r1', 1);
    expect(screen.getByRole('img', { name: '2 matched, 1 missing' })).toBeTruthy();
    expect(within(screen.getByLabelText('Missing skills')).getByText('Kubernetes')).toBeTruthy();
    expect(screen.getByText('The posting asks for 3–5 years.')).toBeTruthy();
    expect(screen.getByText(/does not mention: Kubernetes/)).toBeTruthy();
    expect(screen.getByText(/not a prediction/)).toBeTruthy();
  });
});
