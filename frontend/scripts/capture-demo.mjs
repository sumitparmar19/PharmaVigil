// Records the README's screenshots and GIF from a running demo stack (docker-compose.demo.yml), so the images
// show the real pipeline rather than mock-ups: the landing page, a "Run the pipeline" replay with its live
// counters, one acknowledged case with its E2B(R3) file, and the signals page. Signs in with the public demo
// account the landing page itself shows. The GIF is stitched by ffmpeg from screenshots taken once a second, so it
// does not depend on Playwright's own video recorder. Usage:
//   node scripts/capture-demo.mjs <base-url> <out-dir>        e.g. https://localhost ../docs/img
// Needs ffmpeg for the GIF.
import { execFileSync } from "node:child_process";
import { mkdirSync, rmSync } from "node:fs";
import { join } from "node:path";
import { chromium } from "playwright";

const [base = "https://localhost", out = "../docs/img"] = process.argv.slice(2);
mkdirSync(out, { recursive: true });
const frameDir = join(out, ".frames");
rmSync(frameDir, { recursive: true, force: true });
mkdirSync(frameDir);
let frame = 0;
const grab = () => page.screenshot({ path: join(frameDir, `f${String(frame++).padStart(4, "0")}.png`) });

// PV_CHROMIUM: a browser binary to use instead of the one this Playwright version downloads.
const browser = await chromium.launch(process.env.PV_CHROMIUM ? { executablePath: process.env.PV_CHROMIUM } : {});
const context = await browser.newContext({ baseURL: base, ignoreHTTPSErrors: true, viewport: { width: 1280, height: 800 } });
const page = await context.newPage();
const shot = (name) => page.screenshot({ path: join(out, `${name}.png`), fullPage: false });

const demo = await (await context.request.get("/api/auth/demo")).json();
await page.goto("/");
const accounts = page.getByRole("region", { name: "Demo accounts" });
await accounts.waitFor();
// No password in an image, even the public demo one: the live page shows it, the screenshots do not. A style rule
// (not a text change, which React would undo on its next render) hides the cell's text and shows a note instead.
await accounts.getByText(demo.password, { exact: true }).evaluate((el) => el.setAttribute("data-pv-masked", ""));
await page.addStyleTag({ content: `[data-pv-masked]{font-size:0!important}
[data-pv-masked]::after{content:"(shown on the live page)";font-size:14px;font-family:ui-monospace,monospace}` });
const masked = await accounts.locator("[data-pv-masked]").evaluate((el) => getComputedStyle(el).fontSize);
if (masked !== "0px") throw new Error("password cell not masked");
await shot("landing");
await grab();
await grab();

await page.goto("/login");
await page.getByLabel("Username").fill("demo-reviewer");
await page.getByLabel("Password").fill(demo.password);
await page.getByRole("button", { name: "Sign in" }).click();
await page.waitForURL(/\/review/);

await page.goto("/demo");
const started = page.waitForResponse((r) => r.url().endsWith("/api/intake/demo/runs") && r.request().method() === "POST");
await page.getByRole("button", { name: "Run the pipeline" }).click();
const runId = (await (await started).json()).runId;
await page.getByRole("region", { name: "Pipeline stages" }).waitFor();
// One frame a second while the counters move, until the regulator has answered most of the run.
const deadline = Date.now() + 900_000;
for (;;) {
  await grab();
  const answered = await page.getByLabel(/^Regulator answered: \d+/).first().getAttribute("aria-label").catch(() => null);
  if (answered && Number(answered.split(": ")[1]) >= 150) break;
  if (Date.now() > deadline) throw new Error("the regulator did not answer in time");
  await page.waitForTimeout(1_000);
}
await shot("pipeline");

const session = await page.evaluate(() => JSON.parse(sessionStorage.getItem("pv.session") ?? "null"));
const auth = { Authorization: `Bearer ${session.token}` };
const cases = await (await context.request.get(`/api/cases/demo/runs/${runId}/cases`, { headers: auth })).json();
const caseId = cases.find((c) => c.status === "ACKNOWLEDGED")?.id;
if (caseId === undefined) throw new Error(`run ${runId}: no acknowledged case`);
await page.goto(`/review/${caseId}`);
await page.getByRole("button", { name: "Show XML" }).click();
await page.getByRole("region", { name: "E2B(R3) file" }).waitFor();
await shot("case");
await grab();
await grab();

const ranked = page.waitForResponse((r) => r.url().includes("/api/signals/top") && r.ok(), { timeout: 60_000 });
await page.goto("/signals");
await page.getByText(/Data scope:/).waitFor();
await ranked;
await page.waitForTimeout(800); // let the table render
await shot("signals");
await grab();
await grab();

await context.close();
await browser.close();

// GIF: 2 frames per second, 960 px wide, one shared palette (keeps the file small).
execFileSync("ffmpeg", ["-y", "-loglevel", "error", "-framerate", "2", "-i", join(frameDir, "f%04d.png"), "-vf",
  "scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=96[p];[b][p]paletteuse=dither=bayer",
  join(out, "demo.gif")]);
rmSync(frameDir, { recursive: true, force: true });
console.log(`wrote ${out}/{landing,pipeline,case,signals}.png and demo.gif (${frame} frames)`);
