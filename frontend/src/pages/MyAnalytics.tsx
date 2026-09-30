import { useState } from 'react';
import { api } from '../api/client';
import type { AnalyticsRange, UserAnalytics } from '../api/types';
import { BarChartPanel, MultiLineChartPanel } from '../components/charts';
import { Card, EmptyState, ErrorState, PageHeader, StatCard } from '../components/ui';
import { useApi } from '../hooks/useApi';

const RANGES: { value: AnalyticsRange; label: string }[] = [
  { value: '7D', label: '7D' },
  { value: '30D', label: '30D' },
  { value: '90D', label: '90D' },
  { value: '1Y', label: '1Y' },
  { value: 'ALL', label: 'All' },
];

const STATUS_LABEL: Record<string, string> = {
  SAVED: 'Saved', APPLIED: 'Applied', INTERVIEW: 'Interview', OFFER: 'Offer', REJECTED: 'Rejected', WITHDRAWN: 'Withdrawn',
};

const percent = (value?: number) => (value === undefined ? null : `${value.toFixed(1)}%`);
const outOfFive = (value?: number) => (value === undefined ? null : `${value.toFixed(1)}/5`);

/**
 * V9.8: how the user's job search, resume, interviews and learning changed over time. The V7.7
 * dashboard shows where things stand; this page shows the history, from stored data only.
 */
export function MyAnalytics() {
  const [range, setRange] = useState<AnalyticsRange>('30D');
  const [attempt, setAttempt] = useState(0);
  const state = useApi(() => api.userAnalytics(range), [range, attempt]);
  const data = state.data;

  return (
    <>
      <PageHeader
        title="My Analytics"
        description="How your job search, resume, interviews and learning have changed over time, from your own data."
        actions={
          <div className="row" role="group" aria-label="Time range" style={{ gap: 4 }}>
            {RANGES.map((option) => (
              <button key={option.value} type="button" className={range === option.value ? 'small' : 'small ghost'}
                aria-pressed={range === option.value} onClick={() => setRange(option.value)}>
                {option.label}
              </button>
            ))}
          </div>
        }
      />
      {state.error ? (
        <ErrorState message={state.error} onRetry={() => setAttempt((count) => count + 1)} />
      ) : (
        <>
          <div className="stat-grid">
            <StatCard label="Jobs saved" value={data?.kpis.jobsSaved} loading={state.loading} />
            <StatCard label="Applications" value={data?.kpis.applications} loading={state.loading} />
            <StatCard label="Moved to interview" value={data?.kpis.interviews} loading={state.loading} />
            <StatCard label="Offers" value={data?.kpis.offers} loading={state.loading} />
            <StatCard label="Average skill match" value={percent(data?.kpis.averageMatch)} loading={state.loading}
              hint="Current resume vs. your saved jobs" />
            <StatCard label="Interview score" value={outOfFive(data?.kpis.averageInterviewScore)} loading={state.loading}
              hint={data ? `${data.kpis.interviewsCompleted} completed in range` : undefined} />
            <StatCard label="Learning completed" value={data?.kpis.learningCompleted} loading={state.loading} hint="Items completed in range" />
          </div>
          {data && <Details data={data} />}
        </>
      )}
    </>
  );
}

function Details({ data }: { data: UserAnalytics }) {
  const { funnel } = data;
  const stages = [
    { label: 'Applied', value: funnel.applied },
    { label: 'Interview', value: funnel.interviewed },
    { label: 'Offer', value: funnel.offers },
  ];
  const statuses = Object.entries(data.statusBreakdown).filter(([, jobs]) => jobs > 0)
    .map(([status, jobs]) => ({ label: STATUS_LABEL[status] ?? status, value: jobs }));
  const hasActivity = data.activity.some((point) => point.saved + point.applied + point.interviews + point.offers > 0);

  return (
    <>
      {data.insights.length > 0 && (
        <Card title="Insights" description="Facts from your data in this range.">
          <ul className="small" aria-label="Insights">
            {data.insights.map((insight) => <li key={insight}>{insight}</li>)}
          </ul>
        </Card>
      )}

      <Card title="Job search activity" description={`Status changes per ${data.bucket.toLowerCase()}.`}>
        {hasActivity ? (
          <MultiLineChartPanel rows={data.activity} series={[
            { key: 'saved', label: 'Saved' }, { key: 'applied', label: 'Applied' },
            { key: 'interviews', label: 'Interview' }, { key: 'offers', label: 'Offer' },
          ]} />
        ) : (
          <EmptyState title="No activity in this range" message="Save a job or update an application's status to see it here." />
        )}
      </Card>

      <div className="builder-layout">
        <Card title="Application funnel" description="Applications started in this range and how far they got.">
          {funnel.applied === 0 ? (
            <EmptyState title="No applications in this range" message="Mark a saved job as applied to start the funnel." />
          ) : (
            <section aria-label="Application funnel">
              <ul className="stack" style={{ gap: 6, listStyle: 'none', padding: 0, margin: 0 }}>
                {stages.map((stage) => (
                  <li key={stage.label} className="row" style={{ gap: 8, alignItems: 'center' }}>
                    <span style={{ minWidth: 80 }}>{stage.label}</span>
                    <div className="match-meter" style={{ flex: 1 }} aria-hidden="true">
                      <div className="match-meter-fill" style={{ width: `${(stage.value * 100) / funnel.applied}%` }} />
                    </div>
                    <span className="tabular small">{stage.value}</span>
                  </li>
                ))}
              </ul>
              <p className="muted small">
                Applied → interview: {percent(funnel.applyToInterviewRate) ?? 'n/a'} · Interview → offer:{' '}
                {percent(funnel.interviewToOfferRate) ?? 'n/a'}
              </p>
            </section>
          )}
        </Card>
        <Card title="Applications by status" description="Where the jobs you saved in this range stand now.">
          <BarChartPanel data={statuses} valueLabel="Jobs" emptyMessage="No jobs saved in this range." />
        </Card>
      </div>

      <div className="builder-layout">
        <Card title="Resume and match trend" description="Each resume version from this range, compared now with all your saved jobs.">
          {data.resumeTrend.length === 0 ? (
            <EmptyState title="No resume versions in this range" message="Upload or build a resume to track how it matches your saved jobs." />
          ) : (
            <MultiLineChartPanel
              rows={data.resumeTrend.map((point) => ({ label: `${point.title} (${point.date})`,
                match: point.averageMatch ?? null, skills: point.skills, gaps: point.missingSkills }))}
              series={[{ key: 'match', label: 'Average match %' }, { key: 'skills', label: 'Skills on resume' },
                { key: 'gaps', label: 'Missing skills' }]} />
          )}
        </Card>
        <Card title="Skill gaps" description="Skills your current resume lacks, by how many jobs saved in this range ask for them.">
          <BarChartPanel data={data.missingSkills.map((gap) => ({ label: gap.skill, value: gap.jobs }))} valueLabel="Jobs"
            emptyMessage="No gaps: either no jobs were saved in this range or your resume covers them." />
        </Card>
      </div>

      <div className="builder-layout">
        <Card title="Interview performance" description="Completed mock interviews, scored 1 to 5.">
          {data.interviews.sessions.length === 0 ? (
            <EmptyState title="No completed interviews in this range" message="Finish a mock interview to track your scores." />
          ) : (
            <MultiLineChartPanel
              rows={data.interviews.sessions.map((session, index) => ({ label: `${index + 1}. ${session.date}`,
                overall: session.overall ?? null, technical: session.technical ?? null, behavioral: session.behavioral ?? null }))}
              series={[{ key: 'overall', label: 'Overall' }, { key: 'technical', label: 'Technical' },
                { key: 'behavioral', label: 'Behavioral' }]} />
          )}
        </Card>
        <Card title="Learning progress" description="Your learning plan against your career goal's skills.">
          {data.learning.items === 0 ? (
            <EmptyState title="No learning items yet" message="Plan a skill on the Learning & Skills page." />
          ) : (
            <>
              <p className="small">
                {data.learning.completed} completed · {data.learning.inProgress} in progress · {data.learning.notStarted} not started
                {data.learning.completionRate !== undefined && ` · ${data.learning.completionRate.toFixed(1)}% complete`}
              </p>
              {data.learning.targetSkills !== undefined && (
                <p className="small">Career roadmap: {data.learning.targetSkillsCovered} of {data.learning.targetSkills} target skills covered.</p>
              )}
              <MultiLineChartPanel height={200} rows={data.learning.activity}
                series={[{ key: 'started', label: 'Started' }, { key: 'completed', label: 'Completed' }]} />
            </>
          )}
        </Card>
      </div>

      <Card title="Portfolio">
        {data.portfolio.exists ? (
          <p className="small">
            {data.portfolio.visibility === 'PUBLIC' ? 'Public' : 'Private'} · last updated{' '}
            {data.portfolio.updatedAt?.slice(0, 10)}
            {data.portfolio.publishedAt && ` · published ${data.portfolio.publishedAt.slice(0, 10)}`}
          </p>
        ) : (
          <p className="muted small">You have not created a portfolio yet.</p>
        )}
      </Card>

      {data.notes.length > 0 && (
        <ul className="muted small" aria-label="About these analytics">
          {data.notes.map((note) => <li key={note}>{note}</li>)}
        </ul>
      )}
    </>
  );
}
