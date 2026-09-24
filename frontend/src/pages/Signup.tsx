import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { AuthLayout } from '../auth/AuthLayout';
import { useAuth } from '../auth/AuthContext';
import { Field } from './Login';

const MIN_PASSWORD = 12;
const MAX_PASSWORD = 72;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * Create an account, then confirm the email with the code the backend sends.
 *
 * <p>Only the checks a user can fix before submitting run here. The backend applies the full
 * password-strength rules, and its message is shown as given.
 */
export function Signup() {
  const { signup } = useAuth();
  const navigate = useNavigate();
  const returnTo = (useLocation().state as { from?: string } | null)?.from ?? '/';

  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const problems = {
    fullName: !fullName.trim()
      ? 'Enter your full name.'
      : fullName.trim().length > 100
        ? 'Use at most 100 characters.'
        : null,
    email: !email.trim()
      ? 'Enter your email address.'
      : !EMAIL_PATTERN.test(email.trim())
        ? 'Enter a valid email address.'
        : null,
    password: password.length < MIN_PASSWORD
      ? `Use at least ${MIN_PASSWORD} characters.`
      : password.length > MAX_PASSWORD
        ? `Use at most ${MAX_PASSWORD} characters.`
        : null,
    confirm: !confirm ? 'Confirm your password.' : confirm !== password ? 'The passwords do not match.' : null,
  };
  // Errors appear after the first submit, or as soon as the user has typed into that field.
  const show = (field: keyof typeof problems, typed: string) =>
    submitted || (typed.length > 0 && field !== 'fullName' && field !== 'email') ? problems[field] : null;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setSubmitted(true);
    const firstProblem = (Object.keys(problems) as (keyof typeof problems)[]).find((field) => problems[field]);
    if (firstProblem) {
      document.getElementById(`signup-${firstProblem}`)?.focus();
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await signup(fullName, email, password);
      navigate('/verify-email', { state: { from: returnTo } });
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : 'Sign-up failed. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  const input = (field: keyof typeof problems, value: string, setValue: (next: string) => void,
                 type: string, autoComplete: string) => {
    const problem = show(field, value);
    return (
      <input
        id={`signup-${field}`}
        type={type}
        autoComplete={autoComplete}
        value={value}
        onChange={(event) => setValue(event.target.value)}
        disabled={submitting}
        aria-invalid={Boolean(problem) || undefined}
        aria-describedby={problem ? `signup-${field}-error` : field === 'password' ? 'signup-password-hint' : undefined}
      />
    );
  };

  return (
    <AuthLayout title="Create your account" subtitle="See where your skills fit in the job market. It takes a minute.">
      <form className="auth-form" onSubmit={submit} noValidate>
        <Field id="signup-fullName" label="Full Name" error={show('fullName', fullName)}
               input={input('fullName', fullName, setFullName, 'text', 'name')} />
        <Field id="signup-email" label="Email" error={show('email', email)}
               input={input('email', email, setEmail, 'email', 'email')} />
        <Field id="signup-password" label="Password" error={show('password', password)}
               hint={`${MIN_PASSWORD}–${MAX_PASSWORD} characters. A few unrelated words make a strong password.`}
               input={input('password', password, setPassword, 'password', 'new-password')} />
        <Field id="signup-confirm" label="Confirm Password" error={show('confirm', confirm)}
               input={input('confirm', confirm, setConfirm, 'password', 'new-password')} />

        {error && (
          <p className="auth-alert" role="alert">
            {error}
          </p>
        )}

        <button type="submit" className="auth-submit" disabled={submitting}>
          {submitting ? <span className="auth-spinner" aria-hidden="true" /> : null}
          {submitting ? 'Creating account…' : 'Create Account'}
        </button>
        <p className="auth-switch">
          Already have an account? <Link to="/login">Login</Link>
        </p>
      </form>
    </AuthLayout>
  );
}
