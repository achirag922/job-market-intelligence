import { useRef, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ApiError, EmailNotVerifiedError } from '../api/client';
import { AuthLayout } from '../auth/AuthLayout';
import { useAuth } from '../auth/AuthContext';

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

interface LocationState {
  from?: string;
  notice?: string;
  email?: string;
}

/** Sign in, then go back to where RequireAuth sent the user from, or the Dashboard. */
export function Login() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const state = (useLocation().state as LocationState | null) ?? {};
  const returnTo = state.from ?? '/';

  const [email, setEmail] = useState(state.email ?? '');
  const [password, setPassword] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const emailInput = useRef<HTMLInputElement>(null);
  const passwordInput = useRef<HTMLInputElement>(null);

  const emailProblem = !email.trim()
    ? 'Enter your email address.'
    : !EMAIL_PATTERN.test(email.trim())
      ? 'Enter a valid email address.'
      : null;
  const passwordProblem = password ? null : 'Enter your password.';

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setSubmitted(true);
    if (emailProblem || passwordProblem) {
      (emailProblem ? emailInput : passwordInput).current?.focus();
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await login(email, password);
      navigate(returnTo, { replace: true });
    } catch (failure) {
      if (failure instanceof EmailNotVerifiedError) {
        navigate('/verify-email', { state: { from: returnTo } });
        return;
      }
      // The backend says the same for a wrong password and an unknown email; so does this.
      setError(failure instanceof ApiError ? failure.message : 'Login failed. Please try again.');
      setPassword('');
      passwordInput.current?.focus();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout title="Welcome back" subtitle="Log in to continue to your job market dashboard.">
      <form className="auth-form" onSubmit={submit} noValidate>
        {state.notice && (
          <p className="auth-notice" role="status">
            {state.notice}
          </p>
        )}
        <Field
          id="login-email"
          label="Email"
          error={submitted ? emailProblem : null}
          input={
            <input
              ref={emailInput}
              id="login-email"
              type="email"
              autoComplete="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              disabled={submitting}
              aria-invalid={Boolean(submitted && emailProblem) || undefined}
              aria-describedby={submitted && emailProblem ? 'login-email-error' : undefined}
            />
          }
        />
        <Field
          id="login-password"
          label="Password"
          error={submitted ? passwordProblem : null}
          input={
            <input
              ref={passwordInput}
              id="login-password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              disabled={submitting}
              aria-invalid={Boolean(submitted && passwordProblem) || undefined}
              aria-describedby={submitted && passwordProblem ? 'login-password-error' : undefined}
            />
          }
        />

        {error && (
          <p className="auth-alert" role="alert">
            {error}
          </p>
        )}

        <button type="submit" className="auth-submit" disabled={submitting}>
          {submitting ? <span className="auth-spinner" aria-hidden="true" /> : null}
          {submitting ? 'Logging in…' : 'Login'}
        </button>
        <p className="auth-switch">
          Don&apos;t have an account? <Link to="/signup">Sign up</Link>
        </p>
      </form>
    </AuthLayout>
  );
}

/** A labelled input with its error beneath, wired together for screen readers. */
// eslint-disable-next-line react-refresh/only-export-components
export function Field({
  id,
  label,
  input,
  error,
  hint,
}: {
  id: string;
  label: string;
  input: React.ReactNode;
  error?: string | null;
  hint?: string;
}) {
  return (
    <div className={error ? 'auth-field has-error' : 'auth-field'}>
      <label htmlFor={id}>{label}</label>
      {input}
      {error ? (
        <span id={`${id}-error`} className="auth-field-error">
          {error}
        </span>
      ) : hint ? (
        <span id={`${id}-hint`} className="auth-field-hint">
          {hint}
        </span>
      ) : null}
    </div>
  );
}
