// The case page: model suggestions show their score and can be accepted or corrected (with the lock version),
// seriousness and duplicate decisions go to the server, the narrative can be edited and approved, approval and
// submission are only offered when allowed, and the regulator's NACK reason is visible.
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { CaseDetail } from "../api/types";
import { renderApp, reviewer, scriptedFetch } from "../test/harness";

function detail(over: Partial<CaseDetail> = {}, head: Record<string, unknown> = {}): CaseDetail {
  return {
    head: { id: 5, caseNumber: "PV-2026-ABC", status: "TRIAGED", seriousness: "UNKNOWN", seriousScore: null,
      receivedDate: "2026-10-01", dueDate: "2026-12-30", version: 2, duplicateOfId: 9, ...head } as CaseDetail["head"],
    lockVersion: 4, reportedText: "Itchy skin after the new tablet.", patientAge: 54, patientSex: "F",
    reporterType: "PHARMACIST", country: "US", onsetDate: null,
    drugs: [{ rxcui: "83367", name: "Atorvastatin", role: "PS", dose: "20 mg", route: "oral" }],
    reactions: [{ reportedTerm: "itchy skin", pt: "pruritus", codeSource: "MODEL", modelName: "meddra-coder",
      modelVersion: "fixture:abc", modelScore: 0.73 }],
    narratives: [{ id: 11, versionNo: 2, body: "Template draft text.", source: "TEMPLATE", modelName: null,
      createdBy: "pipeline", createdAt: "2026-10-02T00:00:00Z", approvedBy: null, approvedAt: null }],
    decisions: [], audit: [], disclaimer: "Spontaneous reports do not prove that a drug caused a reaction.",
    ...over,
  };
}

const ok = { caseId: 5, status: "IN_REVIEW", version: 3, lockVersion: 5 };

function routes(d: CaseDetail, extra: Record<string, unknown> = {}) {
  return scriptedFetch({ "GET /api/cases/5": d, "GET /api/cases/5/submissions": [], ...extra });
}

describe("CaseDetailPage", () => {
  it("shows the report, the suggestion with its score and the disclaimer", async () => {
    renderApp("/review/5", routes(detail()).impl, reviewer);
    expect(await screen.findByRole("heading", { name: "Case PV-2026-ABC" })).toBeInTheDocument();
    expect(screen.getByText("Suggested: pruritus (score 0.73)")).toBeInTheDocument();
    expect(screen.getByText(/Itchy skin after the new tablet/)).toBeInTheDocument();
    expect(screen.getAllByText(/do not prove/).length).toBeGreaterThanOrEqual(2); // banner and the case page
  });

  it("accepts or corrects a code with the current lock version", async () => {
    const f = routes(detail(), { "POST /api/cases/5/reactions/0/code": ok });
    renderApp("/review/5", f.impl, reviewer);
    await userEvent.click(await screen.findByRole("button", { name: "Accept" }));
    await waitFor(() => expect(f.calls.some((c) => c.path === "/api/cases/5/reactions/0/code")).toBe(true));
    expect(f.calls.find((c) => c.path.endsWith("/code"))?.body).toEqual({ pt: "pruritus", lockVersion: 4 });
    const box = screen.getByLabelText("Preferred term for reaction 1");
    await userEvent.clear(box);
    await userEvent.type(box, "Rash");
    await userEvent.click(screen.getByRole("button", { name: "Correct" }));
    await waitFor(() => expect(f.calls.filter((c) => c.path.endsWith("/code")).at(-1)?.body).toEqual({ pt: "Rash", lockVersion: 4 }));
  });

  it("records seriousness and duplicate decisions", async () => {
    const f = routes(detail(), { "POST /api/cases/5/seriousness": ok, "POST /api/cases/5/duplicate": ok });
    renderApp("/review/5", f.impl, reviewer);
    expect(await screen.findByText("Possible duplicate of case #9.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Serious" }));
    await userEvent.click(screen.getByRole("button", { name: "Not a duplicate" }));
    await waitFor(() => expect(f.calls.find((c) => c.path.endsWith("/duplicate"))?.body).toEqual({ duplicateOf: null }));
    expect(f.calls.find((c) => c.path.endsWith("/seriousness"))?.body).toEqual({ value: "SERIOUS" });
  });

  it("saves an edited narrative and approves an unedited one", async () => {
    const f = routes(detail(), { "POST /api/cases/5/narratives": 12, "POST /api/cases/5/narratives/11/approve": ok });
    renderApp("/review/5", f.impl, reviewer);
    const text = await screen.findByLabelText("Narrative text");
    expect(screen.getByRole("button", { name: "Save edit" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Approve narrative" }));
    await waitFor(() => expect(f.calls.some((c) => c.path === "/api/cases/5/narratives/11/approve")).toBe(true));
    await userEvent.type(text, " Edited.");
    expect(screen.getByRole("button", { name: "Approve narrative" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Save edit" }));
    await waitFor(() => expect(f.calls.find((c) => c.path === "/api/cases/5/narratives")?.body)
      .toEqual({ body: "Template draft text. Edited." }));
  });

  it("offers approval and submission only when allowed and shows a blocked submission", async () => {
    const approvedNarrative = { ...detail().narratives[0]!, approvedBy: "rev", approvedAt: "2026-10-03T00:00:00Z" };
    const f = routes(detail({ narratives: [approvedNarrative] }, { status: "APPROVED" }), {
      "POST /api/cases/5/submit": () => ({ status: 422, body: { status: 422,
        detail: "The E2B(R3) file did not pass the official schema check (SCHEMA_UNAVAILABLE); nothing was queued.",
        errors: ["ICH E2B(R3) schema set not found; run scripts/fetch-e2b-xsd.sh"] } }),
    });
    renderApp("/review/5", f.impl, reviewer);
    expect(await screen.findByRole("button", { name: "Approve case" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Submit E2B(R3)" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("nothing was queued");
    expect(within(alert).getByText(/fetch-e2b-xsd/)).toBeInTheDocument();
  });

  it("shows the submission history with the NACK reason", async () => {
    const f = routes(detail({}, { status: "IN_REVIEW" }), { "GET /api/cases/5/submissions": [
      { version: 2, safetyReportId: "US-1", messageId: null, status: "REQUESTED", reason: null, at: "t1" },
      { version: 2, safetyReportId: "US-1", messageId: "M-1", status: "NACK", reason: "schema validation failed: line 3", at: "t2" },
    ] });
    renderApp("/review/5", f.impl, reviewer);
    expect(await screen.findByText("NACK")).toBeInTheDocument();
    expect(screen.getByText("schema validation failed: line 3")).toBeInTheDocument();
  });
});
