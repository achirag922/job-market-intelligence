import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { ApplicationInsights as Insights, ApplicationInsightsCount, ApplicationStatus } from '../api/types';
import { useApi } from '../hooks/useApi';
import { AsyncPanel } from './AsyncPanel';
import { MultiLineChartPanel } from './charts';
import { Card, StatCard } from './ui';

const STATUS_ORDER: ApplicationStatus[] = ['SAVED', 'APPLIED', 'INTERVIEW', 'OFFER', 'REJECTED', 'WITHDRAWN'];
const STATUS_LABEL: Record<ApplicationStatus, string> = {
  SAVED: 'Saved', APPLIED: 'Applied', INTERVIEW: 'Interview', OFFER: 'Offer', REJECTED: 'Rejected', WITHDRAWN: 'Withdrawn',
};

function formatDay(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { dateStyle: 'medium' });
}

/**
 * V8.5: facts about the user's own applications. Anything that needs more data than there is
 * shows the server's note instead of a number.
 */
export function ApplicationInsights({ refreshKey }: { refreshKey: string }) {
  const insights = useApi<Insights>(() => api.applicationInsights(), [refreshKey]);

  return (
    <Card title="Application intelligence" description="From the jobs you track here. Visible only to you; not a hiring prediction.">
      <AsyncPanel state={insights} skeleton="cards" skeletonCount={4}>
        {(data) => <InsightsBody data={data} />}
      </AsyncPanel>
    </Card>
  );
}

function InsightsBody({ data }: { data: Insights }) {
  const { funnel } = data;
  const stages = [
    { label: 'Applied', value: funnel.applied },
    { label: 'Interviewed', value: funnel.interviewed },
    { label: 'Offers', value: funnel.offers },
  ];
  return (
    <div className="stack" style={{ gap: 16 }}>
      <div className="stat-grid">
        <StatCard label="Tracked jobs" value={data.tracked} />
        <StatCard label="Applications" value={data.applications} hint="Moved past Saved" />
        <StatCard
          label="Interview rate"
          value={funnel.interviewRate === undefined ? null : `${funnel.interviewRate.toFixed(0)}%`}
          hint={funnel.note ?? 'Applications that reached an interview'}
        />
        <StatCard
          label="Average match"
          value={data.averageMatchPercentage === undefined ? null : `${data.averageMatchPercentage.toFixed(0)}%`}
          hint={data.matchNote ?? `Across ${data.scoredApplications} applications`}
        />
      </div>

      <section aria-label="Application funnel">
        <h3 className="small">Funnel</h3>
        <ul className="stack" style={{ gap: 6, listStyle: 'none', padding: 0, margin: 0 }}>
          {stages.map((stage) => (
            <li key={stage.label} className="row" style={{ gap: 8, alignItems: 'center' }}>
              <span style={{ minWidth: 96 }}>{stage.label}</span>
              <div className="match-meter" style={{ flex: 1 }} aria-hidden="true">
                <div className="match-meter-fill" style={{ width: `${funnel.applied ? (stage.value * 100) / funnel.applied : 0}%` }} />
              </div>
              <strong className="tabular">{stage.value}</strong>
            </li>
          ))}
        </ul>
        {funnel.offerRate !== undefined && <p className="muted small">Offer rate {funnel.offerRate.toFixed(0)}% of applications.</p>}
        <p className="muted small">
          By status: {STATUS_ORDER.map((status) => `${STATUS_LABEL[status]} ${data.statusCounts[status] ?? 0}`).join(' · ')}
        </p>
      </section>

      <section aria-label="Activity over time">
        <h3 className="small">Activity by month</h3>
        {data.activityNote && <p className="muted small">{data.activityNote}</p>}
        {data.activity.length > 0 && (
          <MultiLineChartPanel
            height={220}
            rows={data.activity.map((month) => ({ label: month.month, saved: month.saved, applied: month.applied, interviews: month.interviews, offers: month.offers }))}
            series={[
              { key: 'saved', label: 'Saved' },
              { key: 'applied', label: 'Applied' },
              { key: 'interviews', label: 'Interviews' },
              { key: 'offers', label: 'Offers' },
            ]}
          />
        )}
      </section>

      <div className="gap-columns">
        <Ranking title="Most common missing skills" items={data.topMissingSkills} empty={data.matchNote ?? 'No missing skills across your applications.'} />
        <Ranking title="Companies applied to most" items={data.topCompanies} empty="No applications yet." />
        <Ranking title="Roles applied to most" items={data.topRoles} empty="No applications yet." />
      </div>

      <section aria-label="Follow-ups">
        <h3 className="small">Upcoming follow-ups</h3>
        {data.upcomingFollowUps.length === 0 && data.overdueFollowUps.length === 0 ? (
          <p className="muted small">None set. Add a follow-up date to any saved job below.</p>
        ) : (
          <ul className="stack" style={{ gap: 4, listStyle: 'none', padding: 0, margin: 0 }}>
            {[...data.overdueFollowUps.map((f) => ({ ...f, overdue: true })), ...data.upcomingFollowUps.map((f) => ({ ...f, overdue: false }))].map((followUp) => (
              <li key={followUp.id} className="row small" style={{ gap: 8, flexWrap: 'wrap' }}>
                <strong className={followUp.overdue ? 'status-error' : undefined}>
                  {followUp.overdue ? 'Overdue · ' : ''}
                  {formatDay(followUp.followUpOn)}
                </strong>
                <Link to={`/jobs/${followUp.jobId}`}>{followUp.jobTitle}</Link>
                <span className="muted">{followUp.companyName} · {STATUS_LABEL[followUp.status]}</span>
                {followUp.note && <span>{followUp.note}</span>}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function Ranking({ title, items, empty }: { title: string; items: ApplicationInsightsCount[]; empty: string }) {
  return (
    <div className="gap-column">
      <h3 className="small">{title}</h3>
      {items.length === 0 ? (
        <p className="muted small">{empty}</p>
      ) : (
        <ol className="small" style={{ margin: 0, paddingLeft: 18 }}>
          {items.map((item) => (
            <li key={item.name}>
              {item.name} <span className="muted">({item.count})</span>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
