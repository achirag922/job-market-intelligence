import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { Card, PageHeader } from '../components/ui';

/** Sign in, then go to the Dashboard. */
export function Login() {
  const { login, status, user } = useAuth();
  const navigate = useNavigate();
  // Where RequireAuth sent the user from, or the Dashboard.
  const returnTo = (useLocation().state as { from?: string } | null)?.from ?? '/';
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await login(email, password);
      navigate(returnTo, { replace: true });
    } catch (failure) {
      // The backend says the same for a wrong password and an unknown email; so does this.
      setError(failure instanceof ApiError ? failure.message : 'Sign-in failed. Please try again.');
      setPassword('');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      <PageHeader title="Log in" description="Sign in to your Job Market Intelligence account." />
      <Card className="auth-card">
        {status === 'signedIn' && user ? (
          <p className="status" role="status">
            You are signed in as <strong>{user.email}</strong>. <Link to="/">Go to the Dashboard</Link>
          </p>
        ) : (
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
            <label className="field">
              Password
              <input
                type="password"
                autoComplete="current-password"
                required
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                disabled={submitting}
              />
            </label>

            {error && (
              <p className="status status-error" role="alert">
                {error}
              </p>
            )}

            <button type="submit" disabled={submitting || !email || !password}>
              {submitting ? 'Signing in…' : 'Log in'}
            </button>
            <p className="muted">
              No account yet? <Link to="/signup">Sign up</Link>
            </p>
          </form>
        )}
      </Card>
    </>
  );
}
