// Holds the signed-in session (token, name, roles) for the tab and exposes one ApiClient that sends the token.
// The token lives in sessionStorage so a reload keeps the user signed in but closing the tab signs them out.
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { ApiClient } from "../api/client";
import type { Session } from "../api/types";

const STORAGE_KEY = "pv.session";

interface AuthState {
  session: Session | null;
  api: ApiClient;
  signIn: (username: string, password: string) => Promise<Session>;
  signOut: () => void;
  hasRole: (...roles: string[]) => boolean;
}

const AuthContext = createContext<AuthState | null>(null);

function stored(): Session | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

export function AuthProvider({ children, fetchImpl }: { children: ReactNode; fetchImpl?: typeof fetch }) {
  const [session, setSession] = useState<Session | null>(stored);
  const api = useMemo(() => new ApiClient(() => session?.token ?? null, fetchImpl), [session, fetchImpl]);

  const signIn = useCallback(async (username: string, password: string) => {
    const s = await new ApiClient(() => null, fetchImpl).login(username, password);
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(s));
    setSession(s);
    return s;
  }, [fetchImpl]);

  const signOut = useCallback(() => {
    sessionStorage.removeItem(STORAGE_KEY);
    setSession(null);
  }, []);

  const hasRole = useCallback((...roles: string[]) => !!session && roles.some((r) => session.roles.includes(r)),
    [session]);

  const value = useMemo(() => ({ session, api, signIn, signOut, hasRole }), [session, api, signIn, signOut, hasRole]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside AuthProvider");
  return ctx;
}
