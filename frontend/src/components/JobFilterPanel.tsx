import { useId, useState } from 'react';
import type { CategoryDemand, SalaryRange } from '../api/types';
import type { AsyncState } from '../hooks/useApi';
import { useSuggestions } from '../hooks/useSuggestions';
import type { SuggestionKind } from '../hooks/useSuggestions';
import {
  EMPLOYMENT_TYPES,
  EXPERIENCE_OPTIONS,
  formatEmploymentType,
  withFilter,
} from '../pages/jobSearchState';
import type { FilterKey, SearchState } from '../pages/jobSearchState';
import { DebouncedInput } from './DebouncedInput';

interface Props {
  state: SearchState;
  onChange: (next: SearchState) => void;
  categories: AsyncState<CategoryDemand[]>;
  currencies: AsyncState<SalaryRange[]>;
  /**
   * {@code live} commits text after a pause, for the desktop panel where every change
   * searches. {@code draft} commits every keystroke into a draft that an Apply button
   * sends, for the mobile drawer. Same controls, same state — only the timing differs.
   */
  mode?: 'live' | 'draft';
}

/**
 * Every job filter, as one set of controls.
 *
 * <p>Rendered in two places — inline on a wide screen, inside the drawer on a narrow one —
 * and deliberately the same component in both. Two implementations would drift, and a
 * filter that works on desktop but not on a phone is the usual result.
 *
 * <p>Option lists come from the data: categories from the classified postings, currencies
 * from the salaries actually stated. Nothing here is a hardcoded list that could offer a
 * value no posting carries. The two fixed vocabularies — employment types and experience
 * bands — are fixed in the schema and the analytics, not invented here.
 */
export function JobFilterPanel({ state, onChange, categories, currencies, mode = 'live' }: Props) {
  const delay = mode === 'draft' ? 0 : 400;
  const set = (key: FilterKey, value: string | undefined) => onChange(withFilter(state, key, value));
  const { filters } = state;
  const currencyRange = currencies.data?.find((row) => row.currency === filters.currency);

  return (
    <div className="job-filter-grid">
      <label className="field">
        Category
        {categories.error ? (
          <OptionError message="Categories could not be loaded." />
        ) : (
          <select
            value={filters.category ?? ''}
            disabled={categories.loading}
            onChange={(event) => set('category', event.target.value)}
          >
            <option value="">{categories.loading ? 'Loading…' : 'Any category'}</option>
            {(categories.data ?? []).map((option) => (
              <option key={option.category} value={option.category}>
                {option.category} ({option.jobCount})
              </option>
            ))}
            {/* A category from a shared link that is no longer in the data still shows,
                rather than the select silently reading "Any" while the filter applies. */}
            {filters.category &&
              categories.data &&
              !categories.data.some((option) => option.category === filters.category) && (
                <option value={filters.category}>{filters.category}</option>
              )}
          </select>
        )}
      </label>

      <SuggestField
        label="Skill"
        kind="skill"
        placeholder="e.g. Java"
        value={filters.skill ?? ''}
        delay={delay}
        onCommit={(value) => set('skill', value)}
      />

      <SuggestField
        label="Location"
        kind="location"
        placeholder="City, state or country"
        value={filters.location ?? ''}
        delay={delay}
        onCommit={(value) => set('location', value)}
      />

      <SuggestField
        label="Company"
        kind="company"
        placeholder="e.g. Acme"
        value={filters.company ?? ''}
        delay={delay}
        onCommit={(value) => set('company', value)}
      />

      <label className="field">
        Experience
        <select value={filters.experience ?? ''} onChange={(event) => set('experience', event.target.value)}>
          <option value="">Any experience</option>
          {EXPERIENCE_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </label>

      <label className="field">
        Employment type
        <select
          value={filters.employmentType ?? ''}
          onChange={(event) => set('employmentType', event.target.value)}
        >
          <option value="">Any type</option>
          {EMPLOYMENT_TYPES.map((type) => (
            <option key={type} value={type}>
              {formatEmploymentType(type)}
            </option>
          ))}
        </select>
      </label>

      {/* Currency first, because the amounts mean nothing without it. The dataset states
          salaries in several currencies with no exchange rates, so a bare "at least
          100,000" would compare rupees against dollars. */}
      <fieldset className="field salary-fieldset">
        <legend>Salary</legend>
        {currencies.error ? (
          <OptionError message="Salary currencies could not be loaded." />
        ) : (
          <div className="salary-inputs">
            <select
              aria-label="Salary currency"
              value={filters.currency ?? ''}
              disabled={currencies.loading}
              onChange={(event) => set('currency', event.target.value)}
            >
              <option value="">{currencies.loading ? 'Loading…' : 'Any currency'}</option>
              {(currencies.data ?? []).map((row) => (
                <option key={row.currency} value={row.currency}>
                  {row.currency} ({row.jobCount})
                </option>
              ))}
            </select>
            <DebouncedInput
              type="number"
              inputMode="numeric"
              min={0}
              aria-label="Minimum salary"
              placeholder={currencyRange ? `Min · ${compact(currencyRange.lowestMin)}` : 'Min'}
              disabled={!filters.currency}
              value={filters.salaryMin ?? ''}
              delay={delay}
              onCommit={(value) => set('salaryMin', value)}
            />
            <DebouncedInput
              type="number"
              inputMode="numeric"
              min={0}
              aria-label="Maximum salary"
              placeholder={
                currencyRange?.highestMax ? `Max · ${compact(currencyRange.highestMax)}` : 'Max'
              }
              disabled={!filters.currency}
              value={filters.salaryMax ?? ''}
              delay={delay}
              onCommit={(value) => set('salaryMax', value)}
            />
          </div>
        )}
        {!filters.currency && !currencies.error && (
          <span className="field-hint">Choose a currency to filter by pay.</span>
        )}
      </fieldset>

      {/* Not "remote": the dataset records remote postings and ones that simply did not say
          the same way, so the honest question is only whether a place is named. */}
      <label className="field">
        Stated location
        <select
          value={filters.locationStated ?? ''}
          onChange={(event) => set('locationStated', event.target.value)}
        >
          <option value="">Any</option>
          <option value="true">Names a place</option>
          <option value="false">No place stated (remote or unspecified)</option>
        </select>
      </label>
    </div>
  );
}

interface SuggestFieldProps {
  label: string;
  kind: SuggestionKind;
  placeholder: string;
  value: string;
  delay: number;
  onCommit: (value: string) => void;
}

/**
 * A text filter with suggestions from the data.
 *
 * <p>A native datalist rather than a custom listbox: the browser supplies the keyboard
 * handling, the screen-reader semantics and the mobile keyboard behaviour, all of which a
 * hand-rolled dropdown gets subtly wrong.
 */
function SuggestField({ label, kind, placeholder, value, delay, onCommit }: SuggestFieldProps) {
  // Starts empty, not at the current value: a filter restored from the URL is not someone
  // typing, and asking the server for suggestions for it on every page load is wasted.
  const [typing, setTyping] = useState('');
  const suggestions = useSuggestions(kind, typing);
  const listId = useId();

  return (
    <label className="field">
      {label}
      <DebouncedInput
        type="text"
        list={listId}
        autoComplete="off"
        placeholder={placeholder}
        value={value}
        delay={delay}
        onDraftChange={setTyping}
        onCommit={onCommit}
      />
      <datalist id={listId}>
        {suggestions.map((name) => (
          <option key={name} value={name} />
        ))}
      </datalist>
    </label>
  );
}

function OptionError({ message }: { message: string }) {
  return (
    <span className="field-error" role="status">
      {message} This filter is unavailable; the others still work.
    </span>
  );
}

/** 2,400,000 reads as 2.4M in a placeholder, where there is room for little. */
function compact(value: number): string {
  return new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(value);
}
