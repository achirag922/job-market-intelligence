import { useEffect, useState } from 'react';
import { ApiError, api } from '../api/client';
import type {
  InterviewDifficulty,
  InterviewQuestion,
  InterviewReport,
  InterviewSession,
  InterviewType,
  Resume,
  SavedJob,
} from '../api/types';
import { PageGuide } from '../components/guidance';
import { Link } from 'react-router-dom';
import { Badge, Card, EmptyState, ErrorState, PageHeader } from '../components/ui';

const CATEGORY_LABEL: Record<string, string> = {
  TECHNICAL: 'Technical',
  ROLE: 'Role',
  RESUME: 'Resume',
  BEHAVIORAL: 'Behavioral',
};

const TYPE_LABEL: Record<InterviewType, string> = {
  MIXED: 'Mixed',
  TECHNICAL: 'Technical',
  BEHAVIORAL: 'Behavioral',
};

const DIFFICULTY_LABEL: Record<InterviewDifficulty, string> = {
  EASY: 'Easy',
  MEDIUM: 'Medium',
  HARD: 'Hard',
};

const QUESTION_COUNTS = [3, 4, 5, 6, 7, 8, 9, 10];

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

function formatDay(iso?: string): string {
  return iso ? new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : '';
}

function score(value?: number): string {
  return value === undefined ? 'n/a' : `${value.toFixed(1)}/5`;
}

/**
 * V8.7: practice interviews for a job the user saved. Questions come from the job and the
 * user's resume; answers are scored by the server's AI provider, and sessions are private.
 * V9.6: a mock interview with a type, difficulty and question count, one question at a time,
 * skipping, ending early and a final report linked to the learning plan.
 */
export function InterviewPrep() {
  const [jobs, setJobs] = useState<SavedJob[] | null>(null);
  const [resumes, setResumes] = useState<Resume[]>([]);
  const [jobId, setJobId] = useState('');
  const [resumeId, setResumeId] = useState('');
  const [interviewType, setInterviewType] = useState<InterviewType>('MIXED');
  const [difficulty, setDifficulty] = useState<InterviewDifficulty>('MEDIUM');
  const [questionCount, setQuestionCount] = useState('');
  const [sessions, setSessions] = useState<InterviewSession[] | null>(null);
  const [session, setSession] = useState<InterviewSession | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const loadHistory = () => api.interviewSessions().then(setSessions, (cause: unknown) => setError(messageOf(cause)));

  useEffect(() => {
    api.savedJobs().then(setJobs, () => setJobs([]));
    api.resumes().then((list) => setResumes(list.filter((resume) => resume.status === 'COMPLETED')), () => setResumes([]));
    void loadHistory();
  }, []);

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(false);
    }
  };

  const start = () =>
    run(async () => {
      setSession(await api.startInterview({
        jobId: Number(jobId),
        resumeId: resumeId || undefined,
        interviewType,
        difficulty,
        questionCount: questionCount ? Number(questionCount) : undefined,
      }));
      await loadHistory();
    });

  const open = (id: string) => run(async () => setSession(await api.interviewSession(id)));

  return (
    <>
      <PageHeader
        title="Interview Preparation"
        description="Run a mock interview for a job you saved: one question at a time, with feedback on each answer and a final report."
      />
      <PageGuide id="interview-prep" title="Practise for a real job" helpAnchor="growth">
        Choose a saved job to generate questions for it, answer them, and get scored feedback with a suggested approach.
      </PageGuide>

      <Card title="Start a mock interview" description="Questions use only the job's and your resume's data. Nothing is invented about you.">
        {jobs === null ? (
          <p className="muted small">Loading your saved jobs…</p>
        ) : jobs.length === 0 ? (
          <EmptyState title="No saved jobs" message="Save a job from the Job Explorer to prepare for it here."
            action={<Link to="/jobs">Browse jobs</Link>} />
        ) : (
          <div className="row" style={{ gap: 8, flexWrap: 'wrap', alignItems: 'flex-end' }}>
            <label className="field" style={{ flex: '1 1 260px' }}>
              Job
              <select value={jobId} onChange={(event) => setJobId(event.target.value)}>
                <option value="">Choose a job</option>
                {jobs.map((saved) => (
                  <option key={saved.id} value={saved.job.id}>
                    {saved.job.title} · {saved.job.company.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="field" style={{ flex: '1 1 200px' }}>
              Resume
              <select value={resumeId} onChange={(event) => setResumeId(event.target.value)}>
                <option value="">Your current resume</option>
                {resumes.map((resume) => (
                  <option key={resume.id} value={resume.id}>
                    {resume.title ?? resume.fileName}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              Interview type
              <select value={interviewType} onChange={(event) => setInterviewType(event.target.value as InterviewType)}>
                {(Object.keys(TYPE_LABEL) as InterviewType[]).map((type) => (
                  <option key={type} value={type}>{TYPE_LABEL[type]}</option>
                ))}
              </select>
            </label>
            <label className="field">
              Difficulty
              <select value={difficulty} onChange={(event) => setDifficulty(event.target.value as InterviewDifficulty)}>
                {(Object.keys(DIFFICULTY_LABEL) as InterviewDifficulty[]).map((level) => (
                  <option key={level} value={level}>{DIFFICULTY_LABEL[level]}</option>
                ))}
              </select>
            </label>
            <label className="field">
              Questions
              <select value={questionCount} onChange={(event) => setQuestionCount(event.target.value)}>
                <option value="">Default</option>
                {QUESTION_COUNTS.map((count) => (
                  <option key={count} value={count}>{count}</option>
                ))}
              </select>
            </label>
            <button type="button" disabled={!jobId || busy} onClick={start}>
              {busy ? 'Starting…' : 'Start interview'}
            </button>
          </div>
        )}
        {error && <p className="status status-error" role="alert">{error}</p>}
      </Card>

      {session && <SessionPanel key={session.id + session.status} session={session}
        onChange={(next) => { setSession(next); void loadHistory(); }} />}

      <Card title="Interview history" description="Newest first. Only you can see these.">
        {sessions === null ? (
          <p className="muted small">Loading…</p>
        ) : sessions.length === 0 ? (
          <p className="muted small">No sessions yet.</p>
        ) : (
          <ul className="alert-list">
            {sessions.map((item) => (
              <li key={item.id} className="alert-item">
                <div className="alert-item-main">
                  <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                    <strong>{item.jobTitle}</strong>
                    <span className="muted">{item.companyName}</span>
                    {item.interviewType && <Badge>{TYPE_LABEL[item.interviewType]}</Badge>}
                    {item.difficulty && <Badge>{DIFFICULTY_LABEL[item.difficulty]}</Badge>}
                    <Badge tone={item.status === 'COMPLETED' ? 'success' : 'brand'}>
                      {item.status === 'COMPLETED' ? 'Completed' : 'In progress'}
                    </Badge>
                  </div>
                  <p className="muted small" style={{ margin: '4px 0 0' }}>
                    {formatDay(item.createdAt)} · {item.answered} of {item.total} answered
                    {item.averageScore !== undefined && ` · average ${item.averageScore}/5`}
                  </p>
                  {item.summary && <p className="small" style={{ margin: '4px 0 0' }}>{item.summary}</p>}
                </div>
                <div className="alert-item-actions">
                  <button type="button" className="small ghost" disabled={busy} onClick={() => open(item.id)}>
                    {item.status === 'COMPLETED' ? 'Review' : 'Continue'}
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </>
  );
}

function SessionPanel({ session, onChange }: { session: InterviewSession; onChange: (next: InterviewSession) => void }) {
  const questions = session.questions ?? [];
  const firstOpen = questions.findIndex((q) => q.feedbackStatus === 'NOT_ANSWERED');
  const [index, setIndex] = useState(firstOpen < 0 ? 0 : firstOpen);
  const [current, setCurrent] = useState<InterviewQuestion[]>(questions);
  const [draft, setDraft] = useState(questions[firstOpen < 0 ? 0 : firstOpen]?.answer ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const completed = session.status === 'COMPLETED';
  const question = current[index];

  if (!question) {
    return null;
  }

  const go = (next: number) => {
    setIndex(next);
    setDraft(current[next]?.answer ?? '');
    setError(null);
  };

  const replace = (updated: InterviewQuestion) =>
    setCurrent((all) => all.map((q) => (q.position === updated.position ? updated : q)));

  const act = async (action: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(false);
    }
  };

  const submit = () => act(async () => replace(await api.answerInterviewQuestion(session.id, question.position, draft)));
  const retry = () => act(async () => replace(await api.evaluateInterviewQuestion(session.id, question.position)));
  const skip = () => act(async () => {
    replace(await api.skipInterviewQuestion(session.id, question.position));
    if (index < current.length - 1) go(index + 1);
  });
  const finish = () => act(async () => onChange(await api.completeInterview(session.id)));
  const answered = current.filter((q) => q.answer).length;
  const skipped = current.filter((q) => q.feedbackStatus === 'SKIPPED').length;

  return (
    <Card
      title={`${session.jobTitle} · ${session.companyName}`}
      description={completed ? 'Completed interview. Review the report, your answers and feedback.'
        : 'Answer each question, read the feedback, then move on. You can skip a question or end the interview at any time.'}
      actions={!completed && (
        <button type="button" className="small" disabled={busy} onClick={finish}>
          End interview
        </button>
      )}
    >
      <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
        {session.interviewType && <Badge>{TYPE_LABEL[session.interviewType]}</Badge>}
        {session.difficulty && <Badge>{DIFFICULTY_LABEL[session.difficulty]}</Badge>}
      </div>
      {session.summary && (
        <p className="status" role="status">
          {session.summary}
        </p>
      )}
      {session.report && <ReportView report={session.report} />}
      <div className="row" style={{ gap: 8, alignItems: 'center' }}>
        <span className="small">Question {index + 1} of {current.length} · {answered} answered</span>
        {skipped > 0 && <span className="muted small">{skipped} skipped</span>}
        <div
          className="match-meter"
          style={{ flex: 1 }}
          role="progressbar"
          aria-label="Answered questions"
          aria-valuenow={answered}
          aria-valuemin={0}
          aria-valuemax={current.length}
        >
          <div className="match-meter-fill" style={{ width: `${(answered * 100) / current.length}%` }} />
        </div>
      </div>

      <div className="stack" style={{ gap: 8, marginTop: 12 }}>
        <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
          <Badge tone="brand">{CATEGORY_LABEL[question.category] ?? question.category}</Badge>
          {question.focus && <span className="muted small">About: {question.focus}</span>}
          {question.feedbackStatus === 'SKIPPED' && <Badge tone="warning">Skipped</Badge>}
        </div>
        <p style={{ margin: 0 }}><strong>{question.question}</strong></p>
        {completed ? (
          <p className="small">{question.answer ?? <span className="muted">Not answered.</span>}</p>
        ) : (
          <label className="field">
            Your answer
            <textarea rows={5} maxLength={4000} value={draft} disabled={busy} onChange={(event) => setDraft(event.target.value)}
              placeholder="Answer as you would in the interview, using your own experience." />
          </label>
        )}
        {!completed && (
          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <button type="button" disabled={busy || !draft.trim()} onClick={submit}>
              {busy ? 'Evaluating…' : 'Submit answer'}
            </button>
            {!question.answer && question.feedbackStatus !== 'SKIPPED' && (
              <button type="button" className="small ghost" disabled={busy} onClick={skip}>
                Skip question
              </button>
            )}
            {question.feedbackStatus === 'UNAVAILABLE' && question.answer && (
              <button type="button" className="small ghost" disabled={busy} onClick={retry}>
                Try feedback again
              </button>
            )}
          </div>
        )}
        {error && <ErrorState message={error} />}
        {question.feedbackNote && <p className="muted small">{question.feedbackNote}</p>}
        {question.feedback && <FeedbackView question={question} />}
      </div>

      <div className="row" style={{ gap: 8, marginTop: 12 }}>
        <button type="button" className="small ghost" disabled={index === 0} onClick={() => go(index - 1)}>
          Previous
        </button>
        <button type="button" className="small" disabled={index >= current.length - 1} onClick={() => go(index + 1)}>
          Next question
        </button>
      </div>
    </Card>
  );
}

function ReportView({ report }: { report: InterviewReport }) {
  const [added, setAdded] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const plan = async (skillId: number, skill: string) => {
    setError(null);
    try {
      await api.createLearningItem({ skillId, topic: `Interview preparation: ${skill}` });
      setAdded((list) => [...list, skill]);
    } catch (cause) {
      setError(messageOf(cause));
    }
  };
  const list = (items: string[]) => (items.length === 0 ? <span className="muted">None</span> : items.join(', '));
  return (
    <section aria-label="Interview report" className="stack" style={{ gap: 6, marginBottom: 12 }}>
      <p style={{ margin: 0 }}>
        <strong>Overall {score(report.overallScore)}</strong>{' '}
        <span className="muted small">Technical {score(report.technicalScore)} · Behavioral {score(report.behavioralScore)}</span>
      </p>
      <p className="small" style={{ margin: 0 }}>Strong areas: {list(report.strongAreas)}</p>
      <p className="small" style={{ margin: 0 }}>Weak areas: {list(report.weakAreas)}</p>
      <p className="small" style={{ margin: 0 }}>Prepare more: {list(report.prepareTopics)}</p>
      {report.learning.length > 0 && (
        <ul className="small" aria-label="Recommended learning">
          {report.learning.map((item) => (
            <li key={item.skill}>
              <strong>{item.skill}</strong>{' '}
              {item.source === 'PLAN_ITEM' ? (
                <span className="muted">in your <a href="/learning">learning plan</a>{item.status ? ` (${item.status.replace('_', ' ').toLowerCase()})` : ''}</span>
              ) : added.includes(item.skill) ? (
                <span className="muted">added to your <a href="/learning">learning plan</a></span>
              ) : (
                <>
                  <span className="muted">{item.reason}</span>{' '}
                  {item.skillId !== undefined && (
                    <button type="button" className="small ghost" onClick={() => plan(item.skillId!, item.skill)}>
                      Add {item.skill} to learning plan
                    </button>
                  )}
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {error && <p className="status status-error" role="alert">{error}</p>}
    </section>
  );
}

function FeedbackView({ question }: { question: InterviewQuestion }) {
  const feedback = question.feedback!;
  const scores = [
    ['Relevance', feedback.relevance],
    ['Completeness', feedback.completeness],
    ['Clarity', feedback.clarity],
    ...(feedback.technicalCorrectness !== undefined ? [['Technical correctness', feedback.technicalCorrectness] as const] : []),
    ...(feedback.communication !== undefined ? [['Communication', feedback.communication] as const] : []),
  ] as const;
  return (
    <section aria-label="Feedback" className="stack" style={{ gap: 6 }}>
      <p className="small" style={{ margin: 0 }}>
        <strong>Score {feedback.score.toFixed(1)}/5</strong>{' '}
        <span className="muted">{scores.map(([label, value]) => `${label} ${value}/5`).join(' · ')}</span>
      </p>
      <div className="gap-columns">
        <div className="gap-column">
          <h4 className="small" style={{ margin: 0 }}>Strengths</h4>
          <ul className="small">{feedback.strengths.map((item) => <li key={item}>{item}</li>)}</ul>
        </div>
        <div className="gap-column">
          <h4 className="small" style={{ margin: 0 }}>To improve</h4>
          <ul className="small">{feedback.improvements.map((item) => <li key={item}>{item}</li>)}</ul>
        </div>
      </div>
      {feedback.suggestedApproach && (
        <p className="small" style={{ margin: 0 }}>
          <strong>A better approach:</strong> {feedback.suggestedApproach}
        </p>
      )}
    </section>
  );
}
