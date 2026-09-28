import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { ApiError, api } from '../api/client';
import type { SavedJob } from '../api/types';
import { IconBookmark } from '../components/icons';

/**
 * V7.2: the signed-in user's saved jobs, loaded once and shared by every bookmark button and
 * the Saved Jobs page, so a job saved from the results shows as saved everywhere at once.
 * Loaded on first use, not on mount, so pages that never show a bookmark cost nothing.
 */
interface SavedJobsValue {
  items: SavedJob[] | null;
  error: string | null;
  ensureLoaded: () => void;
  reload: () => void;
  savedFor: (jobId: number) => SavedJob | undefined;
  save: (jobId: number) => Promise<void>;
  unsave: (jobId: number) => Promise<void>;
  replace: (saved: SavedJob) => void;
  remove: (id: string) => Promise<void>;
}

const SavedJobsContext = createContext<SavedJobsValue | null>(null);

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

export function SavedJobsProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<SavedJob[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const requested = useRef(false);

  const load = useCallback(() => {
    requested.current = true;
    setError(null);
    api
      .savedJobs()
      .then(setItems)
      .catch((failure: unknown) => setError(messageOf(failure)));
  }, []);

  const ensureLoaded = useCallback(() => {
    if (!requested.current) {
      load();
    }
  }, [load]);

  const value = useMemo<SavedJobsValue>(
    () => ({
      items,
      error,
      ensureLoaded,
      reload: load,
      savedFor: (jobId) => items?.find((saved) => saved.job.id === jobId),
      save: async (jobId) => {
        const saved = await api.saveJob(jobId);
        setItems((list) => [saved, ...(list ?? []).filter((item) => item.id !== saved.id)]);
      },
      unsave: async (jobId) => {
        await api.unsaveJob(jobId);
        setItems((list) => (list ?? []).filter((item) => item.job.id !== jobId));
      },
      replace: (saved) => setItems((list) => (list ?? []).map((item) => (item.id === saved.id ? saved : item))),
      remove: async (id) => {
        await api.deleteSavedJob(id);
        setItems((list) => (list ?? []).filter((item) => item.id !== id));
      },
    }),
    [items, error, ensureLoaded, load],
  );

  return <SavedJobsContext.Provider value={value}>{children}</SavedJobsContext.Provider>;
}

/** The shared saved-jobs state, or null outside the signed-in shell. */
export function useSavedJobs(): SavedJobsValue | null {
  return useContext(SavedJobsContext);
}

/**
 * Bookmark toggle for one job. Renders nothing outside the provider, so pages and tests that
 * do not set one up are unaffected.
 */
export function SaveJobButton({ jobId, size = 'normal' }: { jobId: number; size?: 'normal' | 'small' }) {
  const saved = useSavedJobs();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    saved?.ensureLoaded();
  }, [saved]);

  if (!saved) {
    return null;
  }

  const record = saved.savedFor(jobId);
  const isSaved = Boolean(record);
  const loading = saved.items === null && !saved.error;

  const toggle = async () => {
    setBusy(true);
    setError(null);
    try {
      await (isSaved ? saved.unsave(jobId) : saved.save(jobId));
    } catch (failure) {
      setError(messageOf(failure));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <button
        type="button"
        className={`save-job-button${isSaved ? ' is-saved' : ''}${size === 'small' ? ' small' : ''}`}
        aria-pressed={isSaved}
        disabled={busy || loading}
        onClick={toggle}
        title={isSaved ? 'Remove from saved jobs' : 'Save this job'}
      >
        <IconBookmark size={15} filled={isSaved} />
        {isSaved ? 'Saved' : 'Save'}
      </button>
      {error && (
        <span className="status status-error small" role="alert">
          {error}
        </span>
      )}
    </>
  );
}
