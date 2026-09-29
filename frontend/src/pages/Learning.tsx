import { useState } from 'react';
import type { FormEvent } from 'react';
import { ApiError, api } from '../api/client';
import type {
  LearningItem,
  LearningPlan,
  LearningPriority,
  LearningResourceType,
  LearningStatus,
} from '../api/types';
import { Badge, Card, EmptyState, ErrorState, PageHeader, SkeletonTable } from '../components/ui';
import { useApi } from '../hooks/useApi';

const STATUS_LABEL: Record<LearningStatus, string> = {
  NOT_STARTED: 'Not started',
  IN_PROGRESS: 'In progress',
  COMPLETED: 'Completed',
};

const PRIORITY_TONE: Record<LearningPriority, 'danger' | 'warning' | 'neutral'> = {
  HIGH: 'danger',
  MEDIUM: 'warning',
  LOW: 'neutral',
};

const RESOURCE_TYPES: LearningResourceType[] = ['COURSE', 'VIDEO', 'ARTICLE', 'DOCUMENTATION', 'PROJECT', 'OTHER'];

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

/** V9.5: the user's learning plan, prioritised by their career goal's roadmap. */
export function Learning() {
  const [attempt, setAttempt] = useState(0);
  const state = useApi(() => api.learningPlan(), [attempt]);
  const [actionError, setActionError] = useState<string | null>(null);
  const [custom, setCustom] = useState({ skillName: '', topic: '', targetDate: '' });

  const reload = () => setAttempt((count) => count + 1);
  const run = async (action: () => Promise<unknown>) => {
    setActionError(null);
    try {
      await action();
      reload();
    } catch (failure) {
      setActionError(messageOf(failure));
    }
  };

  const addCustom = (event: FormEvent) => {
    event.preventDefault();
    void run(async () => {
      await api.createLearningItem({
        skillName: custom.skillName.trim(),
        topic: custom.topic.trim(),
        targetDate: custom.targetDate || undefined,
      });
      setCustom({ skillName: '', topic: '', targetDate: '' });
    });
  };

  const plan = state.data;
  return (
    <>
      <PageHeader
        title="Learning & Skills"
        description="Plan what to learn next from your career goal's roadmap, track progress and keep your own resources."
      />
      {actionError && (
        <p className="status status-error" role="alert">
          {actionError}
        </p>
      )}
      {state.error ? (
        <ErrorState message={state.error} onRetry={reload} />
      ) : !plan ? (
        <SkeletonTable rows={4} columns={3} />
      ) : (
        <>
          <Overview plan={plan} />
          <Card
            title="Priority skills"
            description={plan.goalRole ? `From your “${plan.goalRole}” career roadmap.` : undefined}
          >
            {plan.note && <p className="card-description">{plan.note}</p>}
            {plan.priorities.length > 0 && (
              <div className="table-wrap">
                <table>
                  <caption className="visually-hidden">Priority skills</caption>
                  <thead>
                    <tr>
                      <th scope="col" className="rank-cell">#</th>
                      <th scope="col">Skill</th>
                      <th scope="col">Why</th>
                      <th scope="col">Plan</th>
                    </tr>
                  </thead>
                  <tbody>
                    {plan.priorities.map((skill) => (
                      <tr key={skill.skillId}>
                        <td className="rank-cell tabular">{skill.rank}</td>
                        <td>
                          <strong>{skill.skill}</strong> <Badge tone={PRIORITY_TONE[skill.suggestedPriority]}>{skill.suggestedPriority}</Badge>
                        </td>
                        <td className="small">{skill.reason}</td>
                        <td>
                          {skill.itemId ? (
                            <span className="muted small">Planned</span>
                          ) : (
                            <button type="button" className="small"
                              onClick={() => run(() => api.createLearningItem({ skillId: skill.skillId, topic: `Learn ${skill.skill}` }))}>
                              Plan {skill.skill}
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <form className="alert-form" onSubmit={addCustom} aria-label="Plan another skill">
              <label className="field">
                Skill
                <input type="text" required maxLength={100} value={custom.skillName} placeholder="e.g. Rust"
                  onChange={(e) => setCustom({ ...custom, skillName: e.target.value })} />
              </label>
              <label className="field">
                Topic
                <input type="text" required maxLength={200} value={custom.topic} placeholder="What you will study"
                  onChange={(e) => setCustom({ ...custom, topic: e.target.value })} />
              </label>
              <label className="field">
                Target date
                <input type="date" value={custom.targetDate} onChange={(e) => setCustom({ ...custom, targetDate: e.target.value })} />
              </label>
              <button type="submit" className="small">Add to plan</button>
            </form>
          </Card>

          <Card title="Learning roadmap" description="Your items, highest priority first.">
            {plan.items.length === 0 ? (
              <EmptyState title="Nothing planned yet" message="Plan a priority skill or add one of your own." />
            ) : (
              <ul className="learning-list" aria-label="Learning items">
                {plan.items.map((item) => (
                  <ItemCard key={item.id} item={item} run={run} />
                ))}
              </ul>
            )}
          </Card>
        </>
      )}
    </>
  );
}

function Overview({ plan }: { plan: LearningPlan }) {
  const { progress, impact } = plan;
  return (
    <Card title="Progress" description="Learning a skill does not add it to your resume; add it there yourself when you are ready.">
      <p>
        <strong>{progress.completed}</strong> of {progress.items} items completed · {progress.inProgress} in progress ·{' '}
        {progress.notStarted} not started
        {progress.averageProgress !== undefined && ` · average progress ${progress.averageProgress.toFixed(0)}%`}
      </p>
      {impact.roadmapPercentComplete !== undefined && (
        <p className="muted small">
          Career roadmap “{impact.goalRole}”: {impact.roadmapCompleted} of {impact.roadmapSkills} skills covered (
          {impact.roadmapPercentComplete.toFixed(0)}%).
        </p>
      )}
      {progress.completedSkills.length > 0 && (
        <div>
          <h3 className="resume-compare-title">Completed skills</h3>
          <ul className="skill-list" aria-label="Completed skills">
            {progress.completedSkills.map((skill) => (
              <li key={skill} className="skill-tag">{skill}</li>
            ))}
          </ul>
        </div>
      )}
      {impact.completedNotOnResume && impact.completedNotOnResume.length > 0 && (
        <p className="small">Completed but not on your resume yet: {impact.completedNotOnResume.join(', ')}.</p>
      )}
      {impact.savedJobDemand && impact.savedJobDemand.length > 0 && (
        <p className="small">
          Asked for by your saved jobs:{' '}
          {impact.savedJobDemand.map((demand) => `${demand.skill} (${demand.savedJobs})`).join(', ')}.
        </p>
      )}
      {impact.note && <p className="muted small">{impact.note}</p>}
    </Card>
  );
}

type Run = (action: () => Promise<unknown>) => Promise<void>;

function ItemCard({ item, run }: { item: LearningItem; run: Run }) {
  const [draft, setDraft] = useState({
    topic: item.topic,
    priority: item.priority,
    progress: item.progress,
    targetDate: item.targetDate ?? '',
    notes: item.notes ?? '',
  });
  const [resource, setResource] = useState({ title: '', url: '', type: 'COURSE' as LearningResourceType });

  const save = (event: FormEvent) => {
    event.preventDefault();
    void run(() => api.updateLearningItem(item.id, {
      topic: draft.topic.trim(),
      priority: draft.priority,
      progress: Number(draft.progress),
      targetDate: draft.targetDate || undefined,
      notes: draft.notes.trim() || undefined,
    }));
  };

  const addResource = (event: FormEvent) => {
    event.preventDefault();
    void run(async () => {
      await api.addLearningResource(item.id, { title: resource.title.trim(), url: resource.url.trim(), type: resource.type });
      setResource({ title: '', url: '', type: 'COURSE' });
    });
  };

  return (
    <li className="learning-item" aria-label={`${item.skill}: ${item.topic}`}>
      <div className="learning-item-head">
        <strong>{item.skill}</strong> <Badge tone={PRIORITY_TONE[item.priority]}>{item.priority}</Badge>{' '}
        <Badge tone={item.status === 'COMPLETED' ? 'success' : item.status === 'IN_PROGRESS' ? 'brand' : 'neutral'}>
          {STATUS_LABEL[item.status]}
        </Badge>
        {item.targetDate && <span className="muted small"> · target {item.targetDate}</span>}
      </div>
      <div className="match-meter" role="meter" aria-label={`Progress on ${item.skill}`} aria-valuenow={item.progress}
        aria-valuemin={0} aria-valuemax={100}>
        <div className="match-meter-fill" style={{ width: `${item.progress}%` }} />
      </div>
      <div className="learning-actions">
        {item.status === 'NOT_STARTED' && (
          <button type="button" className="small" onClick={() => run(() => api.setLearningItemStatus(item.id, 'IN_PROGRESS'))}>
            Start
          </button>
        )}
        {item.status !== 'COMPLETED' && (
          <button type="button" className="small" onClick={() => run(() => api.setLearningItemStatus(item.id, 'COMPLETED'))}>
            Mark complete
          </button>
        )}
        {item.status === 'COMPLETED' && (
          <button type="button" className="small" onClick={() => run(() => api.setLearningItemStatus(item.id, 'IN_PROGRESS'))}>
            Reopen
          </button>
        )}
        <button type="button" className="small ghost" onClick={() => run(() => api.deleteLearningItem(item.id))}>
          Remove {item.skill}
        </button>
      </div>
      {item.roadmapStatus && <p className="muted small">On your career roadmap: {STATUS_LABEL[item.roadmapStatus]}</p>}

      <form className="alert-form" onSubmit={save} aria-label={`Edit ${item.skill}`}>
        <label className="field">
          Topic
          <input type="text" required maxLength={200} value={draft.topic} onChange={(e) => setDraft({ ...draft, topic: e.target.value })} />
        </label>
        <label className="field">
          Priority
          <select value={draft.priority} onChange={(e) => setDraft({ ...draft, priority: e.target.value as LearningPriority })}>
            <option value="HIGH">High</option>
            <option value="MEDIUM">Medium</option>
            <option value="LOW">Low</option>
          </select>
        </label>
        <label className="field">
          Progress (%)
          <input type="number" min={0} max={100} value={draft.progress}
            onChange={(e) => setDraft({ ...draft, progress: Number(e.target.value) })} />
        </label>
        <label className="field">
          Target date
          <input type="date" value={draft.targetDate} onChange={(e) => setDraft({ ...draft, targetDate: e.target.value })} />
        </label>
        <label className="field">
          Notes
          <input type="text" maxLength={2000} value={draft.notes} onChange={(e) => setDraft({ ...draft, notes: e.target.value })} />
        </label>
        <button type="submit" className="small">Save</button>
      </form>

      <h3 className="resume-compare-title">Resources</h3>
      {item.resources.length === 0 ? (
        <p className="muted small">No resources yet. Add the ones you use.</p>
      ) : (
        <ul className="learning-resources" aria-label={`Resources for ${item.skill}`}>
          {item.resources.map((entry) => (
            <li key={entry.id}>
              <a href={entry.url} target="_blank" rel="noopener noreferrer">{entry.title}</a> <Badge>{entry.type}</Badge>
              {entry.notes && <span className="muted small"> · {entry.notes}</span>}{' '}
              <button type="button" className="small ghost" onClick={() => run(() => api.deleteLearningResource(entry.id))}>
                Remove {entry.title}
              </button>
            </li>
          ))}
        </ul>
      )}
      <form className="alert-form" onSubmit={addResource} aria-label={`Add a resource for ${item.skill}`}>
        <label className="field">
          Title
          <input type="text" required maxLength={200} value={resource.title} onChange={(e) => setResource({ ...resource, title: e.target.value })} />
        </label>
        <label className="field">
          URL
          <input type="url" required maxLength={500} value={resource.url} placeholder="https://"
            onChange={(e) => setResource({ ...resource, url: e.target.value })} />
        </label>
        <label className="field">
          Type
          <select value={resource.type} onChange={(e) => setResource({ ...resource, type: e.target.value as LearningResourceType })}>
            {RESOURCE_TYPES.map((type) => (
              <option key={type} value={type}>{type}</option>
            ))}
          </select>
        </label>
        <button type="submit" className="small">Add resource</button>
      </form>
    </li>
  );
}
