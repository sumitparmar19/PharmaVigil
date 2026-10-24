// Demo mode pages (Phase 2): the public landing page with the shared demo accounts, the live pipeline run with its
// stage counters, the public evidence page, and the signals drug search.
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderApp, reviewer, scriptedFetch } from "../test/harness";

const run = { runId: "abc234def5", account: "demo-reviewer", startedAt: "2026-10-24T08:00:00Z", finishedAt: null,
  size: 200, sent: 120, accepted: 118, rejected: 2, viaSoap: 90, viaRest: 30, errors: [] };
const stages = { runId: "abc234def5", received: 110, deduplicated: 100, codedByModel: 70, manualCoding: 20, triaged: 85,
  serious: 40, approved: 60, submitted: 55, acknowledged: 50, rejected: 1, possibleDuplicates: 3 };

describe("LandingPage", () => {
  it("shows the shared demo accounts and the password when demo mode is on", async () => {
    renderApp("/", scriptedFetch({ "GET /api/auth/demo": { enabled: true, password: "shown-on-purpose",
      accounts: [{ username: "demo-reviewer", role: "Safety reviewer" }] } }).impl, null);
    const box = await screen.findByRole("region", { name: "Demo accounts" });
    expect(within(box).getByRole("rowheader", { name: "demo-reviewer" })).toBeInTheDocument();
    expect(within(box).getByText("shown-on-purpose")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: /PharmaVigil architecture/ })).toBeInTheDocument();
    expect(screen.getAllByText(/do not prove that a drug caused a reaction/).length).toBeGreaterThan(0);
  });

  it("offers only the sign-in when demo mode is off", async () => {
    renderApp("/", scriptedFetch({ "GET /api/auth/demo": { enabled: false } }).impl, null);
    expect(await screen.findByRole("link", { name: "Sign in to the demo" })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("region", { name: "Demo accounts" })).not.toBeInTheDocument());
  });
});

describe("DemoRunPage", () => {
  it("starts a run and shows every stage counted by the services", async () => {
    const f = scriptedFetch({
      "POST /api/intake/demo/runs": () => ({ status: 202, body: { ...run, sent: 0, accepted: 0 } }),
      "GET /api/intake/demo/runs/abc234def5": run,
      "GET /api/cases/demo/runs/abc234def5": stages,
      "GET /api/cases/demo/runs/abc234def5/cases": [{ id: 7, caseNumber: "PV-2026-7", status: "ACKNOWLEDGED", seriousness: "SERIOUS" }],
    });
    renderApp("/demo", f.impl, reviewer);
    await userEvent.click(screen.getByRole("button", { name: "Run the pipeline" }));
    expect(await screen.findByLabelText("Sent to intake: 120")).toBeInTheDocument();
    expect(screen.getByText("90 SOAP · 30 REST")).toBeInTheDocument();
    expect(await screen.findByLabelText("MedDRA coded: 90")).toBeInTheDocument();
    expect(screen.getByText("50 ACK · 1 NACK")).toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "PV-2026-7" })).toHaveAttribute("href", "/review/7");
    expect(f.calls.find((c) => c.method === "POST")?.body).toEqual({ size: 200 });
  });

  it("explains why a run cannot start (cooldown)", async () => {
    renderApp("/demo", scriptedFetch({ "POST /api/intake/demo/runs": () => ({ status: 409,
      body: { detail: "Please wait 42 s before starting another run." } }) }).impl, reviewer);
    await userEvent.click(screen.getByRole("button", { name: "Run the pipeline" }));
    expect(await screen.findByText(/Please wait 42 s/)).toBeInTheDocument();
  });
});

describe("EvidencePage", () => {
  it("lists every number with its scope and source file, and says pending plainly", async () => {
    renderApp("/evidence", scriptedFetch({ "GET /evidence.json": {
      generatedAt: "2026-10-24", repository: "https://github.com/sumitparmar19/PharmaVigil",
      sections: [{ title: "Signal baselines", items: [
        { label: "EBGM AUC on OMOP", value: "0.70 (0.65–0.75)", scope: "subset: FAERS 2019Q1–2026Q2", file: "analysis/p2/baselines.json", status: "measured" },
        { label: "Time-travel lead time", value: "pending", scope: "—", file: "analysis/p2/README.md", status: "pending" },
      ] }] } }).impl, null);
    const section = await screen.findByRole("region", { name: "Signal baselines" });
    expect(within(section).getByText("0.70 (0.65–0.75)")).toBeInTheDocument();
    expect(within(section).getByText("subset: FAERS 2019Q1–2026Q2")).toBeInTheDocument();
    expect(within(section).getByRole("link", { name: "analysis/p2/baselines.json" }))
      .toHaveAttribute("href", "https://github.com/sumitparmar19/PharmaVigil/blob/main/analysis/p2/baselines.json");
    expect(within(section).getByText("pending")).toBeInTheDocument();
  });
});

describe("SignalsPage search", () => {
  it("searches a drug and shows the reference-set badge", async () => {
    const sig = { rxcui: "rx9", ingredient: "AMIODARONE", pt: "pulmonary fibrosis", reports: 12, prr: 5, prrLo: 3.1, ror: 5,
      rorLo: 3.2, ic: 2, ic025: 1.5, ebgm: 4, eb05: 2.9, eb95: 5, safetyformer: 0.42, reference: "OMOP positive" };
    const f = scriptedFetch({
      "GET /api/signals/meta": { loaded: true, scope: "subset: FAERS 2019Q1–2026Q2", pairs: 1, withSafetyformer: true,
        loadedAt: null, evaluation: null, warningEb05: 2, disclaimer: "x" },
      "GET /api/signals/top": { method: "ebgm", scope: "s", signals: [], disclaimer: "x" },
      "GET /api/signals/search": { method: "search", scope: "s", signals: [sig], disclaimer: "x" },
    });
    renderApp("/signals", f.impl, reviewer);
    await userEvent.type(await screen.findByLabelText("Search a drug"), "amio");
    await userEvent.click(screen.getByRole("button", { name: "Search" }));
    expect(await screen.findByText("OMOP positive")).toBeInTheDocument();
    expect(screen.getByText("0.42")).toBeInTheDocument();
    expect(f.calls.some((c) => c.path === "/api/signals/search?q=amio&limit=50")).toBe(true);
  });
});
