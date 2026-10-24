// Live smoke test of the public demo: the landing page shows the demo accounts, demo-reviewer signs in with the
// password the page shows, a small replay of real FAERS reports runs, one case reaches the regulator's ACK, its
// E2B(R3) XML opens, and the Signals and Evidence pages answer. Uses only what any visitor can see.
import { expect, test } from "@playwright/test";

test("the live demo works end to end", async ({ page, request }) => {
  await page.goto("/");
  await expect(page.getByText("Not for clinical or regulatory use.").first()).toBeVisible();
  const demo = await (await request.get("/api/auth/demo")).json();
  expect(demo.enabled).toBe(true);
  const box = page.getByRole("region", { name: "Demo accounts" });
  await expect(box.getByRole("rowheader", { name: "demo-reviewer" })).toBeVisible();

  const status = await (await request.get("/api/status")).json();
  expect(status.status).toBe("UP");

  await page.goto("/login");
  await page.getByLabel("Username").fill("demo-reviewer");
  await page.getByLabel("Password").fill(demo.password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/review/);

  // A small replay through the API with the signed-in token (the UI button sends 200; the smoke test needs 10).
  const session = await page.evaluate(() => JSON.parse(sessionStorage.getItem("pv.session") ?? "null"));
  const auth = { Authorization: `Bearer ${session.token}` };
  const started = await request.post("/api/intake/demo/runs", { headers: auth, data: { size: 10 } });
  expect(started.status(), await started.text()).toBe(202);
  const runId = (await started.json()).runId as string;

  let ackCase: { id: number } | undefined;
  await expect.poll(async () => {
    const cases = await (await request.get(`/api/cases/demo/runs/${runId}/cases`, { headers: auth })).json();
    ackCase = (cases as { id: number; status: string }[]).find((c) => c.status === "ACKNOWLEDGED");
    return ackCase !== undefined;
  }, { timeout: 480_000, intervals: [5_000] }).toBe(true);

  await page.goto(`/review/${ackCase!.id}`);
  await page.getByRole("button", { name: "Show XML" }).click();
  await expect(page.getByRole("region", { name: "E2B(R3) file" })).toContainText("MCCI_IN200100UV01");
  await expect(page.getByText("ACK").first()).toBeVisible();

  await page.goto("/signals");
  await expect(page.getByText("Spontaneous reports do not prove that a drug caused a reaction.").first()).toBeVisible();
  await expect(page.getByText(/Data scope:/)).toBeVisible();
  await page.goto("/evidence");
  await expect(page.getByRole("heading", { name: "Evidence" })).toBeVisible();
});
