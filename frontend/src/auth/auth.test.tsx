import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ApiError, EmailNotVerifiedError, UNAUTHORIZED_EVENT } from '../api/client';
import type { AuthSession } from '../api/types';
import { AppShell } from '../components/AppShell';
import { Login } from '../pages/Login';
import { Signup } from '../pages/Signup';
import { VerifyEmail } from '../pages/VerifyEmail';
import { AuthProvider } from './AuthContext';
import { maskEmail } from './AuthLayout';
import { RequireAuth } from './RequireAuth';

const login = vi.fn();
const signup = vi.fn();
const logout = vi.fn();
const currentSession = vi.fn();
const verifyEmail = vi.fn();
const resendVerification = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      login: (...args: unknown[]) => login(...args),
      signup: (...args: unknown[]) => signup(...args),
      logout: (...args: unknown[]) => logout(...args),
      currentSession: (...args: unknown[]) => currentSession(...args),
      verifyEmail: (...args: unknown[]) => verifyEmail(...args),
      resendVerification: (...args: unknown[]) => resendVerification(...args),
    },
  };
});

const EMAIL = 'chirag@gmail.com';
const PASSWORD = 'correct horse battery';
const session: AuthSession = {
  user: {
    id: 'u-1', fullName: 'Chirag Agarwal', email: EMAIL, role: 'USER', emailVerified: true,
    createdAt: '2026-09-24T10:00:00Z',
  },
  csrfToken: 'token-1',
};

/** The app's real arrangement: auth screens standalone, protected pages in the shell. */
function renderApp(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/signup" element={<Signup />} />
          <Route path="/verify-email" element={<VerifyEmail />} />
          <Route element={<RequireAuth />}>
            <Route path="/" element={<AppShell><p>Dashboard home</p></AppShell>} />
            <Route path="/jobs" element={<AppShell><p>Jobs page</p></AppShell>} />
          </Route>
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const click = (name: string) => fireEvent.click(screen.getByRole('button', { name }));
const pasteCode = (code: string) =>
  fireEvent.paste(screen.getByLabelText('Digit 1 of 6'), { clipboardData: { getData: () => code } });

function fillSignup(overrides: Partial<Record<'name' | 'email' | 'password' | 'confirm', string>> = {}) {
  type('Full Name', overrides.name ?? 'Chirag Agarwal');
  type('Email', overrides.email ?? EMAIL);
  type('Password', overrides.password ?? PASSWORD);
  type('Confirm Password', overrides.confirm ?? PASSWORD);
}

describe('authentication screens', () => {
  beforeEach(() => {
    [login, signup, logout, currentSession, verifyEmail, resendVerification].forEach((mock) => mock.mockReset());
    currentSession.mockResolvedValue(null);
    login.mockResolvedValue(session);
    signup.mockResolvedValue({ ...session.user, emailVerified: false });
    logout.mockResolvedValue(undefined);
    verifyEmail.mockResolvedValue(undefined);
    resendVerification.mockResolvedValue({ email: EMAIL, resendAvailableInSeconds: 60, codeValidForSeconds: 600 });
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  // ------------------------------------------------------------------ login

  it('logs in and lands on the Dashboard with the name in the header', async () => {
    renderApp('/login');
    expect(screen.getByRole('heading', { name: 'Welcome back' })).toBeInTheDocument();
    type('Email', EMAIL);
    type('Password', PASSWORD);
    click('Login');

    expect(await screen.findByText('Dashboard home')).toBeInTheDocument();
    expect(login).toHaveBeenCalledWith(EMAIL, PASSWORD);
    expect(screen.getByText('Chirag Agarwal')).toBeInTheDocument();
  });

  it('validates the login form before calling the API', async () => {
    renderApp('/login');
    click('Login');
    expect(await screen.findByText('Enter your email address.')).toBeInTheDocument();
    expect(screen.getByText('Enter your password.')).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toHaveFocus();

    type('Email', 'not-an-email');
    click('Login');
    expect(await screen.findByText('Enter a valid email address.')).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true');
    expect(login).not.toHaveBeenCalled();
  });

  it('shows the backend message on a failed login and clears the password', async () => {
    login.mockRejectedValue(new ApiError(401, 'Invalid email or password'));
    renderApp('/login');
    type('Email', EMAIL);
    type('Password', 'wrong password');
    click('Login');

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password');
    expect(screen.getByLabelText('Password')).toHaveValue('');
  });

  it('sends an unverified login to the code screen with the email masked', async () => {
    login.mockRejectedValue(new EmailNotVerifiedError('Verify your email address to sign in.'));
    renderApp('/login');
    type('Email', EMAIL);
    type('Password', PASSWORD);
    click('Login');

    expect(await screen.findByRole('heading', { name: 'Verify your email' })).toBeInTheDocument();
    expect(screen.getByText('c****@gmail.com')).toBeInTheDocument();
    expect(screen.getByText(/Enter the 6-digit code we sent to your Gmail address/)).toBeInTheDocument();
  });

  it('offers no Forgot password link, since the backend has no reset flow', () => {
    renderApp('/login');
    expect(screen.queryByText(/forgot password/i)).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Sign up' })).toHaveAttribute('href', '/signup');
  });

  // ------------------------------------------------------------------ signup

  it('validates every signup field before calling the API', async () => {
    renderApp('/signup');
    expect(screen.getByRole('heading', { name: 'Create your account' })).toBeInTheDocument();
    click('Create Account');

    expect(await screen.findByText('Enter your full name.')).toBeInTheDocument();
    expect(screen.getByText('Enter your email address.')).toBeInTheDocument();
    expect(screen.getByText('Use at least 12 characters.')).toBeInTheDocument();
    expect(screen.getByText('Confirm your password.')).toBeInTheDocument();
    expect(screen.getByLabelText('Full Name')).toHaveFocus();

    fillSignup({ confirm: 'correct horse batterx' });
    expect(screen.getByText('The passwords do not match.')).toBeInTheDocument();
    click('Create Account');
    expect(signup).not.toHaveBeenCalled();
  });

  it('shows the backend reason when signup is refused', async () => {
    signup.mockRejectedValue(new ApiError(409, 'An account with this email already exists'));
    renderApp('/signup');
    fillSignup();
    click('Create Account');

    expect(await screen.findByRole('alert')).toHaveTextContent('already exists');
  });

  // ------------------------------------------------------------------ verification

  it('signs up, verifies the pasted code, signs in and lands on the Dashboard', async () => {
    renderApp('/signup');
    fillSignup();
    click('Create Account');

    expect(await screen.findByRole('heading', { name: 'Verify your email' })).toBeInTheDocument();
    expect(signup).toHaveBeenCalledWith('Chirag Agarwal', EMAIL, PASSWORD);
    expect(screen.getByLabelText('Digit 1 of 6')).toHaveFocus();

    pasteCode('482913');

    expect(await screen.findByRole('heading', { name: 'Email verified' })).toBeInTheDocument();
    expect(verifyEmail).toHaveBeenCalledWith(EMAIL, '482913');
    expect(login).toHaveBeenCalledWith(EMAIL, PASSWORD);
    expect(await screen.findByText('Dashboard home', {}, { timeout: 2000 })).toBeInTheDocument();
  });

  it('shows an invalid code clearly, and clears the error as the user retypes', async () => {
    verifyEmail.mockRejectedValue(new ApiError(400, 'That code is not correct. Check the email and try again.'));
    renderApp('/signup');
    fillSignup();
    click('Create Account');
    await screen.findByRole('heading', { name: 'Verify your email' });

    pasteCode('111111');

    expect(await screen.findByRole('alert')).toHaveTextContent('That code is not correct');
    expect(screen.getByLabelText('Digit 1 of 6')).toHaveAttribute('aria-invalid', 'true');
    expect(login).not.toHaveBeenCalled();

    fireEvent.keyDown(screen.getByLabelText('Digit 6 of 6'), { key: 'Backspace' });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('on an expired code, offers Resend OTP straight away', async () => {
    verifyEmail.mockRejectedValue(new ApiError(400, 'That code has expired. Request a new one.'));
    renderApp('/signup');
    fillSignup();
    click('Create Account');
    await screen.findByRole('heading', { name: 'Verify your email' });

    pasteCode('222222');

    expect(await screen.findByRole('alert')).toHaveTextContent('expired');
    fireEvent.click(screen.getByRole('button', { name: 'Resend OTP' }));
    await waitFor(() => expect(resendVerification).toHaveBeenCalledWith(EMAIL));
    expect(await screen.findByText('A new code is on its way. Check your inbox.')).toBeInTheDocument();
  });

  it('counts down before Resend OTP is available, then restarts after a resend', async () => {
    // The countdown is a deadline read from Date.now(); moving that clock is enough, and the
    // screen's own 250 ms refresh picks the new time up.
    let clock = 1_800_000_000_000;
    const now = vi.spyOn(Date, 'now').mockImplementation(() => clock);
    try {
      renderApp('/signup');
      fillSignup();
      click('Create Account');
      await screen.findByRole('heading', { name: 'Verify your email' });

      expect(screen.getByText('Resend OTP in 1:00')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Resend OTP' })).not.toBeInTheDocument();

      clock += 15_000;
      expect(await screen.findByText('Resend OTP in 0:45')).toBeInTheDocument();

      clock += 45_000;
      fireEvent.click(await screen.findByRole('button', { name: 'Resend OTP' }));

      await waitFor(() => expect(resendVerification).toHaveBeenCalledTimes(1));
      expect(await screen.findByText('Resend OTP in 1:00')).toBeInTheDocument();
      expect(screen.getByText('A new code is on its way. Check your inbox.')).toBeInTheDocument();
    } finally {
      now.mockRestore();
    }
  });

  it('sends a direct visit to the code screen, with nothing pending, to Login', async () => {
    renderApp('/verify-email');
    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument();
    expect(screen.getByText('Log in to get a verification code.')).toBeInTheDocument();
  });

  it('masks emails down to their first character', () => {
    expect(maskEmail('chirag@gmail.com')).toBe('c****@gmail.com');
    expect(maskEmail('a@b.co')).toBe('a****@b.co');
  });

  // ------------------------------------------------------------------ guard and session (unchanged behaviour)

  it('sends a signed-out visitor from a protected page to Login, and back after signing in', async () => {
    renderApp('/jobs');
    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument();
    type('Email', EMAIL);
    type('Password', PASSWORD);
    click('Login');
    expect(await screen.findByText('Jobs page')).toBeInTheDocument();
  });

  it('restores a session on load, and logging out returns to Login', async () => {
    currentSession.mockResolvedValue(session);
    renderApp('/');

    fireEvent.click(await screen.findByRole('button', { name: 'Log out' }));

    await waitFor(() => expect(logout).toHaveBeenCalledTimes(1));
    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument();
  });

  it('returns to Login when an API call reports the session has ended', async () => {
    currentSession.mockResolvedValue(session);
    renderApp('/jobs');
    expect(await screen.findByText('Jobs page')).toBeInTheDocument();

    act(() => {
      window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
    });

    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument();
  });

  it('shows neither the page nor Login while the session is still being checked', async () => {
    currentSession.mockReturnValue(new Promise(() => {}));
    renderApp('/jobs');

    await waitFor(() => expect(currentSession).toHaveBeenCalled());
    expect(screen.queryByText('Jobs page')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Welcome back' })).not.toBeInTheDocument();
  });
});
