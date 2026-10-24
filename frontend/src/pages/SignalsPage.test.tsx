// The signals page: states the data scope and the causation disclaimer, ranks by the chosen method, shows a pair's
// yearly trend (chart and table), the evaluation summary, and says plainly when no export is loaded.
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { NO_CAUSATION } from "../components/Banners";
import { renderApp, reviewer, scriptedFetch } from "../test/harness";

const signal = (pt: string, eb05: number, prrLo: number) => ({
  rxcui: "rx1", ingredient: "drug1", pt, reports: 24, prr: 13.5, prrLo, ror: 18.8, rorLo: 10.4, ic: 2.8,
  ic025: 2.1, ebgm: 2.6, eb05, eb95: 3.4, safetyformer: null,
});
const meta = {
  loaded: true, scope: "synthetic test fixture", pairs: 425, withSafetyformer: false, loadedAt: "t", warningEb05: 2,
  disclaimer: NO_CAUSATION,
  evaluation: { "primary_suspect/omop": { methods: { eb05: { auc: 0.71, ci_low: 0.6, ci_high: 0.8 } } } },
};

function routes(extra: Record<string, unknown> = {}) {
  return scriptedFetch({
    "GET /api/signals/meta": meta,
    "GET /api/signals/top?method=ebgm&limit=25": { method: "ebgm", scope: "s", signals: [signal("acute kidney injury", 1.95, 8.4)], disclaimer: "" },
    "GET /api/signals/top?method=prr&limit=25": { method: "prr", scope: "s", signals: [signal("rhabdomyolysis", 1.9, 10.6)], disclaimer: "" },
    "GET /api/signals/trend": { rxcui: "rx1", pt: "acute kidney injury", scope: "s", points: [{ year: 2019, reports: 5 }, { year: 2020, reports: 19 }] },
    ...extra,
  });
}

describe("SignalsPage", () => {
  it("states the scope and the causation disclaimer", async () => {
    renderApp("/signals", routes().impl, reviewer);
    expect(await screen.findByText("synthetic test fixture")).toBeInTheDocument();
    expect(screen.getAllByText(new RegExp(NO_CAUSATION)).length).toBeGreaterThan(0);
  });

  it("ranks by EBGM first and switches method", async () => {
    const f = routes();
    renderApp("/signals", f.impl, reviewer);
    expect(await screen.findByText("acute kidney injury")).toBeInTheDocument();
    expect(screen.getByText("1.95")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("tab", { name: "PRR (lower 95%)" }));
    expect(await screen.findByText("rhabdomyolysis")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "PRR (lower 95%)" })).toHaveAttribute("aria-selected", "true");
  });

  it("shows the yearly trend of a chosen pair as a chart and a table", async () => {
    const f = routes();
    renderApp("/signals", f.impl, reviewer);
    await userEvent.click(await screen.findByRole("button", { name: "drug1" }));
    expect(await screen.findByRole("img", { name: /reports per year/ })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "19" })).toBeInTheDocument();
    expect(f.calls.some((c) => c.path === "/api/signals/trend?rxcui=rx1&pt=acute%20kidney%20injury")).toBe(true);
  });

  it("shows the reference-set evaluation", async () => {
    renderApp("/signals", routes().impl, reviewer);
    const evaluation = await screen.findByRole("region", { name: "Evaluation" });
    await waitFor(() => expect(within(evaluation).getByText("0.71 (0.60–0.80)")).toBeInTheDocument());
    expect(within(evaluation).getByText("primary_suspect/omop")).toBeInTheDocument();
  });

  it("says when no export is loaded instead of showing numbers", async () => {
    const f = routes({ "GET /api/signals/meta": { ...meta, loaded: false, scope: null, evaluation: null },
      "GET /api/signals/top?method=ebgm&limit=25": { method: "ebgm", scope: null, signals: [], disclaimer: "" } });
    renderApp("/signals", f.impl, reviewer);
    expect(await screen.findByRole("status")).toHaveTextContent("No signal data is loaded yet");
    expect(await screen.findByText("No pairs to rank for this method.")).toBeInTheDocument();
  });
});
