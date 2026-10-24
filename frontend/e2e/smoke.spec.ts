// Browser smoke test of the whole stack (docker compose, demo profile, synthetic patients): a pharmacist
// dispenses an OTC drug from the first-expiring in-date lot, sees the safety-evidence panel and sends a
// side-effect report; a safety reviewer then finds the case in the queue. The demo password comes from the
// PV_DEMO_PASSWORD environment variable, never from the repository.
import { expect, test, type Page } from "@playwright/test";

const password = process.env.PV_DEMO_PASSWORD ?? "";

async function signIn(page: Page, username: string) {
  await page.goto("/login");
  await page.getByLabel("Username").fill(username);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
}

test.beforeAll(() => {
  expect(password.length, "set PV_DEMO_PASSWORD to the stack's demo password").toBeGreaterThanOrEqual(12);
});

test("pharmacist dispenses and reports; reviewer sees the case", async ({ page }) => {
  await signIn(page, "pharmacist");
  await expect(page.getByRole("heading", { name: "Pharmacy counter" })).toBeVisible();
  const banner = page.getByRole("note", { name: "Disclaimers" });
  await expect(banner).toContainText("Not for clinical or regulatory use.");
  await expect(banner).toContainText("Spontaneous reports do not prove that a drug caused a reaction.");
  await expect(banner).toContainText("Synthetic patients only.");

  const dispenseForm = page.getByRole("form", { name: "Dispense" });
  const patient = dispenseForm.getByLabel("Patient");
  await expect(patient.locator("option")).not.toHaveCount(1); // the synthetic patients have loaded
  await patient.selectOption({ index: 1 });
  const drug = dispenseForm.getByLabel("Drug");
  const ibuprofen = drug.locator("option", { hasText: "Ibuprofen" });
  await expect(ibuprofen).toHaveCount(1);
  await drug.selectOption({ value: (await ibuprofen.getAttribute("value")) ?? "" });
  await dispenseForm.getByRole("button", { name: "Dispense" }).click();

  // First expiry, first out: the in-date lot that expires soonest; the expired lot is never used.
  await expect(dispenseForm).toContainText("Ibuprofen");
  await expect(dispenseForm).toContainText("DEMO-IBU-200-L1");
  await expect(dispenseForm).not.toContainText("DEMO-IBU-200-EXP");
  await expect(page.getByRole("region", { name: "Safety evidence" })).toBeVisible();

  const reportForm = page.getByRole("form", { name: "Report a side effect" });
  await reportForm.getByLabel("What happened?").fill("Itchy rash on both arms the day after starting the tablets.");
  await reportForm.getByRole("button", { name: "Send report" }).click();
  await expect(reportForm.getByRole("status")).toContainText("saved");

  await page.getByRole("button", { name: "Sign out" }).click();
  await signIn(page, "reviewer");
  await expect(page.getByRole("heading", { name: "Review queue" })).toBeVisible();
  // The report travels pharmacy → intake → Kafka pipeline; the queue refreshes itself every 15 s.
  const caseLink = page.getByRole("table").getByRole("link").first();
  await expect(caseLink).toBeVisible({ timeout: 120_000 });
  await caseLink.click();
  await expect(page.getByRole("heading", { name: /^Case / })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Reactions" })).toBeVisible();
  await expect(page.getByRole("note", { name: "Disclaimers" })).toContainText("Not for clinical or regulatory use.");
});

test("signals page carries the causation disclaimer", async ({ page }) => {
  await signIn(page, "reviewer");
  await page.getByRole("link", { name: "Signals" }).click();
  await expect(page.getByRole("heading", { name: "Drug–event signals" })).toBeVisible();
  await expect(page.getByRole("note", { name: "Disclaimers" }))
    .toContainText("Spontaneous reports do not prove that a drug caused a reaction.");
});
