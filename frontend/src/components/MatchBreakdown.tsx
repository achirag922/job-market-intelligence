import { useEffect, useState, type FormEvent } from 'react';
import { ApiError, api } from '../api/client';
import type { MatchBreakdown, MatchDimension, MatchPreferences } from '../api/types';
import { Badge, Card } from './ui';

const DIMENSIONS: { key: keyof Omit<MatchBreakdown, 'overallPercentage'>; label: string }[] = [
  { key: 'skills', label: 'Skills' },
  { key: 'experience', label: 'Experience' },
  { key: 'location', label: 'Location' },
  { key: 'workMode', label: 'Work mode' },
  { key: 'salary', label: 'Salary' },
];

const STATUS: Record<MatchDimension['status'], { label: string; tone: 'success' | 'warning' | 'danger' | 'neutral' }> = {
  MATCH: { label: 'Match', tone: 'success' },
  PARTIAL: { label: 'Partial', tone: 'warning' },
  NO_MATCH: { label: 'No match', tone: 'danger' },
  UNAVAILABLE: { label: 'Unavailable', tone: 'neutral' },
};

/**
 * V8.3: each dimension of the match with its status and the rule behind it. A dimension
 * without data says so; nothing is guessed.
 */
export function MatchBreakdownList({ breakdown }: { breakdown: MatchBreakdown }) {
  return (
    <ul className="stack" style={{ gap: 6, listStyle: 'none', padding: 0, margin: 0 }} aria-label="Match breakdown">
      {DIMENSIONS.map(({ key, label }) => {
        const dimension = breakdown[key];
        const status = STATUS[dimension.status];
        return (
          <li key={key} className="row" style={{ gap: 8, flexWrap: 'wrap', alignItems: 'baseline' }}>
            <strong style={{ minWidth: 88 }}>{label}</strong>
            <Badge tone={status.tone}>
              {dimension.score === undefined ? status.label : `${status.label} · ${dimension.score.toFixed(0)}%`}
            </Badge>
            <span className="muted">{dimension.detail}</span>
          </li>
        );
      })}
    </ul>
  );
}

interface Form {
  yearsExperience: string;
  preferredLocation: string;
  workMode: string;
  minSalary: string;
  salaryCurrency: string;
}

function toForm(preferences: MatchPreferences): Form {
  return {
    yearsExperience: preferences.yearsExperience?.toString() ?? '',
    preferredLocation: preferences.preferredLocation ?? '',
    workMode: preferences.workMode ?? '',
    minSalary: preferences.minSalary?.toString() ?? '',
    salaryCurrency: preferences.salaryCurrency ?? '',
  };
}

/** V8.3: the signed-in user's own preferences, used for every match and recommendation score. */
export function MatchPreferencesCard({ onSaved }: { onSaved: () => void }) {
  const [form, setForm] = useState<Form>(toForm({}));
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ error: boolean; text: string } | null>(null);

  useEffect(() => {
    let active = true;
    api.matchPreferences().then(
      (preferences) => active && setForm(toForm(preferences)),
      () => undefined,
    );
    return () => {
      active = false;
    };
  }, []);

  const set = (key: keyof Form, value: string) => setForm((current) => ({ ...current, [key]: value }));

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setMessage(null);
    try {
      const saved = await api.saveMatchPreferences({
        yearsExperience: form.yearsExperience === '' ? undefined : Number(form.yearsExperience),
        preferredLocation: form.preferredLocation.trim() || undefined,
        workMode: (form.workMode || undefined) as MatchPreferences['workMode'],
        minSalary: form.minSalary === '' ? undefined : Number(form.minSalary),
        salaryCurrency: form.salaryCurrency.trim().toUpperCase() || undefined,
      });
      setForm(toForm(saved));
      setMessage({ error: false, text: 'Preferences saved. Scores below now use them.' });
      onSaved();
    } catch (error) {
      setMessage({ error: true, text: error instanceof ApiError ? error.message : 'Could not save preferences.' });
    } finally {
      setSaving(false);
    }
  }

  return (
    <Card
      title="Match preferences"
      description="Optional. Each one you set adds a dimension to the match score; the ones you leave empty are shown as unavailable."
    >
      <form className="alert-form" onSubmit={submit} aria-label="Match preferences">
        <label className="field">
          Years of experience
          <input type="number" min={0} max={60} value={form.yearsExperience} onChange={(e) => set('yearsExperience', e.target.value)} />
        </label>
        <label className="field">
          Preferred location
          <input type="text" maxLength={200} value={form.preferredLocation} onChange={(e) => set('preferredLocation', e.target.value)} placeholder="City, or City, Country" />
        </label>
        <label className="field">
          Work mode
          <select value={form.workMode} onChange={(e) => set('workMode', e.target.value)}>
            <option value="">No preference</option>
            <option value="REMOTE">Remote</option>
            <option value="HYBRID">Hybrid</option>
            <option value="ON_SITE">On-site</option>
          </select>
        </label>
        <label className="field">
          Minimum salary
          <input type="number" min={0} value={form.minSalary} onChange={(e) => set('minSalary', e.target.value)} />
        </label>
        <label className="field">
          Currency
          <input type="text" maxLength={3} value={form.salaryCurrency} onChange={(e) => set('salaryCurrency', e.target.value)} placeholder="e.g. EUR" />
        </label>
        <div className="alert-form-actions">
          <button type="submit" disabled={saving}>
            {saving ? 'Saving…' : 'Save preferences'}
          </button>
        </div>
        {message && (
          <p className={message.error ? 'status status-error alert-form-wide' : 'status alert-form-wide'} role={message.error ? 'alert' : 'status'}>
            {message.text}
          </p>
        )}
      </form>
    </Card>
  );
}
