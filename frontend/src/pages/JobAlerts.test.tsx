import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import type { JobAlert } from '../api/types';
import { JobAlerts, alertSearchLink } from './JobAlerts';

const jobAlerts = vi.fn();
const jobCategories = vi.fn();
const createJobAlert = vi.fn();
const updateJobAlert = vi.fn();
const setJobAlertActive = vi.fn();
const deleteJobAlert = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      jobAlerts: (...args: unknown[]) => jobAlerts(...args),
      jobCategories: (...args: unknown[]) => jobCategories(...args),
      createJobAlert: (...args: unknown[]) => createJobAlert(...args),
      updateJobAlert: (...args: unknown[]) => updateJobAlert(...args),
      setJobAlertActive: (...args: unknown[]) => setJobAlertActive(...args),
      deleteJobAlert: (...args: unknown[]) => deleteJobAlert(...args),
    },
  };
});

function alert(overrides: Partial<JobAlert> = {}): JobAlert {
  return {
    id: 'a1',
    name: 'Java in Berlin',
    keywords: 'backend',
    category: 'Software Engineering',
    location: 'Berlin',
    experience: '2-5',
    skill: 'Java',
    frequency: 'DAILY',
    active: true,
    createdAt: '2026-09-25T08:00:00Z',
    updatedAt: '2026-09-25T08:00:00Z',
    ...overrides,
  };
}

function renderPage() {
  return render(
    <MemoryRouter>
      <JobAlerts />
    </MemoryRouter>,
  );
}

function field(name: RegExp) {
  return within(screen.getByRole('form')).getByLabelText(name);
}

describe('JobAlerts', () => {
  beforeEach(() => {
    jobCategories.mockResolvedValue([{ category: 'Software Engineering', jobCount: 10 }]);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    vi.restoreAllMocks();
  });

  it('lists the alerts with status, frequency, criteria and a link to matching jobs', async () => {
    jobAlerts.mockResolvedValue([alert(), alert({ id: 'a2', name: 'Weekly data', frequency: 'WEEKLY', active: false })]);
    renderPage();

    const first = (await screen.findByText('Java in Berlin')).closest('li')!;
    expect(within(first).getByText('Active')).toBeTruthy();
    expect(within(first).getByText('Daily')).toBeTruthy();
    expect(within(first).getByText(/Skill: Java/)).toBeTruthy();
    expect(within(first).getByText(/Experience: 2–5 years/)).toBeTruthy();
    expect(within(first).getByRole('link', { name: 'View matching jobs' }).getAttribute('href')).toBe(
      '/jobs?q=backend&category=Software+Engineering&skill=Java&location=Berlin&experience=2-5',
    );

    const second = screen.getByText('Weekly data').closest('li')!;
    expect(within(second).getByText('Paused')).toBeTruthy();
    expect(within(second).getByText('Weekly')).toBeTruthy();
  });

  it('shows an empty state when there are no alerts', async () => {
    jobAlerts.mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText('No alerts yet')).toBeTruthy();
  });

  it('creates an alert and adds it to the top of the list', async () => {
    jobAlerts.mockResolvedValue([alert()]);
    createJobAlert.mockResolvedValue(alert({ id: 'new', name: 'Remote Go', keywords: undefined, skill: 'Go', frequency: 'WEEKLY' }));
    renderPage();
    await screen.findByText('Java in Berlin');

    fireEvent.change(field(/Name/), { target: { value: 'Remote Go' } });
    fireEvent.change(field(/Skill/), { target: { value: 'Go' } });
    fireEvent.change(field(/Frequency/), { target: { value: 'WEEKLY' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create alert' }));

    expect(await screen.findByText('Remote Go')).toBeTruthy();
    expect(createJobAlert).toHaveBeenCalledWith(expect.objectContaining({ name: 'Remote Go', skill: 'Go', frequency: 'WEEKLY' }));
    const items = screen.getAllByRole('listitem');
    expect(within(items[0]).getByText('Remote Go')).toBeTruthy();
    expect((field(/Name/) as HTMLInputElement).value).toBe('');
  });

  it("shows the server's validation message and keeps what was typed", async () => {
    jobAlerts.mockResolvedValue([]);
    createJobAlert.mockRejectedValue(
      new ApiError(400, 'give at least one of keywords, category, location, experience or skill'),
    );
    renderPage();
    await screen.findByText('No alerts yet');

    fireEvent.change(field(/Name/), { target: { value: 'Too vague' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create alert' }));

    expect((await screen.findByRole('alert')).textContent).toContain('at least one of');
    expect((field(/Name/) as HTMLInputElement).value).toBe('Too vague');
  });

  it('edits an alert in place', async () => {
    jobAlerts.mockResolvedValue([alert()]);
    updateJobAlert.mockResolvedValue(alert({ name: 'Senior Java', frequency: 'WEEKLY' }));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit' }));

    expect((field(/Name/) as HTMLInputElement).value).toBe('Java in Berlin');
    expect((field(/Location/) as HTMLInputElement).value).toBe('Berlin');
    fireEvent.change(field(/Name/), { target: { value: 'Senior Java' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('Senior Java')).toBeTruthy();
    expect(updateJobAlert).toHaveBeenCalledWith('a1', expect.objectContaining({ name: 'Senior Java', location: 'Berlin' }));
    expect(screen.queryByText('Java in Berlin')).toBeNull();
    expect(screen.getByRole('button', { name: 'Create alert' })).toBeTruthy();
  });

  it('pauses and resumes an alert', async () => {
    jobAlerts.mockResolvedValue([alert()]);
    setJobAlertActive.mockResolvedValueOnce(alert({ active: false })).mockResolvedValueOnce(alert({ active: true }));
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Pause' }));
    expect(await screen.findByText('Paused')).toBeTruthy();
    expect(setJobAlertActive).toHaveBeenCalledWith('a1', false);

    fireEvent.click(screen.getByRole('button', { name: 'Resume' }));
    expect(await screen.findByText('Active')).toBeTruthy();
    expect(setJobAlertActive).toHaveBeenLastCalledWith('a1', true);
  });

  it('deletes an alert only after confirmation', async () => {
    jobAlerts.mockResolvedValue([alert()]);
    deleteJobAlert.mockResolvedValue(undefined);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true);
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }));
    expect(deleteJobAlert).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('No alerts yet')).toBeTruthy();
    expect(deleteJobAlert).toHaveBeenCalledWith('a1');
    expect(confirm).toHaveBeenCalledTimes(2);
  });

  it('shows an error with a retry when the alerts cannot be loaded', async () => {
    jobAlerts.mockRejectedValueOnce(new ApiError(0, 'Cannot reach the API')).mockResolvedValueOnce([alert()]);
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: /retry|try again/i }));
    expect(await screen.findByText('Java in Berlin')).toBeTruthy();
  });
});

describe('alertSearchLink', () => {
  it('leaves out empty filters', () => {
    expect(alertSearchLink({ name: 'x', skill: 'Go', keywords: '', frequency: 'DAILY' })).toBe('/jobs?skill=Go');
  });
});
