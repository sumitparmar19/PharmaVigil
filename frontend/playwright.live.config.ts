// Playwright configuration for the daily live smoke test against the public demo (PV_LIVE_URL, for example
// https://203-0-113-7.sslip.io). Never part of normal CI; run by the "Live smoke test" workflow or `npm run e2e:live`.
import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "e2e-live",
  timeout: 600_000,
  expect: { timeout: 20_000 },
  retries: 0,
  reporter: [["list"], ["html", { open: "never", outputFolder: "playwright-live-report" }]],
  use: {
    baseURL: process.env.PV_LIVE_URL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    launchOptions: process.env.PW_CHROMIUM_PATH ? { executablePath: process.env.PW_CHROMIUM_PATH } : {},
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
