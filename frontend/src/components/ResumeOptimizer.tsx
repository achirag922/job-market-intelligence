import { useEffect, useState } from 'react';
import { ApiError, api } from '../api/client';
import type { Resume, ResumeJobComparison, ResumeKeyword, ResumeOptimization } from '../api/types';
import { MatchBreakdownList } from './MatchBreakdown';
import { Badge, Card, ErrorState, SkillBadge, StatCard } from './ui';

const AREA_LABEL: Record<string, string> = {
  SKILLS: 'Skills',
  KEYWORDS: 'Keywords',
  SECTIONS: 'Sections',
  EXPERIENCE: 'Experience',
  ALIGNMENT: 'Alignment',
};

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

function percent(value?: number): string | null {
  return value === undefined ? null : `${value.toFixed(0)}%`;
}

function signed(value?: number): string {
  return value === undefined ? 'n/a' : `${value > 0 ? '+' : ''}${value.toFixed(1)} points`;
}

/**
 * V8.6: optimise one of the user's resumes for one job. Everything shown comes from the resume
 * and the posting; nothing is written into the resume.
 */
export function ResumeOptimizer({ resumeId, jobId }: { resumeId: string; jobId: number }) {
  const [result, setResult] = useState<ResumeOptimization | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setResult(await api.optimizeResume(resumeId, jobId));
    } catch (failure) {
      setError(messageOf(failure));
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card
      title="7. Optimize resume"
      description="Keywords, sections and suggestions for this job, from your resume and the posting only. JMIP never edits your resume."
      actions={
        !result && (
          <button type="button" onClick={load} disabled={loading}>
            {loading ? 'Optimizing…' : 'Optimize for this job'}
          </button>
        )
      }
    >
      {error && <ErrorState message={error} onRetry={load} />}
      {!result && !error && <p className="muted small">See which of the posting's terms your resume uses and what to improve.</p>}
      {result && <OptimizationBody result={result} resumeId={resumeId} jobId={jobId} />}
    </Card>
  );
}

function OptimizationBody({ result, resumeId, jobId }: { result: ResumeOptimization; resumeId: string; jobId: number }) {
  return (
    <div className="stack" style={{ gap: 16 }}>
      <div className="stat-grid">
        <StatCard label="Overall match" value={percent(result.overallMatchPercentage)} hint="Skills plus your preferences" />
        <StatCard label="Skill match" value={percent(result.skillMatchPercentage)} hint={`${result.matchedSkills.length} of ${result.matchedSkills.length + result.missingSkills.length} skills`} />
        <StatCard label="Posting terms used" value={result.presentKeywords ? `${result.presentKeywords.length} of ${result.presentKeywords.length + (result.missingKeywords?.length ?? 0)}` : null} hint={result.keywordNote ? 'Unavailable' : 'From the title and description'} />
      </div>

      {result.breakdown && <MatchBreakdownList breakdown={result.breakdown} />}
      <p className="muted small">{result.experienceGap}</p>

      <section aria-label="Skills comparison" className="gap-columns">
        <div className="gap-column">
          <h3 className="small">Matched skills ({result.matchedSkills.length})</h3>
          <ul className="skill-list">
            {result.matchedSkills.length === 0 ? <li className="muted">None</li> : result.matchedSkills.map((skill) => <SkillBadge key={skill.id} name={skill.name} state="matched" />)}
          </ul>
        </div>
        <div className="gap-column">
          <h3 className="small">Missing skills ({result.missingSkills.length})</h3>
          <ul className="skill-list">
            {result.missingSkills.length === 0 ? <li className="muted">None</li> : result.missingSkills.map((skill) => <SkillBadge key={skill.id} name={skill.name} state="missing" />)}
          </ul>
        </div>
      </section>

      <section aria-label="Keyword analysis">
        <h3 className="small">Keyword analysis</h3>
        {result.keywordNote ? (
          <p className="muted small">{result.keywordNote}</p>
        ) : (
          <div className="gap-columns">
            <KeywordList title="In your resume" keywords={result.presentKeywords ?? []} empty="None of the posting's terms." tone="success" />
            <KeywordList title="Not in your resume" keywords={result.missingKeywords ?? []} empty="Your resume uses all of them." tone="warning" />
            {(result.overusedKeywords?.length ?? 0) > 0 && (
              <KeywordList title="Repeated heavily" keywords={result.overusedKeywords ?? []} empty="" tone="danger" />
            )}
          </div>
        )}
        {result.sectionsFound && (
          <p className="muted small">
            Sections found: {result.sectionsFound.length ? result.sectionsFound.join(', ') : 'none recognised'}
            {result.sectionsMissing && result.sectionsMissing.length > 0 && ` · Not found: ${result.sectionsMissing.join(', ')}`}
          </p>
        )}
      </section>

      <section aria-label="Improvement suggestions">
        <h3 className="small">Improvement suggestions</h3>
        <ul className="analysis-suggestions">
          {result.suggestions.map((suggestion) => (
            <li key={suggestion.text}>
              <Badge tone="brand">{AREA_LABEL[suggestion.area] ?? suggestion.area}</Badge> {suggestion.text}
            </li>
          ))}
        </ul>
        <p className="card-description">{result.disclaimer}</p>
      </section>

      <VersionComparison resumeId={resumeId} jobId={jobId} />
    </div>
  );
}

function KeywordList({ title, keywords, empty, tone }: { title: string; keywords: ResumeKeyword[]; empty: string; tone: 'success' | 'warning' | 'danger' }) {
  return (
    <div className="gap-column">
      <h4 className="small" style={{ margin: '0 0 4px' }}>{title} ({keywords.length})</h4>
      {keywords.length === 0 ? (
        <p className="muted small">{empty}</p>
      ) : (
        <ul className="skill-list" aria-label={title}>
          {keywords.map((keyword) => (
            <li key={keyword.term}>
              <Badge tone={tone}>
                {keyword.term} · job {keyword.jobMentions}× · resume {keyword.resumeMentions}×
              </Badge>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** Another processed version of the user's resume against the same job. */
function VersionComparison({ resumeId, jobId }: { resumeId: string; jobId: number }) {
  const [versions, setVersions] = useState<Resume[]>([]);
  const [other, setOther] = useState('');
  const [comparison, setComparison] = useState<ResumeJobComparison | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    api.resumes().then(
      (all) => active && setVersions(all.filter((resume) => resume.id !== resumeId && resume.status === 'COMPLETED')),
      () => undefined,
    );
    return () => {
      active = false;
    };
  }, [resumeId]);

  if (versions.length === 0) {
    return <p className="muted small">Upload another version of your resume to compare how each matches this job.</p>;
  }

  const compare = async () => {
    setError(null);
    try {
      setComparison(await api.compareResumesForJob(resumeId, other, jobId));
    } catch (failure) {
      setError(messageOf(failure));
    }
  };

  return (
    <section aria-label="Resume version comparison">
      <h3 className="small">Compare with another version</h3>
      <div className="row" style={{ gap: 8, flexWrap: 'wrap', alignItems: 'flex-end' }}>
        <label className="field">
          Version
          <select value={other} onChange={(event) => setOther(event.target.value)}>
            <option value="">Choose a version</option>
            {versions.map((version) => (
              <option key={version.id} value={version.id}>
                {version.title ?? version.fileName}{version.versionLabel ? ` (${version.versionLabel})` : ''}
              </option>
            ))}
          </select>
        </label>
        <button type="button" className="small" disabled={!other} onClick={compare}>
          Compare for this job
        </button>
      </div>
      {error && <p className="status status-error" role="alert">{error}</p>}
      {comparison && (
        <div className="stack" style={{ gap: 6, marginTop: 8 }}>
          <p className="small" style={{ margin: 0 }}>
            Overall match {percent(comparison.first.overallMatchPercentage) ?? 'n/a'} → {percent(comparison.second.overallMatchPercentage) ?? 'n/a'} ({signed(comparison.overallChange)}) ·
            Skill match {percent(comparison.first.skillMatchPercentage) ?? 'n/a'} → {percent(comparison.second.skillMatchPercentage) ?? 'n/a'} ({signed(comparison.skillChange)})
          </p>
          <p className="small" style={{ margin: 0 }}>
            Skills added: {comparison.versions.skillsAdded.map((skill) => skill.name).join(', ') || 'none'} · Skills removed:{' '}
            {comparison.versions.skillsRemoved.map((skill) => skill.name).join(', ') || 'none'}
          </p>
          {comparison.keywordsGained && (
            <p className="small" style={{ margin: 0 }}>
              Posting terms gained: {comparison.keywordsGained.join(', ') || 'none'} · lost: {comparison.keywordsLost?.join(', ') || 'none'}
            </p>
          )}
        </div>
      )}
    </section>
  );
}
