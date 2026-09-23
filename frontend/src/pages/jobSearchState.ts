import type { JobFilters, JobOrder } from '../api/types';

/**
 * Job search state, and the one place it is translated to and from the URL.
 *
 * <p>The URL is the source of truth. There is no copy in component state or a store:
 * filters are read from the query string on every render and written back to it on every
 * change. That is what makes a search survive a refresh, work with the back button, and
 * be shareable as a link — all for free, because those are things URLs already do.
 *
 * <p>Everything here is a pure function, so the rules below are tested directly rather
 * than through a rendered page.
 */

export const PAGE_SIZES = [10, 20, 50] as const;
export type PageSize = (typeof PAGE_SIZES)[number];
export const DEFAULT_PAGE_SIZE: PageSize = 20;
export const DEFAULT_ORDER: JobOrder = 'newest';

/** The filters a URL can carry, in the order they are written to it. */
export const FILTER_KEYS = [
  'q',
  'category',
  'skill',
  'location',
  'company',
  'employmentType',
  'experience',
  'currency',
  'salaryMin',
  'salaryMax',
  'locationStated',
  // Kept so links written before V6.2 still open the search they described.
  'title',
] as const satisfies readonly (keyof JobFilters)[];

export type FilterKey = (typeof FILTER_KEYS)[number];

export interface SearchState {
  filters: JobFilters;
  order: JobOrder;
  /** One-based, as it appears in the URL. The API is zero-based. */
  page: number;
  size: PageSize;
}

export const EXPERIENCE_OPTIONS = [
  { value: '0-2', label: '0–2 years' },
  { value: '2-5', label: '2–5 years' },
  { value: '5-8', label: '5–8 years' },
  { value: '8+', label: '8+ years' },
  { value: 'unspecified', label: 'Not specified' },
] as const;

export const EMPLOYMENT_TYPES = [
  'FULL_TIME',
  'PART_TIME',
  'CONTRACT',
  'INTERNSHIP',
  'TEMPORARY',
  'FREELANCE',
] as const;

export const ORDER_OPTIONS: { value: JobOrder; label: string }[] = [
  { value: 'newest', label: 'Newest' },
  { value: 'oldest', label: 'Oldest' },
  { value: 'relevance', label: 'Relevance' },
  { value: 'salary-high', label: 'Salary — high to low' },
  { value: 'salary-low', label: 'Salary — low to high' },
  { value: 'title', label: 'Job title A–Z' },
  { value: 'company', label: 'Company A–Z' },
];

const ORDER_VALUES = new Set<string>(ORDER_OPTIONS.map((option) => option.value));

/**
 * Why an ordering cannot be chosen right now, or null if it can.
 *
 * <p>Relevance needs something to be relevant to, and salary needs a single currency to
 * rank within. Offering either without its precondition would produce an order that looks
 * like an answer and is not one.
 */
export function orderUnavailableReason(order: JobOrder, filters: JobFilters): string | null {
  if (order === 'relevance' && !filters.q) {
    return 'Enter search text to rank by relevance';
  }
  if ((order === 'salary-high' || order === 'salary-low') && !filters.currency) {
    return 'Choose a salary currency to sort by pay';
  }
  return null;
}

/** Reads the state out of a URL, ignoring anything it does not recognise. */
export function parseSearch(params: URLSearchParams): SearchState {
  const filters: JobFilters = {};
  for (const key of FILTER_KEYS) {
    const value = params.get(key)?.trim();
    if (value) {
      filters[key] = value;
    }
  }

  const rawOrder = params.get('order') ?? '';
  const order = ORDER_VALUES.has(rawOrder) ? (rawOrder as JobOrder) : DEFAULT_ORDER;

  const rawSize = Number(params.get('size'));
  const size = (PAGE_SIZES as readonly number[]).includes(rawSize)
    ? (rawSize as PageSize)
    : DEFAULT_PAGE_SIZE;

  const rawPage = Number(params.get('page'));
  const page = Number.isInteger(rawPage) && rawPage >= 1 ? rawPage : 1;

  // A hand-edited URL can ask for salary order with no currency; the API would refuse it,
  // so it is quietly corrected here rather than shown as an error the reader did not cause.
  return normalise({ filters, order, page, size });
}

/**
 * Writes the state to a URL, leaving out anything at its default.
 *
 * <p>A URL that only carries what differs from the defaults is short enough to read and
 * share — "/jobs?q=java&amp;location=Bengaluru", not a dozen empty parameters.
 */
export function toSearchParams(state: SearchState): URLSearchParams {
  const params = new URLSearchParams();
  for (const key of FILTER_KEYS) {
    const value = state.filters[key]?.trim();
    if (value) {
      params.set(key, value);
    }
  }
  if (state.order !== DEFAULT_ORDER) {
    params.set('order', state.order);
  }
  if (state.size !== DEFAULT_PAGE_SIZE) {
    params.set('size', String(state.size));
  }
  if (state.page > 1) {
    params.set('page', String(state.page));
  }
  return params;
}

/**
 * Repairs combinations that have no valid answer.
 *
 * <ul>
 *   <li>An ordering that cannot apply — relevance with no search text, salary with no
 *       currency — falls back to newest. Keeping it would leave the sort control naming
 *       one order while the results arrive in another.</li>
 *   <li>Salary bounds without a currency are dropped — the API refuses them, since a bare
 *       amount would compare rupees with dollars.</li>
 * </ul>
 */
export function normalise(state: SearchState): SearchState {
  const filters = { ...state.filters };
  if (!filters.currency) {
    delete filters.salaryMin;
    delete filters.salaryMax;
  }
  const order = orderUnavailableReason(state.order, filters) ? DEFAULT_ORDER : state.order;
  return { ...state, filters, order };
}

/**
 * The next state after one filter changes.
 *
 * <p>Changing what is being searched for sends the reader back to page one: page four of
 * a narrower search may not exist, and landing on an empty page reads as "no results".
 */
export function withFilter(state: SearchState, key: FilterKey, value: string | undefined): SearchState {
  const filters = { ...state.filters };
  if (value === undefined || value.trim() === '') {
    delete filters[key];
  } else {
    filters[key] = value;
  }
  return normalise({ ...state, filters, page: 1 });
}

/** Everything back to defaults except the page size, which is a reading preference. */
export function cleared(state: SearchState): SearchState {
  return { filters: {}, order: DEFAULT_ORDER, page: 1, size: state.size };
}

export function hasAnyFilter(filters: JobFilters): boolean {
  return FILTER_KEYS.some((key) => Boolean(filters[key]));
}

/** What the API is sent: the filters, with salary bounds only when they can apply. */
export function toApiFilters(filters: JobFilters): JobFilters {
  return normalise({ filters, order: DEFAULT_ORDER, page: 1, size: DEFAULT_PAGE_SIZE }).filters;
}

// ---------------------------------------------------------------- active filters

export interface ActiveFilter {
  key: FilterKey;
  /** What kind of filter, spoken before the value so a chip is never ambiguous. */
  label: string;
  value: string;
}

const LABELS: Record<FilterKey, string> = {
  q: 'Search',
  category: 'Category',
  skill: 'Skill',
  location: 'Location',
  company: 'Company',
  employmentType: 'Type',
  experience: 'Experience',
  currency: 'Currency',
  salaryMin: 'Min salary',
  salaryMax: 'Max salary',
  locationStated: 'Location',
  title: 'Title',
};

/**
 * The applied filters as removable chips, each saying what kind of filter it is.
 *
 * <p>"Java" alone could be a skill, a search or a title word; "Skill: Java" cannot. The
 * label is text, so an active filter is identified by words and never by colour alone.
 */
export function activeFilters(filters: JobFilters): ActiveFilter[] {
  return FILTER_KEYS.filter((key) => filters[key]).map((key) => ({
    key,
    label: LABELS[key],
    value: displayValue(key, filters[key] as string, filters),
  }));
}

function displayValue(key: FilterKey, value: string, filters: JobFilters): string {
  switch (key) {
    case 'experience':
      return EXPERIENCE_OPTIONS.find((option) => option.value === value)?.label ?? value;
    case 'employmentType':
      return formatEmploymentType(value);
    case 'locationStated':
      return value === 'false' ? 'None stated' : 'Stated';
    case 'salaryMin':
    case 'salaryMax':
      return `${filters.currency ?? ''} ${Number(value).toLocaleString('en-US')}`.trim();
    default:
      return value;
  }
}

export function formatEmploymentType(value: string): string {
  return value
    .split('_')
    .map((word) => word.charAt(0) + word.slice(1).toLowerCase())
    .join(' ');
}

/** "Showing 21–40 of 342 jobs", computed from what the server said, never estimated. */
export function resultSummary(page: number, size: number, shown: number, total: number): string {
  if (total === 0) {
    return 'No jobs found';
  }
  const first = (page - 1) * size + 1;
  const last = first + shown - 1;
  const noun = total === 1 ? 'job' : 'jobs';
  return total <= size && page === 1
    ? `${total.toLocaleString('en-US')} ${noun} found`
    : `Showing ${first.toLocaleString('en-US')}–${last.toLocaleString('en-US')} of ${total.toLocaleString('en-US')} ${noun}`;
}
