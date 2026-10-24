// Public landing page of the live demo (no sign-in): what PharmaVigil is, the architecture, the shared demo
// accounts with their password (shown on purpose; set on the server, never in the repository), the disclaimers,
// and links to the code, the evidence page and the dashboards. Without demo mode it offers the normal sign-in.
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { useAuth } from "../auth/AuthContext";
import { ArchitectureDiagram } from "../components/ArchitectureDiagram";
import { Banners, NO_CAUSATION, NOT_FOR_CLINICAL_USE, SYNTHETIC_PATIENTS } from "../components/Banners";

export const REPOSITORY = "https://github.com/sumitparmar19/PharmaVigil";

export function LandingPage() {
  const { publicApi } = useAuth();
  const demo = useQuery({ queryKey: ["demo-info"], queryFn: () => publicApi.demoInfo(), retry: false });
  const info = demo.data?.enabled ? demo.data : null;
  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <Banners showSynthetic />
      <main className="mx-auto max-w-5xl space-y-8 px-4 py-8">
        <header className="space-y-3">
          <h1 className="text-3xl font-semibold">PharmaVigil</h1>
          <p className="max-w-3xl text-slate-700">
            An AI drug-safety (pharmacovigilance) platform. A pharmacist reports a side effect in one click; the report is
            de-duplicated, coded to a MedDRA preferred term and triaged for seriousness by machine-learning models, checked
            by a reviewer, and sent as a schema-valid ICH E2B(R3) file to a mock regulator that answers over JMS. Safety
            signals computed from public FDA FAERS reports flow back to the pharmacy counter as evidence, not a verdict.
          </p>
          <div className="flex flex-wrap gap-3 text-sm">
            <Link to="/login" className="rounded-md bg-slate-900 px-4 py-2 text-white">Sign in to the demo</Link>
            <Link to="/evidence" className="rounded-md border bg-white px-4 py-2">Evidence: every number and its data</Link>
            <a href={REPOSITORY} className="rounded-md border bg-white px-4 py-2">Source code on GitHub</a>
            <a href="/grafana/" className="rounded-md border bg-white px-4 py-2">Live dashboards (Grafana)</a>
          </div>
        </header>

        {info && (
          <section aria-label="Demo accounts" className="rounded-lg border border-sky-200 bg-sky-50 p-4 text-sm">
            <h2 className="mb-2 text-lg font-medium">Try it: shared demo accounts</h2>
            <table className="mb-2 text-left">
              <tbody>
                {info.accounts?.map((a) => (
                  <tr key={a.username}><th scope="row" className="pr-4 font-mono">{a.username}</th><td>{a.role}</td></tr>
                ))}
                <tr><th scope="row" className="pr-4">Password</th><td className="font-mono">{info.password}</td></tr>
              </tbody>
            </table>
            <p>
              Sign in as <span className="font-mono">demo-reviewer</span> and press <strong>Run the pipeline</strong>: 200 real
              FAERS reports travel through intake (REST and SOAP), the Kafka stages and the models to the regulator while the
              counters move. The demo resets every night; please do not type personal information.
            </p>
          </section>
        )}

        <section aria-label="Architecture" className="rounded-lg border bg-white p-4">
          <h2 className="mb-2 text-lg font-medium">How it fits together</h2>
          <ArchitectureDiagram />
        </section>

        <section aria-label="Limits" className="text-sm text-slate-700">
          <h2 className="mb-1 text-lg font-medium text-slate-900">What this is not</h2>
          <ul className="list-disc space-y-1 pl-5">
            <li>{NOT_FOR_CLINICAL_USE} It is a portfolio project.</li>
            <li>{NO_CAUSATION}</li>
            <li>{SYNTHETIC_PATIENTS} The replayed reports are public, de-identified FAERS records.</li>
            <li>AI outputs are suggestions; in the demo, replayed cases are auto-reviewed so they can reach the regulator,
              and are labelled as such.</li>
          </ul>
        </section>
      </main>
    </div>
  );
}
