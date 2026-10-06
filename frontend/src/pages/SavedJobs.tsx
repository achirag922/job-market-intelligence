import { useEffect, useState } from 'react';
import { useConfirm } from '../components/feedback';
import { Link } from 'react-router-dom';
import { ApiError, api } from '../api/client';
import type { ApplicationAnalysis, ApplicationStatus, SavedJob } from '../api/types';
import { ApplicationInsights } from '../components/ApplicationInsights';
import { formatLocation } from '../components/format';
import { Badge, Card, EmptyState, ErrorState, PageHeader, SkeletonTable, SkillBadge } from '../components/ui';
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
  // V8.5: the match of each tracked job, loaded once; it does not change with status or notes.
  const [analysis, setAnalysis] = useState<Record<string, ApplicationAnalysis>>({});

  useEffect(() => {
    saved?.ensureLoaded();
  }, [saved]);

  const trackedCount = saved?.items?.length ?? 0;
  useEffect(() => {
    let active = true;
    if (trackedCount > 0) {
      api.applications().then(
        (rows) => active && setAnalysis(Object.fromEntries(rows.map((row) => [row.id, row]))),
        () => undefined,
      );
    }
    return () => {
      active = false;
    };
  }, [trackedCount]);

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
        title="Applications"
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

      <ApplicationInsights
        refreshKey={(items ?? []).map((item) => `${item.id}:${item.status}:${item.followUpOn ?? ''}`).join(',')}
      />

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
                <SavedJobCard item={item} analysis={analysis[item.id]} />
              </li>
            ))}
          </ul>
        )}
      </Card>
    </>
  );
}

function SavedJobCard({ item, analysis }: { item: SavedJob; analysis?: ApplicationAnalysis }) {
  const saved = useSavedJobs()!;
  const [notes, setNotes] = useState(item.notes ?? '');
  const [followUpOn, setFollowUpOn] = useState(item.followUpOn ?? '');
  const [followUpNote, setFollowUpNote] = useState(item.followUpNote ?? '');
  const followUpChanged = followUpOn !== (item.followUpOn ?? '') || followUpNote.trim() !== (item.followUpNote ?? '');
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

  const saveFollowUp = () =>
    run(async () => {
      const updated = await api.setSavedJobFollowUp(item.id, followUpOn || null, followUpNote);
      saved.replace(updated);
      setFollowUpOn(updated.followUpOn ?? '');
      setFollowUpNote(updated.followUpNote ?? '');
    }, followUpOn ? 'Follow-up saved' : 'Follow-up cleared');

  const confirmAction = useConfirm();
  const remove = async () => {
    if (await confirmAction({ title: `Remove “${item.job.title}” from your saved jobs?`, confirmLabel: 'Remove', tone: 'danger' })) {
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
        {analysis && <MatchSummary analysis={analysis} />}
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
        <label className="field">
          Priority
          <select value={item.priority ?? ''} disabled={busy}
            onChange={(event) => run(async () => {
              const value = (event.target.value || null) as 'HIGH' | 'MEDIUM' | 'LOW' | null;
              saved.replace(await api.setSavedJobPriority(item.id, value));
            }, 'Priority saved')}>
            <option value="">None</option>
            <option value="HIGH">High</option>
            <option value="MEDIUM">Medium</option>
            <option value="LOW">Low</option>
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
        </div>
        <div className="row" style={{ gap: 8, flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <label className="field">
            Follow up on
            <input type="date" value={followUpOn} disabled={busy} onChange={(event) => setFollowUpOn(event.target.value)} />
          </label>
          <label className="field" style={{ flex: '1 1 200px' }}>
            Reminder
            <input
              type="text"
              maxLength={200}
              value={followUpNote}
              disabled={busy || !followUpOn}
              placeholder="e.g. Email the recruiter"
              onChange={(event) => setFollowUpNote(event.target.value)}
            />
          </label>
          <button type="button" className="small" disabled={busy || !followUpChanged} onClick={saveFollowUp}>
            Save follow-up
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

/** V8.5: the V8.3 match of the current resume with this job, or why there is none. */
function MatchSummary({ analysis }: { analysis: ApplicationAnalysis }) {
  if (analysis.overallMatchPercentage === undefined) {
    return analysis.matchNote ? <p className="muted small" style={{ margin: '4px 0 0' }}>{analysis.matchNote}</p> : null;
  }
  return (
    <div className="stack" style={{ gap: 4, marginTop: 6 }}>
      <p className="small" style={{ margin: 0 }}>
        <strong>{analysis.overallMatchPercentage.toFixed(0)}% match</strong>
        {analysis.skillMatchPercentage !== undefined && (
          <span className="muted"> · {analysis.skillMatchPercentage.toFixed(0)}% of its skills on your resume</span>
        )}
      </p>
      {((analysis.matchedSkills?.length ?? 0) > 0 || (analysis.missingSkills?.length ?? 0) > 0) && (
        <ul className="skill-list" aria-label="Matched and missing skills">
          {(analysis.matchedSkills ?? []).map((skill) => (
            <SkillBadge key={`m${skill.id}`} name={skill.name} state="matched" />
          ))}
          {(analysis.missingSkills ?? []).map((skill) => (
            <SkillBadge key={`x${skill.id}`} name={skill.name} state="missing" />
          ))}
        </ul>
      )}
    </div>
  );
}
