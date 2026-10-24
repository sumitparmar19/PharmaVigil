// The queue: cases in server order with seriousness, model score, the deadline clock (overdue, today, days left),
// duplicate flags and links to the case; an empty queue says so.
import { screen, within } from "@testing-library/react";
import { renderApp, reviewer, scriptedFetch } from "../test/harness";
import { daysLeft } from "./ReviewQueuePage";

const item = (id: number, over: Record<string, unknown> = {}) => ({
  id, caseNumber: `PV-2026-${id}`, status: "TRIAGED", seriousness: "SERIOUS", seriousScore: 0.81,
  receivedDate: "2026-10-01", dueDate: "2026-10-16", version: 2, duplicateOfId: null, ...over,
});

describe("ReviewQueuePage", () => {
  it("counts days to the deadline in UTC", () => {
    const today = new Date("2026-10-14T23:30:00Z");
    expect(daysLeft("2026-10-16", today)).toBe(2);
    expect(daysLeft("2026-10-14", today)).toBe(0);
    expect(daysLeft("2026-10-10", today)).toBe(-4);
  });

  it("lists cases in the server's order with links", async () => {
    renderApp("/review", scriptedFetch({ "GET /api/cases/queue": [item(1), item(2, { seriousness: "NON_SERIOUS" })] }).impl, reviewer);
    const links = await screen.findAllByRole("link", { name: /PV-2026-/ });
    expect(links.map((l) => l.textContent)).toEqual(["PV-2026-1", "PV-2026-2"]);
    expect(links[0]).toHaveAttribute("href", "/review/1");
  });

  it("shows seriousness with the model score", async () => {
    renderApp("/review", scriptedFetch({ "GET /api/cases/queue": [item(1)] }).impl, reviewer);
    const row = (await screen.findByRole("link", { name: "PV-2026-1" })).closest("tr")!;
    expect(within(row).getByText("serious")).toBeInTheDocument();
    expect(within(row).getByText("model 0.81")).toBeInTheDocument();
  });

  it("marks overdue cases and possible duplicates", async () => {
    const f = scriptedFetch({ "GET /api/cases/queue": [item(3, { dueDate: "2026-10-01", duplicateOfId: 1 })] });
    renderApp("/review", f.impl, reviewer);
    expect(await screen.findByText(/overdue/)).toBeInTheDocument();
    expect(screen.getByText("possible duplicate of #1")).toBeInTheDocument();
  });

  it("says when nothing is waiting", async () => {
    renderApp("/review", scriptedFetch({ "GET /api/cases/queue": [] }).impl, reviewer);
    expect(await screen.findByText("No cases are waiting for review.")).toBeInTheDocument();
  });

  it("keeps pharmacists out of the reviewer pages", async () => {
    const f = scriptedFetch({ "GET /api/signals/meta": { loaded: false, warningEb05: 2, disclaimer: "" },
      "GET /api/signals/top": { method: "ebgm", scope: null, signals: [], disclaimer: "" } });
    renderApp("/review", f.impl, { ...reviewer, roles: ["PHARMACIST"] });
    expect(await screen.findByRole("heading", { name: "Drug–event signals" })).toBeInTheDocument();
    expect(f.calls.some((c) => c.path.startsWith("/api/cases"))).toBe(false);
  });
});
