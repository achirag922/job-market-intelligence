import { describe, expect, it } from 'vitest';
import { pageWindow } from '../components/pageWindow';
import {
  DEFAULT_ORDER,
  DEFAULT_PAGE_SIZE,
  activeFilters,
  cleared,
  hasAnyFilter,
  normalise,
  orderUnavailableReason,
  parseSearch,
  resultSummary,
  toApiFilters,
  toSearchParams,
  withFilter,
} from './jobSearchState';
import type { SearchState } from './jobSearchState';

/**
 * The URL is the only copy of a search, so these rules are what make a search shareable,
 * refreshable and back-button safe. They are pure functions, tested directly.
 */

const base: SearchState = { filters: {}, order: DEFAULT_ORDER, page: 1, size: DEFAULT_PAGE_SIZE };

const parse = (query: string) => parseSearch(new URLSearchParams(query));

describe('parseSearch / toSearchParams', () => {
  it('round-trips a full search through the URL unchanged', () => {
    const query =
      'q=java+spring&category=Backend+Developer&skill=Spring+Boot&location=Bengaluru'
      + '&experience=2-5&currency=INR&salaryMin=1000000&order=salary-high&size=50&page=3';

    const state = parse(query);

    expect(state.filters).toMatchObject({
      q: 'java spring',
      category: 'Backend Developer',
      skill: 'Spring Boot',
      location: 'Bengaluru',
      experience: '2-5',
      currency: 'INR',
      salaryMin: '1000000',
    });
    expect(state.order).toBe('salary-high');
    expect(state.size).toBe(50);
    expect(state.page).toBe(3);
    expect(parse(toSearchParams(state).toString())).toEqual(state);
  });

  it('leaves defaults out of the URL, so a plain search stays short', () => {
    const params = toSearchParams({ ...base, filters: { q: 'java' } });

    expect(params.toString()).toBe('q=java');
  });

  it('encodes 8+ so a URL does not read the plus as a space', () => {
    const params = toSearchParams({ ...base, filters: { experience: '8+' } });

    expect(params.toString()).toBe('experience=8%2B');
    expect(parse(params.toString()).filters.experience).toBe('8+');
  });

  it('falls back to defaults for values it does not recognise', () => {
    const state = parse('order=popularity&size=999&page=-4');

    expect(state.order).toBe(DEFAULT_ORDER);
    expect(state.size).toBe(DEFAULT_PAGE_SIZE);
    expect(state.page).toBe(1);
  });

  it('ignores parameters that are not search filters', () => {
    expect(parse('token=secret&debug=1').filters).toEqual({});
  });

  it('still reads the pre-V6.2 title parameter, so old links keep working', () => {
    expect(parse('title=engineer').filters.title).toBe('engineer');
  });

  it('keeps the parameter names other pages already link with', () => {
    // Company, location, skill and category analytics all deep-link into /jobs.
    const state = parse('company=Acme&location=India&skill=Java&category=Data+Engineer');

    expect(state.filters).toEqual({
      company: 'Acme',
      location: 'India',
      skill: 'Java',
      category: 'Data Engineer',
    });
  });
});

describe('normalise', () => {
  it('drops salary bounds that have no currency to scope them', () => {
    const state = normalise({ ...base, filters: { salaryMin: '100000' } });

    expect(state.filters.salaryMin).toBeUndefined();
  });

  it('keeps salary bounds when a currency is set', () => {
    const state = normalise({ ...base, filters: { currency: 'USD', salaryMin: '100000' } });

    expect(state.filters.salaryMin).toBe('100000');
  });

  it('resets a salary ordering that has lost its currency', () => {
    expect(normalise({ ...base, order: 'salary-high' }).order).toBe(DEFAULT_ORDER);
  });

  it('resets relevance when there is no search text to be relevant to', () => {
    expect(normalise({ ...base, order: 'relevance' }).order).toBe(DEFAULT_ORDER);
    expect(normalise({ ...base, order: 'relevance', filters: { q: 'java' } }).order).toBe('relevance');
  });

  it('repairs a hand-edited URL on the way in', () => {
    expect(parse('order=salary-low').order).toBe(DEFAULT_ORDER);
  });
});

describe('withFilter', () => {
  it('sends the reader back to page one when the filters change', () => {
    const next = withFilter({ ...base, page: 4 }, 'skill', 'Java');

    expect(next.page).toBe(1);
    expect(next.filters.skill).toBe('Java');
  });

  it('removes a filter set to empty rather than searching for ""', () => {
    const next = withFilter({ ...base, filters: { skill: 'Java' } }, 'skill', '   ');

    expect(next.filters).not.toHaveProperty('skill');
  });

  it('removing the currency also removes the salary bounds and salary order', () => {
    const state: SearchState = {
      ...base,
      order: 'salary-high',
      filters: { currency: 'INR', salaryMin: '1000000', salaryMax: '2000000' },
    };

    const next = withFilter(state, 'currency', undefined);

    expect(next.filters).toEqual({});
    expect(next.order).toBe(DEFAULT_ORDER);
  });
});

describe('cleared', () => {
  it('resets every filter and the order but keeps the page size', () => {
    const next = cleared({
      filters: { q: 'java', skill: 'Java' },
      order: 'title',
      page: 3,
      size: 50,
    });

    expect(next).toEqual({ filters: {}, order: DEFAULT_ORDER, page: 1, size: 50 });
  });
});

describe('orderUnavailableReason', () => {
  it('explains why relevance and salary orders are unavailable', () => {
    expect(orderUnavailableReason('relevance', {})).toMatch(/search text/);
    expect(orderUnavailableReason('salary-high', {})).toMatch(/currency/);
    expect(orderUnavailableReason('salary-high', { currency: 'USD' })).toBeNull();
    expect(orderUnavailableReason('title', {})).toBeNull();
  });
});

describe('activeFilters', () => {
  it('labels every chip with its kind, so a value is never ambiguous', () => {
    const chips = activeFilters({ skill: 'Java', q: 'java', experience: '2-5' });

    expect(chips.map((chip) => `${chip.label}: ${chip.value}`)).toEqual([
      'Search: java',
      'Skill: Java',
      'Experience: 2–5 years',
    ]);
  });

  it('shows salary bounds with their currency', () => {
    const chips = activeFilters({ currency: 'INR', salaryMin: '1000000' });

    expect(chips.find((chip) => chip.key === 'salaryMin')?.value).toBe('INR 1,000,000');
  });

  it('describes the location-presence filter without calling it remote', () => {
    const chip = activeFilters({ locationStated: 'false' })[0];

    expect(chip.value).toBe('None stated');
    expect(chip.value.toLowerCase()).not.toContain('remote');
  });
});

describe('toApiFilters', () => {
  it('never sends salary bounds the API would refuse', () => {
    expect(toApiFilters({ salaryMin: '5' })).toEqual({});
  });
});

describe('hasAnyFilter', () => {
  it('is false for an empty search', () => {
    expect(hasAnyFilter({})).toBe(false);
    expect(hasAnyFilter({ q: 'java' })).toBe(true);
  });
});

describe('resultSummary', () => {
  it('states a single page plainly', () => {
    expect(resultSummary(1, 20, 7, 7)).toBe('7 jobs found');
    expect(resultSummary(1, 20, 1, 1)).toBe('1 job found');
  });

  it('gives the range on a later page', () => {
    expect(resultSummary(2, 20, 20, 342)).toBe('Showing 21–40 of 342 jobs');
  });

  it('handles a short last page', () => {
    expect(resultSummary(18, 20, 2, 342)).toBe('Showing 341–342 of 342 jobs');
  });

  it('says so when nothing matched', () => {
    expect(resultSummary(1, 20, 0, 0)).toBe('No jobs found');
  });
});

describe('pageWindow', () => {
  it('lists every page when there are few', () => {
    expect(pageWindow(0, 5)).toEqual([0, 1, 2, 3, 4]);
  });

  it('keeps first, last and the neighbours of the current page, with gaps', () => {
    expect(pageWindow(9, 20)).toEqual([0, null, 8, 9, 10, null, 19]);
  });

  it('does not show a gap marker between adjacent pages', () => {
    expect(pageWindow(1, 20)).toEqual([0, 1, 2, null, 19]);
  });
});
