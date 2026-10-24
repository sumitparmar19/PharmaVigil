// Drug–event signals learned from public FAERS reports: the top pairs per method (EBGM, PRR, ROR, IC,
// SafetyFormer), the yearly trend of a chosen pair, and the reference-set evaluation summary. Every view states its
// data scope and that spontaneous reports do not prove causation. With no export loaded, it says so.
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import type { Signal, SignalMethod } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { NO_CAUSATION } from "../components/Banners";
import { ErrorNote } from "../components/ErrorNote";
import { Layout } from "../components/Layout";
import { TrendChart } from "../components/TrendChart";

export const METHODS: { id: SignalMethod; label: string; score: (s: Signal) => number | null }[] = [
  { id: "ebgm", label: "EBGM (EB05)", score: (s) => s.eb05 },
  { id: "prr", label: "PRR (lower 95%)", score: (s) => s.prrLo },
  { id: "ror", label: "ROR (lower 95%)", score: (s) => s.rorLo },
  { id: "ic", label: "IC (IC025)", score: (s) => s.ic025 },
  { id: "safetyformer", label: "SafetyFormer", score: (s) => s.safetyformer },
];

function num(v: unknown, digits = 3): string {
  return typeof v === "number" ? v.toFixed(digits) : "—";
}

export function SignalsPage() {
  const { api } = useAuth();
  const [method, setMethod] = useState<SignalMethod>("ebgm");
  const [pair, setPair] = useState<Signal | null>(null);
  const meta = useQuery({ queryKey: ["signal-meta"], queryFn: () => api.signalMeta() });
  const top = useQuery({ queryKey: ["signals", method], queryFn: () => api.topSignals(method, 25) });
  const trend = useQuery({
    queryKey: ["trend", pair?.rxcui, pair?.pt],
    queryFn: () => api.trend(pair!.rxcui, pair!.pt),
    enabled: pair !== null,
  });
  const m = METHODS.find((x) => x.id === method)!;
  const evaluation = meta.data?.evaluation as
    Record<string, { methods?: Record<string, { auc?: number; ci_low?: number; ci_high?: number }> }> | null | undefined;

  return (
    <Layout>
      <h1 className="text-2xl font-semibold">Drug–event signals</h1>
      <p className="mb-4 text-sm text-slate-600">{NO_CAUSATION}</p>
      <ErrorNote error={meta.error ?? top.error} />
      {meta.data && !meta.data.loaded && (
        <p role="status" className="rounded-md border bg-white p-3 text-sm">
          No signal data is loaded yet. Signals appear once a FAERS export has been computed and loaded
          (see OWNER_STEPS.md); nothing is shown in the meantime rather than invented numbers.
        </p>
      )}
      {meta.data?.loaded && (
        <p className="mb-3 text-sm">Data scope: <strong>{meta.data.scope}</strong> · {meta.data.pairs} drug–event pairs</p>
      )}

      <div role="tablist" aria-label="Method" className="mb-3 flex flex-wrap gap-2">
        {METHODS.map((x) => (
          <button key={x.id} role="tab" aria-selected={x.id === method} type="button"
            className={`rounded-md border px-3 py-1 text-sm ${x.id === method ? "bg-slate-900 text-white" : "bg-white"}`}
            onClick={() => { setMethod(x.id); setPair(null); }}>{x.label}</button>
        ))}
      </div>

      <div className="grid gap-6 md:grid-cols-[2fr_1fr]">
        <section aria-label="Top signals" className="rounded-lg border bg-white p-3">
          {top.data && top.data.signals.length === 0 && <p className="text-sm">No pairs to rank for this method.</p>}
          {top.data && top.data.signals.length > 0 && (
            <table className="w-full text-left text-sm">
              <thead className="text-slate-600">
                <tr><th scope="col">Drug</th><th scope="col">Reaction (PT)</th><th scope="col">Reports</th><th scope="col">{m.label}</th></tr>
              </thead>
              <tbody>
                {top.data.signals.map((s) => (
                  <tr key={`${s.rxcui}|${s.pt}`} className={`border-t ${pair?.rxcui === s.rxcui && pair.pt === s.pt ? "bg-blue-50" : ""}`}>
                    <td>
                      <button type="button" className="text-left text-blue-700 underline" onClick={() => setPair(s)}>
                        {s.ingredient ?? s.rxcui}
                      </button>
                    </td>
                    <td>{s.pt}</td>
                    <td>{s.reports}</td>
                    <td>{num(m.score(s), 2)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        <aside className="space-y-4">
          <section aria-label="Trend" className="rounded-lg border bg-white p-3">
            {pair ? (
              <TrendChart title={`${pair.ingredient ?? pair.rxcui} – ${pair.pt}`} points={trend.data?.points ?? []} />
            ) : <p className="text-sm text-slate-600">Choose a pair to see its reports per year.</p>}
          </section>
          <section aria-label="Evaluation" className="rounded-lg border bg-white p-3 text-sm">
            <h2 className="mb-1 font-medium">Reference-set evaluation (AUC)</h2>
            {!evaluation && <p className="text-slate-600">No evaluation summary loaded.</p>}
            {evaluation && (
              <table className="w-full text-left">
                <thead className="text-slate-600">
                  <tr><th scope="col">Set</th><th scope="col">Method</th><th scope="col">AUC (95% CI)</th></tr>
                </thead>
                <tbody>
                  {Object.entries(evaluation).flatMap(([set, summary]) =>
                    Object.entries(summary.methods ?? {}).map(([name, v]) => (
                      <tr key={`${set}-${name}`}>
                        <td>{set}</td>
                        <td>{name}</td>
                        <td>{num(v.auc, 2)} ({num(v.ci_low, 2)}–{num(v.ci_high, 2)})</td>
                      </tr>
                    )))}
                </tbody>
              </table>
            )}
          </section>
        </aside>
      </div>
    </Layout>
  );
}
