// "Run the pipeline": replays a batch of real, de-identified FAERS reports through the real system and shows
// each stage counting up live. The first two counters come from intake-gateway (what it sent and accepted,
// over SOAP and REST); every later counter is counted by case-service from its own audit trail, so the tiles show
// what really happened to these cases. Finished cases can be opened end to end (reviewer accounts).
import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router";
import type { DemoRun, DemoStages } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { ErrorNote } from "../components/ErrorNote";
import { Layout } from "../components/Layout";

const POLL_MS = 1500;

export function stageTiles(run: DemoRun | undefined, s: DemoStages | undefined) {
  return [
    { label: "Sent to intake", value: run?.sent ?? 0, of: run?.size, note: run ? `${run.viaSoap} SOAP · ${run.viaRest} REST` : "" },
    { label: "Accepted by intake", value: run?.accepted ?? 0, of: run?.size, note: run && run.rejected ? `${run.rejected} rejected` : "E2B(R3) schema-checked" },
    { label: "Case created (Kafka)", value: s?.received ?? 0, of: run?.accepted, note: "report.received" },
    { label: "De-duplicated", value: s?.deduplicated ?? 0, of: run?.accepted, note: s ? `${s.possibleDuplicates} possible duplicates` : "" },
    { label: "MedDRA coded", value: (s?.codedByModel ?? 0) + (s?.manualCoding ?? 0), of: run?.accepted, note: s ? `${s.codedByModel} by the model · ${s.manualCoding} manual` : "" },
    { label: "Triaged", value: s?.triaged ?? 0, of: run?.accepted, note: s ? `${s.serious} serious` : "" },
    { label: "Approved (demo auto-review)", value: s?.approved ?? 0, of: run?.accepted, note: "decisions stored" },
    { label: "E2B(R3) sent over JMS", value: s?.submitted ?? 0, of: run?.accepted, note: "case.ready → Artemis" },
    { label: "Regulator answered", value: (s?.acknowledged ?? 0) + (s?.rejected ?? 0), of: run?.accepted, note: s ? `${s.acknowledged} ACK · ${s.rejected} NACK` : "" },
  ];
}

export function DemoRunPage() {
  const { api, hasRole } = useAuth();
  const [runId, setRunId] = useState<string | null>(null);
  const start = useMutation({ mutationFn: () => api.startDemoRun(200), onSuccess: (r) => setRunId(r.runId) });
  const run = useQuery({ queryKey: ["demo-run", runId], queryFn: () => api.demoRun(runId!), enabled: runId !== null,
    refetchInterval: POLL_MS });
  const stages = useQuery({ queryKey: ["demo-stages", runId], queryFn: () => api.demoStages(runId!),
    enabled: runId !== null, refetchInterval: POLL_MS });
  const finished = useQuery({ queryKey: ["demo-cases", runId], queryFn: () => api.demoCases(runId!),
    enabled: runId !== null && (stages.data?.submitted ?? 0) > 0, refetchInterval: 5000 });
  const tiles = stageTiles(run.data, stages.data);
  const reviewer = hasRole("SAFETY_REVIEWER", "ADMIN");

  return (
    <Layout>
      <h1 className="text-2xl font-semibold">Run the pipeline</h1>
      <p className="mb-4 max-w-3xl text-sm text-slate-600">
        Sends 200 real, de-identified FAERS reports (a random sample of the newest quarter loaded) through the whole
        system: intake over SOAP and REST as E2B(R3) messages, the Kafka stages (de-duplication, MedDRA coding, seriousness
        triage), a demo auto-review, the E2B(R3) export and the JMS hand-over to the mock regulator. One run at a time per
        account, then a short cooldown.
      </p>
      <button type="button" className="rounded-md bg-slate-900 px-4 py-2 text-white disabled:opacity-50"
        disabled={start.isPending || (run.data !== undefined && run.data.finishedAt === null)}
        onClick={() => start.mutate()}>
        {start.isPending ? "Starting…" : "Run the pipeline"}
      </button>
      <ErrorNote error={start.error ?? run.error ?? stages.error} />

      {runId && (
        <section aria-label="Pipeline stages" className="mt-6">
          <p className="mb-2 text-sm text-slate-600">Run <span className="font-mono">{runId}</span></p>
          <ol className="grid gap-3 sm:grid-cols-3">
            {tiles.map((t) => (
              <li key={t.label} className="rounded-lg border bg-white p-3">
                <div className="text-xs uppercase tracking-wide text-slate-500">{t.label}</div>
                <div className="text-2xl font-semibold tabular-nums" aria-label={`${t.label}: ${t.value}`}>
                  {t.value}{t.of ? <span className="text-base font-normal text-slate-500"> / {t.of}</span> : null}
                </div>
                <div className="text-xs text-slate-600">{t.note}</div>
              </li>
            ))}
          </ol>
        </section>
      )}

      {finished.data && finished.data.length > 0 && (
        <section aria-label="Open a case" className="mt-6 rounded-lg border bg-white p-3 text-sm">
          <h2 className="mb-2 font-medium">Open one case end to end</h2>
          {!reviewer && <p className="mb-2 text-slate-600">Sign in as demo-reviewer to open a case with its E2B(R3) file and the regulator's answer.</p>}
          <ul className="space-y-1">
            {finished.data.map((c) => (
              <li key={c.id}>
                {reviewer ? <Link className="text-blue-700 underline" to={`/review/${c.id}`}>{c.caseNumber}</Link> : c.caseNumber}
                {" "}· {c.status} · {c.seriousness}
              </li>
            ))}
          </ul>
        </section>
      )}
      <p className="mt-6 text-sm text-slate-600">
        Watch the same run on the <a className="text-blue-700 underline" href="/grafana/">pipeline dashboard</a>.
      </p>
    </Layout>
  );
}
