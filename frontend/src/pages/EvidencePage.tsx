// Public evidence page: every number the project reports, each with its data scope and a link to the committed
// result file it comes from (evidence.json is generated from analysis/ by frontend/scripts/build-evidence.mjs at
// build time). Items that are not measured yet say "pending" plainly.
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { useAuth } from "../auth/AuthContext";
import { Banners, NO_CAUSATION } from "../components/Banners";
import { ErrorNote } from "../components/ErrorNote";


export function EvidencePage() {
  const { publicApi } = useAuth();
  const evidence = useQuery({ queryKey: ["evidence"], queryFn: () => publicApi.evidence(), retry: false });
  const e = evidence.data;
  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <Banners />
      <main className="mx-auto max-w-5xl space-y-6 px-4 py-8">
        <header>
          <p className="text-sm"><Link to="/" className="text-blue-700 underline">PharmaVigil</Link></p>
          <h1 className="text-2xl font-semibold">Evidence</h1>
          <p className="max-w-3xl text-sm text-slate-600">
            Every number below was produced by a script in the repository and its result file is committed; each states
            the data it was measured on. {NO_CAUSATION}
          </p>
        </header>
        <ErrorNote error={evidence.error} />
        {e?.sections.map((s) => (
          <section key={s.title} aria-label={s.title} className="rounded-lg border bg-white p-4">
            <h2 className="mb-2 text-lg font-medium">{s.title}</h2>
            <table className="w-full text-left text-sm">
              <thead className="text-slate-600">
                <tr><th scope="col">Measure</th><th scope="col">Result</th><th scope="col">Data scope</th><th scope="col">Source</th></tr>
              </thead>
              <tbody>
                {s.items.map((i) => (
                  <tr key={i.label} className="border-t align-top">
                    <td className="py-1 pr-2">{i.label}{i.note && <div className="text-xs text-slate-500">{i.note}</div>}</td>
                    <td className={`py-1 pr-2 ${i.status === "pending" ? "text-amber-800" : "font-semibold"}`}>{i.value}</td>
                    <td className="py-1 pr-2">{i.scope}</td>
                    <td className="py-1"><a className="text-blue-700 underline" href={`${e.repository}/blob/main/${i.file}`}>{i.file}</a></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        ))}
        {e && <p className="text-xs text-slate-500">Generated {e.generatedAt}.</p>}
      </main>
    </div>
  );
}
