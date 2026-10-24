// Safety evidence shown at the counter after a dispense: counts and scores from public FAERS reports, never a
// verdict, always with the causation disclaimer. When signal-service is down the dispense still happened and the
// panel says so.
import type { SignalLookup } from "../api/types";
import { NO_CAUSATION } from "./Banners";

const fmt = (v: number | null, digits = 2) => (v === null || v === undefined ? "—" : v.toFixed(digits));

export function WarningPanel({ lookup }: { lookup: SignalLookup }) {
  if (!lookup.available) {
    return (
      <section aria-label="Safety evidence" className="rounded-md border border-slate-300 bg-slate-100 p-3 text-sm">
        {lookup.note ?? "Safety signals are temporarily unavailable."}
      </section>
    );
  }
  return (
    <section aria-label="Safety evidence" className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm">
      <h3 className="font-semibold">Reported reactions to look out for</h3>
      {lookup.warnings.length === 0 ? (
        <p className="mt-1">No drug–event signal above the warning threshold for this drug.</p>
      ) : (
        <table className="mt-2 w-full text-left">
          <thead>
            <tr className="text-slate-600">
              <th scope="col">Reaction (MedDRA PT)</th>
              <th scope="col">Reports</th>
              <th scope="col">EBGM</th>
              <th scope="col">EB05</th>
              <th scope="col">SafetyFormer</th>
            </tr>
          </thead>
          <tbody>
            {lookup.warnings.map((w) => (
              <tr key={w.pt}>
                <td>{w.pt}</td>
                <td>{w.reports}</td>
                <td>{fmt(w.ebgm)}</td>
                <td>{fmt(w.eb05)}</td>
                <td>{fmt(w.safetyformer)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <p className="mt-2 text-slate-700">{NO_CAUSATION} This is evidence to discuss with the patient, not a verdict.</p>
    </section>
  );
}
