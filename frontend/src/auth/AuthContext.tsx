import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { api, configureApiClient } from '../lib/apiClient';

export interface AuthUser {
  id: string;
  username: string;
  email: string;
  karmaPost: number;
  karmaComment: number;
  status: string;
  createdAt: string;
}

interface AuthResponse {
  accessToken: string;
  tokenType: string;
}

interface AuthContextValue {
  user: AuthUser | null;
  initializing: boolean;
  login: (username: string, password: string) => Promise<void>;
  register: (username: string, email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  // The access token lives in a ref, not state: apiClient reads it through a getter closure
  // registered once, so a stale snapshot from a re-render would silently keep sending an old
  // token. It is never written to localStorage/sessionStorage — only the httpOnly refresh
  // cookie persists a session across reloads.
  const tokenRef = useRef<string | null>(null);
  const [user, setUser] = useState<AuthUser | null>(null);
  const [initializing, setInitializing] = useState(true);

  const loadMe = useCallback(async () => {
    const me = (await api.get('/api/v1/me')) as AuthUser;
    setUser(me);
  }, []);

  const refresh = useCallback(async (): Promise<string | null> => {
    try {
      const auth = (await api.post('/api/v1/access_token/refresh')) as AuthResponse;
      tokenRef.current = auth.accessToken;
      return auth.accessToken;
    } catch {
      tokenRef.current = null;
      setUser(null);
      return null;
    }
  }, []);

  useEffect(() => {
    configureApiClient({ getAccessToken: () => tokenRef.current, refresh });
  }, [refresh]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const token = await refresh();
      if (cancelled) return;
      if (token) {
        try {
          await loadMe();
        } catch {
          tokenRef.current = null;
        }
      }
      if (!cancelled) setInitializing(false);
    })();
    return () => {
      cancelled = true;
    };
  }, [refresh, loadMe]);

  const applySession = useCallback(
    async (auth: AuthResponse) => {
      tokenRef.current = auth.accessToken;
      await loadMe();
    },
    [loadMe],
  );

  const login = useCallback(
    async (username: string, password: string) => {
      const auth = (await api.post('/api/v1/access_token', { username, password })) as AuthResponse;
      await applySession(auth);
    },
    [applySession],
  );

  // No session to apply — the backend no longer logs a new account in (it must be verified first), so
  // this just creates the account and lets the caller (Register.tsx) send the user to /login.
  const register = useCallback(async (username: string, email: string, password: string) => {
    await api.post('/api/v1/register', { username, email, password });
  }, []);

  const logout = useCallback(async () => {
    try {
      await api.post('/api/v1/logout');
    } finally {
      // Client state is cleared regardless of whether the network call succeeded — the user
      // expects to be logged out locally even if the revoke request failed in flight.
      tokenRef.current = null;
      setUser(null);
    }
  }, []);

  return (
    <AuthContext.Provider value={{ user, initializing, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
