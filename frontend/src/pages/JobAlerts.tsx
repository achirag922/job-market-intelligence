import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, api } from '../api/client';
import type { AlertFrequency, CategoryDemand, JobAlert, JobAlertInput } from '../api/types';
import { Badge, Card, EmptyState, ErrorState, PageHeader, SkeletonTable } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { EXPERIENCE_OPTIONS } from './jobSearchState';

const FREQUENCY_LABEL: Record<AlertFrequency, string> = { DAILY: 'Daily', WEEKLY: 'Weekly' };

const EMPTY_FORM: JobAlertInput = { name: '', keywords: '', category: '', location: '', experience: '', skill: '', frequency: 'WEEKLY' };

/** The alert's filters as a Job Explorer link, so the user can see what it currently matches. */
export function alertSearchLink(alert: JobAlertInput): string {
  const params = new URLSearchParams();
  const filters: [string, string | undefined][] = [
    ['q', alert.keywords],
    ['category', alert.category],
    ['skill', alert.skill],
    ['location', alert.location],
    ['experience', alert.experience],
  ];
  filters.forEach(([key, value]) => value && params.set(key, value));
  return `/jobs?${params.toString()}`;
}

function criteriaOf(alert: JobAlert): string[] {
  const experience = EXPERIENCE_OPTIONS.find((option) => option.value === alert.experience)?.label;
  return [
    alert.keywords && `“${alert.keywords}”`,
    alert.category,
    alert.skill && `Skill: ${alert.skill}`,
    alert.location && `Location: ${alert.location}`,
    experience && `Experience: ${experience}`,
  ].filter((value): value is string => Boolean(value));
}

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

/**
 * V7.1: saved job searches the signed-in user wants to hear about. Notifications are not
 * sent yet; the frequency records the user's choice for when they are.
 */
export function JobAlerts() {
  const [alerts, setAlerts] = useState<JobAlert[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [form, setForm] = useState<JobAlertInput>(EMPTY_FORM);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const categories = useApi<CategoryDemand[]>(() => api.jobCategories(), []);

  useEffect(() => {
    let current = true;
    setLoadError(null);
    api
      .jobAlerts()
      .then((loaded) => current && setAlerts(loaded))
      .catch((error: unknown) => current && setLoadError(messageOf(error)));
    return () => {
      current = false;
    };
  }, [reload]);

  const set = (key: keyof JobAlertInput, value: string) => setForm((previous) => ({ ...previous, [key]: value }));

  const resetForm = () => {
    setForm(EMPTY_FORM);
    setEditingId(null);
    setFormError(null);
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setSaving(true);
    setFormError(null);
    try {
      if (editingId) {
        const updated = await api.updateJobAlert(editingId, form);
        setAlerts((list) => (list ?? []).map((alert) => (alert.id === updated.id ? updated : alert)));
      } else {
        const created = await api.createJobAlert(form);
        setAlerts((list) => [created, ...(list ?? [])]);
      }
      resetForm();
    } catch (error) {
      setFormError(messageOf(error));
    } finally {
      setSaving(false);
    }
  };

  const edit = (alert: JobAlert) => {
    setEditingId(alert.id);
    setFormError(null);
    setForm({
      name: alert.name,
      keywords: alert.keywords ?? '',
      category: alert.category ?? '',
      location: alert.location ?? '',
      experience: alert.experience ?? '',
      skill: alert.skill ?? '',
      frequency: alert.frequency,
    });
  };

  const toggle = async (alert: JobAlert) => {
    setActionError(null);
    try {
      const updated = await api.setJobAlertActive(alert.id, !alert.active);
      setAlerts((list) => (list ?? []).map((item) => (item.id === updated.id ? updated : item)));
    } catch (error) {
      setActionError(messageOf(error));
    }
  };

  const remove = async (alert: JobAlert) => {
    if (!window.confirm(`Delete the alert “${alert.name}”?`)) {
      return;
    }
    setActionError(null);
    try {
      await api.deleteJobAlert(alert.id);
      setAlerts((list) => (list ?? []).filter((item) => item.id !== alert.id));
      if (editingId === alert.id) {
        resetForm();
      }
    } catch (error) {
      setActionError(messageOf(error));
    }
  };

  return (
    <>
      <PageHeader
        title="Job Alerts"
        description="Save a job search and choose how often you want to hear about new matches. Email notifications are coming soon."
      />

      <Card
        title={editingId ? 'Edit alert' : 'New alert'}
        description="Give it a name and at least one filter. The filters work exactly like the Job Explorer's."
      >
        <form className="alert-form" onSubmit={submit} aria-label={editingId ? 'Edit alert' : 'New alert'}>
          <label className="field alert-form-wide">
            Name
            <input type="text" value={form.name} maxLength={100} required onChange={(e) => set('name', e.target.value)} placeholder="e.g. Java roles in Berlin" />
          </label>
          <label className="field">
            Keywords
            <input type="text" value={form.keywords} maxLength={200} onChange={(e) => set('keywords', e.target.value)} placeholder="e.g. backend" />
          </label>
          <label className="field">
            Category
            <select value={form.category} onChange={(e) => set('category', e.target.value)}>
              <option value="">Any category</option>
              {(categories.data ?? []).map((option) => (
                <option key={option.category} value={option.category}>
                  {option.category}
                </option>
              ))}
              {form.category && !(categories.data ?? []).some((option) => option.category === form.category) && (
                <option value={form.category}>{form.category}</option>
              )}
            </select>
          </label>
          <label className="field">
            Skill
            <input type="text" value={form.skill} maxLength={100} onChange={(e) => set('skill', e.target.value)} placeholder="e.g. Java" />
          </label>
          <label className="field">
            Location
            <input type="text" value={form.location} maxLength={200} onChange={(e) => set('location', e.target.value)} placeholder="City, state or country" />
          </label>
          <label className="field">
            Experience
            <select value={form.experience} onChange={(e) => set('experience', e.target.value)}>
              <option value="">Any experience</option>
              {EXPERIENCE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            Frequency
            <select value={form.frequency} onChange={(e) => set('frequency', e.target.value)}>
              <option value="DAILY">Daily</option>
              <option value="WEEKLY">Weekly</option>
            </select>
          </label>

          <div className="alert-form-actions">
            <button type="submit" disabled={saving}>
              {saving ? 'Saving…' : editingId ? 'Save changes' : 'Create alert'}
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

      <Card title="Your alerts" description="Newest first. Paused alerts are kept but will not notify you.">
        {actionError && (
          <p className="status status-error" role="alert">
            {actionError}
          </p>
        )}
        {loadError ? (
          <ErrorState message={loadError} onRetry={() => setReload((count) => count + 1)} />
        ) : alerts === null ? (
          <SkeletonTable rows={3} columns={3} />
        ) : alerts.length === 0 ? (
          <EmptyState title="No alerts yet" message="Create your first alert above." />
        ) : (
          <ul className="alert-list">
            {alerts.map((alert) => (
              <li key={alert.id} className={`alert-item${alert.active ? '' : ' alert-paused'}`}>
                <div className="alert-item-main">
                  <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                    <strong>{alert.name}</strong>
                    <Badge tone={alert.active ? 'success' : 'neutral'}>{alert.active ? 'Active' : 'Paused'}</Badge>
                    <Badge tone="brand">{FREQUENCY_LABEL[alert.frequency]}</Badge>
                  </div>
                  <p className="muted small" style={{ margin: '4px 0 0' }}>
                    {criteriaOf(alert).join(' · ')}
                  </p>
                  <p className="muted small" style={{ margin: '2px 0 0' }}>
                    Created {new Date(alert.createdAt).toLocaleDateString(undefined, { dateStyle: 'medium' })}
                  </p>
                </div>
                <div className="alert-item-actions">
                  <Link to={alertSearchLink(alert)} className="button-link">
                    View matching jobs
                  </Link>
                  <button type="button" className="small ghost" onClick={() => edit(alert)}>
                    Edit
                  </button>
                  <button type="button" className="small ghost" onClick={() => toggle(alert)}>
                    {alert.active ? 'Pause' : 'Resume'}
                  </button>
                  <button type="button" className="small ghost" onClick={() => remove(alert)}>
                    Delete
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
