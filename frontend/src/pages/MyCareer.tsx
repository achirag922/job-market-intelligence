import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { PersonalDashboard, SkillProgressStatus } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { BarChartPanel, PieChartPanel } from '../components/charts';
import { Badge, Card, PageHeader, StatCard } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { ScopeLine } from './MarketIntelligence';
import { STATUS_LABEL } from './SavedJobs';

const PROGRESS_LABEL: Record<SkillProgressStatus, string> = {
  NOT_STARTED: 'Not started',
  IN_PROGRESS: 'In progress',
  COMPLETED: 'Completed',
};

const MODE_LABEL: Record<string, string> = { REMOTE: 'Remote', HYBRID: 'Hybrid', ON_SITE: 'On-site', NOT_STATED: 'Not stated' };

function percent(value?: number): string {
  return value === undefined || value === null ? '—' : `${value.toFixed(0)}%`;
}

function Chips({ items, label, empty }: { items: string[]; label: string; empty: string }) {
  if (items.length === 0) {
    return <p className="muted small">{empty}</p>;
  }
  return (
    <ul className="skill-list" aria-label={label}>
      {items.map((item) => (
        <li key={item} className="skill-tag">
          {item}
        </li>
      ))}
    </ul>
  );
}

function Note({ text }: { text?: string }) {
  return text ? <p className="muted small">{text}</p> : null;
}

function Meter({ value, label }: { value?: number; label: string }) {
  if (value === undefined || value === null) {
    return null;
  }
  return (
    <div className="match-meter" role="meter" aria-label={label} aria-valuenow={Math.round(value)} aria-valuemin={0} aria-valuemax={100}>
      <div className="match-meter-fill" style={{ width: `${value}%` }} />
    </div>
  );
}

/**
 * V7.7: the signed-in user's career in one place. One request; every figure comes from the
 * feature that owns it, and each card links to that feature's page for the detail.
 */
export function MyCareer() {
  const dashboard = useApi<PersonalDashboard>(() => api.dashboard(), []);

  return (
    <>
      <PageHeader
        title="My Career"
        description="Your resume, goal, applications and the market for your target role, together. Figures come from your own data and the postings in JMIP."
      />
      <AsyncPanel state={dashboard} skeleton="cards" skeletonCount={4}>
        {(data) => <DashboardView data={data} />}
      </AsyncPanel>
    </>
  );
}

export function DashboardView({ data }: { data: PersonalDashboard }) {
  const { resume, skills, recommendations, applications, careerGoal, market } = data;
  const open = applications.saved + applications.applied + applications.interview + applications.offer;

  return (
    <>
      <div className="stat-grid">
        <StatCard label="Skills on your resume" value={resume.current?.skillCount ?? '—'} hint={resume.current?.title} />
        <StatCard label="Average match, top jobs" value={percent(recommendations.averageMatchPercentage)}
          hint={recommendations.count > 0 ? `across ${recommendations.count} recommendations` : undefined} />
        <StatCard label="Open applications" value={open} hint={`${applications.total} saved jobs in total`} />
        <StatCard label="Goal progress" value={percent(careerGoal.progress?.percentComplete)} hint={careerGoal.targetRole} />
      </div>

      <div className="career-grid">
        <Card title="Resume" actions={<Link to="/resume" className="button-link">Open</Link>}>
          {resume.current ? (
            <>
              <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                <strong>{resume.current.title}</strong>
                {resume.current.versionLabel && <Badge>{resume.current.versionLabel}</Badge>}
                {resume.current.isDefault && <Badge tone="brand">Default</Badge>}
              </div>
              <p className="muted small">
                {resume.current.skillCount} skills identified · {resume.resumeCount} resume{resume.resumeCount === 1 ? '' : 's'} in total
              </p>
              {resume.matchSummary && resume.matchSummary.jobsCompared > 0 && (
                <p className="small">
                  Best match {percent(resume.matchSummary.topMatchPercentage)}, average {percent(resume.matchSummary.averageMatchPercentage)} across
                  your top {resume.matchSummary.jobsCompared} jobs.
                </p>
              )}
              <h3 className="resume-compare-title">Missing for your goal</h3>
              <Chips items={resume.missingSkillsForGoal} label="Skills missing for your goal" empty="Nothing missing, or no goal yet." />
            </>
          ) : null}
          <Note text={resume.note} />
        </Card>

        <Card title="Career goal" actions={<Link to="/career-goals" className="button-link">Open</Link>}>
          {careerGoal.available && careerGoal.progress ? (
            <>
              <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                <strong>{careerGoal.targetRole}</strong>
                <Badge tone="brand">{careerGoal.targetCategory}</Badge>
              </div>
              <p className="small" style={{ marginBottom: 6 }}>{percent(careerGoal.progress.percentComplete)} of the roadmap covered</p>
              <Meter value={careerGoal.progress.percentComplete} label="Goal progress" />
              <p className="muted small">
                {careerGoal.progress.onResume} on your resume · {careerGoal.progress.completed} completed · {careerGoal.progress.inProgress} in
                progress · {careerGoal.progress.notStarted} not started
              </p>
              <h3 className="resume-compare-title">Highest-priority skills</h3>
              {careerGoal.topMissingSkills.length === 0 ? (
                <p className="muted small">Nothing left to develop for this goal.</p>
              ) : (
                <ol className="career-priority-list" aria-label="Highest-priority skills">
                  {careerGoal.topMissingSkills.map((skill) => (
                    <li key={skill.skill}>
                      <strong>{skill.skill}</strong> <Badge tone={skill.status === 'IN_PROGRESS' ? 'warning' : 'neutral'}>{PROGRESS_LABEL[skill.status]}</Badge>
                    </li>
                  ))}
                </ol>
              )}
            </>
          ) : (
            <Note text={careerGoal.note} />
          )}
        </Card>

        <Card title="Skill progress">
          <h3 className="resume-compare-title">On your resume ({skills.currentSkills.length})</h3>
          <Chips items={skills.currentSkills.map((skill) => skill.name)} label="Current skills" empty="No resume skills yet." />
          <h3 className="resume-compare-title">Developing ({skills.inProgress.length})</h3>
          <Chips items={skills.inProgress} label="Skills in progress" empty="None marked in progress." />
          <h3 className="resume-compare-title">Completed ({skills.completed.length})</h3>
          <Chips items={skills.completed} label="Completed skills" empty="None completed yet." />
          {skills.roadmapPercentComplete !== undefined && (
            <p className="muted small">{skills.notStarted} not started · roadmap {percent(skills.roadmapPercentComplete)} covered</p>
          )}
          <Note text={skills.note} />
        </Card>

        <Card title="Recommended jobs" actions={<Link to="/resume" className="button-link">View all</Link>}>
          {recommendations.topJobs.length > 0 ? (
            <ul className="career-job-list" aria-label="Top matching jobs">
              {recommendations.topJobs.map((job) => (
                <li key={job.jobId}>
                  <Link to={`/jobs/${job.jobId}`}>{job.title}</Link>
                  <span className="muted small"> · {job.company}</span>
                  <Badge tone="success">{percent(job.matchPercentage)}</Badge>
                </li>
              ))}
            </ul>
          ) : null}
          <Note text={recommendations.note} />
        </Card>

        <Card title="Applications" actions={<Link to="/saved-jobs" className="button-link">Open</Link>}
          className="career-wide">
          {applications.available ? (
            <div className="market-split career-applications">
              <div>
                <BarChartPanel
                  data={applications.funnel.map((stage) => ({ label: STATUS_LABEL[stage.stage], value: stage.jobs }))}
                  valueLabel="Jobs"
                  height={200}
                />
                <p className="muted small">
                  Current status of each saved job · {applications.rejected} rejected · {applications.withdrawn} withdrawn
                </p>
              </div>
              <div>
                <h3 className="resume-compare-title">Recently updated</h3>
                <ul className="career-job-list" aria-label="Recent applications">
                  {applications.recent.map((item) => (
                    <li key={item.savedJobId}>
                      <Link to={`/jobs/${item.jobId}`}>{item.title}</Link>
                      <span className="muted small"> · {item.company}</span>
                      <Badge>{STATUS_LABEL[item.status]}</Badge>
                    </li>
                  ))}
                </ul>
              </div>
            </div>
          ) : (
            <Note text={applications.note} />
          )}
        </Card>

        <Card title={`Market${market.category ? ` for ${market.category}` : ': all postings'}`}
          actions={<Link to="/market" className="button-link">Explore</Link>} className="career-wide">
          <ScopeLine scope={market.scope} />
          {market.available && (
            <div className="chart-grid">
              <div>
                <h3 className="resume-compare-title">Most requested skills</h3>
                <BarChartPanel data={market.topSkills.map((row) => ({ label: row.skill, value: row.percentageOfPostings }))}
                  valueLabel="Share of postings" suffix="%" />
              </div>
              <div>
                <h3 className="resume-compare-title">Work mode</h3>
                <PieChartPanel data={market.workModes.filter((row) => row.postings > 0)
                  .map((row) => ({ label: MODE_LABEL[row.mode] ?? row.mode, value: row.postings }))} height={220} />
              </div>
              <div>
                <h3 className="resume-compare-title">Top locations</h3>
                <ul className="career-job-list" aria-label="Top locations">
                  {market.topLocations.map((row) => (
                    <li key={row.locationId}>{row.location} <span className="muted small">· {row.postings} postings</span></li>
                  ))}
                  {market.locationNotStated > 0 && <li className="muted small">{market.locationNotStated} with no location stated</li>}
                </ul>
                <h3 className="resume-compare-title">Top hiring companies</h3>
                <ul className="career-job-list" aria-label="Top companies">
                  {market.topCompanies.map((row) => (
                    <li key={row.companyId}>{row.company} <span className="muted small">· {row.postings} postings</span></li>
                  ))}
                </ul>
              </div>
              <div>
                <h3 className="resume-compare-title">Salary, per currency</h3>
                {market.salaries.length === 0 ? (
                  <p className="muted small">No posting here states a salary.</p>
                ) : (
                  <ul className="career-job-list" aria-label="Salaries">
                    {market.salaries.map((row) => (
                      <li key={row.currency}>
                        <strong>{row.currency}</strong> {row.averageMin?.toLocaleString('en-US')} – {row.averageMax?.toLocaleString('en-US')}
                        <span className="muted small"> · {row.postings} postings</span>{' '}
                        {!row.reliable && <Badge tone="warning">Too few postings</Badge>}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          )}
          <ul className="market-notes">
            {market.notes.map((note) => (
              <li key={note} className="muted small">{note}</li>
            ))}
          </ul>
        </Card>
      </div>
    </>
  );
}
