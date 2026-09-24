import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { CareerInsights as Insights, CategoryDemand, InsightDemandSkill } from '../api/types';
import { AsyncPanel } from './AsyncPanel';
import { Badge, Card, EmptyState, SkillBadge } from './ui';
import { useApi } from '../hooks/useApi';

interface Props {
  resumeId: string;
  /** Brings the full recommended-jobs list into view. */
  onShowRecommendations: () => void;
}

/**
 * The resume set against one job category's demand.
 *
 * <p>Every number shown comes from the career-insights response, which in turn copies it
 * from the existing analytics and recommendation endpoints. The optional AI summary is
 * requested only on demand, so browsing categories costs no model calls.
 */
export function CareerInsights({ resumeId, onShowRecommendations }: Props) {
  // Empty means "let the backend pick the category of the best recommendation".
  const [category, setCategory] = useState('');
  const [withSummary, setWithSummary] = useState(false);
  const [attempt, setAttempt] = useState(0);

  const insights = useApi<Insights>(
    () => api.careerInsights(resumeId, category || undefined, withSummary),
    [resumeId, category, withSummary, attempt],
  );
  const categories = useApi<CategoryDemand[]>(() => api.jobCategories().catch(() => []), []);

  return (
    <Card
      title="Career insights"
      description="Your skills against what postings in one job category ask for. Figures are from the stored job data; trends are market-wide."
      actions={
        <label className="field" style={{ minWidth: 220 }}>
          <span className="visually-hidden">Target role</span>
          <select
            aria-label="Target role"
            value={category}
            onChange={(event) => {
              setCategory(event.target.value);
              setWithSummary(false);
            }}
          >
            <option value="">Best match from your recommendations</option>
            {(categories.data ?? []).map((row) => (
              <option key={row.category} value={row.category}>
                {row.category}
              </option>
            ))}
          </select>
        </label>
      }
    >
      <AsyncPanel state={insights} onRetry={() => setAttempt((count) => count + 1)} skeleton="cards" skeletonCount={2}>
        {(data) =>
          !data.targetCategory ? (
            <EmptyState title="No target role yet" message={data.note ?? 'Choose a target role above to see insights.'} />
          ) : (
            <InsightSections
              data={data}
              summaryRequested={withSummary}
              onSummarise={() => setWithSummary(true)}
              onShowRecommendations={onShowRecommendations}
            />
          )
        }
      </AsyncPanel>
    </Card>
  );
}

function InsightSections({
  data,
  summaryRequested,
  onSummarise,
  onShowRecommendations,
}: {
  data: Insights;
  summaryRequested: boolean;
  onSummarise: () => void;
  onShowRecommendations: () => void;
}) {
  const role = data.targetCategory ?? '';
  return (
    <div className="stack insights">
      <div className="insight-target">
        <span className="recommendation-label">Target role</span>
        <div className="row">
          <strong>{role}</strong>
          {data.categorySource === 'TOP_RECOMMENDATION' && <Badge tone="brand">From your top recommendation</Badge>}
        </div>
      </div>

      <div className="gap-columns">
        <section className="gap-column" aria-labelledby="insight-your-skills">
          <h3 id="insight-your-skills">Your skills ({data.resumeSkills.length})</h3>
          {data.resumeSkills.length === 0 ? (
            <p className="muted">No skills were recognised in this resume.</p>
          ) : (
            <ul className="skill-list">
              {data.resumeSkills.map((skill) => (
                <SkillBadge key={skill.id} name={skill.name} />
              ))}
            </ul>
          )}
        </section>

        <DemandList
          id="insight-strong"
          title={`Strong skills (${data.strongSkills.length})`}
          empty={`None of your skills appear in ${role} postings yet.`}
          skills={data.strongSkills}
          role={role}
        />

        <DemandList
          id="insight-gaps"
          title={`Skill gaps (${data.skillGaps.length})`}
          empty={`You already show every top ${role} skill.`}
          skills={data.skillGaps}
          role={role}
        />
      </div>

      <div className="gap-columns">
        <DemandList
          id="insight-demand"
          title="High-demand skills"
          empty={`No skills are recorded for ${role} postings.`}
          skills={data.highDemandSkills}
          role={role}
          ranked
        />

        <section className="gap-column" aria-labelledby="insight-trending">
          <h3 id="insight-trending">Trending skills</h3>
          {data.trendingSkills.length === 0 ? (
            <p className="muted">No {role} skill is rising in the snapshot history.</p>
          ) : (
            <ul className="insight-list">
              {data.trendingSkills.map((skill) => (
                <li key={skill.skillId}>
                  <span>
                    {skill.skill}
                    {skill.onResume && <span className="muted"> · on your resume</span>}
                  </span>
                  <span className="trend trend-rising tabular">
                    ▲ +{skill.changeInPercentagePoints.toFixed(1)} pts
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="gap-column" aria-labelledby="insight-focus">
          <h3 id="insight-focus">Recommended focus areas</h3>
          {data.focusAreas.length === 0 ? (
            <p className="muted">No gaps among the top {role} skills.</p>
          ) : (
            <ol className="insight-list">
              {data.focusAreas.map((area) => (
                <li key={area.skillId}>
                  <span>
                    <strong>{area.skill}</strong>
                    <span className="insight-reason">
                      In {area.percentageOfJobs}% of {role} postings (rank {area.demandRank})
                      {area.changeInPercentagePoints !== undefined &&
                        `, rising ${area.changeInPercentagePoints.toFixed(1)} pts market-wide`}
                    </span>
                  </span>
                </li>
              ))}
            </ol>
          )}
        </section>
      </div>

      <div className="insight-footer">
        <p>
          {data.recommendedJobs.length === 0
            ? `None of your top recommendations are ${role} roles.`
            : `${data.recommendedJobs.length} of your top recommendations ${data.recommendedJobs.length === 1 ? 'is a' : 'are'} ${role} role${data.recommendedJobs.length === 1 ? '' : 's'}:`}
        </p>
        {data.recommendedJobs.length > 0 && (
          <ul className="insight-list">
            {data.recommendedJobs.map((job) => (
              <li key={job.jobId}>
                <Link to={`/jobs/${job.jobId}`}>{job.jobTitle}</Link>
                <span className="tabular muted">{job.matchPercentage.toFixed(0)}% match</span>
              </li>
            ))}
          </ul>
        )}
        <button type="button" onClick={onShowRecommendations}>View recommended jobs</button>
      </div>

      <div className="insight-summary" aria-live="polite">
        {data.summary ? (
          <>
            <span className="recommendation-label">AI summary of the figures above</span>
            <p>{data.summary}</p>
          </>
        ) : data.note ? (
          <p className="muted">{data.note}</p>
        ) : (
          !summaryRequested && (
            <button type="button" onClick={onSummarise}>Summarise with AI</button>
          )
        )}
      </div>
    </div>
  );
}

function DemandList({
  id,
  title,
  empty,
  skills,
  role,
  ranked = false,
}: {
  id: string;
  title: string;
  empty: string;
  skills: InsightDemandSkill[];
  role: string;
  ranked?: boolean;
}) {
  return (
    <section className="gap-column" aria-labelledby={id}>
      <h3 id={id}>{title}</h3>
      {skills.length === 0 ? (
        <p className="muted">{empty}</p>
      ) : (
        <ul className="insight-list">
          {skills.map((skill) => (
            <li key={skill.skillId}>
              <span>
                {ranked && <span className="muted tabular">{skill.rank}. </span>}
                {skill.skill}
                {ranked && skill.onResume && (
                  <>
                    <span aria-hidden="true" className="trend-rising"> ✓</span>
                    <span className="visually-hidden"> (on your resume)</span>
                  </>
                )}
              </span>
              <span className="tabular muted" title={`Share of ${role} postings`}>
                {skill.percentageOfJobs}%
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
