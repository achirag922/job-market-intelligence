import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import type { JobSummary, PagedResponse } from '../api/types';
import { JobExplorer } from './JobExplorer';

/**
 * Job Explorer, rendered against a mocked API.
 *
 * <p>jsdom applies no media queries, so the desktop filter panel and the drawer's copy of
 * it are both in the DOM at once. Every query below is scoped to one or the other — the
 * page's search region, or the dialog — so a test always names which it is driving.
 */

const jobs = vi.fn();
const jobCategories = vi.fn();
const salaryCurrencies = vi.fn();
const skills = vi.fn(() => Promise.resolve({ content: [] }));
const companies = vi.fn(() => Promise.resolve({ content: [] }));
const locations = vi.fn(() => Promise.resolve({ content: [] }));

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      jobs: (...args: unknown[]) => jobs(...args),
      jobCategories: () => jobCategories(),
      salaryCurrencies: () => salaryCurrencies(),
      // Suggestions are a convenience; empty keeps them out of the way. Spies, so the
      // request-count test can check none are made unprompted.
      skills: (...args: unknown[]) => skills(...(args as [])),
      companies: (...args: unknown[]) => companies(...(args as [])),
      locations: (...args: unknown[]) => locations(...(args as [])),
    },
  };
});

function job(id: number, title: string): JobSummary {
  return {
    id,
    title,
    company: { id: 1, name: 'Acme Systems' },
    location: { id: 1, city: 'Bengaluru', country: 'India', displayName: 'Bengaluru, India' },
    employmentType: 'FULL_TIME',
    experience: { min: 2, max: 4 },
    category: 'Backend Developer',
    skills: [{ id: 1, name: 'Java' }],
    postedDate: '2026-08-01',
  } as JobSummary;
}

function page(content: JobSummary[], extra: Partial<PagedResponse<JobSummary>> = {}): PagedResponse<JobSummary> {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: 1,
    first: true,
    last: true,
    ...extra,
  };
}

/** Renders the page at a URL, with the current query string exposed for assertions. */
function renderAt(url = '/jobs') {
  function LocationProbe() {
    return <output data-testid="search">{useLocation().search}</output>;
  }
  return render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route
          path="/jobs"
          element={
            <>
              <JobExplorer />
              <LocationProbe />
            </>
          }
        />
      </Routes>
    </MemoryRouter>,
  );
}

const currentSearch = () => screen.getByTestId('search').textContent ?? '';
const panel = () => within(screen.getByRole('region', { name: 'Search and filters' }));
/** The filters of the most recent API call. */
const lastFilters = () => jobs.mock.calls.at(-1)?.[0];

/** Type into a text filter and press Enter, which commits without waiting for the pause. */
function commitText(input: HTMLElement, value: string) {
  fireEvent.change(input, { target: { value } });
  fireEvent.keyDown(input, { key: 'Enter' });
}

describe('JobExplorer', () => {
  beforeEach(() => {
    skills.mockClear();
    companies.mockClear();
    locations.mockClear();
    jobs.mockReset().mockResolvedValue(page([job(1, 'Java Developer'), job(2, 'Backend Engineer')]));
    jobCategories.mockReset().mockResolvedValue([
      { category: 'Backend Developer', jobCount: 19, percentageOfJobs: 13.8, rank: 1 },
      { category: 'Data Engineer', jobCount: 15, percentageOfJobs: 10.9, rank: 2 },
    ]);
    salaryCurrencies.mockReset().mockResolvedValue([
      { currency: 'INR', jobCount: 32, lowestMin: 380000, highestMax: 5610000 },
      { currency: 'USD', jobCount: 21, lowestMin: 42000, highestMax: 420000 },
    ]);
  });

  afterEach(cleanup);

  // ------------------------------------------------------------- requests

  it('makes one search request per load, and no suggestion requests unprompted', async () => {
    // Found in the browser: the drawer's hidden copy of the panel, and fields seeding
    // suggestions from URL values, both fetched on every page view.
    renderAt('/jobs?skill=Java&location=Bengaluru&company=Acme');
    await screen.findByText('Java Developer');
    // Give any stray effect the chance to fire before counting.
    await new Promise((resolve) => setTimeout(resolve, 400));

    expect(jobs).toHaveBeenCalledTimes(1);
    expect(jobCategories).toHaveBeenCalledTimes(1);
    expect(salaryCurrencies).toHaveBeenCalledTimes(1);
    expect(skills).not.toHaveBeenCalled();
    expect(companies).not.toHaveBeenCalled();
    expect(locations).not.toHaveBeenCalled();
  });

  it('asks for suggestions only once someone types, and loads locations just once', async () => {
    renderAt();
    await screen.findByText('Java Developer');
    const location = panel().getByLabelText('Location');

    // Type, clear, type again: the location list must still be fetched a single time.
    for (const text of ['B', '', 'Be']) {
      fireEvent.change(location, { target: { value: text } });
    }

    await waitFor(() => expect(locations).toHaveBeenCalledTimes(1));
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(locations).toHaveBeenCalledTimes(1);
  });

  // ---------------------------------------------------------------- search

  it('searches the text box and writes it to the URL', async () => {
    renderAt();
    await screen.findByText('Java Developer');

    commitText(screen.getByLabelText('Search jobs'), 'java spring');

    await waitFor(() => expect(currentSearch()).toBe('?q=java+spring'));
    await waitFor(() => expect(lastFilters()).toMatchObject({ q: 'java spring' }));
  });

  it('waits for typing to pause instead of searching on every key', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      renderAt();
      await screen.findByText('Java Developer');
      const callsBefore = jobs.mock.calls.length;
      const input = screen.getByLabelText('Search jobs');

      for (const text of ['j', 'ja', 'jav', 'java']) {
        fireEvent.change(input, { target: { value: text } });
        await act(async () => {
          await vi.advanceTimersByTimeAsync(100);
        });
      }
      // Four keystrokes, all inside the pause: nothing searched yet.
      expect(jobs.mock.calls.length).toBe(callsBefore);

      await act(async () => {
        await vi.advanceTimersByTimeAsync(400);
      });
      await waitFor(() => expect(jobs.mock.calls.length).toBe(callsBefore + 1));
      expect(lastFilters()).toMatchObject({ q: 'java' });
    } finally {
      vi.useRealTimers();
    }
  });

  // --------------------------------------------------------------- filters

  it('filters by category', async () => {
    renderAt();
    await screen.findByText('Java Developer');
    // Categories arrive from the API; wait for the option before choosing it.
    await panel().findByRole('option', { name: 'Backend Developer (19)' });

    fireEvent.change(panel().getByLabelText('Category'), { target: { value: 'Backend Developer' } });

    await waitFor(() => expect(currentSearch()).toBe('?category=Backend+Developer'));
    expect(lastFilters()).toMatchObject({ category: 'Backend Developer' });
  });

  it('filters by skill', async () => {
    renderAt();
    await screen.findByText('Java Developer');

    commitText(panel().getByLabelText('Skill'), 'Java');

    await waitFor(() => expect(lastFilters()).toMatchObject({ skill: 'Java' }));
  });

  it('filters by location', async () => {
    renderAt();
    await screen.findByText('Java Developer');

    commitText(panel().getByLabelText('Location'), 'Bengaluru');

    await waitFor(() => expect(lastFilters()).toMatchObject({ location: 'Bengaluru' }));
  });

  it('filters by company', async () => {
    renderAt();
    await screen.findByText('Java Developer');

    commitText(panel().getByLabelText('Company'), 'Acme');

    await waitFor(() => expect(lastFilters()).toMatchObject({ company: 'Acme' }));
  });

  it('filters by experience band', async () => {
    renderAt();
    await screen.findByText('Java Developer');

    fireEvent.change(panel().getByLabelText('Experience'), { target: { value: '2-5' } });

    await waitFor(() => expect(currentSearch()).toBe('?experience=2-5'));
    expect(lastFilters()).toMatchObject({ experience: '2-5' });
  });

  it('keeps salary amounts disabled until a currency scopes them', async () => {
    renderAt();
    await screen.findByText('Java Developer');
    await panel().findByRole('option', { name: 'INR (32)' });

    expect(panel().getByLabelText('Minimum salary')).toBeDisabled();

    fireEvent.change(panel().getByLabelText('Salary currency'), { target: { value: 'INR' } });
    await waitFor(() => expect(panel().getByLabelText('Minimum salary')).toBeEnabled());

    commitText(panel().getByLabelText('Minimum salary'), '1000000');

    await waitFor(() =>
      expect(lastFilters()).toMatchObject({ currency: 'INR', salaryMin: '1000000' }),
    );
  });

  it('combines filters from the URL into one request', async () => {
    renderAt('/jobs?category=Backend+Developer&skill=Java&location=Bengaluru&experience=2-5');

    await waitFor(() =>
      expect(lastFilters()).toEqual({
        category: 'Backend Developer',
        skill: 'Java',
        location: 'Bengaluru',
        experience: '2-5',
      }),
    );
  });

  // ------------------------------------------------------------ URL state

  it('restores every control from a shared URL', async () => {
    renderAt('/jobs?q=java&skill=Spring+Boot&experience=2-5&order=title&size=50&page=2');
    await screen.findByText('Java Developer');

    expect(screen.getByLabelText('Search jobs')).toHaveValue('java');
    expect(panel().getByLabelText('Skill')).toHaveValue('Spring Boot');
    expect(panel().getByLabelText('Experience')).toHaveValue('2-5');
    expect(screen.getByLabelText('Sort by')).toHaveValue('title');
    // One-based in the URL, zero-based to the API.
    expect(jobs).toHaveBeenLastCalledWith(
      expect.objectContaining({ q: 'java', skill: 'Spring Boot' }), 1, 50, undefined, 'title',
    );
  });

  it('shows each applied filter as a labelled, removable chip', async () => {
    renderAt('/jobs?skill=Java&location=Bengaluru');
    await screen.findByText('Java Developer');

    const chips = within(screen.getByLabelText('Active filters'));
    expect(chips.getByText('Skill:')).toBeInTheDocument();
    expect(chips.getByText('Location:')).toBeInTheDocument();

    fireEvent.click(chips.getByRole('button', { name: 'Remove filter Skill: Java' }));

    await waitFor(() => expect(currentSearch()).toBe('?location=Bengaluru'));
  });

  it('clears every filter at once', async () => {
    renderAt('/jobs?skill=Java&location=Bengaluru&order=title');
    await screen.findByText('Java Developer');

    fireEvent.click(screen.getByRole('button', { name: 'Clear all filters' }));

    await waitFor(() => expect(currentSearch()).toBe(''));
    expect(screen.queryByLabelText('Active filters')).not.toBeInTheDocument();
  });

  // ------------------------------------------------------------ ordering

  it('sorts, and sends the named order to the API', async () => {
    renderAt();
    await screen.findByText('Java Developer');

    fireEvent.change(screen.getByLabelText('Sort by'), { target: { value: 'company' } });

    await waitFor(() => expect(currentSearch()).toBe('?order=company'));
    expect(jobs.mock.calls.at(-1)?.[4]).toBe('company');
  });

  it('disables orderings whose precondition is missing, and says why', async () => {
    renderAt();
    await screen.findByText('Java Developer');

    const sort = within(screen.getByLabelText('Sort by'));
    expect(sort.getByRole('option', { name: /Relevance — enter search text/ })).toBeDisabled();
    expect(sort.getByRole('option', { name: /Salary — high to low — choose a salary currency/ })).toBeDisabled();
    expect(sort.getByRole('option', { name: 'Newest' })).toBeEnabled();
  });

  // ------------------------------------------------------------ paging

  it('pages through results via the URL', async () => {
    jobs.mockResolvedValue(
      page([job(1, 'Java Developer')], { totalElements: 45, totalPages: 3, last: false }),
    );
    renderAt();
    await screen.findByText('Java Developer');

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    await waitFor(() => expect(currentSearch()).toBe('?page=2'));
    expect(jobs.mock.calls.at(-1)?.[1]).toBe(1);
  });

  it('changes the page size and goes back to page one', async () => {
    jobs.mockResolvedValue(
      page([job(1, 'Java Developer')], { totalElements: 45, totalPages: 3, page: 1, last: false, first: false }),
    );
    renderAt('/jobs?page=2');
    await screen.findByText('Java Developer');

    fireEvent.change(screen.getByLabelText('Per page'), { target: { value: '50' } });

    await waitFor(() => expect(currentSearch()).toBe('?size=50'));
  });

  it('summarises the results from what the server returned', async () => {
    jobs.mockResolvedValue(
      page([job(1, 'Java Developer')], { totalElements: 342, totalPages: 18, page: 1, first: false, last: false }),
    );
    renderAt('/jobs?page=2');

    expect(await screen.findByText('Showing 21–21 of 342 jobs')).toBeInTheDocument();
  });

  // --------------------------------------------------------------- states

  it('shows skeleton cards on the first load, not an empty state', async () => {
    jobs.mockReturnValue(new Promise(() => {}));
    renderAt();

    expect(await screen.findByLabelText('Loading jobs')).toBeInTheDocument();
    expect(screen.queryByText(/No jobs found/)).not.toBeInTheDocument();
  });

  it('explains an empty result and offers to clear the filters', async () => {
    jobs.mockResolvedValue(page([]));
    renderAt('/jobs?skill=Cobol');

    expect(await screen.findByText('No jobs found matching your filters')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }));

    await waitFor(() => expect(currentSearch()).toBe(''));
  });

  it('shows a failed search with its message and a working retry', async () => {
    jobs.mockRejectedValueOnce(new ApiError(0, 'Cannot reach the API at http://localhost:8080.'));
    renderAt();

    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the API');
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('Java Developer')).toBeInTheDocument();
  });

  it('keeps the other filters usable when an option list fails to load', async () => {
    jobCategories.mockRejectedValue(new ApiError(500, 'boom'));
    renderAt();
    await screen.findByText('Java Developer');

    expect(await panel().findByText(/Categories could not be loaded/)).toBeInTheDocument();
    expect(panel().getByLabelText('Skill')).toBeEnabled();
  });

  // ----------------------------------------------------------- navigation

  it('links each result to its details with the search attached, and to resume matching', async () => {
    renderAt('/jobs?skill=Java');
    await screen.findByText('Java Developer');

    const card = screen.getByRole('article', { name: 'Java Developer' });
    expect(within(card).getByRole('link', { name: 'View details' })).toHaveAttribute('href', '/jobs/1');
    expect(within(card).getByRole('link', { name: /Compare resume/ })).toHaveAttribute(
      'href',
      '/resume?jobId=1',
    );
  });

  // --------------------------------------------------------------- drawer

  it('edits a draft in the mobile drawer and searches only on Apply', async () => {
    renderAt();
    await screen.findByText('Java Developer');
    const callsBefore = jobs.mock.calls.length;

    fireEvent.click(screen.getByRole('button', { name: /^Filters/ }));
    const drawer = within(await screen.findByRole('dialog', { name: 'Filters' }));

    fireEvent.change(drawer.getByLabelText('Experience'), { target: { value: '5-8' } });
    fireEvent.change(drawer.getByLabelText('Skill'), { target: { value: 'Java' } });

    // A draft: nothing has been searched and the URL has not moved.
    expect(currentSearch()).toBe('');
    expect(jobs.mock.calls.length).toBe(callsBefore);

    fireEvent.click(drawer.getByRole('button', { name: 'Apply filters' }));

    await waitFor(() => expect(currentSearch()).toBe('?skill=Java&experience=5-8'));
    expect(lastFilters()).toMatchObject({ skill: 'Java', experience: '5-8' });
  });

  it('clears the drawer draft without searching until Apply', async () => {
    renderAt('/jobs?skill=Java');
    await screen.findByText('Java Developer');

    fireEvent.click(screen.getByRole('button', { name: /^Filters/ }));
    const drawer = within(await screen.findByRole('dialog', { name: 'Filters' }));
    fireEvent.click(drawer.getByRole('button', { name: 'Clear all' }));

    expect(currentSearch()).toBe('?skill=Java');
    fireEvent.click(drawer.getByRole('button', { name: 'Apply filters' }));

    await waitFor(() => expect(currentSearch()).toBe(''));
  });

  it('closes the drawer without applying the draft', async () => {
    renderAt();
    await screen.findByText('Java Developer');

    fireEvent.click(screen.getByRole('button', { name: /^Filters/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Filters' });
    fireEvent.change(within(dialog).getByLabelText('Experience'), { target: { value: '8+' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close filters' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(currentSearch()).toBe('');
  });

  it('shows how many filters are active on the drawer button', async () => {
    renderAt('/jobs?skill=Java&location=Bengaluru');
    await screen.findByText('Java Developer');

    expect(screen.getByRole('button', { name: /^Filters/ })).toHaveTextContent('2');
  });
});
