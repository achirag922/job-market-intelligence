import type { ReactNode } from 'react';
import type { AsyncState } from '../hooks/useApi';

interface Props<T> {
  state: AsyncState<T>;
  children: (data: T) => ReactNode;
  /** Shown when the call succeeded but returned nothing. */
  empty?: string;
  isEmpty?: (data: T) => boolean;
}

/**
 * Renders loading, error and empty states in one place, so every page handles a failed
 * API call the same way rather than each inventing its own.
 */
export function AsyncPanel<T>({ state, children, empty, isEmpty }: Props<T>) {
  if (state.loading) {
    return <p className="status">Loading…</p>;
  }
  if (state.error) {
    return (
      <p className="status status-error" role="alert">
        {state.error}
      </p>
    );
  }
  if (state.data === null) {
    return <p className="status">No data.</p>;
  }
  if (isEmpty?.(state.data)) {
    return <p className="status">{empty ?? 'Nothing to show.'}</p>;
  }
  return <>{children(state.data)}</>;
}
