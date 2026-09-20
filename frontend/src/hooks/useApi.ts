import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';

export interface AsyncState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
}

/**
 * Runs an API call and tracks its loading and error state.
 *
 * <p>Responses from a superseded call are discarded. Without that, typing quickly in a
 * search box can land an older, slower response after a newer one and show the wrong
 * results.
 *
 * @param fetcher the call to run
 * @param deps    re-runs the call when these change, like useEffect
 */
export function useApi<T>(fetcher: () => Promise<T>, deps: unknown[]): AsyncState<T> {
  const [state, setState] = useState<AsyncState<T>>({ data: null, loading: true, error: null });

  useEffect(() => {
    let superseded = false;
    setState((previous) => ({ ...previous, loading: true, error: null }));

    fetcher()
      .then((data) => {
        if (!superseded) {
          setState({ data, loading: false, error: null });
        }
      })
      .catch((error: unknown) => {
        if (superseded) {
          return;
        }
        const message =
          error instanceof ApiError ? error.message : 'Unexpected error loading data';
        setState({ data: null, loading: false, error: message });
      });

    return () => {
      superseded = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return state;
}
