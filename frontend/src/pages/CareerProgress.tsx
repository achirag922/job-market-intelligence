import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { Achievement, CareerProgress } from '../api/types';
import { Card, ErrorState, PageHeader, SkeletonCards } from '../components/ui';
import { useApi } from '../hooks/useApi';

const RADIUS = 76;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;

function formatDay(iso?: string) {
  return iso ? new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { dateStyle: 'medium' }) : '';
}

function Bar({ value, max, label }: { value: number; max: number; label: string }) {
  const percent = max === 0 ? 0 : Math.round((value / max) * 100);
  return (
    <div className="progress-bar" role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={max} aria-valuenow={value}>
      <div className={percent >= 100 ? 'progress-bar-fill is-full' : 'progress-bar-fill'} style={{ width: `${percent}%` }} />
    </div>
  );
}

/** The score as a ring that fills up once it is on screen. */
function ScoreRing({ score, level }: { score: number; level: string }) {
  const [shown, setShown] = useState(0);
  useEffect(() => {
    const frame = requestAnimationFrame(() => setShown(score));
    return () => cancelAnimationFrame(frame);
  }, [score]);
  return (
    <div className="score-ring" role="img" aria-label={`Career readiness ${score} out of 100: ${level}`}>
      <svg width="180" height="180" viewBox="0 0 180 180" aria-hidden="true">
        <circle className="score-ring-track" cx="90" cy="90" r={RADIUS} fill="none" strokeWidth="14" />
        <circle className="score-ring-value" cx="90" cy="90" r={RADIUS} fill="none" strokeWidth="14"
          strokeDasharray={CIRCUMFERENCE} strokeDashoffset={CIRCUMFERENCE * (1 - shown / 100)} />
      </svg>
      <div className="score-ring-label" aria-hidden="true">
        <span className="score-ring-number">{score}</span>
        <span className="muted small">of 100</span>
        <strong className="small">{level}</strong>
      </div>
    </div>
  );
}

/**
 * V9.13: overall career progress. The score, achievements and streaks come from the server, which
 * computes them from the user's own goals, resume, learning, interviews, applications and portfolio.
 */
export function CareerProgressPage() {
  const [attempt, setAttempt] = useState(0);
  const state = useApi(() => api.careerProgress(), [attempt]);
  return (
    <>
      <PageHeader title="Career Progress"
        description="Where you stand on the way to your target role: a transparent readiness score, progress, milestones and streaks." />
      {state.error ? (
        <ErrorState message={state.error} onRetry={() => setAttempt((count) => count + 1)} />
      ) : !state.data ? (
        <SkeletonCards count={4} />
      ) : (
        <ProgressView data={state.data} />
      )}
    </>
  );
}

function ProgressView({ data }: { data: CareerProgress }) {
  const { readiness, progress } = data;
  const earned = data.achievements.filter((a) => a.achieved);
  const timeline = earned.filter((a) => a.achievedOn).sort((a, b) => (a.achievedOn! < b.achievedOn! ? -1 : 1));

  return (
    <>
      <Card title="Career readiness" description="The score is the sum of the parts below; each part is capped, so only real progress counts."
        info="Seven parts add up to 100: profile, resume, skill gap, learning, interviews, job search and portfolio. It is a guide to what to do next, not a prediction of hiring.">
        <div className="progress-hero">
          <ScoreRing score={readiness.score} level={readiness.level} />
          <ul className="score-components" aria-label="Score components">
            {readiness.components.map((component) => (
              <li key={component.key}>
                <div className="score-component-head">
                  <strong>{component.label}</strong>
                  <span className="tabular">{component.points} / {component.maxPoints}</span>
                </div>
                <Bar value={component.points} max={component.maxPoints} label={`${component.label} points`} />
                <span className="muted small">{component.detail}{component.hint && <> · <em>Next: {component.hint}</em></>}</span>
              </li>
            ))}
          </ul>
        </div>
      </Card>

      <div className="progress-grid" aria-label="Streaks">
        {data.streaks.map((streak) => (
          <Card key={streak.key} title={`${streak.label} streak`}>
            <p className="streak-value" style={{ margin: 0 }}>
              {streak.currentWeeks} week{streak.currentWeeks === 1 ? '' : 's'}
            </p>
            <p className="muted small" style={{ margin: 0 }}>
              {streak.activeThisWeek ? 'Active this week' : streak.currentWeeks > 0 ? 'Keep it going this week' : 'No streak yet'}
              {streak.longestWeeks > 0 && ` · longest ${streak.longestWeeks}`}
              {streak.lastActiveOn && ` · last ${formatDay(streak.lastActiveOn)}`}
            </p>
          </Card>
        ))}
      </div>

      <div className="progress-grid">
        <Card title="Target role">
          {progress.target ? (
            <>
              <p style={{ margin: 0 }}><strong>{progress.target.role}</strong> <span className="muted small">· {progress.target.category}</span></p>
              {progress.target.percentComplete !== undefined ? (
                <>
                  <Bar value={Math.round(progress.target.percentComplete)} max={100} label="Roadmap skills covered" />
                  <p className="small" style={{ margin: 0 }}>
                    {progress.target.percentComplete.toFixed(0)}% of roadmap skills covered · {progress.target.skillsOnResume} on your resume ·{' '}
                    {progress.target.skillsCompleted} learned · {progress.target.skillsInProgress} in progress
                  </p>
                  {progress.target.nextSkills.length > 0 && (
                    <p className="muted small" style={{ margin: 0 }}>Next to learn: {progress.target.nextSkills.join(', ')}</p>
                  )}
                </>
              ) : (
                <p className="muted small">No market data for this role's category yet.</p>
              )}
            </>
          ) : (
            <p className="small">No active goal. <Link to="/career-goals">Set a career goal</Link> to track your target role.</p>
          )}
        </Card>
        <Card title="Learning">
          <p style={{ margin: 0 }}><strong>{progress.learning.completed}</strong> of {progress.learning.items} items completed</p>
          <Bar value={progress.learning.completed} max={Math.max(progress.learning.items, 1)} label="Learning items completed" />
          <p className="muted small" style={{ margin: 0 }}>
            {progress.learning.inProgress} in progress
            {progress.learning.averageProgress !== undefined && ` · average progress ${progress.learning.averageProgress.toFixed(0)}%`}
            {progress.learning.items === 0 && <> · <Link to="/learning">Plan a skill</Link></>}
          </p>
        </Card>
        <Card title="Interview preparation">
          <p style={{ margin: 0 }}><strong>{progress.interviews.completed}</strong> practice interview{progress.interviews.completed === 1 ? '' : 's'} completed</p>
          <p className="muted small" style={{ margin: 0 }}>
            {progress.interviews.averageScore !== undefined
              ? `Average ${progress.interviews.averageScore.toFixed(1)}/5 · latest ${progress.interviews.latestScore?.toFixed(1)}/5`
              : <Link to="/interview-prep">Start a practice interview</Link>}
          </p>
        </Card>
        <Card title="Applications">
          <ul className="small" style={{ margin: 0, paddingLeft: 18 }} aria-label="Application progress">
            <li>{progress.applications.saved} saved</li>
            <li>{progress.applications.applied} applied</li>
            <li>{progress.applications.interviewing} at interview stage</li>
            <li>{progress.applications.offers} offer{progress.applications.offers === 1 ? '' : 's'}</li>
          </ul>
        </Card>
      </div>

      {data.nextMilestones.length > 0 && (
        <Card title="Next milestones" description="What to aim for next.">
          <ul className="achievement-grid" aria-label="Next milestones">
            {data.nextMilestones.map((milestone) => <AchievementCard key={milestone.key} achievement={milestone} />)}
          </ul>
        </Card>
      )}

      <Card title="Achievements" description={`${earned.length} of ${data.achievements.length} earned. Each is earned once.`}>
        <ul className="achievement-grid" aria-label="Achievements">
          {data.achievements.map((achievement) => <AchievementCard key={achievement.key} achievement={achievement} />)}
        </ul>
      </Card>

      <Card title="Milestone timeline">
        {timeline.length === 0 ? (
          <p className="muted small">Your milestones will appear here as you reach them.</p>
        ) : (
          <ol className="progress-timeline" aria-label="Milestone timeline">
            {timeline.map((item) => (
              <li key={item.key}>
                <strong className="small">{formatDay(item.achievedOn)}</strong>
                <div>{item.title}</div>
              </li>
            ))}
          </ol>
        )}
      </Card>

      <ul className="muted small" aria-label="How progress is calculated">
        {data.notes.map((note) => <li key={note}>{note}</li>)}
      </ul>
    </>
  );
}

function AchievementCard({ achievement }: { achievement: Achievement }) {
  return (
    <li className={achievement.achieved ? 'achievement is-earned' : 'achievement is-locked'}
      aria-label={`${achievement.title}: ${achievement.achieved ? 'earned' : 'not yet earned'}`}>
      <span className="achievement-badge" aria-hidden="true">{achievement.achieved ? '✓' : '·'}</span>
      <strong>{achievement.title}</strong>
      <span className="small">{achievement.description}</span>
      <span className="muted small">
        {achievement.achieved
          ? achievement.achievedOn ? `Earned ${formatDay(achievement.achievedOn)}` : 'Earned'
          : achievement.progress}
      </span>
    </li>
  );
}
