import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, api } from '../api/client';
import type { JobSummary, PagedResponse, Resume, ResumeMatch } from '../api/types';
import { AsyncPanel } from '../components/AsyncPanel';
import { formatLocation } from '../components/format';
import { useApi } from '../hooks/useApi';

const JOB_RESULTS = 8;

/**
 * The resume workflow: upload, then read the extracted skills, then pick a job and see
 * the overlap and the gap.
 *
 * <p>Steps below the current one stay hidden rather than appearing disabled, so the page
 * only ever shows what can actually be done next.
 */
export function ResumeIntelligence() {
  const [resume, setResume] = useState<Resume | null>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);

  const [jobQuery, setJobQuery] = useState('');
  const [appliedQuery, setAppliedQuery] = useState('');
  const [selectedJob, setSelectedJob] = useState<JobSummary | null>(null);

  const [match, setMatch] = useState<ResumeMatch | null>(null);
  const [matchError, setMatchError] = useState<string | null>(null);

  const jobs = useApi<PagedResponse<JobSummary>>(
    () => api.jobs({ title: appliedQuery }, 0, JOB_RESULTS),
    [appliedQuery],
  );

  const handleUpload = async (file: File) => {
    setUploading(true);
    setUploadError(null);
    setResume(null);
    setMatch(null);
    setSelectedJob(null);
    try {
      setResume(await api.uploadResume(file));
    } catch (error) {
      setUploadError(error instanceof ApiError ? error.message : 'Upload failed');
    } finally {
      setUploading(false);
    }
  };

  const handleSelectJob = async (job: JobSummary) => {
    if (!resume) {
      return;
    }
    setSelectedJob(job);
    setMatch(null);
    setMatchError(null);
    try {
      setMatch(await api.resumeMatch(resume.id, job.id));
    } catch (error) {
      setMatchError(error instanceof ApiError ? error.message : 'Could not compare the resume');
    }
  };

  const readyToMatch = resume?.status === 'COMPLETED';

  return (
    <section>
      <h1>Resume Intelligence</h1>
      <p className="subtitle">
        Upload a PDF resume, then pick a job to see which of its skills you already have
        and which are missing. The comparison looks only at skills — it says nothing about
        experience, seniority or your chances of being hired.
      </p>

      <div className="card">
        <h2>1. Upload your resume</h2>
        <input
          type="file"
          accept="application/pdf,.pdf"
          disabled={uploading}
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) {
              void handleUpload(file);
            }
          }}
        />
        {uploading && <p className="status">Uploading and processing…</p>}
        {uploadError && (
          <p className="status status-error" role="alert">
            {uploadError}
          </p>
        )}
        {resume && (
          <dl className="detail-grid">
            <dt>File</dt>
            <dd>{resume.fileName}</dd>
            <dt>Size</dt>
            <dd>{formatBytes(resume.fileSizeBytes)}</dd>
            <dt>Status</dt>
            <dd>
              <StatusTag status={resume.status} />
            </dd>
            {resume.errorMessage && (
              <>
                <dt>Problem</dt>
                <dd>{resume.errorMessage}</dd>
              </>
            )}
          </dl>
        )}
      </div>

      {resume?.status === 'COMPLETED' && (
        <div className="card">
          <h2>2. Your skills</h2>
          {resume.skills.length === 0 ? (
            <p className="status">
              No known skills were recognised in this resume. Only skills that already
              appear in job postings can be recognised.
            </p>
          ) : (
            <ul className="skill-list">
              {resume.skills.map((skill) => (
                <li key={skill.id} className="skill-tag">
                  {skill.name}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {readyToMatch && (
        <div className="card">
          <h2>3. Choose a job</h2>
          <form
            className="filters"
            onSubmit={(event) => {
              event.preventDefault();
              setAppliedQuery(jobQuery.trim());
            }}
          >
            <label>
              Search job titles
              <input
                type="text"
                value={jobQuery}
                placeholder="e.g. backend engineer"
                onChange={(event) => setJobQuery(event.target.value)}
              />
            </label>
            <div className="filter-actions">
              <button type="submit">Search</button>
            </div>
          </form>

          <AsyncPanel
            state={jobs}
            isEmpty={(data) => data.content.length === 0}
            empty="No jobs match that search."
          >
            {(data) => (
              <table>
                <thead>
                  <tr>
                    <th>Title</th>
                    <th>Company</th>
                    <th>Location</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {data.content.map((job) => (
                    <tr key={job.id}>
                      <td>{job.title}</td>
                      <td>{job.company.name}</td>
                      <td>{formatLocation(job)}</td>
                      <td>
                        <button type="button" onClick={() => void handleSelectJob(job)}>
                          {selectedJob?.id === job.id ? 'Selected' : 'Compare'}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </AsyncPanel>
        </div>
      )}

      {matchError && (
        <p className="status status-error" role="alert">
          {matchError}
        </p>
      )}

      {match && (
        <>
          <div className="card">
            <h2>4. Match against {match.jobTitle}</h2>
            <p className="subtitle">
              {match.companyName} · {match.totalJobSkills} required skills
              {match.jobCategory && <> · Category: <strong>{match.jobCategory}</strong></>}
            </p>

            {match.matchPercentage === undefined ? (
              <p className="status">{match.matchNote}</p>
            ) : (
              <div className="stat-grid">
                <div className="stat-card">
                  <span className="stat-value">{match.matchPercentage.toFixed(0)}%</span>
                  <span className="stat-label">Skill match</span>
                </div>
                <div className="stat-card">
                  <span className="stat-value">{match.matchedSkillCount}</span>
                  <span className="stat-label">Skills you have</span>
                </div>
                <div className="stat-card">
                  <span className="stat-value">{match.missingSkillCount}</span>
                  <span className="stat-label">Skills you are missing</span>
                </div>
              </div>
            )}

            <SkillGroup title="Matched" skills={match.matchedSkills} empty="None of this job's skills were found." />
          </div>

          <div className="card">
            <h2>5. Your skill gap</h2>
            <p className="subtitle">
              What this job asks for that your resume does not show. These are the skills
              to work on for this role.
            </p>
            <SkillGroup
              title="Missing"
              skills={match.missingSkills}
              empty="Nothing missing — your resume covers every skill this job lists."
            />
            <SkillGroup
              title="On your resume but not required here"
              skills={match.resumeOnlySkills}
              empty="Every skill on your resume is asked for by this job."
            />
            <p className="subtitle">
              <Link to={`/jobs/${match.jobId}`}>View the full job posting</Link>
            </p>
          </div>
        </>
      )}
    </section>
  );
}

function SkillGroup({ title, skills, empty }: { title: string; skills: { id: number; name: string }[]; empty: string }) {
  return (
    <>
      <h2>{title}</h2>
      {skills.length === 0 ? (
        <p className="status">{empty}</p>
      ) : (
        <ul className="skill-list">
          {skills.map((skill) => (
            <li key={skill.id} className="skill-tag">
              {skill.name}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

function StatusTag({ status }: { status: Resume['status'] }) {
  const className = status === 'COMPLETED' ? 'trend-rising' : status === 'FAILED' ? 'trend-falling' : 'trend-stable';
  return <span className={`trend ${className}`}>{status}</span>;
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
