import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, api } from '../api/client';
import type { ApplicationStatus, SavedJob } from '../api/types';
import { formatLocation } from '../components/format';
import { Badge, Card, EmptyState, ErrorState, PageHeader, SkeletonTable } from '../components/ui';
import { useSavedJobs } from '../saved/SavedJobs';

export const PIPELINE: ApplicationStatus[] = ['SAVED', 'APPLIED', 'INTERVIEW', 'OFFER'];
export const CLOSED: ApplicationStatus[] = ['REJECTED', 'WITHDRAWN'];

export const STATUS_LABEL: Record<ApplicationStatus, string> = {
  SAVED: 'Saved',
  APPLIED: 'Applied',
  INTERVIEW: 'Interview',
  OFFER: 'Offer',
  REJECTED: 'Rejected',
  WITHDRAWN: 'Withdrawn',
};

const STATUS_TONE: Record<ApplicationStatus, 'neutral' | 'brand' | 'success' | 'warning' | 'danger'> = {
  SAVED: 'neutral',
  APPLIED: 'brand',
  INTERVIEW: 'warning',
  OFFER: 'success',
  REJECTED: 'danger',
  WITHDRAWN: 'neutral',
};

type Filter = 'ALL' | 'CLOSED' | ApplicationStatus;

function formatDay(iso?: string | null): string {
  return iso ? new Date(iso).toLocaleDateString(undefined, { dateStyle: 'medium' }) : '';
}

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

function matches(saved: SavedJob, filter: Filter): boolean {
  if (filter === 'ALL') {
    return true;
  }
  return filter === 'CLOSED' ? CLOSED.includes(saved.status) : saved.status === filter;
}

/**
 * V7.2: saved jobs and where each application stands. Status and notes are the user's own;
 * the backend keeps them private to the account.
 */
export function SavedJobs() {
  const saved = useSavedJobs();
  const [filter, setFilter] = useState<Filter>('ALL');

  useEffect(() => {
    saved?.ensureLoaded();
  }, [saved]);

  if (!saved) {
    return null;
  }

  const items = saved.items;
  const count = (status: ApplicationStatus) => (items ?? []).filter((item) => item.status === status).length;
  const shown = (items ?? []).filter((item) => matches(item, filter));
  const toggleFilter = (next: Filter) => setFilter((current) => (current === next ? 'ALL' : next));

  return (
    <>
      <PageHeader
        title="Saved Jobs"
        description="Jobs you bookmarked, and where each application stands. Your status and notes are visible only to you."
      />

      <Card title="Application pipeline" description="Select a stage to show only those jobs.">
        <div className="pipeline" role="group" aria-label="Application pipeline">
          {PIPELINE.map((status, index) => (
            <div key={status} className="pipeline-step">
              {index > 0 && (
                <span className="pipeline-arrow" aria-hidden="true">
                  →
                </span>
              )}
              <button
                type="button"
                className={`pipeline-stage${filter === status ? ' is-active' : ''}`}
                aria-pressed={filter === status}
                onClick={() => toggleFilter(status)}
              >
                <span className="pipeline-count tabular">{count(status)}</span>
                <span>{STATUS_LABEL[status]}</span>
              </button>
            </div>
          ))}
        </div>
        <div className="pipeline-closed">
          <button
            type="button"
            className={`pipeline-stage closed${filter === 'CLOSED' ? ' is-active' : ''}`}
            aria-pressed={filter === 'CLOSED'}
            onClick={() => toggleFilter('CLOSED')}
          >
            Closed: {count('REJECTED')} rejected · {count('WITHDRAWN')} withdrawn
          </button>
        </div>
      </Card>

      <Card
        title="Your saved jobs"
        description="Most recently updated first."
        actions={
          <label className="field saved-filter">
            <span className="visually-hidden">Filter by status</span>
            <select value={filter} onChange={(event) => setFilter(event.target.value as Filter)} aria-label="Filter by status">
              <option value="ALL">All statuses</option>
              {[...PIPELINE, ...CLOSED].map((status) => (
                <option key={status} value={status}>
                  {STATUS_LABEL[status]}
                </option>
              ))}
              <option value="CLOSED">Closed (rejected or withdrawn)</option>
            </select>
          </label>
        }
      >
        {saved.error ? (
          <ErrorState message={saved.error} onRetry={saved.reload} />
        ) : items === null ? (
          <SkeletonTable rows={3} columns={3} />
        ) : items.length === 0 ? (
          <EmptyState
            title="No saved jobs yet"
            message="Use Save on any job in the Job Explorer to keep track of it here."
            action={
              <Link className="button-link primary" to="/jobs">
                Browse jobs
              </Link>
            }
          />
        ) : shown.length === 0 ? (
          <EmptyState title="Nothing at this stage" message="Choose another status, or show all." />
        ) : (
          <ul className="saved-list">
            {shown.map((item) => (
              <li key={item.id}>
                <SavedJobCard item={item} />
              </li>
            ))}
          </ul>
        )}
      </Card>
    </>
  );
}

function SavedJobCard({ item }: { item: SavedJob }) {
  const saved = useSavedJobs()!;
  const [notes, setNotes] = useState(item.notes ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const notesChanged = notes.trim() !== (item.notes ?? '');

  const run = async (action: () => Promise<void>, done?: string) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await action();
      if (done) {
        setNotice(done);
      }
    } catch (failure) {
      setError(messageOf(failure));
    } finally {
      setBusy(false);
    }
  };

  const changeStatus = (status: ApplicationStatus) =>
    run(async () => saved.replace(await api.setSavedJobStatus(item.id, status)));

  const saveNotes = () =>
    run(async () => {
      const updated = await api.setSavedJobNotes(item.id, notes);
      saved.replace(updated);
      setNotes(updated.notes ?? '');
    }, 'Notes saved');

  const remove = () => {
    if (window.confirm(`Remove “${item.job.title}” from your saved jobs?`)) {
      void run(() => saved.remove(item.id));
    }
  };

  return (
    <article className="saved-card" aria-labelledby={`saved-${item.id}-title`}>
      <div className="saved-card-main">
        <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
          <h3 id={`saved-${item.id}-title`} className="saved-card-title">
            <Link to={`/jobs/${item.job.id}`}>{item.job.title}</Link>
          </h3>
          <Badge tone={STATUS_TONE[item.status]}>{STATUS_LABEL[item.status]}</Badge>
        </div>
        <p className="muted small" style={{ margin: '2px 0 0' }}>
          {item.job.company.name} · {formatLocation(item.job)}
        </p>
        <p className="muted small" style={{ margin: '2px 0 0' }}>
          Saved {formatDay(item.savedAt)}
          {item.appliedAt ? ` · Applied ${formatDay(item.appliedAt)}` : ''}
        </p>
      </div>

      <div className="saved-card-controls">
        <label className="field">
          Status
          <select value={item.status} disabled={busy} onChange={(event) => changeStatus(event.target.value as ApplicationStatus)}>
            {[...PIPELINE, ...CLOSED].map((status) => (
              <option key={status} value={status}>
                {STATUS_LABEL[status]}
              </option>
            ))}
          </select>
        </label>
        <button type="button" className="small ghost" disabled={busy} onClick={remove}>
          Remove
        </button>
      </div>

      <div className="saved-card-notes">
        <label className="field">
          Notes
          <textarea
            value={notes}
            maxLength={2000}
            rows={2}
            placeholder="Contacts, dates, follow-ups…"
            onChange={(event) => setNotes(event.target.value)}
          />
        </label>
        <div className="row" style={{ gap: 8 }}>
          <button type="button" className="small" disabled={busy || !notesChanged} onClick={saveNotes}>
            Save notes
          </button>
          {notice && <span className="muted small">{notice}</span>}
          {error && (
            <span className="status status-error small" role="alert">
              {error}
            </span>
          )}
        </div>
      </div>
    </article>
  );
}
