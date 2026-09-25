import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MarketCompanies, MarketLocations, MarketRemote, MarketSalary, MarketScope, MarketSkills } from '../api/types';
import { MarketIntelligence, monthLabel } from './MarketIntelligence';

const marketSalary = vi.fn();
const marketLocations = vi.fn();
const marketRemote = vi.fn();
const marketCompanies = vi.fn();
const marketSkills = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      jobCategories: () => Promise.resolve([{ category: 'Backend Developer', jobCount: 3 }]),
      marketSalary: (...args: unknown[]) => marketSalary(...args),
      marketLocations: (...args: unknown[]) => marketLocations(...args),
      marketRemote: (...args: unknown[]) => marketRemote(...args),
      marketCompanies: (...args: unknown[]) => marketCompanies(...args),
      marketSkills: (...args: unknown[]) => marketSkills(...args),
    },
  };
});

const scope: MarketScope = {
  postings: 5, datedPostings: 4, earliestMonth: '2026-07-01', latestMonth: '2026-09-01', latestPostingInData: '2026-09-12',
};

const salary: MarketSalary = {
  scope,
  postingsWithSalary: 4,
  byCurrency: [
    { currency: 'EUR', postings: 3, averageMin: 50000, averageMax: 66667, lowestMin: 40000, highestMax: 80000, reliable: true },
    { currency: 'USD', postings: 1, averageMin: 90000, averageMax: 120000, lowestMin: 90000, highestMax: 120000, reliable: false },
  ],
  byCategory: [{ currency: 'EUR', category: 'Backend Developer', postings: 2, averageMin: 55000, averageMax: 75000, reliable: false }],
  trend: [{ month: '2026-09-01', currency: 'EUR', postings: 1, averageMin: 50000, averageMax: 70000, reliable: false }],
  notes: ['Salaries are shown as stated, per currency. Currencies are never converted or combined.'],
};
const locations: MarketLocations = {
  scope, locationStated: 4, locationNotStated: 1,
  topLocations: [{ locationId: 1, location: 'Berlin, Germany', country: 'Germany', postings: 2, percentageOfPostings: 40 }],
  notes: [],
};
const remote: MarketRemote = {
  scope,
  distribution: [
    { mode: 'REMOTE', postings: 1, percentageOfPostings: 20 }, { mode: 'HYBRID', postings: 1, percentageOfPostings: 20 },
    { mode: 'ON_SITE', postings: 1, percentageOfPostings: 20 }, { mode: 'NOT_STATED', postings: 2, percentageOfPostings: 40 },
  ],
  trend: [{ month: '2026-09-01', total: 2, remote: 1, hybrid: 0, onSite: 1, notStated: 0 }],
  method: "Read from each posting's description.",
  notes: [],
};
const companies: MarketCompanies = {
  scope,
  topCompanies: [{ companyId: 2, company: 'Beta', postings: 3, percentageOfPostings: 60 }],
  trend: [{ companyId: 2, company: 'Beta', points: [{ month: '2026-09-01', postings: 1 }] }],
  notes: ["Counts are postings in JMIP's dataset per posting month, not a company's total hiring."],
};
const skills: MarketSkills = {
  scope,
  topSkills: [{ skillId: 1, skill: 'Java', postings: 3, percentageOfPostings: 60, rank: 1 }],
  trend: {
    window: { fromPeriod: '2026-07-01', toPeriod: '2026-09-01', periods: 3, earlierPeriods: [], recentPeriods: [], totalJobsInWindow: 4, minJobsThreshold: 3 },
    trends: [{ skillId: 1, skill: 'Java', jobCountInWindow: 3, earlierSharePercentage: 50, recentSharePercentage: 66.7,
      changeInPercentagePoints: 16.7, direction: 'RISING', series: [] }],
  } as MarketSkills['trend'],
  notes: [],
};

function card(title: string) {
  return screen.getByRole('heading', { name: title }).closest('.card') as HTMLElement;
}

describe('MarketIntelligence', () => {
  beforeEach(() => {
    marketSalary.mockResolvedValue(salary);
    marketLocations.mockResolvedValue(locations);
    marketRemote.mockResolvedValue(remote);
    marketCompanies.mockResolvedValue(companies);
    marketSkills.mockResolvedValue(skills);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('shows every section with its scope, figures and data notes', async () => {
    render(<MarketIntelligence />);

    const salaryCard = card('Salary');
    expect(await within(salaryCard).findByText('4 of 5 postings state a salary.')).toBeTruthy();
    expect(within(salaryCard).getByText(/posted Jul 2026 – Sep 2026 · newest posting in the data: 2026-09-12/)).toBeTruthy();
    const byCurrency = within(salaryCard).getByRole('table', { name: 'Salary by currency' });
    const usd = within(byCurrency).getByText('USD').closest('tr')!;
    expect(within(usd).getByText('Too few postings')).toBeTruthy();
    expect(within(within(byCurrency).getByText('EUR').closest('tr')!).getByText('50,000 – 66,667')).toBeTruthy();
    expect(within(salaryCard).getByText(/never converted or combined/, { selector: 'li' })).toBeTruthy();

    expect(within(card('Location demand')).getByText('No location stated')).toBeTruthy();
    const modes = within(card('Remote, hybrid and on-site')).getByLabelText('Work mode distribution');
    expect(within(modes).getByText('Not stated').closest('li')!.textContent).toContain('2 (40%)');
    expect(within(card('Remote, hybrid and on-site')).getByText(/description/)).toBeTruthy();
    expect(within(card('Hiring companies')).getByText(/not a company's total hiring/)).toBeTruthy();

    const skillCard = card('Skill demand');
    expect(within(skillCard).getByText('Stored skill history, Jul 2026 – Sep 2026')).toBeTruthy();
    const java = within(skillCard).getByText('Java', { selector: 'td' }).closest('tr')!;
    expect(within(java).getByText('+16.7')).toBeTruthy();
    expect(within(java).getByText('rising')).toBeTruthy();
  });

  it('sends the filters to every view', async () => {
    render(<MarketIntelligence />);
    await screen.findByText('4 of 5 postings state a salary.');
    await screen.findByRole('option', { name: 'Backend Developer' });

    const filters = screen.getByRole('group', { name: 'Market filters' });
    fireEvent.change(within(filters).getByLabelText('Job category'), { target: { value: 'Backend Developer' } });
    fireEvent.change(within(filters).getByLabelText('Experience'), { target: { value: '2-5' } });
    fireEvent.change(within(filters).getByLabelText('Period'), { target: { value: '6' } });

    await vi.waitFor(() =>
      expect(marketSkills).toHaveBeenLastCalledWith({ category: 'Backend Developer', experience: '2-5', months: 6 }),
    );
    for (const call of [marketSalary, marketLocations, marketRemote, marketCompanies]) {
      expect(call).toHaveBeenLastCalledWith({ category: 'Backend Developer', experience: '2-5', months: 6 });
    }
  });

  it('explains missing data instead of showing figures', async () => {
    marketSalary.mockResolvedValue({ ...salary, postingsWithSalary: 0, byCurrency: [], byCategory: [], trend: [],
      notes: ['No posting in this selection states a salary, so there are no salary figures.'] });
    marketSkills.mockResolvedValue({ ...skills, trend: undefined,
      notes: ['Skill trends come from the stored monthly skill history, which covers all postings.'] });
    render(<MarketIntelligence />);

    const salaryCard = card('Salary');
    expect(await within(salaryCard).findByText(/No posting in this selection states a salary/)).toBeTruthy();
    expect(within(salaryCard).queryByRole('table')).toBeNull();
    const skillCard = card('Skill demand');
    expect(await within(skillCard).findByText(/covers all postings/)).toBeTruthy();
    expect(within(skillCard).queryByText('Earlier vs recent posting months')).toBeNull();
  });
});

describe('monthLabel', () => {
  it('names a posting month', () => {
    expect(monthLabel('2026-09-01')).toBe('Sep 2026');
  });
});
