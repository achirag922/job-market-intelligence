import { useRef, useState } from 'react';
import { ApiError, api } from '../api/client';
import type { AssistantResponse, ConversationContext, Resume } from '../api/types';
import { AssistantVisualization } from '../components/AssistantVisualization';

/** Starting points, so the first question does not have to be invented from nothing. */
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
  const nextId = useRef(1);

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
    <section>
      <h1>Ask the Data</h1>
      <p className="subtitle">
        Ask about skills, categories, companies, locations, salaries or trends in this
        dataset. Every answer is generated from figures read out of the database, and the
        rows behind it are shown so you can check them. The dataset is synthetic
        development data and does not describe the real job market.
      </p>

      <div className="card">
        <form
          className="filters"
          onSubmit={(event) => {
            event.preventDefault();
            void submit(question);
          }}
        >
          <label style={{ flex: 1 }}>
            Your question
            <input
              type="text"
              value={question}
              placeholder="e.g. What are the top skills for Data Engineer jobs?"
              aria-label="Your question"
              maxLength={500}
              onChange={(event) => setQuestion(event.target.value)}
            />
          </label>
          <div className="filter-actions">
            <button type="submit" disabled={pending || question.trim() === ''}>
              {pending ? 'Thinking…' : 'Ask'}
            </button>
            {(turns.length > 0 || context) && (
              <button
                type="button"
                onClick={() => {
                  setTurns([]);
                  setContext(undefined);
                }}
              >
                New conversation
              </button>
            )}
          </div>
        </form>

        {turns.length === 0 && (
          <>
            <p className="subtitle">Try one of these:</p>
            <ul className="skill-list">
              {EXAMPLES.map((example) => (
                <li key={example}>
                  <button type="button" className="skill-tag" onClick={() => void submit(example)}>
                    {example}
                  </button>
                </li>
              ))}
            </ul>
          </>
        )}

        <details>
          <summary>Ask about your resume</summary>
          <p className="subtitle">
            Questions like &ldquo;what skills am I missing for Data Engineer jobs?&rdquo;
            need a resume. It is compared against job skills only, and nothing from it is
            sent anywhere except this application.
          </p>
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
          {resume && (
            <p className="status">
              Using <strong>{resume.fileName}</strong> ({resume.skills.length} skills recognised).
            </p>
          )}
          {resumeError && (
            <p className="status status-error" role="alert">
              {resumeError}
            </p>
          )}
        </details>
      </div>

      {turns.map((turn) => (
        <TurnView key={turn.id} turn={turn} />
      ))}
    </section>
  );
}

function TurnView({ turn }: { turn: Turn }) {
  return (
    <div className="card">
      <p className="assistant-question">
        <strong>You asked:</strong> {turn.question}
      </p>

      {turn.error && (
        <p className="status status-error" role="alert">
          {turn.error}
        </p>
      )}

      {!turn.response && !turn.error && (
        <p className="status">Looking this up in the dataset…</p>
      )}

      {turn.response && (
        <>
          <p>{turn.response.answer}</p>

          {turn.response.note && <p className="subtitle">{turn.response.note}</p>}

          {turn.response.grounded ? (
            <AssistantVisualization
              visualization={turn.response.visualization}
              rows={turn.response.data}
            />
          ) : (
            // Said plainly rather than implied by an empty table: this reply is the
            // assistant describing itself, not a finding about the dataset.
            <p className="subtitle">No data was retrieved for this question.</p>
          )}
        </>
      )}
    </div>
  );
}
