import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { Card, PageHeader } from '../components/ui';

const MIN_PASSWORD = 12;
const MAX_PASSWORD = 72;

/**
 * Create an account, sign in, then go to the Dashboard.
 *
 * <p>Only the checks a user can fix before submitting run here — length and the confirm
 * field. The backend applies the full strength rules and its message is shown as given.
 */
export function Signup() {
  const { signup } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState(false);

  const localProblem =
    password.length > 0 && password.length < MIN_PASSWORD
      ? `Use at least ${MIN_PASSWORD} characters.`
      : password.length > MAX_PASSWORD
        ? `Use at most ${MAX_PASSWORD} characters.`
        : confirm.length > 0 && confirm !== password
          ? 'The passwords do not match.'
          : null;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (localProblem) {
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await signup(email, password);
      setCreated(true);
      navigate('/', { replace: true });
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : 'Sign-up failed. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      <PageHeader title="Sign up" description="Create an account. Your password is stored only as a one-way hash." />
      <Card className="auth-card">
        <form className="auth-form" onSubmit={submit} noValidate>
          <label className="field">
            Email
            <input
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              disabled={submitting}
            />
          </label>
          {/* Explicit label so the hint is a description, not part of the field's name. */}
          <div className="field">
            <label htmlFor="signup-password">Password</label>
            <input
              id="signup-password"
              type="password"
              autoComplete="new-password"
              required
              aria-describedby="password-hint"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              disabled={submitting}
            />
            <span id="password-hint" className="field-hint">
              {MIN_PASSWORD}–{MAX_PASSWORD} characters. A few unrelated words make a strong password.
            </span>
          </div>
          <label className="field">
            Confirm password
            <input
              type="password"
              autoComplete="new-password"
              required
              value={confirm}
              onChange={(event) => setConfirm(event.target.value)}
              disabled={submitting}
            />
          </label>

          {localProblem && <p className="field-error">{localProblem}</p>}
          {error && (
            <p className="status status-error" role="alert">
              {error}
            </p>
          )}
          {created && (
            <p className="status" role="status">
              Account created. Signing you in…
            </p>
          )}

          <button type="submit" disabled={submitting || !email || !password || !confirm || Boolean(localProblem)}>
            {submitting ? 'Creating account…' : 'Create account'}
          </button>
          <p className="muted">
            Already have an account? <Link to="/login">Log in</Link>
          </p>
        </form>
      </Card>
    </>
  );
}
