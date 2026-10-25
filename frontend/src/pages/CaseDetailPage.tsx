// One case for the safety reviewer: the reported facts, the coder's suggestions with scores (accept or correct
// each), seriousness, the duplicate flag, the narrative (edit, approve), approval, submission and the regulator's
// answers. Every action is a separate, audited server call; the page reloads the case after each one.
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useParams } from "react-router";
import type { CaseDetail, ReactionView } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { ErrorNote } from "../components/ErrorNote";
import { Layout } from "../components/Layout";

function Reaction({ r, index, detail, onDone }: { r: ReactionView; index: number; detail: CaseDetail; onDone: () => void }) {
  const { api } = useAuth();
  const [pt, setPt] = useState(r.pt ?? "");
  const code = useMutation({
    mutationFn: (value: string) => api.codeReaction(detail.head.id, index, value, detail.lockVersion),
    onSuccess: onDone,
  });
  const suggestion = r.codeSource === "MODEL";
  return (
    <li className="rounded-md border p-3">
      <div className="text-sm text-slate-600">Reported: “{r.reportedTerm}”</div>
      <div className="mt-1 flex flex-wrap items-center gap-2">
        {r.pt && (
          <span className={`rounded px-2 py-0.5 text-sm ${suggestion ? "bg-blue-100 text-blue-900" : "bg-green-100 text-green-900"}`}>
            {suggestion ? "Suggested" : "Coded"}: {r.pt}
            {suggestion && r.modelScore !== null && ` (score ${r.modelScore.toFixed(2)})`}
          </span>
        )}
        {suggestion && r.pt && (
          <button type="button" className="rounded-md border px-2 py-1 text-sm" disabled={code.isPending}
            onClick={() => code.mutate(r.pt!)}>Accept</button>
        )}
        <label className="text-sm">
          <span className="sr-only">Preferred term for reaction {index + 1}</span>
          <input className="rounded-md border px-2 py-1" value={pt} onChange={(e) => setPt(e.target.value)}
            placeholder="MedDRA PT" />
        </label>
        <button type="button" className="rounded-md border px-2 py-1 text-sm" disabled={!pt.trim() || code.isPending}
          onClick={() => code.mutate(pt.trim())}>{r.pt ? "Correct" : "Code"}</button>
      </div>
      {suggestion && r.modelName && (
        <div className="mt-1 text-xs text-slate-500">{r.modelName} {r.modelVersion}</div>
      )}
      <ErrorNote error={code.error} />
    </li>
  );
}

/** The E2B(R3) file of the case as case-service exports it (the same XML the regulator receives). */
function E2bFile({ caseId }: { caseId: number }) {
  const { api } = useAuth();
  const [open, setOpen] = useState(false);
  const xml = useQuery({ queryKey: ["e2b", caseId], queryFn: () => api.e2bXml(caseId), enabled: open });
  return (
    <section aria-label="E2B(R3) file" className="mt-6 rounded-lg border bg-white p-4 text-sm">
      <div className="flex items-center gap-3">
        <h2 className="text-lg font-medium">E2B(R3) file</h2>
        <button type="button" className="rounded-md border px-2 py-1" onClick={() => setOpen(!open)}>
          {open ? "Hide XML" : "Show XML"}
        </button>
      </div>
      <ErrorNote error={xml.error} />
      {open && xml.data && (
        <pre className="mt-3 max-h-96 overflow-auto rounded bg-slate-900 p-3 text-xs text-slate-100">{xml.data}</pre>
      )}
    </section>
  );
}

export function CaseDetailPage() {
  const { api } = useAuth();
  const id = Number(useParams().id);
  const qc = useQueryClient();
  const detail = useQuery({ queryKey: ["case", id], queryFn: () => api.caseDetail(id) });
  const submissions = useQuery({ queryKey: ["submissions", id], queryFn: () => api.submissions(id), refetchInterval: 10_000 });
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ["case", id] });
    void qc.invalidateQueries({ queryKey: ["submissions", id] });
  };
  const [narrative, setNarrative] = useState<string | null>(null);
  const [duplicateOf, setDuplicateOf] = useState("");

  const seriousness = useMutation({ mutationFn: (v: "SERIOUS" | "NON_SERIOUS") => api.decideSeriousness(id, v), onSuccess: refresh });
  const duplicate = useMutation({ mutationFn: (v: number | null) => api.decideDuplicate(id, v), onSuccess: refresh });
  const write = useMutation({ mutationFn: (body: string) => api.writeNarrative(id, body), onSuccess: () => { setNarrative(null); refresh(); } });
  const approveNarrative = useMutation({ mutationFn: (nid: number) => api.approveNarrative(id, nid), onSuccess: refresh });
  const approve = useMutation({ mutationFn: () => api.approve(id), onSuccess: refresh });
  const submit = useMutation({ mutationFn: () => api.submit(id), onSuccess: refresh });

  if (detail.isLoading) return <Layout><p>Loading…</p></Layout>;
  if (detail.error || !detail.data) return <Layout><ErrorNote error={detail.error ?? "Case not found"} /></Layout>;
  const c = detail.data;
  const latest = c.narratives[c.narratives.length - 1];
  const approved = c.narratives.find((n) => n.approvedAt);
  const busy = [seriousness, duplicate, write, approveNarrative, approve, submit].some((m) => m.isPending);

  return (
    <Layout>
      <h1 className="text-2xl font-semibold">Case {c.head.caseNumber}</h1>
      <p className="mb-4 text-sm text-slate-600">
        Status {c.head.status.replaceAll("_", " ").toLowerCase()} · version {c.head.version} · due {c.head.dueDate}
      </p>
      <div className="grid gap-6 md:grid-cols-2">
        <section aria-label="Report" className="space-y-2 rounded-lg border bg-white p-4 text-sm">
          <h2 className="text-lg font-medium">Report</h2>
          <p>{[c.patientAge !== null ? `${c.patientAge} years` : null, c.patientSex, c.reporterType?.toLowerCase(), c.country]
            .filter(Boolean).join(" · ")}</p>
          {c.onsetDate && <p>Onset {c.onsetDate}</p>}
          <blockquote className="whitespace-pre-wrap border-l-4 pl-3 text-slate-700">{c.reportedText}</blockquote>
          <ul>
            {c.drugs.map((d) => (
              <li key={`${d.name}-${d.role}`}>{d.name} <span className="text-slate-500">({d.role}{d.dose ? `, ${d.dose}` : ""})</span></li>
            ))}
          </ul>
        </section>

        <section aria-label="Coding" className="space-y-2 rounded-lg border bg-white p-4">
          <h2 className="text-lg font-medium">Reactions</h2>
          {c.reactions.length === 0 && <p className="text-sm">No reactions coded yet.</p>}
          <ul className="space-y-2">
            {c.reactions.map((r, i) => <Reaction key={`${i}-${r.reportedTerm}-${r.pt}`} r={r} index={i} detail={c} onDone={refresh} />)}
          </ul>
        </section>

        <section aria-label="Seriousness and duplicates" className="space-y-3 rounded-lg border bg-white p-4 text-sm">
          <h2 className="text-lg font-medium">Seriousness</h2>
          <p>Current: <strong>{c.head.seriousness.replace("_", "-").toLowerCase()}</strong>
            {c.head.seriousScore !== null && ` (model score ${c.head.seriousScore.toFixed(2)})`}</p>
          <div className="flex gap-2">
            <button type="button" className="rounded-md border px-2 py-1" disabled={busy} onClick={() => seriousness.mutate("SERIOUS")}>Serious</button>
            <button type="button" className="rounded-md border px-2 py-1" disabled={busy} onClick={() => seriousness.mutate("NON_SERIOUS")}>Not serious</button>
          </div>
          <h2 className="pt-2 text-lg font-medium">Duplicate check</h2>
          <p>{c.head.duplicateOfId !== null ? `Possible duplicate of case #${c.head.duplicateOfId}.` : "No duplicate flagged."}</p>
          <div className="flex flex-wrap items-center gap-2">
            <button type="button" className="rounded-md border px-2 py-1" disabled={busy} onClick={() => duplicate.mutate(null)}>Not a duplicate</button>
            <label>
              <span className="sr-only">Duplicate of case id</span>
              <input className="w-28 rounded-md border px-2 py-1" placeholder="case id" value={duplicateOf}
                onChange={(e) => setDuplicateOf(e.target.value.replace(/\D/g, ""))} />
            </label>
            <button type="button" className="rounded-md border px-2 py-1" disabled={busy || !duplicateOf}
              onClick={() => duplicate.mutate(Number(duplicateOf))}>Mark duplicate</button>
          </div>
          <ErrorNote error={seriousness.error ?? duplicate.error} />
        </section>

        <section aria-label="Narrative" className="space-y-2 rounded-lg border bg-white p-4 text-sm">
          <h2 className="text-lg font-medium">Narrative</h2>
          {latest ? (
            <>
              <p className="text-slate-500">
                {latest.source === "LLM" ? `Draft by ${latest.modelName}` : latest.source === "TEMPLATE" ? "Template draft" : `Written by ${latest.createdBy}`}
                {latest.approvedAt ? ` · approved by ${latest.approvedBy}` : " · not approved"}
              </p>
              <label className="block">
                <span className="sr-only">Narrative text</span>
                <textarea className="w-full rounded-md border px-2 py-1" rows={7} value={narrative ?? latest.body}
                  onChange={(e) => setNarrative(e.target.value)} />
              </label>
              <div className="flex gap-2">
                <button type="button" className="rounded-md border px-2 py-1" disabled={busy || narrative === null || !narrative.trim()}
                  onClick={() => write.mutate(narrative!)}>Save edit</button>
                <button type="button" className="rounded-md border px-2 py-1" disabled={busy || narrative !== null || !!latest.approvedAt}
                  onClick={() => approveNarrative.mutate(latest.id)}>Approve narrative</button>
              </div>
            </>
          ) : <p>No draft yet.</p>}
          <ErrorNote error={write.error ?? approveNarrative.error} />
        </section>
      </div>

      <section aria-label="Approval and submission" className="mt-6 space-y-3 rounded-lg border bg-white p-4 text-sm">
        <h2 className="text-lg font-medium">Approval and submission</h2>
        {c.audit.some((a) => a.actor === "demo-auto-review") && (
          <p role="note" className="rounded bg-sky-50 p-2 text-sky-900">
            Auto-reviewed for the demo: the demo accepted the model suggestions (or the terms as FDA coded them) so the
            replay can reach the regulator. Every decision is stored with the model suggestion, as for a person.
          </p>
        )}
        <div className="flex gap-2">
          <button type="button" className="rounded-md bg-slate-900 px-3 py-2 text-white disabled:opacity-50"
            disabled={busy || c.head.status !== "IN_REVIEW" || !approved} onClick={() => approve.mutate()}>Approve case</button>
          <button type="button" className="rounded-md bg-amber-700 px-3 py-2 text-white disabled:opacity-50"
            disabled={busy || c.head.status !== "APPROVED"} onClick={() => submit.mutate()}>Submit E2B(R3)</button>
        </div>
        <ErrorNote error={approve.error ?? submit.error} />
        {submissions.data && submissions.data.length > 0 && (
          <table className="w-full text-left">
            <thead><tr className="text-slate-600"><th scope="col">Version</th><th scope="col">Status</th><th scope="col">Message</th><th scope="col">Reason</th></tr></thead>
            <tbody>
              {submissions.data.map((s, i) => (
                <tr key={i}>
                  <td>{s.version}</td>
                  <td className={s.status === "NACK" ? "font-semibold text-red-700" : s.status === "ACK" ? "font-semibold text-green-700" : ""}>{s.status}</td>
                  <td>{s.messageId ?? "—"}</td>
                  <td>{s.reason ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      <E2bFile caseId={id} />
      <p className="mt-4 text-sm text-slate-600">{c.disclaimer}</p>
    </Layout>
  );
}
