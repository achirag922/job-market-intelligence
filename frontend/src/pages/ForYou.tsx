import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, api } from '../api/client';
import type { PersonalizedFeed, PersonalizedFeedItem } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { MatchBreakdownList, MatchPreferencesCard } from '../components/MatchBreakdown';
import { formatLocation } from '../components/format';
import { Badge, Card, PageHeader } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { SaveJobButton, useSavedJobs } from '../saved/SavedJobs';

const REASON_TONE: Record<string, 'success' | 'danger' | 'neutral'> = {
  POSITIVE: 'success',
  NEGATIVE: 'danger',
  INFO: 'neutral',
};

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

/**
 * V9.2: jobs ranked for the signed-in user by their V8.3 match, career goal, preferences and
 * application history, each with the reasons behind its place. A ranking aid, not a hiring prediction.
 */
export function ForYou() {
  const [refresh, setRefresh] = useState(0);
  const feed = useApi<PersonalizedFeed>(() => api.personalizedJobs(20), [refresh]);
  const reload = () => setRefresh((count) => count + 1);

  return (
    <>
      <PageHeader
        title="For You"
        description="Jobs ranked by your resume match, career goal, preferences and applications, with why each one is here. Jobs you applied to or excluded are left out."
        actions={<Link className="button-link" to="/jobs">Search all jobs</Link>}
      />
      <MatchPreferencesCard onSaved={reload} />
      <Card title="Recommended for you" description="Match is the V8.3 score; priority adds the personal signals listed with each job.">
        <AsyncPanel state={feed} onRetry={reload} skeleton="cards" skeletonCount={3}
          isEmpty={(data) => data.jobs.length === 0} emptyTitle="Nothing to recommend yet"
          empty="No active job fits your preferences. Loosen an exclusion or search all jobs.">
          {(data) => (
            <div className="stack" style={{ gap: 12 }}>
              {data.note && <p className="muted small" style={{ margin: 0 }}>{data.note}</p>}
              <ul className="recommendation-list" aria-label="Recommended jobs" style={{ listStyle: 'none', padding: 0, margin: 0 }}>
                {data.jobs.map((item) => (
                  <li key={item.job.id}>
                    <FeedCard item={item} onApplied={reload} />
                  </li>
                ))}
              </ul>
            </div>
          )}
        </AsyncPanel>
      </Card>
    </>
  );
}

function FeedCard({ item, onApplied }: { item: PersonalizedFeedItem; onApplied: () => void }) {
  const saved = useSavedJobs();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  /** Marks the job applied: saves it if needed, then moves it to Applied (it then leaves the feed). */
  const apply = async () => {
    setBusy(true);
    setError(null);
    try {
      const record = await api.saveJob(item.job.id);
      const updated = await api.setSavedJobStatus(record.id, 'APPLIED');
      saved?.replace(updated);
      onApplied();
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(false);
    }
  };

  return (
    <article className="recommendation-card" aria-labelledby={`feed-${item.job.id}`}>
      <div className="recommendation-score" aria-label={item.matchPercentage === undefined
        ? 'No match score' : `${item.matchPercentage.toFixed(0)} percent match`}>
        <strong>{item.matchPercentage === undefined ? '—' : `${item.matchPercentage.toFixed(0)}%`}</strong>
        <span>Match</span>
      </div>
      <div className="recommendation-main">
        <div className="recommendation-heading">
          <div>
            <h3 id={`feed-${item.job.id}`}><Link to={`/jobs/${item.job.id}`}>{item.job.title}</Link></h3>
            <p>{item.job.company.name} · {formatLocation(item.job)}</p>
          </div>
          <span className="muted small">Priority {item.priority.toFixed(1)}</span>
        </div>
        <ul className="skill-list" aria-label={`Why ${item.job.title} is recommended`}>
          {item.reasons.map((reason) => (
            <li key={reason.text}>
              <Badge tone={REASON_TONE[reason.kind] ?? 'neutral'}>
                {reason.text}{reason.points ? ` (+${reason.points})` : ''}
              </Badge>
            </li>
          ))}
        </ul>
        {item.breakdown && (
          <details>
            <summary>Match breakdown</summary>
            <MatchBreakdownList breakdown={item.breakdown} />
          </details>
        )}
        <div className="recommendation-actions">
          <SaveJobButton jobId={item.job.id} size="small" />
          <button type="button" className="small" disabled={busy} onClick={apply}>
            {busy ? 'Saving…' : 'Mark applied'}
          </button>
          {error && <span className="status status-error small" role="alert">{error}</span>}
        </div>
      </div>
    </article>
  );
}
