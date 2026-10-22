// Page frame: the disclaimer banners, a navigation bar with the pages the signed-in roles may use, and sign-out.
import type { ReactNode } from "react";
import { NavLink } from "react-router";
import { useAuth } from "../auth/AuthContext";
import { Banners } from "./Banners";

const link = ({ isActive }: { isActive: boolean }) =>
  `px-3 py-2 rounded-md text-sm font-medium ${isActive ? "bg-slate-900 text-white" : "text-slate-700 hover:bg-slate-200"}`;

export function Layout({ children, synthetic = false }: { children: ReactNode; synthetic?: boolean }) {
  const { session, signOut, hasRole } = useAuth();
  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <Banners showSynthetic={synthetic} />
      <header className="border-b border-slate-200 bg-white">
        <nav aria-label="Main" className="mx-auto flex max-w-6xl items-center gap-2 px-4 py-2">
          <span className="mr-4 font-semibold">PharmaVigil</span>
          {hasRole("PHARMACIST") && <NavLink to="/pharmacy" className={link}>Pharmacy</NavLink>}
          {hasRole("SAFETY_REVIEWER", "ADMIN") && <NavLink to="/review" className={link}>Review queue</NavLink>}
          <NavLink to="/signals" className={link}>Signals</NavLink>
          {session && (
            <span className="ml-auto flex items-center gap-3 text-sm text-slate-600">
              {session.displayName}
              <button type="button" onClick={signOut} className="rounded-md border px-2 py-1 hover:bg-slate-100">
                Sign out
              </button>
            </span>
          )}
        </nav>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6">{children}</main>
    </div>
  );
}
