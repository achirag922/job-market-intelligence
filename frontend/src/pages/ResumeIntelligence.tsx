import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ApiError, api } from '../api/client';
import type {
  JobSummary,
  PagedResponse,
  Resume,
  ResumeMatch,
  ResumeRecommendation,
} from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { Badge, Card, EmptyState, PageHeader, SkillBadge, StatCard } from '../components/ui';
import { IconCheck, IconFile } from '../components/icons';
import { CareerInsights } from '../components/CareerInsights';
import { formatLocation } from '../components/format';
import { useApi } from '../hooks/useApi';
import { JobAnalysisPanel, ResumeVersions } from '../components/ResumeVersions';

const JOB_RESULTS = 8;

const STEPS = ['Upload resume', 'Extracted skills', 'Choose a job', 'Match and gap'];

/**
 * The resume workflow: upload, read the extracted skills, pick a job, see the overlap and
 * the gap.
 *
 * <p>Steps below the current one stay hidden rather than appearing disabled, so the page
 * only ever shows what can actually be done next. The stepper along the top is what makes
 * the sequence legible while they are hidden.
 */
export function ResumeIntelligence() {
  const [searchParams] = useSearchParams();
  // Arriving from a posting's "Compare with resume" preselects that job.
  const requestedJobId = Number(searchParams.get('jobId')) || null;

  const [resume, setResume] = useState<Resume | null>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  // V7.3: bumped after an upload so the version list shows the new resume.
  const [versionsKey, setVersionsKey] = useState(0);

  const [jobQuery, setJobQuery] = useState('');
  const [appliedQuery, setAppliedQuery] = useState('');
  const [selectedJob, setSelectedJob] = useState<JobSummary | null>(null);

  const [match, setMatch] = useState<ResumeMatch | null>(null);
  const [matchError, setMatchError] = useState<string | null>(null);
  const [matching, setMatching] = useState(false);

  const jobs = useApi<PagedResponse<JobSummary>>(
    () => api.jobs({ title: appliedQuery }, 0, JOB_RESULTS),
    [appliedQuery],
  );

  const readyToMatch = resume?.status === 'COMPLETED';

  // Bumped by "Try again" to re-request recommendations without re-uploading.
  const [recommendationAttempt, setRecommendationAttempt] = useState(0);
  const recommendations = useApi<ResumeRecommendation[]>(
    () => (readyToMatch && resume ? api.resumeRecommendations(resume.id) : Promise.resolve([])),
    [resume?.id, readyToMatch, recommendationAttempt],
  );

  // The comparison renders well below the recommendations. Without scrolling to it,
  // "Compare resume" on a recommendation would look as though nothing had happened.
  const matchSection = useRef<HTMLDivElement>(null);
  const scrollToMatch = useRef(false);
  useEffect(() => {
    if (scrollToMatch.current && (matching || match || matchError)) {
      scrollToMatch.current = false;
      matchSection.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }, [matching, match, matchError]);

  const recommendationsSection = useRef<HTMLDivElement>(null);

  const runMatch = async (jobId: number, job?: JobSummary) => {
    if (!resume) {
      return;
    }
    setSelectedJob(job ?? null);
    setMatch(null);
    setMatchError(null);
    setMatching(true);
    try {
      setMatch(await api.resumeMatch(resume.id, jobId));
    } catch (error) {
      setMatchError(error instanceof ApiError ? error.message : 'Could not compare the resume');
    } finally {
      setMatching(false);
    }
  };

  // A job carried in from Job Details is compared as soon as a resume is ready.
  useEffect(() => {
    if (requestedJobId && readyToMatch && !match && !matching) {
      void runMatch(requestedJobId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [requestedJobId, readyToMatch]);

  const handleUpload = async (file: File) => {
    setUploading(true);
    setUploadError(null);
    setResume(null);
    setMatch(null);
    setSelectedJob(null);
    try {
      setResume(await api.uploadResume(file));
      setVersionsKey((key) => key + 1);
    } catch (error) {
      setUploadError(error instanceof ApiError ? error.message : 'Upload failed');
    } finally {
      setUploading(false);
    }
  };

  // V7.3: switching version starts the comparison afresh with that resume.
  const selectVersion = (next: Resume | null) => {
    setResume(next);
    setMatch(null);
    setMatchError(null);
    setSelectedJob(null);
  };

  const currentStep = match ? 3 : readyToMatch ? 2 : resume ? 1 : 0;

  return (
    <>
      <PageHeader
        title="Resume Intelligence"
        description="Upload a PDF resume, then pick a job to see which of its skills you already have and which are missing. The comparison looks only at skills — it says nothing about experience, seniority or your chances of being hired."
      />

      <ol className="stepper">
        {STEPS.map((label, index) => (
          <li
            key={label}
            className={`step ${index === currentStep ? 'active' : ''} ${index < currentStep ? 'done' : ''}`}
            aria-current={index === currentStep ? 'step' : undefined}
          >
            <span className="step-number" aria-hidden="true">
              {index < currentStep ? '✓' : index + 1}
            </span>
            {label}
          </li>
        ))}
      </ol>

      <Card title="1. Upload your resume" description="PDF only. It is stored on this server and compared against job skills.">
        <label className="field">
          <span className="visually-hidden">Resume PDF</span>
          <input
            type="file"
            accept="application/pdf,.pdf"
            aria-label="Resume PDF"
            disabled={uploading}
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) {
                void handleUpload(file);
              }
            }}
          />
        </label>

        {uploading && (
          <p className="status" role="status">
            Uploading and extracting skills…
          </p>
        )}
        {uploadError && (
          <p className="status status-error" role="alert">
            {uploadError}
          </p>
        )}

        {resume && (
          <div className="row" style={{ marginTop: 16, gap: 20 }}>
            <span className="row" style={{ gap: 8 }}>
              <IconFile size={16} />
              <strong>{resume.fileName}</strong>
              <span className="muted">{formatBytes(resume.fileSizeBytes)}</span>
            </span>
            <StatusBadge status={resume.status} />
            {resume.errorMessage && <span className="muted">{resume.errorMessage}</span>}
          </div>
        )}
      </Card>

      <ResumeVersions
        selectedId={resume?.id}
        refreshKey={versionsKey}
        onSelect={selectVersion}
        // Coming back to the page picks up the default version instead of asking for an upload.
        onLoaded={(list) => {
          if (!resume && !uploading && list.length > 0) {
            selectVersion(list.find((item) => item.isDefault) ?? list[0]);
          }
        }}
        onDeleted={(id) => {
          if (resume?.id === id) {
            selectVersion(null);
          }
        }}
      />

      {resume?.status === 'COMPLETED' && (
        <Card
          title="2. Your skills"
          description="Only skills that already appear in job postings can be recognised."
          actions={<Badge>{resume.skills.length} found</Badge>}
        >
          {resume.skills.length === 0 ? (
            <EmptyState
              title="No skills recognised"
              message="Nothing in this resume matched the skill dictionary. The comparison below will show every job skill as missing."
            />
          ) : (
            <ul className="skill-list" style={{ marginBottom: 0 }}>
              {resume.skills.map((skill) => (
                <SkillBadge key={skill.id} name={skill.name} />
              ))}
            </ul>
          )}
        </Card>
      )}

      {readyToMatch && resume && (
        <CareerInsights
          key={resume.id}
          resumeId={resume.id}
          onShowRecommendations={() =>
            recommendationsSection.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
          }
        />
      )}

      {readyToMatch && (
        <div ref={recommendationsSection} className="match-section">
          <Card
            title="Recommended jobs"
            description="Ranked by the share of each job's listed skills that your resume covers. This is a skills-overlap measure, not a hiring prediction."
            actions={<Badge tone="brand">Top matches</Badge>}
          >
            <AsyncPanel
              state={recommendations}
              onRetry={() => setRecommendationAttempt((count) => count + 1)}
              skeleton="cards"
              skeletonCount={3}
              isEmpty={(data) => data.length === 0}
              emptyTitle="No matching jobs yet"
              empty="No stored job with listed skills overlaps with this resume. Try another resume after checking the extracted skills above."
            >
              {(data) => (
                <div className="recommendation-list" aria-label="Recommended jobs">
                  {data.map((recommendation) => (
                    <article className="recommendation-card" key={recommendation.jobId}>
                      <div className="recommendation-score" aria-label={`${recommendation.matchPercentage.toFixed(0)} percent skill match`}>
                        <strong>{recommendation.matchPercentage.toFixed(0)}%</strong>
                        <span>Skill match</span>
                      </div>
                      <div className="recommendation-main">
                        <div className="recommendation-heading">
                          <div>
                            <h3 id={`recommendation-${recommendation.jobId}-title`}>{recommendation.jobTitle}</h3>
                            <p>{recommendation.companyName} · {recommendation.location?.displayName ?? 'Location not stated'}</p>
                          </div>
                          {recommendation.jobCategory && <Badge tone="brand">{recommendation.jobCategory}</Badge>}
                        </div>
                        <div className="recommendation-skills">
                          <div>
                            <span className="recommendation-label">Matched ({recommendation.matchedSkills.length})</span>
                            <ul className="skill-list">
                              {recommendation.matchedSkills.map((skill) => (
                                <SkillBadge key={skill.id} name={skill.name} state="matched" />
                              ))}
                            </ul>
                          </div>
                          <div>
                            <span className="recommendation-label">Missing ({recommendation.missingSkills.length})</span>
                            <ul className="skill-list">
                              {recommendation.missingSkills.length === 0 ? (
                                <li className="muted">None</li>
                              ) : recommendation.missingSkills.map((skill) => (
                                <SkillBadge key={skill.id} name={skill.name} state="missing" />
                              ))}
                            </ul>
                          </div>
                        </div>
                        <div className="recommendation-actions">
                          <Link className="button-link" to={`/jobs/${recommendation.jobId}`}>View job</Link>
                          <button
                            type="button"
                            className="small"
                            aria-describedby={`recommendation-${recommendation.jobId}-title`}
                            onClick={() => {
                              scrollToMatch.current = true;
                              void runMatch(recommendation.jobId);
                            }}
                          >
                            Compare resume
                          </button>
                        </div>
                      </div>
                    </article>
                  ))}
                </div>
              )}
            </AsyncPanel>
          </Card>
        </div>
      )}

      {readyToMatch && (
        <Card title="3. Choose a job" description="Search by title, then compare.">
          <form
            className="search-row"
            onSubmit={(event) => {
              event.preventDefault();
              setAppliedQuery(jobQuery.trim());
            }}
          >
            <input
              type="search"
              value={jobQuery}
              placeholder="Search job titles — e.g. backend engineer"
              aria-label="Search job titles"
              onChange={(event) => setJobQuery(event.target.value)}
            />
            <button type="submit">Search</button>
          </form>

          <AsyncPanel
            state={jobs}
            skeleton="table"
            skeletonCount={4}
            isEmpty={(data) => data.content.length === 0}
            emptyTitle="No jobs found"
            empty="No postings match that search. Try a broader term."
          >
            {(data) => (
              <div className="table-wrap" style={{ marginBottom: 0 }}>
                <table>
                  <caption className="visually-hidden">Jobs available to compare against</caption>
                  <thead>
                    <tr>
                      <th scope="col">Role</th>
                      <th scope="col">Location</th>
                      <th scope="col">
                        <span className="visually-hidden">Actions</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.content.map((job) => (
                      <tr key={job.id}>
                        <td className="wrap">
                          <span className="cell-strong">{job.title}</span>
                          <span className="cell-sub">{job.company.name}</span>
                        </td>
                        <td>{formatLocation(job)}</td>
                        <td>
                          <button
                            type="button"
                            className="small"
                            aria-pressed={selectedJob?.id === job.id}
                            onClick={() => void runMatch(job.id, job)}
                          >
                            {selectedJob?.id === job.id ? 'Selected' : 'Compare'}
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </AsyncPanel>
        </Card>
      )}

      {/* One target for the comparison, whichever state it is in, so "Compare resume" on a
          recommendation can bring it into view. */}
      <div ref={matchSection} className="match-section">
      {matching && (
        <Card>
          <p className="status" role="status">
            Comparing your resume against this posting…
          </p>
        </Card>
      )}

      {matchError && (
        <p className="status status-error" role="alert">
          {matchError}
        </p>
      )}

      {match && (
        <>
          <Card
            title={`4. Match against ${match.jobTitle}`}
            description={`${match.companyName} · ${match.totalJobSkills} required skills`}
            actions={match.jobCategory ? <Badge tone="brand">{match.jobCategory}</Badge> : undefined}
          >
            {match.matchPercentage === undefined ? (
              <EmptyState title="No score available" message={match.matchNote ?? 'This job lists no skills to compare against.'} />
            ) : (
              <div className="match-summary">
                <div className="match-figure">
                  <span className="match-figure-value">{match.matchPercentage.toFixed(0)}%</span>
                  <span className="match-figure-label">Skill match</span>
                </div>
                <div style={{ flex: '1 1 260px', minWidth: 0 }}>
                  <div className="stat-grid" style={{ marginBottom: 12 }}>
                    <StatCard label="Skills you have" value={match.matchedSkillCount} />
                    <StatCard label="Skills you are missing" value={match.missingSkillCount} />
                    <StatCard label="Required by this job" value={match.totalJobSkills} />
                  </div>
                  <div
                    className="match-meter"
                    role="meter"
                    aria-valuenow={Math.round(match.matchPercentage)}
                    aria-valuemin={0}
                    aria-valuemax={100}
                    aria-label="Share of this job's skills your resume covers"
                  >
                    <div className="match-meter-fill" style={{ width: `${match.matchPercentage}%` }} />
                  </div>
                  <p className="card-description" style={{ marginTop: 8 }}>
                    Skills only. This is not a prediction about being hired.
                  </p>
                </div>
              </div>
            )}
          </Card>

          <Card
            title="5. Your skill gap"
            description="What this job asks for, split by whether your resume shows it. Each skill is marked with a symbol as well as a colour."
          >
            <div className="gap-columns">
              <div className="gap-column">
                <h3>
                  <IconCheck size={15} />
                  On your resume ({match.matchedSkills.length})
                </h3>
                {match.matchedSkills.length === 0 ? (
                  <p className="muted">None of this job's skills were found on your resume.</p>
                ) : (
                  <ul className="skill-list">
                    {match.matchedSkills.map((skill) => (
                      <SkillBadge key={skill.id} name={skill.name} state="matched" />
                    ))}
                  </ul>
                )}
              </div>

              <div className="gap-column">
                <h3>
                  <span aria-hidden="true">✕</span>
                  Missing ({match.missingSkills.length})
                </h3>
                {match.missingSkills.length === 0 ? (
                  <p className="muted">Nothing missing — your resume covers every skill this job lists.</p>
                ) : (
                  <ul className="skill-list">
                    {match.missingSkills.map((skill) => (
                      <SkillBadge key={skill.id} name={skill.name} state="missing" />
                    ))}
                  </ul>
                )}
              </div>

              <div className="gap-column">
                <h3>Also on your resume ({match.resumeOnlySkills.length})</h3>
                {match.resumeOnlySkills.length === 0 ? (
                  <p className="muted">Every skill on your resume is asked for by this job.</p>
                ) : (
                  <ul className="skill-list">
                    {match.resumeOnlySkills.map((skill) => (
                      <SkillBadge key={skill.id} name={skill.name} />
                    ))}
                  </ul>
                )}
              </div>
            </div>

            <hr className="divider" />
            <Link to={`/jobs/${match.jobId}`}>View the full job posting</Link>
          </Card>

          <JobAnalysisPanel key={`${match.resumeId}-${match.jobId}`} resumeId={match.resumeId} jobId={match.jobId} />
        </>
      )}
      </div>
    </>
  );
}

/** Processing status, as a word rather than only a colour. */
function StatusBadge({ status }: { status: Resume['status'] }) {
  if (status === 'COMPLETED') {
    return <Badge tone="success">Processed</Badge>;
  }
  if (status === 'FAILED') {
    return <Badge tone="danger">Failed</Badge>;
  }
  return <Badge tone="warning">{status.toLowerCase()}</Badge>;
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
