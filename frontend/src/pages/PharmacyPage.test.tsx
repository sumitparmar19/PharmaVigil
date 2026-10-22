// The counter: dispensing shows the FEFO lots used and the safety evidence with the causation note; an unavailable
// signal service never blocks a dispense; the one-click report sends what was typed; server refusals are shown.
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { NO_CAUSATION, SYNTHETIC_PATIENTS } from "../components/Banners";
import { pharmacist, renderApp, scriptedFetch } from "../test/harness";

const patients = [{ id: 7, givenName: "Ana", familyName: "Example", birthDate: "1970-02-03", sex: "F", synthetic: true }];
const drugs = [
  { id: 3, productCode: "P3", name: "Atorvastatin", strength: "20 mg", doseForm: "tablet", rxcui: "83367", rxOnly: false, unitPriceCents: 25, inDateStock: 40 },
  { id: 4, productCode: "P4", name: "Lisinopril", strength: "10 mg", doseForm: "tablet", rxcui: "29046", rxOnly: true, unitPriceCents: 10, inDateStock: 5 },
];
const dispensed = (safety: unknown) => ({
  dispense: { orderId: 91, drug: "Atorvastatin", rxcui: "83367", quantity: 2, totalCents: 50,
    lots: [{ lotNumber: "L-OLD", expiryDate: "2026-11-01", quantity: 2 }] },
  safety, disclaimers: [],
});

function routes(extra: Record<string, unknown> = {}) {
  return scriptedFetch({
    "GET /api/patients": patients, "GET /api/drugs": drugs, "GET /api/patients/7/prescriptions": [], ...extra,
  });
}

async function choose() {
  await screen.findByRole("option", { name: /Example, Ana/ });
  await screen.findByRole("option", { name: /Atorvastatin/ });
  await userEvent.selectOptions(screen.getByLabelText("Patient"), "7");
  await userEvent.selectOptions(screen.getByLabelText("Drug"), "3");
}

describe("PharmacyPage", () => {
  it("shows the synthetic-patients banner and lists patients and drugs", async () => {
    renderApp("/pharmacy", routes().impl, pharmacist);
    expect(screen.getByRole("note")).toHaveTextContent(SYNTHETIC_PATIENTS);
    expect(await screen.findByRole("option", { name: /Example, Ana/ })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: /Atorvastatin 20 mg — 40 in date/ })).toBeInTheDocument();
  });

  it("dispenses and shows the FEFO lot and the evidence with the causation note", async () => {
    const f = routes({ "POST /api/dispenses": dispensed({ available: true, note: null, warnings: [
      { rxcui: "83367", pt: "rhabdomyolysis", reports: 120, ebgm: 4.2, eb05: 3.1, safetyformer: 1.7, labelText: null, disclaimer: "x" },
    ] }) });
    renderApp("/pharmacy", f.impl, pharmacist);
    await choose();
    await userEvent.clear(screen.getByLabelText("Quantity"));
    await userEvent.type(screen.getByLabelText("Quantity"), "2");
    await userEvent.click(screen.getByRole("button", { name: "Dispense" }));
    expect(await screen.findByText(/L-OLD \(expires 2026-11-01, 2\)/)).toBeInTheDocument();
    const evidence = screen.getByRole("region", { name: "Safety evidence" });
    expect(within(evidence).getByText("rhabdomyolysis")).toBeInTheDocument();
    expect(evidence).toHaveTextContent(NO_CAUSATION);
    expect(f.calls.find((c) => c.path === "/api/dispenses")?.body).toEqual({ patientId: 7, drugId: 3, quantity: 2, prescriptionId: null });
  });

  it("still completes the dispense when signals are unavailable", async () => {
    const f = routes({ "POST /api/dispenses": dispensed({ available: false, warnings: [],
      note: "Safety signals are temporarily unavailable; dispense was not blocked." }) });
    renderApp("/pharmacy", f.impl, pharmacist);
    await choose();
    await userEvent.click(screen.getByRole("button", { name: "Dispense" }));
    expect(await screen.findByText(/dispense was not blocked/)).toBeInTheDocument();
    expect(screen.getByText(/order\s+#91/)).toBeInTheDocument();
  });

  it("warns before dispensing an Rx-only drug without a prescription and shows the refusal", async () => {
    const f = routes({ "POST /api/dispenses": () => ({ status: 409, body: { status: 409, detail: "No active prescription." } }) });
    renderApp("/pharmacy", f.impl, pharmacist);
    await screen.findByRole("option", { name: /Lisinopril/ });
    await screen.findByRole("option", { name: /Example, Ana/ });
    await userEvent.selectOptions(screen.getByLabelText("Patient"), "7");
    await userEvent.selectOptions(screen.getByLabelText("Drug"), "4");
    expect(await screen.findByText(/No active prescription for this drug/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Dispense" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("No active prescription.");
  });

  it("sends a one-click side-effect report", async () => {
    const f = routes({ "POST /api/reports": { id: 55, patientId: 7, drug: "Atorvastatin", rxcui: "83367",
      narrative: "Leg cramps", onsetDate: null, status: "PENDING_SUBMISSION", reporter: "pharm", createdAt: "now" } });
    renderApp("/pharmacy", f.impl, pharmacist);
    expect(screen.getByRole("button", { name: "Send report" })).toBeDisabled();
    await choose();
    await userEvent.type(screen.getByLabelText("What happened?"), "Leg cramps");
    await userEvent.click(screen.getByRole("button", { name: "Send report" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Report #55 saved");
    expect(f.calls.find((c) => c.path === "/api/reports")).toMatchObject({
      body: { patientId: 7, drugId: 3, narrative: "Leg cramps", onsetDate: null }, auth: "Bearer test-token-pharmacist",
    });
  });
});
