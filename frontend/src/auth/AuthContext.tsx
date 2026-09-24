import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api } from '../api/client';
import type { AuthUser } from '../api/types';

export type AuthStatus = 'loading' | 'signedIn' | 'signedOut';

interface AuthValue {
  status: AuthStatus;
  user: AuthUser | null;
  login: (email: string, password: string) => Promise<void>;
  /** Creates the account, then signs straight in with the same credentials. */
  signup: (email: string, password: string) => Promise<void>;
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

  const login = useCallback(async (email: string, password: string) => {
    const session = await api.login(email, password);
    setUser(session.user);
    setStatus('signedIn');
  }, []);

  const signup = useCallback(
    async (email: string, password: string) => {
      await api.signup(email, password);
      await login(email, password);
    },
    [login],
  );

  const logout = useCallback(async () => {
    try {
      await api.logout();
    } finally {
      // Even if the call failed, this browser should stop showing a signed-in user.
      setUser(null);
      setStatus('signedOut');
    }
  }, []);

  const value = useMemo(() => ({ status, user, login, signup, logout }), [status, user, login, signup, logout]);
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
