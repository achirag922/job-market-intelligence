import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BuilderContent, Resume } from '../api/types';
import { ResumePreview } from '../components/ResumePreview';
import { ResumeBuilder, validate } from './ResumeBuilder';

const resumes = vi.fn();
const createBuiltResume = vi.fn();
const builtResume = vi.fn();
const saveBuiltResume = vi.fn();
const duplicateResume = vi.fn();
const setDefaultResume = vi.fn();
const deleteResume = vi.fn();
const updateResume = vi.fn();
const savedJobs = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      resumes: (...a: unknown[]) => resumes(...a),
      createBuiltResume: (...a: unknown[]) => createBuiltResume(...a),
      builtResume: (...a: unknown[]) => builtResume(...a),
      saveBuiltResume: (...a: unknown[]) => saveBuiltResume(...a),
      duplicateResume: (...a: unknown[]) => duplicateResume(...a),
      setDefaultResume: (...a: unknown[]) => setDefaultResume(...a),
      deleteResume: (...a: unknown[]) => deleteResume(...a),
      updateResume: (...a: unknown[]) => updateResume(...a),
      savedJobs: (...a: unknown[]) => savedJobs(...a),
    },
  };
});

const built = (overrides: Partial<Resume> = {}): Resume => ({
  id: 'r1', fileName: 'resume.pdf', fileSizeBytes: 100, status: 'COMPLETED', skills: [{ id: 1, name: 'Java' }],
  uploadedAt: '2026-09-01T10:00:00Z', title: 'Main CV', isDefault: true, updatedAt: '2026-09-01T10:00:00Z', source: 'BUILDER',
  ...overrides,
}) as Resume;

const content: BuilderContent = {
  template: 'CLASSIC',
  personal: { fullName: 'José Müller', headline: 'Backend Developer', email: 'jose@example.test' },
  summary: 'Builds Java services.',
  skills: ['Java'],
  experience: [{ title: 'Software Engineer', company: 'Acme', start: '2021', current: true, bullets: ['Built APIs.'] }],
};

beforeEach(() => {
  resumes.mockResolvedValue([built(), built({ id: 'u1', title: 'Uploaded', source: 'UPLOAD', isDefault: false })]);
  builtResume.mockResolvedValue({ resume: built(), content });
  savedJobs.mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('ResumeBuilder', () => {
  it('lists only built resumes, with duplicate and delete, and creates a new one', async () => {
    createBuiltResume.mockResolvedValue({ resume: built({ id: 'r2', title: 'Backend' }), content });
    duplicateResume.mockResolvedValue({ resume: built({ id: 'r3' }), content });
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    render(<ResumeBuilder />);

    expect(await screen.findByText('Main CV')).toBeTruthy();
    expect(screen.queryByText('Uploaded')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Duplicate' }));
    await waitFor(() => expect(duplicateResume).toHaveBeenCalledWith('r1'));
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(deleteResume).toHaveBeenCalledWith('r1'));

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Backend' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create resume' }));
    await waitFor(() => expect(createBuiltResume).toHaveBeenCalledWith({ title: 'Backend' }));
    expect(await screen.findByRole('navigation', { name: 'Resume sections' })).toBeTruthy();
  });

  it('edits sections with a live preview, tracks unsaved changes, validates and saves', async () => {
    saveBuiltResume.mockImplementation((_id: string, next: BuilderContent) => Promise.resolve({ resume: built(), content: next }));
    render(<ResumeBuilder />);
    fireEvent.click(await screen.findByRole('button', { name: 'Edit Main CV' }));

    const preview = await screen.findByRole('article', { name: 'Resume preview' });
    expect(within(preview).getByText('José Müller')).toBeTruthy();
    expect(screen.getByText('All changes saved')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Headline'), { target: { value: 'Senior Backend Developer' } });
    expect(within(preview).getByText('Senior Backend Developer')).toBeTruthy();
    expect(screen.getByText('Unsaved changes')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Experience' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add experience' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Experience 2: job title and company are required.')).toBeTruthy();
    expect(saveBuiltResume).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Remove experience 2' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saveBuiltResume).toHaveBeenCalledWith('r1', expect.objectContaining({
      personal: expect.objectContaining({ headline: 'Senior Backend Developer' }) })));
    expect(await screen.findByText('All changes saved')).toBeTruthy();
  });

  it('validates the sections the server requires', () => {
    expect(validate({ personal: { fullName: ' ' } })).toContain('Personal: full name is required.');
    expect(validate({ personal: { fullName: 'A', email: 'nope' } })).toContain('Personal: the email address is not valid.');
    expect(validate(content)).toEqual([]);
  });

  it('renders both templates with the same content', () => {
    const { rerender } = render(<ResumePreview content={content} />);
    expect(screen.getByRole('article', { name: 'Resume preview' }).className).toContain('resume-classic');
    rerender(<ResumePreview content={{ ...content, template: 'MODERN' }} />);
    const sheet = screen.getByRole('article', { name: 'Resume preview' });
    expect(sheet.className).toContain('resume-modern');
    expect(within(sheet).getByText('2021 – Present')).toBeTruthy();
    expect(within(sheet).getByText('Built APIs.')).toBeTruthy();
  });
});
