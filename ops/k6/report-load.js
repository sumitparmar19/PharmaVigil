// k6 load test for the report pipeline (docs/PERFORMANCE.md). Scenario "reports" posts de-identified pharmacy
// reports to intake-gateway at a rising arrival rate; scenario "review" is one reviewer working the queue, which
// feeds the AI-quality metrics. Pipeline numbers (intake-to-coded p95, consumer lag) come from Prometheus
// afterwards (ops/k6/collect_results.py). Synthetic data only; the demo password comes from PV_DEMO_PASSWORD.
// The --summary-export file contains setup_data (the sign-in tokens): collect_results.py --strip-summary removes
// it before a summary is kept.
import http from "k6/http";
import { check, sleep } from "k6";

const BASE = __ENV.PV_BASE_URL || "http://localhost:8080";
const PEAK = Number(__ENV.PV_K6_PEAK || 40); // reports per second at the last stage
const STAGE = __ENV.PV_K6_STAGE || "1m";

export const options = {
  scenarios: {
    reports: {
      executor: "ramping-arrival-rate",
      startRate: 1,
      timeUnit: "1s",
      preAllocatedVUs: 20,
      maxVUs: 200,
      stages: [
        { target: Math.max(1, Math.round(PEAK / 8)), duration: STAGE },
        { target: Math.max(1, Math.round(PEAK / 4)), duration: STAGE },
        { target: Math.max(1, Math.round(PEAK / 2)), duration: STAGE },
        { target: PEAK, duration: STAGE },
        { target: PEAK, duration: STAGE },
      ],
    },
    review: { executor: "constant-vus", exec: "review", vus: 1, duration: "5m", startTime: "20s" },
  },
  thresholds: {
    "http_req_failed{scenario:reports}": ["rate<0.01"],
    "http_req_duration{scenario:reports}": ["p(95)<1000"],
  },
};

const TERMS = ["rash", "nausea", "headache", "dizziness", "pruritus", "vomiting", "myalgia", "diarrhoea"];
const DRUGS = [
  { rxcui: "5640", name: "Ibuprofen 200 mg tablet" },
  { rxcui: "83367", name: "Atorvastatin 20 mg tablet" },
  { rxcui: "6809", name: "Metformin 500 mg tablet" },
  { rxcui: "723", name: "Amoxicillin 500 mg capsule" },
];

function login(username) {
  const res = http.post(`${BASE}/api/auth/login`, JSON.stringify({ username, password: __ENV.PV_DEMO_PASSWORD }),
    { headers: { "Content-Type": "application/json" }, tags: { name: "login" } });
  check(res, { [`${username} signed in`]: (r) => r.status === 200 });
  return res.json("token");
}

export function setup() {
  if (!__ENV.PV_DEMO_PASSWORD) throw new Error("set PV_DEMO_PASSWORD");
  return { pharmacist: login("pharmacist"), reviewer: login("reviewer") };
}

export default function (tokens) {
  const term = TERMS[Math.floor(Math.random() * TERMS.length)];
  const drug = DRUGS[Math.floor(Math.random() * DRUGS.length)];
  const body = {
    pharmacyReportId: `K6-${__VU}-${__ITER}-${Date.now()}`,
    patientAgeYears: 18 + Math.floor(Math.random() * 70),
    patientSex: Math.random() < 0.5 ? "F" : "M",
    narrative: `Synthetic load-test report: ${term} after starting the medicine.`,
    drugs: [{ ...drug, role: "PS", doseText: "as directed" }],
    reactionTerms: [term],
  };
  const res = http.post(`${BASE}/api/intake/reports`, JSON.stringify(body), {
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${tokens.pharmacist}` },
    tags: { name: "intake report" },
  });
  check(res, { "report accepted (202)": (r) => r.status === 202 });
}

// One reviewer: open the first case waiting in the queue, keep each model code (one in five is changed to
// another term, as a reviewer correction would be) and confirm the suggested seriousness.
export function review(tokens) {
  const auth = { headers: { Authorization: `Bearer ${tokens.reviewer}`, "Content-Type": "application/json" } };
  const queue = http.get(`${BASE}/api/cases/queue`, { ...auth, tags: { name: "queue" } });
  const next = (queue.json() || []).find((c) => c.status === "TRIAGED" || c.status === "NEEDS_MANUAL_CODING");
  if (!next) {
    sleep(2);
    return;
  }
  const detail = http.get(`${BASE}/api/cases/${next.id}`, { ...auth, tags: { name: "case" } }).json();
  let lock = detail.lockVersion;
  detail.reactions.forEach((r, i) => {
    if (r.codeSource !== "MODEL") return;
    const pt = Math.random() < 0.2 ? TERMS[(TERMS.indexOf(r.pt) + 1) % TERMS.length] : r.pt;
    const res = http.post(`${BASE}/api/cases/${next.id}/reactions/${i}/code`, JSON.stringify({ pt, lockVersion: lock }),
      { ...auth, tags: { name: "code" } });
    if (res.status === 200) lock = res.json("lockVersion");
  });
  const value = detail.head.seriousness === "SERIOUS" ? "SERIOUS" : "NON_SERIOUS";
  http.post(`${BASE}/api/cases/${next.id}/seriousness`, JSON.stringify({ value }), { ...auth, tags: { name: "seriousness" } });
  sleep(1);
}
