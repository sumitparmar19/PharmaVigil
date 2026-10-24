// The pharmacy counter: pick a (synthetic) patient and a drug, dispense (the lots used are chosen first-expiry-
// first-out by the server), see the safety evidence for that drug, and report a side effect in one click.
import { useMutation, useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import type { DispenseResponse, ReportView } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { ErrorNote } from "../components/ErrorNote";
import { Layout } from "../components/Layout";
import { WarningPanel } from "../components/WarningPanel";

export function PharmacyPage() {
  const { api } = useAuth();
  const patients = useQuery({ queryKey: ["patients"], queryFn: () => api.patients() });
  const drugs = useQuery({ queryKey: ["drugs"], queryFn: () => api.drugs() });
  const [patientId, setPatientId] = useState<number | null>(null);
  const [drugId, setDrugId] = useState<number | null>(null);
  const [quantity, setQuantity] = useState(1);
  const [narrative, setNarrative] = useState("");
  const [onset, setOnset] = useState("");
  const prescriptions = useQuery({
    queryKey: ["prescriptions", patientId],
    queryFn: () => api.prescriptions(patientId!),
    enabled: patientId !== null,
  });
  const drug = drugs.data?.find((d) => d.id === drugId);
  const prescription = prescriptions.data?.find((p) => p.drugId === drugId && p.status === "ACTIVE") ?? null;

  const dispense = useMutation<DispenseResponse, Error>({
    mutationFn: () => api.dispense(patientId!, drugId!, quantity, prescription?.id ?? null),
  });
  const report = useMutation<ReportView, Error>({
    mutationFn: () => api.report(patientId!, drugId!, narrative.trim(), onset || null),
    onSuccess: () => setNarrative(""),
  });

  const ready = patientId !== null && drugId !== null;

  function onDispense(e: FormEvent) {
    e.preventDefault();
    dispense.mutate();
  }

  function onReport(e: FormEvent) {
    e.preventDefault();
    report.mutate();
  }

  return (
    <Layout synthetic>
      <h1 className="mb-4 text-2xl font-semibold">Pharmacy counter</h1>
      <ErrorNote error={patients.error ?? drugs.error} />
      <div className="grid gap-6 md:grid-cols-2">
        <form onSubmit={onDispense} className="space-y-3 rounded-lg border bg-white p-4" aria-label="Dispense">
          <h2 className="text-lg font-medium">Dispense</h2>
          <label className="block text-sm">
            Patient
            <select className="mt-1 w-full rounded-md border px-2 py-1" value={patientId ?? ""}
              onChange={(e) => setPatientId(e.target.value ? Number(e.target.value) : null)}>
              <option value="">Choose a patient…</option>
              {patients.data?.map((p) => (
                <option key={p.id} value={p.id}>{p.familyName}, {p.givenName} ({p.birthDate})</option>
              ))}
            </select>
          </label>
          <label className="block text-sm">
            Drug
            <select className="mt-1 w-full rounded-md border px-2 py-1" value={drugId ?? ""}
              onChange={(e) => setDrugId(e.target.value ? Number(e.target.value) : null)}>
              <option value="">Choose a drug…</option>
              {drugs.data?.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name} {d.strength} — {d.inDateStock} in date{d.rxOnly ? " (Rx)" : ""}
                </option>
              ))}
            </select>
          </label>
          {drug?.rxOnly && ready && (
            <p className="text-sm text-slate-600">
              {prescription ? `Prescription by ${prescription.prescriber}, ${prescription.remainingQuantity} left`
                : "No active prescription for this drug: the dispense will be refused."}
            </p>
          )}
          <label className="block text-sm">
            Quantity
            <input type="number" min={1} max={1000} className="mt-1 ml-2 w-32 rounded-md border px-2 py-1" value={quantity}
              onChange={(e) => setQuantity(Number(e.target.value))} />
          </label>
          <button type="submit" disabled={!ready || dispense.isPending}
            className="rounded-md bg-slate-900 px-3 py-2 text-white disabled:opacity-50">Dispense</button>
          <ErrorNote error={dispense.error} />
          {dispense.data && (
            <div className="space-y-3" aria-live="polite">
              <p className="text-sm">
                Dispensed {dispense.data.dispense.quantity} × {dispense.data.dispense.drug} (order
                #{dispense.data.dispense.orderId}) from lot{dispense.data.dispense.lots.length > 1 ? "s" : ""}{" "}
                {dispense.data.dispense.lots.map((l) => `${l.lotNumber} (expires ${l.expiryDate}, ${l.quantity})`)
                  .join(", ")}.
              </p>
              <WarningPanel lookup={dispense.data.safety} />
            </div>
          )}
        </form>

        <form onSubmit={onReport} className="space-y-3 rounded-lg border bg-white p-4" aria-label="Report a side effect">
          <h2 className="text-lg font-medium">Report a side effect</h2>
          <p className="text-sm text-slate-600">For the patient and drug chosen on the left.</p>
          <label className="block text-sm">
            What happened?
            <textarea className="mt-1 w-full rounded-md border px-2 py-1" rows={4} maxLength={4000} value={narrative}
              onChange={(e) => setNarrative(e.target.value)} />
          </label>
          <label className="block text-sm">
            Onset date (optional)
            <input type="date" className="mt-1 ml-2 rounded-md border px-2 py-1" value={onset}
              onChange={(e) => setOnset(e.target.value)} />
          </label>
          <button type="submit" disabled={!ready || !narrative.trim() || report.isPending}
            className="rounded-md bg-amber-700 px-3 py-2 text-white disabled:opacity-50">Send report</button>
          <ErrorNote error={report.error} />
          {report.data && (
            <p role="status" className="text-sm text-green-800">
              Report #{report.data.id} saved; it goes to the safety team automatically ({report.data.status}).
            </p>
          )}
        </form>
      </div>
    </Layout>
  );
}
