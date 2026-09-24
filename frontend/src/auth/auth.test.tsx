import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ApiError, UNAUTHORIZED_EVENT } from '../api/client';
import type { AuthSession } from '../api/types';
import { AppShell } from '../components/AppShell';
import { Login } from '../pages/Login';
import { Signup } from '../pages/Signup';
import { AuthProvider } from './AuthContext';
import { RequireAuth } from './RequireAuth';

const login = vi.fn();
const signup = vi.fn();
const logout = vi.fn();
const currentSession = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return {
    ...actual,
    api: {
      login: (...args: unknown[]) => login(...args),
      signup: (...args: unknown[]) => signup(...args),
      logout: (...args: unknown[]) => logout(...args),
      currentSession: (...args: unknown[]) => currentSession(...args),
    },
  };
});

const session: AuthSession = {
  user: { id: 'u-1', email: 'jane@example.com', role: 'USER', createdAt: '2026-09-24T10:00:00Z' },
  csrfToken: 'token-1',
};

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <AppShell>
          <Routes>
            <Route path="/" element={<p>Dashboard home</p>} />
            <Route path="/login" element={<Login />} />
            <Route path="/signup" element={<Signup />} />
          </Routes>
        </AppShell>
      </AuthProvider>
    </MemoryRouter>,
  );
}

/** The app's real arrangement: protected routes inside RequireAuth, Log in outside it. */
function renderGuarded(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Routes>
          <Route element={<RequireAuth />}>
            <Route path="/jobs" element={<p>Jobs page</p>} />
            <Route path="/" element={<p>Dashboard home</p>} />
          </Route>
          <Route path="/login" element={<Login />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

function type(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

describe('authentication', () => {
  beforeEach(() => {
    [login, signup, logout, currentSession].forEach((mock) => mock.mockReset());
    currentSession.mockResolvedValue(null);
    login.mockResolvedValue(session);
    signup.mockResolvedValue(session.user);
    logout.mockResolvedValue(undefined);
  });

  afterEach(cleanup);

  it('logs in and lands on the Dashboard, with the user in the header', async () => {
    renderAt('/login');
    type('Email', 'jane@example.com');
    type('Password', 'correct horse battery');
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }));

    expect(await screen.findByText('Dashboard home')).toBeInTheDocument();
    expect(login).toHaveBeenCalledWith('jane@example.com', 'correct horse battery');
    expect(screen.getByText('jane@example.com')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Log out' })).toBeInTheDocument();
  });

  it('shows the backend message on a failed login and clears the password', async () => {
    login.mockRejectedValue(new ApiError(401, 'Invalid email or password'));
    renderAt('/login');
    type('Email', 'jane@example.com');
    type('Password', 'wrong password');
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password');
    expect(screen.getByLabelText('Password')).toHaveValue('');
    expect(screen.queryByText('Dashboard home')).not.toBeInTheDocument();
  });

  it('shows a disabled, busy button while signing in', async () => {
    let finish: (value: AuthSession) => void = () => {};
    login.mockReturnValue(new Promise<AuthSession>((resolve) => (finish = resolve)));
    renderAt('/login');
    type('Email', 'jane@example.com');
    type('Password', 'correct horse battery');
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }));

    expect(await screen.findByRole('button', { name: 'Signing in…' })).toBeDisabled();
    finish(session);
    expect(await screen.findByText('Dashboard home')).toBeInTheDocument();
  });

  it('checks length and confirmation before submitting a signup', async () => {
    renderAt('/signup');
    type('Email', 'jane@example.com');
    type('Password', 'short');
    expect(await screen.findByText('Use at least 12 characters.')).toBeInTheDocument();

    type('Password', 'correct horse battery');
    type('Confirm password', 'correct horse batterx');
    expect(screen.getByText('The passwords do not match.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create account' })).toBeDisabled();
    expect(signup).not.toHaveBeenCalled();
  });

  it('shows the backend reason when signup is refused', async () => {
    signup.mockRejectedValue(new ApiError(409, 'An account with this email already exists'));
    renderAt('/signup');
    type('Email', 'jane@example.com');
    type('Password', 'correct horse battery');
    type('Confirm password', 'correct horse battery');
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('already exists');
    expect(login).not.toHaveBeenCalled();
  });

  it('signs up, signs in with the same credentials and lands on the Dashboard', async () => {
    renderAt('/signup');
    type('Email', 'jane@example.com');
    type('Password', 'correct horse battery');
    type('Confirm password', 'correct horse battery');
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByText('Dashboard home')).toBeInTheDocument();
    expect(signup).toHaveBeenCalledWith('jane@example.com', 'correct horse battery');
    expect(login).toHaveBeenCalledWith('jane@example.com', 'correct horse battery');
  });

  it('restores a session on load, and logging out returns to the signed-out header', async () => {
    currentSession.mockResolvedValue(session);
    renderAt('/');

    fireEvent.click(await screen.findByRole('button', { name: 'Log out' }));

    await waitFor(() => expect(logout).toHaveBeenCalledTimes(1));
    expect(await screen.findByRole('link', { name: 'Log in' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Sign up' })).toBeInTheDocument();
    expect(screen.queryByText('jane@example.com')).not.toBeInTheDocument();
    // Logging out lands on the login page.
    expect(await screen.findByRole('button', { name: 'Log in' })).toBeInTheDocument();
  });

  it('sends a signed-out visitor from a protected page to Log in, and back after signing in', async () => {
    renderGuarded('/jobs?q=java');

    expect(await screen.findByRole('button', { name: 'Log in' })).toBeInTheDocument();
    expect(screen.queryByText('Jobs page')).not.toBeInTheDocument();

    type('Email', 'jane@example.com');
    type('Password', 'correct horse battery');
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }));

    expect(await screen.findByText('Jobs page')).toBeInTheDocument();
  });

  it('shows a protected page to a signed-in user', async () => {
    currentSession.mockResolvedValue(session);
    renderGuarded('/jobs');

    expect(await screen.findByText('Jobs page')).toBeInTheDocument();
  });

  it('returns to Log in when an API call reports the session has ended', async () => {
    currentSession.mockResolvedValue(session);
    renderGuarded('/jobs');
    expect(await screen.findByText('Jobs page')).toBeInTheDocument();

    act(() => {
      window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
    });

    expect(await screen.findByRole('button', { name: 'Log in' })).toBeInTheDocument();
    expect(screen.queryByText('Jobs page')).not.toBeInTheDocument();
  });

  it('shows neither the page nor Log in while the session is still being checked', async () => {
    currentSession.mockReturnValue(new Promise(() => {}));
    renderGuarded('/jobs');

    await waitFor(() => expect(currentSession).toHaveBeenCalled());
    expect(screen.queryByText('Jobs page')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Log in' })).not.toBeInTheDocument();
  });

  it('shows Log in and Sign up when nobody is signed in', async () => {
    renderAt('/');

    expect(await screen.findByRole('link', { name: 'Log in' })).toHaveAttribute('href', '/login');
    expect(screen.getByRole('link', { name: 'Sign up' })).toHaveAttribute('href', '/signup');
  });
});
