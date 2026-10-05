import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, api } from '../api/client';
import type { Portfolio } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useConfirm, useToast } from '../components/feedback';
import { MatchPreferencesCard } from '../components/MatchBreakdown';
import { Badge, Card, PageHeader } from '../components/ui';
import { useTheme } from '../hooks/useTheme';
import { NotificationPreferencesCard } from './Notifications';

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

/**
 * V9.17: one place for the user's settings. Each section uses the existing feature's own data and
 * API (match preferences, notification preferences, the portfolio, the theme); only the account
 * itself (name, password, deletion) is new.
 */
export function SettingsPage() {
  return (
    <>
      <PageHeader title="Settings" description="Your profile, preferences, privacy, appearance and account." />
      <nav className="quick-actions" aria-label="Settings sections">
        <a className="quick-action" href="#profile">Profile</a>
        <a className="quick-action" href="#job-preferences">Job preferences</a>
        <a className="quick-action" href="#notifications">Notifications</a>
        <a className="quick-action" href="#privacy">Privacy</a>
        <a className="quick-action" href="#appearance">Appearance</a>
        <a className="quick-action" href="#account">Account</a>
      </nav>
      <div id="profile"><ProfileSection /></div>
      <div id="job-preferences"><JobPreferencesSection /></div>
      <div id="notifications"><NotificationPreferencesCard onSaved={() => undefined} /></div>
      <div id="privacy"><PrivacySection /></div>
      <div id="appearance"><AppearanceSection /></div>
      <div id="account"><AccountSection /></div>
    </>
  );
}

function ProfileSection() {
  const { user, replaceUser } = useAuth();
  const [name, setName] = useState(user?.fullName ?? '');
  const [busy, setBusy] = useState(false);
  const toast = useToast();
  const save = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      replaceUser(await api.updateAccountName(name.trim()));
      toast('Profile saved.');
    } catch (cause) {
      toast(messageOf(cause), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Card title="Profile" description="How you appear in JMIP. Your career profile and goal are edited in Profile & Preferences.">
      <form className="alert-form" onSubmit={save} aria-label="Profile">
        <label className="field">Full name
          <input type="text" required maxLength={100} value={name} disabled={busy} onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="field">Email
          <input type="email" value={user?.email ?? ''} readOnly aria-readonly="true" />
        </label>
        <div className="alert-form-wide row" style={{ gap: 8, flexWrap: 'wrap' }}>
          <button type="submit" disabled={busy || !name.trim() || name.trim() === (user?.fullName ?? '')}>
            {busy ? 'Saving…' : 'Save profile'}
          </button>
          <Link className="button-link" to="/onboarding">Edit career profile & goal</Link>
        </div>
      </form>
    </Card>
  );
}

function JobPreferencesSection() {
  const toast = useToast();
  return <MatchPreferencesCard onSaved={() => toast('Job preferences saved.')} />;
}

function PrivacySection() {
  const [portfolio, setPortfolio] = useState<Portfolio | null | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const toast = useToast();
  useEffect(() => {
    api.portfolio().then(setPortfolio, () => setPortfolio(null));
  }, []);
  const toggle = async () => {
    if (!portfolio) return;
    setBusy(true);
    try {
      const next = portfolio.visibility === 'PUBLIC' ? await api.unpublishPortfolio() : await api.publishPortfolio();
      setPortfolio(next);
      toast(next.visibility === 'PUBLIC' ? 'Your profile is public.' : 'Your profile is private.');
    } catch (cause) {
      toast(messageOf(cause), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Card title="Privacy & public profile"
      description="Your resume, applications, interviews and learning plan are always private. Only a published portfolio is public.">
      {portfolio === undefined ? (
        <p className="muted small">Loading…</p>
      ) : portfolio === null ? (
        <p className="small">You have no portfolio, so nothing about you is public. <Link to="/portfolio">Create a portfolio</Link></p>
      ) : (
        <div className="stack" style={{ gap: 8 }}>
          <p className="small" style={{ margin: 0 }}>
            Public profile: <Badge tone={portfolio.visibility === 'PUBLIC' ? 'success' : 'neutral'}>
              {portfolio.visibility === 'PUBLIC' ? 'Public' : 'Private'}</Badge>{' '}
            {portfolio.visibility === 'PUBLIC' && <a href={portfolio.publicPath} target="_blank" rel="noopener noreferrer">{portfolio.publicPath}</a>}
          </p>
          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <button type="button" className="small" disabled={busy} onClick={toggle}>
              {portfolio.visibility === 'PUBLIC' ? 'Make private' : 'Publish'}
            </button>
            <Link className="button-link" to="/portfolio">Choose visible sections</Link>
          </div>
        </div>
      )}
    </Card>
  );
}

function AppearanceSection() {
  const { theme, toggle } = useTheme();
  return (
    <Card title="Appearance" description="Saved in this browser.">
      <fieldset className="builder-entry">
        <legend>Theme</legend>
        <div className="row" style={{ gap: 16 }}>
          {(['light', 'dark'] as const).map((option) => (
            <label key={option} className="row small" style={{ gap: 6 }}>
              <input type="radio" name="theme" checked={theme === option} onChange={() => { if (theme !== option) toggle(); }} />
              {option === 'light' ? 'Light' : 'Dark'}
            </label>
          ))}
        </div>
      </fieldset>
    </Card>
  );
}

function AccountSection() {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirmNext, setConfirmNext] = useState('');
  const [deletePassword, setDeletePassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const toast = useToast();
  const confirm = useConfirm();

  const changePassword = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    if (next.length < 12) return setError('The new password must be at least 12 characters.');
    if (next !== confirmNext) return setError('The new passwords do not match.');
    setBusy(true);
    try {
      await api.changePassword(current, next);
      setCurrent('');
      setNext('');
      setConfirmNext('');
      toast('Password changed.');
    } catch (cause) {
      setError(messageOf(cause));
    } finally {
      setBusy(false);
    }
  };

  const deleteAccount = async (event: FormEvent) => {
    event.preventDefault();
    if (!(await confirm({ title: 'Delete your account?', message: 'Your resumes, applications, goals, learning plan, interviews and portfolio are deleted for good. This cannot be undone.',
      confirmLabel: 'Delete my account', tone: 'danger' }))) return;
    setBusy(true);
    try {
      await api.deleteAccount(deletePassword);
      window.location.assign('/welcome');
    } catch (cause) {
      toast(messageOf(cause), 'error');
      setBusy(false);
    }
  };

  return (
    <Card title="Account" description="Your password and your account.">
      <form className="alert-form" onSubmit={changePassword} aria-label="Change password">
        <label className="field">Current password
          <input type="password" autoComplete="current-password" required value={current} onChange={(e) => setCurrent(e.target.value)} />
        </label>
        <label className="field">New password
          <input type="password" autoComplete="new-password" required minLength={12} maxLength={72} value={next} onChange={(e) => setNext(e.target.value)} />
        </label>
        <label className="field">Confirm new password
          <input type="password" autoComplete="new-password" required value={confirmNext} onChange={(e) => setConfirmNext(e.target.value)} />
        </label>
        {error && <p className="status status-error alert-form-wide" role="alert">{error}</p>}
        <div className="alert-form-wide"><button type="submit" disabled={busy}>Change password</button></div>
      </form>
      <form className="alert-form danger-zone" onSubmit={deleteAccount} aria-label="Delete account" style={{ marginTop: 16 }}>
        <label className="field">Password to confirm deletion
          <input type="password" autoComplete="current-password" required value={deletePassword} onChange={(e) => setDeletePassword(e.target.value)} />
        </label>
        <div className="alert-form-wide">
          <button type="submit" className="danger" disabled={busy || !deletePassword}>Delete account</button>
        </div>
      </form>
    </Card>
  );
}
