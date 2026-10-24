// Builds frontend/public/evidence.json, the numbers the Evidence page and the project site show, only from result
// files committed under analysis/ (each number carries the scope label of the file it came from). A result that
// does not exist yet is listed as "pending" instead of being filled in. Run: npm run evidence
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const out = join(root, "frontend", "public", "evidence.json");

function load(file) {
  const path = join(root, file);
  return existsSync(path) ? JSON.parse(readFileSync(path, "utf8")) : null;
}

const int = (n) => Number(n).toLocaleString("en-US");
const pct = (x) => `${(100 * x).toFixed(1)}%`;
const dec = (x) => Number(x).toFixed(3);
const auc = (m) => (m && m.auc != null ? `${dec(m.auc)} (95% CI ${dec(m.ci_low)}–${dec(m.ci_high)})` : null);

function item(label, file, data, pick, note) {
  const value = data ? pick(data) : null;
  if (value == null) {
    return { label, value: "not measured yet", scope: "", file, status: "pending", ...(note ? { note } : {}) };
  }
  return { label, value: String(value), scope: data.scope ?? "", file, status: "measured", ...(note ? { note } : {}) };
}

const sections = [];

// P1: the data platform on real FAERS.
const counts = load("analysis/p1/data_counts.json");
const dataFile = "analysis/p1/data_counts.json";
sections.push({
  title: "Real FAERS data (bronze → silver → gold)",
  items: [
    item("Quarters loaded", dataFile, counts,
      (d) => (d.quarters?.length ? `${d.quarters.length} (${d.quarters[0]}–${d.quarters[d.quarters.length - 1]})` : null)),
    item("FAERS report rows", dataFile, counts, (d) => int(d.faers_report_rows)),
    item("Unique cases after de-duplication", dataFile, counts, (d) => int(d.unique_cases_after_dedup),
      "FDA's rule: latest version of each case, deleted cases removed"),
    item("Drug mentions mapped to an RxNorm ingredient", dataFile, counts,
      (d) => `${int(d.drug_mentions_mapped_to_rxnorm)} of ${int(d.drug_mentions)} (${pct(d.drug_mentions_mapped_to_rxnorm / d.drug_mentions)})`),
    item("Distinct ingredients / preferred terms", dataFile, counts,
      (d) => `${int(d.distinct_ingredients)} / ${int(d.distinct_pts)}`),
    item("Drug–event pairs (primary suspect)", dataFile, counts, (d) => int(d.drug_event_pairs_primary_suspect)),
  ],
});

// P2: disproportionality baselines against the public reference sets.
const base = load("analysis/p2/baselines.json");
const baseFile = "analysis/p2/baselines.json";
const refName = { omop: "OMOP", euadr: "EU-ADR" };
for (const set of ["omop", "euadr"]) {
  const r = (d) => d.variants?.primary_suspect?.reference_sets?.[set];
  sections.push({
    title: `Signal detection: ${refName[set]} reference set (primary-suspect drugs)`,
    items: [
      item("Scorable pairs (positives / negatives)", baseFile, base,
        (d) => (r(d) ? `${r(d).scorable_pairs} of ${r(d).pairs} (${r(d).scorable_positives} / ${r(d).scorable_negatives})` : null)),
      ...["prr", "ror", "ic", "ebgm"].map((m) =>
        item(`${m.toUpperCase()} AUC`, baseFile, base, (d) => auc(r(d)?.methods?.[m]))),
    ],
  });
}

// P3: SafetyFormer on the same pairs as the baselines.
const sf = load("analysis/p3/safetyformer.json");
const sfFile = "analysis/p3/safetyformer.json";
sections.push({
  title: "SafetyFormer (learned signal scores) on the same pairs",
  items: ["omop", "euadr"].flatMap((set) => [
    item(`${refName[set]}: SafetyFormer AUC`, sfFile, sf, (d) => auc(d.reference_sets?.[set]?.methods?.safetyformer)),
    item(`${refName[set]}: best baseline AUC on the same pairs`, sfFile, sf, (d) => {
      const methods = d.reference_sets?.[set]?.methods ?? {};
      const best = ["prr", "ror", "ic", "ebgm"].filter((m) => methods[m]).sort((a, b) => methods[b].auc - methods[a].auc)[0];
      return best ? `${best.toUpperCase()} ${auc(methods[best])}` : null;
    }),
  ]),
});

// P4: the case-service models.
const triage = load("analysis/p4/triage.json");
const dups = load("analysis/p4/duplicates.json");
const coder = load("analysis/p4/coder.json");
sections.push({
  title: "Case models (suggestions a reviewer confirms)",
  items: [
    item("Seriousness triage ROC-AUC / PR-AUC (time-split test)", "analysis/p4/triage.json", triage,
      (d) => (d.roc_auc != null ? `${dec(d.roc_auc)} / ${dec(d.pr_auc)} (serious rate ${pct(d.test_serious_rate)})` : null)),
    item("Duplicate detection PR-AUC; precision / recall at best F1", "analysis/p4/duplicates.json", dups,
      (d) => (d.pr_auc != null ? `${dec(d.pr_auc)}; ${dec(d.at_best_f1.precision)} / ${dec(d.at_best_f1.recall)}` : null),
      "proxy label: consecutive versions of one FAERS case"),
    item("MedDRA coder", "analysis/p4/coder.json", coder,
      (d) => (d.evaluation && !String(d.evaluation).startsWith("pending") ? d.evaluation : null),
      coder ? `${coder.kind}; ${coder.label_space}. Accuracy needs narratives with human-assigned terms.` : undefined),
  ],
});

// P6: memory of the demo stack, one entry per measurement file.
const p6 = join(root, "analysis", "p6");
const memFiles = existsSync(p6) ? readdirSync(p6).filter((f) => /^memory_.*\.json$/.test(f)).sort() : [];
sections.push({
  title: "Live demo memory (docker stats)",
  items: memFiles.length
    ? memFiles.map((f) => {
        const file = relative(root, join(p6, f));
        return item(`Idle / peak during a replay (${f.replace(/^memory_|\.json$/g, "")})`, file, load(file),
          (d) => (d.idle_total_mib != null ? `${int(Math.round(d.idle_total_mib))} MiB / ${int(Math.round(d.peak_total_mib))} MiB` : null));
      })
    : [item("Idle / peak during a replay", "analysis/p6/", null, () => null)],
});

mkdirSync(dirname(out), { recursive: true });
const evidence = {
  generatedAt: new Date().toISOString().slice(0, 10),
  repository: "https://github.com/sumitparmar19/PharmaVigil",
  sections,
};
writeFileSync(out, JSON.stringify(evidence, null, 2) + "\n");
const pending = sections.flatMap((s) => s.items).filter((i) => i.status === "pending").length;
console.log(`wrote ${relative(root, out)} (${pending} pending)`);
