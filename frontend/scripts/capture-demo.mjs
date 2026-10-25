// Records the README's screenshots and GIF from a running demo stack (docker-compose.demo.yml), so the images
// show the real pipeline rather than mock-ups: the landing page, a "Run the pipeline" replay with its live
// counters, one acknowledged case with its E2B(R3) file, and the signals page. Signs in with the public demo
// account the landing page itself shows. Usage:
//   node scripts/capture-demo.mjs <base-url> <out-dir>        e.g. https://localhost ../docs/img
// Needs ffmpeg for the GIF.
import { execFileSync } from "node:child_process";
import { mkdirSync, readdirSync, renameSync, rmSync } from "node:fs";
import { join } from "node:path";
import { chromium } from "playwright";

const [base = "https://localhost", out = "../docs/img"] = process.argv.slice(2);
mkdirSync(out, { recursive: true });
const videoDir = join(out, ".video");
rmSync(videoDir, { recursive: true, force: true });

const browser = await chromium.launch();
const context = await browser.newContext({
  baseURL: base, ignoreHTTPSErrors: true, viewport: { width: 1280, height: 800 },
  recordVideo: { dir: videoDir, size: { width: 1280, height: 800 } },
});
const page = await context.newPage();
const shot = (name) => page.screenshot({ path: join(out, `${name}.png`), fullPage: false });

await page.goto("/");
await page.getByRole("region", { name: "Demo accounts" }).waitFor();
await shot("landing");

const demo = await (await context.request.get("/api/auth/demo")).json();
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
// Wait until the regulator has answered some cases, so the counters show every stage moving.
await page.getByLabel(/^Regulator answered: [1-9]/).waitFor({ timeout: 900_000 });
await page.waitForTimeout(3_000);
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

await page.goto("/signals");
await page.getByText(/Data scope:/).waitFor();
await shot("signals");

await context.close();
await browser.close();

const [video] = readdirSync(videoDir).filter((f) => f.endsWith(".webm"));
renameSync(join(videoDir, video), join(out, "demo.webm"));
rmSync(videoDir, { recursive: true, force: true });
// GIF: 8 frames per second, 960 px wide, one shared palette (keeps the file small).
execFileSync("ffmpeg", ["-y", "-loglevel", "error", "-i", join(out, "demo.webm"), "-vf",
  "fps=8,scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=96[p];[b][p]paletteuse=dither=bayer",
  join(out, "demo.gif")]);
rmSync(join(out, "demo.webm"));
console.log(`wrote ${out}/{landing,pipeline,case,signals}.png and demo.gif`);
