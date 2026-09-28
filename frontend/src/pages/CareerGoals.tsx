import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { ApiError, api } from '../api/client';
import type {
  CareerGoal,
  CareerGoalInput,
  CareerGoalStatus,
  CategoryDemand,
  Roadmap,
  SkillProgressStatus,
} from '../api/types';
import { Badge, Card, EmptyState, ErrorState, PageHeader, SkeletonTable } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { EXPERIENCE_OPTIONS } from './jobSearchState';

const STATUS_LABEL: Record<CareerGoalStatus, string> = { ACTIVE: 'Active', COMPLETED: 'Completed', ARCHIVED: 'Archived' };
const PROGRESS_LABEL: Record<SkillProgressStatus, string> = {
  NOT_STARTED: 'Not started',
  IN_PROGRESS: 'In progress',
  COMPLETED: 'Completed',
};

interface FormState {
  targetRole: string;
  targetCategory: string;
  targetLocation: string;
  targetExperience: string;
  targetSkills: string;
}

const EMPTY_FORM: FormState = { targetRole: '', targetCategory: '', targetLocation: '', targetExperience: '', targetSkills: '' };

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

/** Comma-separated skill names, trimmed, without blanks or repeats. */
export function parseSkills(text: string): string[] {
  return [...new Set(text.split(',').map((skill) => skill.trim()).filter(Boolean))];
}

function toInput(form: FormState): CareerGoalInput {
  return {
    targetRole: form.targetRole,
    targetCategory: form.targetCategory,
    targetLocation: form.targetLocation || undefined,
    targetExperience: form.targetExperience || undefined,
    targetSkills: parseSkills(form.targetSkills),
  };
}

/**
 * V7.4: career goals and their skill roadmaps. Priorities and figures come from the
 * backend's market data; the page only shows them.
 */
export function CareerGoals() {
  const [goals, setGoals] = useState<CareerGoal[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [filter, setFilter] = useState<CareerGoalStatus | 'ALL'>('ACTIVE');
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [openGoal, setOpenGoal] = useState<string | null>(null);
  const categories = useApi<CategoryDemand[]>(() => api.jobCategories(), []);

  useEffect(() => {
    let current = true;
    setLoadError(null);
    api
      .careerGoals()
      .then((loaded) => current && setGoals(loaded))
      .catch((error: unknown) => current && setLoadError(messageOf(error)));
    return () => {
      current = false;
    };
  }, [reload]);

  const set = (key: keyof FormState, value: string) => setForm((previous) => ({ ...previous, [key]: value }));
  const resetForm = () => {
    setForm(EMPTY_FORM);
    setEditingId(null);
    setFormError(null);
  };
  const replace = (updated: CareerGoal) =>
    setGoals((list) => (list ?? []).map((goal) => (goal.id === updated.id ? updated : goal)));

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setSaving(true);
    setFormError(null);
    try {
      if (editingId) {
        replace(await api.updateCareerGoal(editingId, toInput(form)));
      } else {
        const created = await api.createCareerGoal(toInput(form));
        setGoals((list) => [created, ...(list ?? [])]);
        setOpenGoal(created.id);
      }
      resetForm();
    } catch (error) {
      setFormError(messageOf(error));
    } finally {
      setSaving(false);
    }
  };

  const edit = (goal: CareerGoal) => {
    setEditingId(goal.id);
    setFormError(null);
    setForm({
      targetRole: goal.targetRole,
      targetCategory: goal.targetCategory,
      targetLocation: goal.targetLocation ?? '',
      targetExperience: goal.targetExperience ?? '',
      targetSkills: goal.targetSkills.map((skill) => skill.name).join(', '),
    });
  };

  const run = async (action: () => Promise<void>) => {
    setActionError(null);
    try {
      await action();
    } catch (error) {
      setActionError(messageOf(error));
    }
  };

  const changeStatus = (goal: CareerGoal, status: CareerGoalStatus) =>
    run(async () => replace(await api.setCareerGoalStatus(goal.id, status)));

  const remove = (goal: CareerGoal) => {
    if (!window.confirm(`Delete the goal “${goal.targetRole}” and its roadmap progress?`)) {
      return;
    }
    void run(async () => {
      await api.deleteCareerGoal(goal.id);
      setGoals((list) => (list ?? []).filter((item) => item.id !== goal.id));
      if (openGoal === goal.id) setOpenGoal(null);
      if (editingId === goal.id) resetForm();
    });
  };

  const shown = (goals ?? []).filter((goal) => filter === 'ALL' || goal.status === filter);
  const categoryOptions = categories.data ?? [];

  return (
    <>
      <PageHeader
        title="Career Goals"
        description="Pick a role to work towards and get a skill roadmap from your resume and real job-market demand."
      />

      <Card
        title={editingId ? 'Edit goal' : 'New goal'}
        description="The job category decides which postings the roadmap learns from."
      >
        <form className="alert-form" onSubmit={submit} aria-label={editingId ? 'Edit goal' : 'New goal'}>
          <label className="field">
            Target role
            <input type="text" required maxLength={100} value={form.targetRole} placeholder="e.g. Senior Backend Engineer"
              onChange={(e) => set('targetRole', e.target.value)} />
          </label>
          <label className="field">
            Job category
            <select required value={form.targetCategory} onChange={(e) => set('targetCategory', e.target.value)}>
              <option value="">Choose a category</option>
              {categoryOptions.map((option) => (
                <option key={option.category} value={option.category}>
                  {option.category}
                </option>
              ))}
              {form.targetCategory && !categoryOptions.some((option) => option.category === form.targetCategory) && (
                <option value={form.targetCategory}>{form.targetCategory}</option>
              )}
            </select>
          </label>
          <label className="field">
            Location (optional)
            <input type="text" maxLength={200} value={form.targetLocation} placeholder="City or country"
              onChange={(e) => set('targetLocation', e.target.value)} />
          </label>
          <label className="field">
            Experience (optional)
            <select value={form.targetExperience} onChange={(e) => set('targetExperience', e.target.value)}>
              <option value="">Any</option>
              {EXPERIENCE_OPTIONS.filter((option) => option.value !== 'unspecified').map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="field alert-form-wide">
            Skills you want to develop (optional, comma-separated)
            <input type="text" value={form.targetSkills} placeholder="e.g. Kubernetes, Go"
              onChange={(e) => set('targetSkills', e.target.value)} />
          </label>
          <div className="alert-form-actions">
            <button type="submit" disabled={saving}>
              {saving ? 'Saving…' : editingId ? 'Save changes' : 'Create goal'}
            </button>
            {editingId && (
              <button type="button" className="ghost" onClick={resetForm}>
                Cancel
              </button>
            )}
          </div>
          {formError && (
            <p className="status status-error alert-form-wide" role="alert">
              {formError}
            </p>
          )}
        </form>
      </Card>

      <Card
        title="Your goals"
        actions={
          <label className="field saved-filter">
            <span className="visually-hidden">Filter goals</span>
            <select aria-label="Filter goals" value={filter} onChange={(e) => setFilter(e.target.value as CareerGoalStatus | 'ALL')}>
              <option value="ACTIVE">Active</option>
              <option value="COMPLETED">Completed</option>
              <option value="ARCHIVED">Archived</option>
              <option value="ALL">All goals</option>
            </select>
          </label>
        }
      >
        {actionError && (
          <p className="status status-error" role="alert">
            {actionError}
          </p>
        )}
        {loadError ? (
          <ErrorState message={loadError} onRetry={() => setReload((count) => count + 1)} />
        ) : goals === null ? (
          <SkeletonTable rows={3} columns={3} />
        ) : shown.length === 0 ? (
          <EmptyState title={goals.length === 0 ? 'No goals yet' : 'No goals here'}
            message={goals.length === 0 ? 'Create your first goal above.' : 'Choose another filter.'} />
        ) : (
          <ul className="alert-list">
            {shown.map((goal) => (
              <li key={goal.id} className="goal-item">
                <div className="alert-item">
                  <div className="alert-item-main">
                    <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                      <strong>{goal.targetRole}</strong>
                      <Badge tone="brand">{goal.targetCategory}</Badge>
                      <Badge tone={goal.status === 'ACTIVE' ? 'success' : 'neutral'}>{STATUS_LABEL[goal.status]}</Badge>
                    </div>
                    <p className="muted small" style={{ margin: '4px 0 0' }}>
                      {[goal.targetLocation, goal.targetExperience && `${goal.targetExperience} years`,
                        goal.targetSkills.length > 0 && `Developing: ${goal.targetSkills.map((skill) => skill.name).join(', ')}`]
                        .filter(Boolean)
                        .join(' · ') || 'No location, experience or chosen skills'}
                    </p>
                  </div>
                  <div className="alert-item-actions">
                    <button type="button" className="small" aria-expanded={openGoal === goal.id}
                      onClick={() => setOpenGoal(openGoal === goal.id ? null : goal.id)}>
                      {openGoal === goal.id ? 'Hide roadmap' : 'View roadmap'}
                    </button>
                    <button type="button" className="small ghost" onClick={() => edit(goal)}>
                      Edit
                    </button>
                    {goal.status !== 'COMPLETED' && (
                      <button type="button" className="small ghost" onClick={() => changeStatus(goal, 'COMPLETED')}>
                        Mark completed
                      </button>
                    )}
                    {goal.status === 'ACTIVE' ? (
                      <button type="button" className="small ghost" onClick={() => changeStatus(goal, 'ARCHIVED')}>
                        Archive
                      </button>
                    ) : (
                      <button type="button" className="small ghost" onClick={() => changeStatus(goal, 'ACTIVE')}>
                        Reactivate
                      </button>
                    )}
                    <button type="button" className="small ghost" onClick={() => remove(goal)}>
                      Delete
                    </button>
                  </div>
                </div>
                {openGoal === goal.id && <RoadmapPanel goalId={goal.id} version={goal.updatedAt} />}
              </li>
            ))}
          </ul>
        )}
      </Card>
    </>
  );
}

/** One goal's roadmap; reloads when the goal changes. */
export function RoadmapPanel({ goalId, version }: { goalId: string; version: string }) {
  const [roadmap, setRoadmap] = useState<Roadmap | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [saveError, setSaveError] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    setError(null);
    api
      .careerGoalRoadmap(goalId)
      .then((loaded) => current && setRoadmap(loaded))
      .catch((failure: unknown) => current && setError(messageOf(failure)));
    return () => {
      current = false;
    };
  }, [goalId, version, attempt]);

  const setStatus = async (skillId: number, status: SkillProgressStatus) => {
    setSaveError(null);
    try {
      await api.setRoadmapSkillStatus(goalId, skillId, status);
      setAttempt((count) => count + 1);
    } catch (failure) {
      setSaveError(messageOf(failure));
    }
  };

  if (error) {
    return <ErrorState message={error} onRetry={() => setAttempt((count) => count + 1)} />;
  }
  if (!roadmap) {
    return <p className="muted small">Building the roadmap…</p>;
  }

  const percent = roadmap.progress.percentComplete;
  return (
    <section className="roadmap" aria-label={`Roadmap for ${roadmap.targetRole}`}>
      <div className="roadmap-progress">
        <strong>{percent === undefined ? 'No skills to track yet' : `${percent.toFixed(0)}% of the roadmap covered`}</strong>
        {percent !== undefined && (
          <div className="match-meter" role="meter" aria-label="Roadmap progress" aria-valuenow={Math.round(percent)}
            aria-valuemin={0} aria-valuemax={100}>
            <div className="match-meter-fill" style={{ width: `${percent}%` }} />
          </div>
        )}
        <p className="muted small">
          {roadmap.progress.onResume} on your resume · {roadmap.progress.completed} completed ·{' '}
          {roadmap.progress.inProgress} in progress · {roadmap.progress.notStarted} not started
          {roadmap.basedOnResume ? ` · measured against “${roadmap.basedOnResume.title}”` : ''}
        </p>
      </div>

      {roadmap.coveredSkills.length > 0 && (
        <div>
          <h3 className="resume-compare-title">Already on your resume</h3>
          <ul className="skill-list" aria-label="Covered skills">
            {roadmap.coveredSkills.map((skill) => (
              <li key={skill.id} className="skill-tag">
                {skill.name}
              </li>
            ))}
          </ul>
        </div>
      )}

      <h3 className="resume-compare-title">Skills to develop, by priority</h3>
      {saveError && (
        <p className="status status-error" role="alert">
          {saveError}
        </p>
      )}
      {roadmap.roadmap.length === 0 ? (
        <p className="muted small">Nothing left to develop for this goal.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Roadmap skills</caption>
            <thead>
              <tr>
                <th scope="col" className="rank-cell">#</th>
                <th scope="col">Skill</th>
                <th scope="col">Why</th>
                <th scope="col">Progress</th>
              </tr>
            </thead>
            <tbody>
              {roadmap.roadmap.map((item) => (
                <tr key={item.skillId}>
                  <td className="rank-cell tabular">{item.priority}</td>
                  <td>
                    <strong>{item.skill}</strong>{' '}
                    {item.source === 'YOUR_CHOICE' && <Badge>Your choice</Badge>}
                    {item.trendChangeInPercentagePoints !== undefined && <Badge tone="success">Rising</Badge>}
                  </td>
                  <td className="small">{item.reason}</td>
                  <td>
                    <select aria-label={`Progress on ${item.skill}`} value={item.status}
                      onChange={(e) => setStatus(item.skillId, e.target.value as SkillProgressStatus)}>
                      {(Object.keys(PROGRESS_LABEL) as SkillProgressStatus[]).map((status) => (
                        <option key={status} value={status}>
                          {PROGRESS_LABEL[status]}
                        </option>
                      ))}
                    </select>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {roadmap.note && <p className="card-description">{roadmap.note}</p>}
    </section>
  );
}
