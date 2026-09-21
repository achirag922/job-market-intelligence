import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AsyncPanel } from './AsyncPanel';
import type { AsyncState } from '../hooks/useApi';

/**
 * The one place loading, empty and error are decided.
 *
 * <p>Every page routes its API state through this, so these four outcomes are the whole
 * application's behaviour, not one component's. The case worth guarding hardest is the
 * last: an error must never render as an empty list, because a reader cannot tell the
 * difference between "nothing matched" and "the request failed".
 */
describe('AsyncPanel', () => {
  afterEach(cleanup);

  const loaded = <T,>(data: T): AsyncState<T> => ({ data, loading: false, error: null });

  it('shows a skeleton shaped like the content while loading', () => {
    const { container } = render(
      <AsyncPanel state={{ data: null, loading: true, error: null }} skeleton="table">
        {() => <p>loaded</p>}
      </AsyncPanel>,
    );

    expect(container.querySelectorAll('.skeleton').length).toBeGreaterThan(0);
    expect(screen.queryByText('loaded')).not.toBeInTheDocument();
  });

  it('announces an error and never renders the children', () => {
    render(
      <AsyncPanel state={{ data: null, loading: false, error: 'Cannot reach the API.' }}>
        {() => <p>loaded</p>}
      </AsyncPanel>,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('Cannot reach the API.');
    expect(screen.queryByText('loaded')).not.toBeInTheDocument();
  });

  it('offers a retry when one is given, and calls it', () => {
    const onRetry = vi.fn();
    render(
      <AsyncPanel state={{ data: null, loading: false, error: 'Timed out.' }} onRetry={onRetry}>
        {() => <p>loaded</p>}
      </AsyncPanel>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('explains an empty result rather than rendering a blank panel', () => {
    render(
      <AsyncPanel
        state={loaded<string[]>([])}
        isEmpty={(rows) => rows.length === 0}
        emptyTitle="No jobs found"
        empty="No postings match these filters."
      >
        {() => <p>loaded</p>}
      </AsyncPanel>,
    );

    expect(screen.getByText('No jobs found')).toBeInTheDocument();
    expect(screen.getByText('No postings match these filters.')).toBeInTheDocument();
  });

  it('renders the children once data has arrived', () => {
    render(
      <AsyncPanel state={loaded(['a'])} isEmpty={(rows) => rows.length === 0}>
        {(rows) => <p>{rows.length} row</p>}
      </AsyncPanel>,
    );

    expect(screen.getByText('1 row')).toBeInTheDocument();
  });

  it('treats a null payload as nothing to show, not as success', () => {
    render(<AsyncPanel state={{ data: null, loading: false, error: null }}>{() => <p>loaded</p>}</AsyncPanel>);

    expect(screen.queryByText('loaded')).not.toBeInTheDocument();
    expect(screen.getByText(/No data was returned/)).toBeInTheDocument();
  });
});
