import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { api, EmailNotVerifiedError, UNAUTHORIZED_EVENT } from '../api/client';
import type { AuthUser, VerificationStatus } from '../api/types';

export type AuthStatus = 'loading' | 'signedIn' | 'signedOut';

/** The account waiting for its email code. Its password never leaves memory. */
interface PendingVerification {
  email: string;
}

interface AuthValue {
  status: AuthStatus;
  user: AuthUser | null;
  /** Set after signup, or after a login refused for an unconfirmed email. */
  pendingVerification: PendingVerification | null;
  /** @throws EmailNotVerifiedError when the password was right but the email is unconfirmed */
  login: (email: string, password: string) => Promise<void>;
  /** Creates the account; the backend emails a code and the UI moves to the code screen. */
  signup: (fullName: string, email: string, password: string) => Promise<void>;
  /**
   * Confirms the email. Signs in straight away when the password typed a moment ago is still
   * in memory; otherwise returns 'needsLogin' (e.g. after a page reload).
   */
  verifyEmail: (code: string) => Promise<'signedIn' | 'needsLogin'>;
  resendVerification: () => Promise<VerificationStatus>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthValue | null>(null);

/**
 * Who is signed in, for the whole app.
 *
 * <p>Nothing about the session is stored in the browser by this code: the backend keeps it
 * and identifies it by an HttpOnly cookie. On load the provider simply asks the backend,
 * so a reload or a second tab shows the right state without anything in localStorage.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [user, setUser] = useState<AuthUser | null>(null);
  const [pendingVerification, setPendingVerification] = useState<PendingVerification | null>(null);
  // Only in memory, only between "create account"/"login" and entering the code.
  const pendingPassword = useRef<string | null>(null);

  useEffect(() => {
    let active = true;
    api
      .currentSession()
      .then((session) => {
        if (active) {
          setUser(session?.user ?? null);
          setStatus(session ? 'signedIn' : 'signedOut');
        }
      })
      .catch(() => {
        // An unreachable backend is shown by the pages themselves; here it means signed out.
        if (active) {
          setStatus('signedOut');
        }
      });
    return () => {
      active = false;
    };
  }, []);

  // A 401 from any application API means the session ended (expired, or signed out in
  // another tab). Show the signed-out state; RequireAuth then sends the user to Log in.
  useEffect(() => {
    const onUnauthorized = () => {
      setUser(null);
      setStatus('signedOut');
    };
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
  }, []);

  const awaitVerification = useCallback((email: string, password: string) => {
    pendingPassword.current = password;
    setPendingVerification({ email: email.trim() });
  }, []);

  const login = useCallback(
    async (email: string, password: string) => {
      try {
        const session = await api.login(email, password);
        pendingPassword.current = null;
        setPendingVerification(null);
        setUser(session.user);
        setStatus('signedIn');
      } catch (error) {
        if (error instanceof EmailNotVerifiedError) {
          awaitVerification(email, password);
        }
        throw error;
      }
    },
    [awaitVerification],
  );

  const signup = useCallback(
    async (fullName: string, email: string, password: string) => {
      await api.signup(fullName, email, password);
      awaitVerification(email, password);
    },
    [awaitVerification],
  );

  const verifyEmail = useCallback(
    async (code: string): Promise<'signedIn' | 'needsLogin'> => {
      if (!pendingVerification) {
        return 'needsLogin';
      }
      await api.verifyEmail(pendingVerification.email, code);
      const password = pendingPassword.current;
      if (!password) {
        setPendingVerification(null);
        return 'needsLogin';
      }
      await login(pendingVerification.email, password);
      return 'signedIn';
    },
    [pendingVerification, login],
  );

  const resendVerification = useCallback(async () => {
    if (!pendingVerification) {
      throw new Error('No email is waiting for verification');
    }
    return api.resendVerification(pendingVerification.email);
  }, [pendingVerification]);

  const logout = useCallback(async () => {
    try {
      await api.logout();
    } finally {
      // Even if the call failed, this browser should stop showing a signed-in user.
      setUser(null);
      setStatus('signedOut');
    }
  }, []);

  const value = useMemo(
    () => ({ status, user, pendingVerification, login, signup, verifyEmail, resendVerification, logout }),
    [status, user, pendingVerification, login, signup, verifyEmail, resendVerification, logout],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return value;
}
