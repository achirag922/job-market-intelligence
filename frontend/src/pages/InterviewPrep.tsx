import { useEffect, useState } from 'react';
import { ApiError, api } from '../api/client';
import type { InterviewQuestion, InterviewSession, SavedJob } from '../api/types';
import { Badge, Card, EmptyState, ErrorState, PageHeader } from '../components/ui';

const CATEGORY_LABEL: Record<string, string> = {
  TECHNICAL: 'Technical',
  ROLE: 'Role',
  RESUME: 'Resume',
  BEHAVIORAL: 'Behavioral',
};

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

function formatDay(iso?: string): string {
  return iso ? new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : '';
}

/**
 * V8.7: practice interviews for a job the user saved. Questions come from the job and the
 * user's resume; answers are scored by the server's AI provider, and sessions are private.
 */
export function InterviewPrep() {
  const [jobs, setJobs] = useState<SavedJob[] | null>(null);
  const [jobId, setJobId] = useState('');
  const [sessions, setSessions] = useState<InterviewSession[] | null>(null);
  const [session, setSession] = useState<InterviewSession | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const loadHistory = () => api.interviewSessions().then(setSessions, (cause: unknown) => setError(messageOf(cause)));

  useEffect(() => {
    api.savedJobs().then(setJobs, () => setJobs([]));
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
      setSession(await api.startInterview(Number(jobId)));
      await loadHistory();
    });

  const open = (id: string) => run(async () => setSession(await api.interviewSession(id)));

  return (
    <>
      <PageHeader
        title="Interview Preparation"
        description="Practise with questions drawn from a job you saved and your own resume, and get feedback on each answer."
      />

      <Card title="Start a practice interview" description="Choose one of your saved jobs. Questions use only the job's and your resume's data.">
        {jobs === null ? (
          <p className="muted small">Loading your saved jobs…</p>
        ) : jobs.length === 0 ? (
          <EmptyState title="No saved jobs" message="Save a job from the Job Explorer to prepare for it here." />
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
            <button type="button" disabled={!jobId || busy} onClick={start}>
              {busy ? 'Starting…' : 'Start interview'}
            </button>
          </div>
        )}
        {error && <p className="status status-error" role="alert">{error}</p>}
      </Card>

      {session && <SessionPanel key={session.id} session={session} onChange={(next) => { setSession(next); void loadHistory(); }} />}

      <Card title="Previous sessions" description="Newest first. Only you can see these.">
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
                    <Badge tone={item.status === 'COMPLETED' ? 'success' : 'brand'}>
                      {item.status === 'COMPLETED' ? 'Completed' : 'In progress'}
                    </Badge>
                  </div>
                  <p className="muted small" style={{ margin: '4px 0 0' }}>
                    {formatDay(item.createdAt)} · {item.answered} of {item.total} answered
                    {item.averageScore !== undefined && ` · average ${item.averageScore}/5`}
                  </p>
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
  const finish = () => act(async () => onChange(await api.completeInterview(session.id)));
  const answered = current.filter((q) => q.answer).length;

  return (
    <Card
      title={`${session.jobTitle} · ${session.companyName}`}
      description={completed ? 'Completed session. Review your answers and feedback.' : 'Answer each question, then read the feedback before moving on.'}
      actions={!completed && (
        <button type="button" className="small" disabled={busy || answered === 0} onClick={finish}>
          Finish session
        </button>
      )}
    >
      {session.summary && (
        <p className="status" role="status">
          {session.summary}
        </p>
      )}
      <div className="row" style={{ gap: 8, alignItems: 'center' }}>
        <span className="small">Question {index + 1} of {current.length} · {answered} answered</span>
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

function FeedbackView({ question }: { question: InterviewQuestion }) {
  const feedback = question.feedback!;
  const scores = [
    ['Relevance', feedback.relevance],
    ['Completeness', feedback.completeness],
    ['Clarity', feedback.clarity],
    ...(feedback.technicalCorrectness !== undefined ? [['Technical correctness', feedback.technicalCorrectness] as const] : []),
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
    </section>
  );
}
