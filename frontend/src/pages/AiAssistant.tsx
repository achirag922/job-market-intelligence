import { useEffect, useRef, useState } from 'react';
import { ApiError, api } from '../api/client';
import type { AssistantResponse, ConversationContext, Resume } from '../api/types';
import { AssistantVisualization } from '../components/AssistantVisualization';
import { Badge, Card, EmptyState, PageHeader } from '../components/ui';
import { IconChat, IconFile, IconSend } from '../components/icons';

/** Starting points, so the first question does not have to be invented from nothing. */
/** V7.6: questions about the signed-in user's own resume, goals and applications. */
const CAREER_EXAMPLES = [
  'What skills am I missing for my target role?',
  'Which jobs match my resume?',
  'What skills should I focus on next?',
  'How is demand for my target role changing?',
  'Show me my application progress.',
  'Which saved jobs should I prioritize?',
  'What should I improve in my resume?',
];

const EXAMPLES = [
  'What are the top skills for Backend Developer jobs?',
  'Which companies are hiring the most Data Engineers?',
  'Which cities have the most Java jobs?',
  'How has Kubernetes demand changed over time?',
  'Compare Backend Developer and Data Engineer demand.',
];

interface Turn {
  id: number;
  question: string;
  /** Absent while the answer is still being fetched. */
  response?: AssistantResponse;
  error?: string;
}

/**
 * A conversation with the dataset.
 *
 * <p>Each answer shows the prose and the rows behind it together. That pairing is the
 * point: the sentence is a model's description, the table underneath is what the database
 * returned, and a reader can check one against the other without taking either on trust.
 *
 * <p>History lives in this component for the length of the visit. There is no server-side
 * session and nothing is persisted — reloading the page starts a new conversation.
 */
export function AiAssistant() {
  const [question, setQuestion] = useState('');
  const [turns, setTurns] = useState<Turn[]>([]);
  const [pending, setPending] = useState(false);
  const [context, setContext] = useState<ConversationContext | undefined>();
  const [resume, setResume] = useState<Resume | null>(null);
  const [resumeError, setResumeError] = useState<string | null>(null);
  // V7.6: a job handed over from Job Details (?jobId=), for "how does my resume compare with this job?".
  const [jobId, setJobId] = useState<number | undefined>(() => {
    const value = Number(new URLSearchParams(window.location.search).get('jobId'));
    return Number.isInteger(value) && value > 0 ? value : undefined;
  });
  const nextId = useRef(1);
  const logEnd = useRef<HTMLDivElement>(null);

  // A new answer that appears below the fold reads as nothing having happened.
  useEffect(() => {
    logEnd.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [turns]);

  const submit = async (asked: string) => {
    const trimmed = asked.trim();
    if (!trimmed || pending) {
      return;
    }

    const id = nextId.current++;
    setTurns((previous) => [...previous, { id, question: trimmed }]);
    setQuestion('');
    setPending(true);

    try {
      const response = await api.askAssistant({
        question: trimmed,
        resumeId: resume?.id,
        jobId,
        context,
      });
      setTurns((previous) =>
        previous.map((turn) => (turn.id === id ? { ...turn, response } : turn)),
      );
      // Only a turn the assistant understood is worth remembering; carrying a rejected
      // one forward would attach the next question to a filter that never applied.
      if (response.grounded && response.context) {
        setContext(response.context);
      }
    } catch (error) {
      const message = error instanceof ApiError ? error.message : 'Something went wrong.';
      setTurns((previous) =>
        previous.map((turn) => (turn.id === id ? { ...turn, error: message } : turn)),
      );
    } finally {
      setPending(false);
    }
  };

  const handleResume = async (file: File) => {
    setResumeError(null);
    try {
      setResume(await api.uploadResume(file));
    } catch (error) {
      setResumeError(error instanceof ApiError ? error.message : 'Upload failed');
    }
  };

  return (
    <>
      <PageHeader
        title="AI Job Market Assistant"
        description="Ask about your own resume, career goals and saved jobs, or about skills, companies, locations, salaries and trends. Every answer is built from your data or the dataset, and the rows behind it are shown so you can check them. It never predicts whether you will be hired."
        actions={
          (turns.length > 0 || context) && (
            <button
              type="button"
              onClick={() => {
                setTurns([]);
                setContext(undefined);
              }}
            >
              New conversation
            </button>
          )
        }
      />

      <div className="chat">
        <div className="chat-log" role="log" aria-live="polite" aria-label="Conversation">
          {turns.length === 0 ? (
            <EmptyState
              title="Ask a question to begin"
              message="Try one of the suggestions below, or ask about your own resume, goals and saved jobs, or about the job market."
            />
          ) : (
            turns.map((turn) => <TurnView key={turn.id} turn={turn} />)
          )}
          <div ref={logEnd} />
        </div>

        {/* Sticky, so the composer is reachable without scrolling to the end of a long
            conversation. Suggestions stay available throughout: they double as a reminder
            of what kinds of question this can answer. */}
        <div className="chat-composer-wrap">
          {jobId !== undefined && (
            <p className="status" style={{ margin: 0 }}>
              <Badge tone="brand">Asking about job #{jobId}</Badge>{' '}
              <button type="button" className="small ghost" onClick={() => setJobId(undefined)}>
                Stop using this job
              </button>
            </p>
          )}
          <p className="chat-suggestions-label muted small">About you</p>
          <ul className="chat-suggestions" aria-label="Questions about you">
            {(jobId !== undefined
              ? ['How does my resume compare with this job?', ...CAREER_EXAMPLES]
              : CAREER_EXAMPLES
            ).map((example) => (
              <li key={example}>
                <button
                  type="button"
                  className="skill-tag"
                  disabled={pending}
                  onClick={() => void submit(example)}
                >
                  {example}
                </button>
              </li>
            ))}
          </ul>
          <p className="chat-suggestions-label muted small">About the job market</p>
          <ul className="chat-suggestions" aria-label="Questions about the job market">
            {EXAMPLES.map((example) => (
              <li key={example}>
                <button
                  type="button"
                  className="skill-tag"
                  disabled={pending}
                  onClick={() => void submit(example)}
                >
                  {example}
                </button>
              </li>
            ))}
          </ul>

          <form
            className="chat-composer"
            onSubmit={(event) => {
              event.preventDefault();
              void submit(question);
            }}
          >
            <label className="visually-hidden" htmlFor="assistant-question">
              Your question
            </label>
            <input
              id="assistant-question"
              type="text"
              value={question}
              placeholder="Ask a question…"
              aria-label="Your question"
              maxLength={500}
              onChange={(event) => setQuestion(event.target.value)}
            />
            <button type="submit" disabled={pending || question.trim() === ''}>
              <IconSend size={15} />
              {pending ? 'Thinking…' : 'Send'}
            </button>
          </form>
        </div>
      </div>

      <Card
        title="Ask about your resume"
        description="Questions about your resume use your default resume from Resume Intelligence, or the one you upload here. It is compared against job skills only, and nothing from it is sent anywhere except this application."
        actions={resume ? <Badge tone="success">{resume.skills.length} skills</Badge> : undefined}
      >
        <label className="field">
          <span className="visually-hidden">Resume PDF</span>
          <input
            type="file"
            accept="application/pdf,.pdf"
            aria-label="Resume PDF"
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) {
                void handleResume(file);
              }
            }}
          />
        </label>
        {resume && (
          <p className="status" style={{ paddingBottom: 0 }}>
            <IconFile size={14} /> Using <strong>{resume.fileName}</strong>.
          </p>
        )}
        {resumeError && (
          <p className="status status-error" role="alert">
            {resumeError}
          </p>
        )}
      </Card>
    </>
  );
}

function TurnView({ turn }: { turn: Turn }) {
  return (
    <>
      <div className="chat-turn user">
        <div className="chat-bubble">{turn.question}</div>
      </div>

      <div className="chat-turn assistant">
        <span className="chat-avatar" aria-hidden="true">
          <IconChat size={16} />
        </span>
        <div className="chat-bubble">
          {turn.error && (
            <p className="status status-error" role="alert" style={{ margin: 0 }}>
              {turn.error}
            </p>
          )}

          {!turn.response && !turn.error && (
            <span className="typing" role="status" aria-label="Looking this up in the dataset">
              <span />
              <span />
              <span />
            </span>
          )}

          {turn.response && (
            <>
              <p className="chat-answer">{turn.response.answer}</p>

              {turn.response.note && (
                <p className="card-description" style={{ marginTop: 8 }}>
                  {turn.response.note}
                </p>
              )}

              {turn.response.grounded ? (
                <AssistantVisualization
                  visualization={turn.response.visualization}
                  rows={turn.response.data}
                />
              ) : (
                // Said plainly rather than implied by an empty table: this reply is the
                // assistant describing itself, not a finding about the dataset.
                <p className="card-description" style={{ marginTop: 8, marginBottom: 0 }}>
                  No data was retrieved for this question.
                </p>
              )}
            </>
          )}
        </div>
      </div>
    </>
  );
}
