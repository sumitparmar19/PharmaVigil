// Sign-in for pharmacists, safety reviewers and admins (demo accounts are created by the "demo" profile).
import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { useAuth } from "../auth/AuthContext";
import { Banners } from "../components/Banners";
import { ErrorNote } from "../components/ErrorNote";

export function LoginPage() {
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const s = await signIn(username, password);
      navigate(s.roles.includes("PHARMACIST") ? "/pharmacy" : s.roles.includes("SAFETY_REVIEWER") ? "/review" : "/signals");
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="min-h-screen bg-slate-50">
      <Banners showSynthetic />
      <form onSubmit={submit} className="mx-auto mt-16 max-w-sm space-y-4 rounded-lg border bg-white p-6 shadow-sm">
        <h1 className="text-xl font-semibold">Sign in to PharmaVigil</h1>
        <label className="block text-sm">
          Username
          <input className="mt-1 w-full rounded-md border px-2 py-1" value={username} autoComplete="username"
            onChange={(e) => setUsername(e.target.value)} required />
        </label>
        <label className="block text-sm">
          Password
          <input className="mt-1 w-full rounded-md border px-2 py-1" type="password" value={password}
            autoComplete="current-password" onChange={(e) => setPassword(e.target.value)} required />
        </label>
        <ErrorNote error={error} />
        <button type="submit" disabled={busy}
          className="w-full rounded-md bg-slate-900 px-3 py-2 text-white disabled:opacity-50">
          {busy ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </div>
  );
}
