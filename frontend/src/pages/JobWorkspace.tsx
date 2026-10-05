import { useState } from 'react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, api } from '../api/client';
import type { JobSummary, JobWorkspace, SavedJob } from '../api/types';
import { useConfirm, useToast } from '../components/feedback';
import { Badge, Card, EmptyState, ErrorState, PageHeader, SkeletonCards } from '../components/ui';
import { useApi } from '../hooks/useApi';

const STATUS_LABEL: Record<string, string> = {
  SAVED: 'Saved', APPLIED: 'Applied', INTERVIEW: 'Interview', OFFER: 'Offer', REJECTED: 'Rejected', WITHDRAWN: 'Withdrawn',
};
const PRIORITY_TONE = { HIGH: 'danger', MEDIUM: 'warning', LOW: 'neutral' } as const;

function today(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

function JobLink({ job }: { job: JobSummary }) {
  return (
    <span className="workspace-item-main">
      <Link to={`/jobs/${job.id}`}><strong>{job.title}</strong></Link>
      <span className="muted small">{job.company.name}{job.location?.city ? ` · ${job.location.city}` : ''}</span>
    </span>
  );
}

/**
 * V9.14: the daily job-search workspace. Everything shown is the signed-in user's own; status,
 * notes and follow-ups are edited on Saved Jobs, so there is still one place for each.
 */
export function JobWorkspacePage() {
  const [attempt, setAttempt] = useState(0);
  const state = useApi(() => api.workspace(), [attempt]);
  const reload = () => setAttempt((count) => count + 1);
  return (
    <>
      <PageHeader title="Job Workspace"
        description="Your job search in one place: follow-ups due, jobs picked for you, what you saved, applied to, viewed and hid."
        actions={<Link className="button-link primary" to="/jobs">Search jobs</Link>} />
      {state.error ? (
        <ErrorState message={state.error} onRetry={reload} />
      ) : !state.data ? (
        <SkeletonCards count={4} />
      ) : (
        <WorkspaceView data={state.data} onChanged={reload} />
      )}
    </>
  );
}

function Section({ title, count, description, children }: { title: string; count?: number; description?: string; children: ReactNode }) {
  return (
    <Card title={count === undefined ? title : `${title} (${count})`} description={description}>
      {children}
    </Card>
  );
}

function WorkspaceView({ data, onChanged }: { data: JobWorkspace; onChanged: () => void }) {
  const toast = useToast();
  const confirm = useConfirm();
  const due = today();

  const unhide = async (job: JobSummary) => {
    try {
      await api.unhideJob(job.id);
      toast(`“${job.title}” is back in your results.`);
      onChanged();
    } catch (cause) {
      toast(messageOf(cause), 'error');
    }
  };
  const deleteSearch = async (id: string, name: string) => {
    if (!(await confirm({ title: `Delete the saved search “${name}”?`, confirmLabel: 'Delete', tone: 'danger' }))) return;
    try {
      await api.deleteSavedSearch(id);
      toast('Saved search deleted.');
      onChanged();
    } catch (cause) {
      toast(messageOf(cause), 'error');
    }
  };

  const tracked = (item: SavedJob) => (
    <li key={item.id} className="workspace-item">
      <JobLink job={item.job} />
      <span className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
        <Badge tone={item.status === 'SAVED' ? 'neutral' : 'success'}>{STATUS_LABEL[item.status]}</Badge>
        {item.priority && <Badge tone={PRIORITY_TONE[item.priority]}>{item.priority.toLowerCase()} priority</Badge>}
        {item.followUpOn && <span className="muted small">follow up {item.followUpOn}</span>}
      </span>
    </li>
  );

  return (
    <>
      <Section title="Follow-ups" count={data.followUps.length} description="Soonest first. Edit dates and reminders on Saved Jobs.">
        {data.followUps.length === 0 ? (
          <p className="muted small">No follow-ups set. Add one to a saved job to be reminded.</p>
        ) : (
          <ul className="workspace-list" aria-label="Follow-ups">
            {data.followUps.map((item) => {
              const overdue = item.followUpOn! < due;
              const isToday = item.followUpOn === due;
              return (
                <li key={item.id} className={overdue ? 'workspace-item is-overdue' : 'workspace-item'}>
                  <JobLink job={item.job} />
                  <span className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
                    <Badge tone={overdue ? 'danger' : isToday ? 'warning' : 'neutral'}>
                      {overdue ? `Overdue · ${item.followUpOn}` : isToday ? 'Due today' : item.followUpOn}
                    </Badge>
                    {item.priority && <Badge tone={PRIORITY_TONE[item.priority]}>{item.priority.toLowerCase()} priority</Badge>}
                    {item.followUpNote && <span className="small">{item.followUpNote}</span>}
                  </span>
                </li>
              );
            })}
          </ul>
        )}
      </Section>

      <div className="builder-layout">
        <Section title="Recommended for you" description="From your resume, preferences and goal; jobs you hid are left out.">
          {data.recommended.length === 0 ? (
            <EmptyState title="No recommendations yet" message="Upload a resume and set your preferences to get recommendations." />
          ) : (
            <ul className="workspace-list" aria-label="Recommended jobs">
              {data.recommended.map((item) => (
                <li key={item.job.id} className="workspace-item">
                  <JobLink job={item.job} />
                  <span className="row" style={{ gap: 6 }}>
                    {item.matchPercentage !== undefined && <Badge tone="success">{Math.round(item.matchPercentage)}% match</Badge>}
                    {item.saved && <Badge>Saved</Badge>}
                  </span>
                </li>
              ))}
            </ul>
          )}
          <p className="small"><Link to="/for-you">See all recommendations</Link></p>
        </Section>
        <Section title="Saved searches" count={data.savedSearches.length} description="Run a search again in one click.">
          {data.savedSearches.length === 0 ? (
            <p className="muted small">Save a search from Job Explorer to find it here.</p>
          ) : (
            <ul className="workspace-list" aria-label="Saved searches">
              {data.savedSearches.map((search) => (
                <li key={search.id} className="workspace-item">
                  <Link to={`/jobs?${new URLSearchParams(search.filters).toString()}`}><strong>{search.name}</strong></Link>
                  <button type="button" className="small ghost" onClick={() => deleteSearch(search.id, search.name)}>
                    Delete {search.name}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Section>
      </div>

      <div className="builder-layout">
        <Section title="Saved" count={data.saved.length}>
          {data.saved.length === 0 ? <p className="muted small">Nothing saved yet.</p>
            : <ul className="workspace-list" aria-label="Saved jobs">{data.saved.map(tracked)}</ul>}
          <p className="small"><Link to="/saved-jobs">Manage saved jobs, notes and follow-ups</Link></p>
        </Section>
        <Section title="Applied" count={data.applied.length}>
          {data.applied.length === 0 ? <p className="muted small">No applications yet.</p>
            : <ul className="workspace-list" aria-label="Applied jobs">{data.applied.map(tracked)}</ul>}
        </Section>
      </div>

      <div className="builder-layout">
        <Section title="Recently viewed" count={data.recentlyViewed.length}>
          {data.recentlyViewed.length === 0 ? <p className="muted small">Jobs you open appear here.</p> : (
            <ul className="workspace-list" aria-label="Recently viewed">
              {data.recentlyViewed.map((item) => (
                <li key={item.job.id} className="workspace-item"><JobLink job={item.job} /></li>
              ))}
            </ul>
          )}
        </Section>
        <Section title="Hidden" count={data.hidden.length} description="Jobs you marked not interested.">
          {data.hidden.length === 0 ? <p className="muted small">No hidden jobs.</p> : (
            <ul className="workspace-list" aria-label="Hidden jobs">
              {data.hidden.map((item) => (
                <li key={item.job.id} className="workspace-item">
                  <JobLink job={item.job} />
                  <button type="button" className="small ghost" onClick={() => unhide(item.job)}>Unhide {item.job.title}</button>
                </li>
              ))}
            </ul>
          )}
        </Section>
      </div>
    </>
  );
}
