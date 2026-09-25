import { useEffect, useState } from 'react';
import { ApiError, api } from '../api/client';
import type { Resume, ResumeComparison, ResumeJobAnalysis, Skill } from '../api/types';
import { Badge, Card, EmptyState, ErrorState } from './ui';

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

function formatDay(iso?: string): string {
  return iso ? new Date(iso).toLocaleDateString(undefined, { dateStyle: 'medium' }) : '';
}

/** "Backend CV · v2", or just the title. */
export function versionName(resume: Pick<Resume, 'title' | 'fileName' | 'versionLabel'>): string {
  const title = resume.title ?? resume.fileName;
  return resume.versionLabel ? `${title} · ${resume.versionLabel}` : title;
}

function SkillChips({ skills, empty, label }: { skills: Skill[]; empty: string; label: string }) {
  if (skills.length === 0) {
    return <p className="muted small">{empty}</p>;
  }
  return (
    <ul className="skill-list" aria-label={label}>
      {skills.map((skill) => (
        <li key={skill.id} className="skill-tag">
          {skill.name}
        </li>
      ))}
    </ul>
  );
}

interface ResumeVersionsProps {
  selectedId?: string;
  /** Bumped by the page after an upload, so the new version appears. */
  refreshKey: number;
  onSelect: (resume: Resume) => void;
  /** The list as loaded, so the page can pick the default when nothing is selected. */
  onLoaded: (resumes: Resume[]) => void;
  onDeleted: (id: string) => void;
}

/**
 * V7.3: the signed-in user's resume versions, with rename, default, delete and comparison.
 */
export function ResumeVersions({ selectedId, refreshKey, onSelect, onLoaded, onDeleted }: ResumeVersionsProps) {
  const [resumes, setResumes] = useState<Resume[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [editing, setEditing] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [label, setLabel] = useState('');
  const [actionError, setActionError] = useState<string | null>(null);
  const [compareA, setCompareA] = useState('');
  const [compareB, setCompareB] = useState('');
  const [comparison, setComparison] = useState<ResumeComparison | null>(null);
  const [comparing, setComparing] = useState(false);

  useEffect(() => {
    let current = true;
    setLoadError(null);
    api
      .resumes()
      .then((list) => {
        if (current) {
          setResumes(list);
          onLoaded(list);
        }
      })
      .catch((error: unknown) => current && setLoadError(messageOf(error)));
    return () => {
      current = false;
    };
    // onLoaded is a fresh function each render; reloading on it would loop.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshKey, attempt]);

  const replace = (updated: Resume, clearOtherDefaults = false) =>
    setResumes((list) =>
      (list ?? []).map((item) =>
        item.id === updated.id ? { ...item, ...updated } : clearOtherDefaults ? { ...item, isDefault: false } : item,
      ),
    );

  const run = async (action: () => Promise<void>) => {
    setActionError(null);
    try {
      await action();
    } catch (error) {
      setActionError(messageOf(error));
    }
  };

  const startEdit = (resume: Resume) => {
    setEditing(resume.id);
    setTitle(resume.title ?? resume.fileName);
    setLabel(resume.versionLabel ?? '');
  };

  const saveEdit = (id: string) =>
    run(async () => {
      replace(await api.updateResume(id, title, label));
      setEditing(null);
    });

  const makeDefault = (id: string) => run(async () => replace(await api.setDefaultResume(id), true));

  const remove = (resume: Resume) => {
    if (!window.confirm(`Delete “${versionName(resume)}”? The file, its text and its skills are removed for good.`)) {
      return;
    }
    void run(async () => {
      await api.deleteResume(resume.id);
      setComparison(null);
      onDeleted(resume.id);
      // The server may have moved the default to another resume; reload to show it.
      setAttempt((count) => count + 1);
    });
  };

  const compare = async () => {
    setComparing(true);
    setComparison(null);
    await run(async () => setComparison(await api.compareResumes(compareA, compareB)));
    setComparing(false);
  };

  const completed = (resumes ?? []).filter((resume) => resume.status === 'COMPLETED');

  return (
    <Card title="Your resumes" description="Keep a version per kind of role. The default is the one selected when you come back.">
      {actionError && (
        <p className="status status-error" role="alert">
          {actionError}
        </p>
      )}
      {loadError ? (
        <ErrorState message={loadError} onRetry={() => setAttempt((count) => count + 1)} />
      ) : resumes === null ? (
        <p className="muted small">Loading your resumes…</p>
      ) : resumes.length === 0 ? (
        <EmptyState title="No resumes yet" message="Upload a PDF above to create your first version." />
      ) : (
        <ul className="resume-version-list">
          {resumes.map((resume) => (
            <li key={resume.id} className={`resume-version${resume.id === selectedId ? ' is-selected' : ''}`}>
              {editing === resume.id ? (
                <form
                  className="resume-version-edit"
                  aria-label="Rename resume"
                  onSubmit={(event) => {
                    event.preventDefault();
                    void saveEdit(resume.id);
                  }}
                >
                  <label className="field">
                    Title
                    <input type="text" value={title} maxLength={100} required onChange={(e) => setTitle(e.target.value)} />
                  </label>
                  <label className="field">
                    Version label
                    <input type="text" value={label} maxLength={50} placeholder="e.g. v2" onChange={(e) => setLabel(e.target.value)} />
                  </label>
                  <div className="row" style={{ gap: 8 }}>
                    <button type="submit" className="small">
                      Save
                    </button>
                    <button type="button" className="small ghost" onClick={() => setEditing(null)}>
                      Cancel
                    </button>
                  </div>
                </form>
              ) : (
                <>
                  <div className="resume-version-main">
                    <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                      <strong>{resume.title ?? resume.fileName}</strong>
                      {resume.versionLabel && <Badge>{resume.versionLabel}</Badge>}
                      {resume.isDefault && <Badge tone="brand">Default</Badge>}
                      {resume.id === selectedId && <Badge tone="success">In use</Badge>}
                      {resume.status !== 'COMPLETED' && <Badge tone={resume.status === 'FAILED' ? 'danger' : 'warning'}>{resume.status}</Badge>}
                    </div>
                    <p className="muted small" style={{ margin: '2px 0 0' }}>
                      {resume.fileName} · uploaded {formatDay(resume.uploadedAt)} · {resume.skills.length} skills
                    </p>
                  </div>
                  <div className="resume-version-actions">
                    <button type="button" className="small" disabled={resume.id === selectedId} onClick={() => onSelect(resume)}>
                      Use
                    </button>
                    {!resume.isDefault && (
                      <button type="button" className="small ghost" onClick={() => makeDefault(resume.id)}>
                        Set default
                      </button>
                    )}
                    <button type="button" className="small ghost" onClick={() => startEdit(resume)}>
                      Rename
                    </button>
                    <button type="button" className="small ghost" onClick={() => remove(resume)}>
                      Delete
                    </button>
                  </div>
                </>
              )}
            </li>
          ))}
        </ul>
      )}

      {completed.length >= 2 && (
        <div className="resume-compare">
          <h3 className="resume-compare-title">Compare two versions</h3>
          <div className="row" style={{ gap: 8, flexWrap: 'wrap', alignItems: 'flex-end' }}>
            <label className="field">
              From
              <select value={compareA} onChange={(e) => setCompareA(e.target.value)}>
                <option value="">Choose a resume</option>
                {completed.map((resume) => (
                  <option key={resume.id} value={resume.id}>
                    {versionName(resume)}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              To
              <select value={compareB} onChange={(e) => setCompareB(e.target.value)}>
                <option value="">Choose a resume</option>
                {completed.map((resume) => (
                  <option key={resume.id} value={resume.id}>
                    {versionName(resume)}
                  </option>
                ))}
              </select>
            </label>
            <button type="button" disabled={!compareA || !compareB || compareA === compareB || comparing} onClick={compare}>
              {comparing ? 'Comparing…' : 'Compare'}
            </button>
          </div>
          {comparison && <ComparisonResult comparison={comparison} />}
        </div>
      )}
    </Card>
  );
}

const FIELD_LABEL: Record<string, string> = {
  title: 'Title',
  versionLabel: 'Version label',
  fileName: 'File name',
  isDefault: 'Default',
  skillCount: 'Skills found',
  uploadedAt: 'Uploaded',
};

function ComparisonResult({ comparison }: { comparison: ResumeComparison }) {
  const { first, second } = comparison;
  const value = (version: typeof first, field: string): string => {
    switch (field) {
      case 'isDefault':
        return version.isDefault ? 'Yes' : 'No';
      case 'uploadedAt':
        return formatDay(version.uploadedAt);
      case 'versionLabel':
        return version.versionLabel ?? '—';
      default:
        return String(version[field as keyof typeof version] ?? '—');
    }
  };

  return (
    <div className="resume-comparison" aria-label="Comparison result">
      <p className="muted small">
        From <strong>{versionName(first)}</strong> to <strong>{versionName(second)}</strong>
      </p>
      <div className="gap-columns">
        <div className="gap-column">
          <h3>Added ({comparison.skillsAdded.length})</h3>
          <SkillChips skills={comparison.skillsAdded} empty="No new skills." label="Skills added" />
        </div>
        <div className="gap-column">
          <h3>Removed ({comparison.skillsRemoved.length})</h3>
          <SkillChips skills={comparison.skillsRemoved} empty="Nothing removed." label="Skills removed" />
        </div>
        <div className="gap-column">
          <h3>In both ({comparison.commonSkills.length})</h3>
          <SkillChips skills={comparison.commonSkills} empty="No skills in common." label="Common skills" />
        </div>
      </div>
      {comparison.differentFields.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Metadata differences</caption>
            <thead>
              <tr>
                <th scope="col">Field</th>
                <th scope="col">From</th>
                <th scope="col">To</th>
              </tr>
            </thead>
            <tbody>
              {comparison.differentFields.map((field) => (
                <tr key={field}>
                  <td>{FIELD_LABEL[field] ?? field}</td>
                  <td>{value(first, field)}</td>
                  <td>{value(second, field)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/**
 * V7.3: the job-specific analysis of one resume, fetched on request. Builds on the V3 match
 * shown above it; adds the posting's experience and suggestions drawn only from the data.
 */
export function JobAnalysisPanel({ resumeId, jobId }: { resumeId: string; jobId: number }) {
  const [analysis, setAnalysis] = useState<ResumeJobAnalysis | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setAnalysis(await api.analyzeResumeJob(resumeId, jobId));
    } catch (failure) {
      setError(messageOf(failure));
    } finally {
      setLoading(false);
    }
  };

  const total = analysis ? analysis.matchedSkillCount + analysis.missingSkillCount : 0;

  return (
    <Card
      title="6. Detailed analysis"
      description="Suggestions drawn only from the skills and experience in your resume and this posting."
      actions={
        !analysis && (
          <button type="button" onClick={load} disabled={loading}>
            {loading ? 'Analyzing…' : 'Show detailed analysis'}
          </button>
        )
      }
    >
      {error && <ErrorState message={error} onRetry={load} />}
      {!analysis && !error && <p className="muted small">See how this resume could be tailored for this job.</p>}
      {analysis && (
        <div className="job-analysis">
          <p>
            <strong>{analysis.resumeTitle}</strong> against <strong>{analysis.jobTitle}</strong> at {analysis.companyName}:{' '}
            {analysis.matchPercentage === undefined
              ? analysis.matchNote
              : `${analysis.matchedSkillCount} of ${analysis.totalJobSkills} required skills found (${analysis.matchPercentage.toFixed(0)}%).`}
          </p>
          {total > 0 && (
            <div className="analysis-split" role="img" aria-label={`${analysis.matchedSkillCount} matched, ${analysis.missingSkillCount} missing`}>
              <div className="analysis-split-matched" style={{ flexGrow: analysis.matchedSkillCount }} />
              <div className="analysis-split-missing" style={{ flexGrow: analysis.missingSkillCount }} />
            </div>
          )}
          <div className="gap-columns">
            <div className="gap-column">
              <h3>Relevant on your resume ({analysis.matchedSkills.length})</h3>
              <SkillChips skills={analysis.matchedSkills} empty="None of the posting's skills." label="Relevant resume skills" />
            </div>
            <div className="gap-column">
              <h3>Required but not on your resume ({analysis.missingSkills.length})</h3>
              <SkillChips skills={analysis.missingSkills} empty="Nothing missing." label="Missing skills" />
            </div>
            <div className="gap-column">
              <h3>Other skills you list ({analysis.otherResumeSkills.length})</h3>
              <SkillChips skills={analysis.otherResumeSkills} empty="None." label="Other resume skills" />
            </div>
          </div>
          <p className="muted small">{analysis.experience.note}</p>
          <h3 className="resume-compare-title">Suggestions</h3>
          <ul className="analysis-suggestions">
            {analysis.suggestions.map((suggestion) => (
              <li key={suggestion}>{suggestion}</li>
            ))}
          </ul>
          <p className="card-description">{analysis.disclaimer}</p>
        </div>
      )}
    </Card>
  );
}
