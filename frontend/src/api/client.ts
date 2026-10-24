// The one typed API client every page uses. Requests go to same-origin /api paths (the Vite dev server and the
// nginx container route them to the right service), carry the bearer token, and turn RFC 7807 problem details
// into ApiError so pages can show the server's explanation.
import type {
  CaseDetail, DispenseResponse, Drug, Patient, Prescription, QueueItem, ReportView, ReviewResult, Seriousness,
  Session, SignalMeta, SignalMethod, SubmissionView, TopSignals, Trend,
} from "./types";

export class ApiError extends Error {
  readonly status: number;
  readonly details: string[];

  constructor(status: number, message: string, details: string[] = []) {
    super(message);
    this.status = status;
    this.details = details;
  }
}

type TokenSource = () => string | null;

export class ApiClient {
  private readonly token: TokenSource;
  private readonly fetchImpl: typeof fetch;

  constructor(token: TokenSource, fetchImpl: typeof fetch = (...args) => fetch(...args)) {
    this.token = token;
    this.fetchImpl = fetchImpl;
  }

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json" };
    const token = this.token();
    if (token) headers.Authorization = `Bearer ${token}`;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const res = await this.fetchImpl(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!res.ok) {
      let message = `${res.status} ${res.statusText}`.trim();
      let details: string[] = [];
      try {
        const problem = await res.json();
        message = problem.detail ?? problem.title ?? message;
        details = problem.errors ?? problem.reasons ?? [];
      } catch {
        // not a problem-details body
      }
      throw new ApiError(res.status, message, details);
    }
    if (res.status === 204) return undefined as T;
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  // auth (pharmacy-service issues tokens that every service accepts)
  login(username: string, password: string) {
    return this.request<Session>("POST", "/api/auth/login", { username, password });
  }

  // pharmacy-service
  drugs() {
    return this.request<Drug[]>("GET", "/api/drugs");
  }
  patients() {
    return this.request<Patient[]>("GET", "/api/patients");
  }
  prescriptions(patientId: number) {
    return this.request<Prescription[]>("GET", `/api/patients/${patientId}/prescriptions`);
  }
  dispense(patientId: number, drugId: number, quantity: number, prescriptionId: number | null) {
    return this.request<DispenseResponse>("POST", "/api/dispenses", { patientId, drugId, quantity, prescriptionId });
  }
  report(patientId: number, drugId: number, narrative: string, onsetDate: string | null) {
    return this.request<ReportView>("POST", "/api/reports", { patientId, drugId, narrative, onsetDate });
  }
  recentReports() {
    return this.request<ReportView[]>("GET", "/api/reports");
  }

  // case-service
  queue() {
    return this.request<QueueItem[]>("GET", "/api/cases/queue");
  }
  caseDetail(id: number) {
    return this.request<CaseDetail>("GET", `/api/cases/${id}`);
  }
  codeReaction(id: number, index: number, pt: string, lockVersion: number) {
    return this.request<ReviewResult>("POST", `/api/cases/${id}/reactions/${index}/code`, { pt, lockVersion });
  }
  decideSeriousness(id: number, value: Exclude<Seriousness, "UNKNOWN">) {
    return this.request<ReviewResult>("POST", `/api/cases/${id}/seriousness`, { value });
  }
  decideDuplicate(id: number, duplicateOf: number | null) {
    return this.request<ReviewResult>("POST", `/api/cases/${id}/duplicate`, { duplicateOf });
  }
  writeNarrative(id: number, body: string) {
    return this.request<number>("POST", `/api/cases/${id}/narratives`, { body });
  }
  approveNarrative(id: number, narrativeId: number) {
    return this.request<ReviewResult>("POST", `/api/cases/${id}/narratives/${narrativeId}/approve`);
  }
  approve(id: number) {
    return this.request<ReviewResult>("POST", `/api/cases/${id}/approve`);
  }
  submit(id: number) {
    return this.request<SubmissionView>("POST", `/api/cases/${id}/submit`);
  }
  submissions(id: number) {
    return this.request<SubmissionView[]>("GET", `/api/cases/${id}/submissions`);
  }

  // signal-service
  topSignals(method: SignalMethod, limit = 25) {
    return this.request<TopSignals>("GET", `/api/signals/top?method=${method}&limit=${limit}`);
  }
  trend(rxcui: string, pt: string) {
    return this.request<Trend>("GET",
      `/api/signals/trend?rxcui=${encodeURIComponent(rxcui)}&pt=${encodeURIComponent(pt)}`);
  }
  signalMeta() {
    return this.request<SignalMeta>("GET", "/api/signals/meta");
  }
}
