// Test harness: renders a page inside the real providers and routes, with a scripted fetch that answers by
// "METHOD path" and records every request, and an optional signed-in session.
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import type { Session } from "../api/types";
import { AppRoutes } from "../App";
import { AuthProvider } from "../auth/AuthContext";

export interface Call {
  method: string;
  path: string;
  body: unknown;
  auth: string | null;
}

type Responder = unknown | ((body: unknown) => { status: number; body: unknown });

export function scriptedFetch(routes: Record<string, Responder>) {
  const calls: Call[] = [];
  const impl = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    const method = init?.method ?? "GET";
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    const headers = (init?.headers ?? {}) as Record<string, string>;
    calls.push({ method, path, body, auth: headers.Authorization ?? null });
    const key = `${method} ${path}`;
    const r = routes[key] ?? routes[`${method} ${path.split("?")[0]}`];
    if (r === undefined) {
      return new Response(JSON.stringify({ status: 404, detail: `no route for ${key}` }), { status: 404 });
    }
    const res = typeof r === "function" ? (r as (b: unknown) => { status: number; body: unknown })(body)
      : { status: 200, body: r };
    return new Response(res.body === undefined ? null : JSON.stringify(res.body), {
      status: res.status,
      headers: { "Content-Type": "application/json" },
    });
  }) as typeof fetch;
  return { impl, calls };
}

export const pharmacist: Session = {
  token: "test-token-pharmacist", username: "pharm", displayName: "Pat Pharmacist", roles: ["PHARMACIST"], patientId: null,
};
export const reviewer: Session = {
  token: "test-token-reviewer", username: "rev", displayName: "Rae Reviewer", roles: ["SAFETY_REVIEWER"], patientId: null,
};

export function renderApp(path: string, fetchImpl: typeof fetch, session: Session | null) {
  sessionStorage.clear();
  if (session) sessionStorage.setItem("pv.session", JSON.stringify(session));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AuthProvider fetchImpl={fetchImpl}>
        <MemoryRouter initialEntries={[path]}>
          <AppRoutes />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
}
