// Types of the PharmaVigil REST APIs, mirroring the Java records of each service (pharmacy-service,
// case-service, signal-service). Kept in one place so every page uses the same shapes.

export interface Session {
  token: string;
  username: string;
  displayName: string;
  roles: string[];
  patientId: number | null;
}

export interface Problem {
  title?: string;
  status: number;
  detail?: string;
  reference?: string;
  errors?: string[];
  reasons?: string[];
}

// pharmacy-service
export interface Drug {
  id: number;
  productCode: string;
  name: string;
  strength: string;
  doseForm: string;
  rxcui: string | null;
  rxOnly: boolean;
  unitPriceCents: number;
  inDateStock: number;
}

export interface Patient {
  id: number;
  givenName: string;
  familyName: string;
  birthDate: string;
  sex: string;
  synthetic: boolean;
}

export interface Prescription {
  id: number;
  drugId: number;
  drug: string;
  prescriber: string;
  writtenDate: string;
  quantity: number;
  remainingQuantity: number;
  status: string;
}

export interface SafetyWarning {
  rxcui: string;
  pt: string;
  reports: number;
  ebgm: number | null;
  eb05: number | null;
  safetyformer: number | null;
  labelText: string | null;
  disclaimer: string;
}

export interface SignalLookup {
  available: boolean;
  warnings: SafetyWarning[];
  note: string | null;
}

export interface DispenseResult {
  orderId: number;
  drug: string;
  rxcui: string | null;
  quantity: number;
  totalCents: number;
  lots: { lotNumber: string; expiryDate: string; quantity: number }[];
}

export interface DispenseResponse {
  dispense: DispenseResult;
  safety: SignalLookup;
  disclaimers: string[];
}

export interface ReportView {
  id: number;
  patientId: number;
  drug: string;
  rxcui: string | null;
  narrative: string;
  onsetDate: string | null;
  status: string;
  reporter: string;
  createdAt: string;
}

// case-service
export type CaseStatus =
  | "RECEIVED" | "NEEDS_MANUAL_CODING" | "CODED" | "TRIAGED" | "IN_REVIEW" | "APPROVED" | "SUBMITTED"
  | "ACKNOWLEDGED" | "REJECTED";
export type Seriousness = "UNKNOWN" | "SERIOUS" | "NON_SERIOUS";

export interface QueueItem {
  id: number;
  caseNumber: string;
  status: CaseStatus;
  seriousness: Seriousness;
  seriousScore: number | null;
  receivedDate: string;
  dueDate: string;
  version: number;
  duplicateOfId: number | null;
}

export interface ReactionView {
  reportedTerm: string;
  pt: string | null;
  codeSource: "NONE" | "MODEL" | "REVIEWER" | "IMPORT";
  modelName: string | null;
  modelVersion: string | null;
  modelScore: number | null;
}

export interface NarrativeView {
  id: number;
  versionNo: number;
  body: string;
  source: "TEMPLATE" | "LLM" | "REVIEWER";
  modelName: string | null;
  createdBy: string;
  createdAt: string;
  approvedBy: string | null;
  approvedAt: string | null;
}

export interface DecisionView {
  id: number;
  field: string;
  itemRef: string | null;
  modelSuggestion: string | null;
  modelName: string | null;
  modelScore: number | null;
  finalValue: string | null;
  accepted: boolean;
  reviewer: string;
  decidedAt: string;
}

export interface AuditView {
  id: number;
  actor: string;
  action: string;
  detail: string;
  occurredAt: string;
}

export interface CaseDetail {
  head: QueueItem;
  lockVersion: number;
  reportedText: string;
  patientAge: number | null;
  patientSex: string | null;
  reporterType: string | null;
  country: string | null;
  onsetDate: string | null;
  drugs: { rxcui: string | null; name: string; role: string; dose: string | null; route: string | null }[];
  reactions: ReactionView[];
  narratives: NarrativeView[];
  decisions: DecisionView[];
  audit: AuditView[];
  disclaimer: string;
}

export interface ReviewResult {
  caseId: number;
  status: CaseStatus;
  version: number;
  lockVersion: number;
}

export interface SubmissionView {
  version: number;
  safetyReportId: string;
  messageId: string | null;
  status: "REQUESTED" | "QUEUED" | "ACK" | "NACK";
  reason: string | null;
  at: string;
}

// signal-service
export type SignalMethod = "ebgm" | "prr" | "ror" | "ic" | "safetyformer";

export interface Signal {
  rxcui: string;
  ingredient: string | null;
  pt: string;
  reports: number;
  prr: number | null;
  prrLo: number | null;
  ror: number | null;
  rorLo: number | null;
  ic: number | null;
  ic025: number | null;
  ebgm: number | null;
  eb05: number | null;
  eb95: number | null;
  safetyformer: number | null;
}

export interface TopSignals {
  method: SignalMethod;
  scope: string | null;
  signals: Signal[];
  disclaimer: string;
}

export interface Trend {
  rxcui: string;
  pt: string;
  scope: string | null;
  points: { year: number; reports: number }[];
}

export interface EvaluationEntry {
  [method: string]: { auc?: number; auc_lo?: number; auc_hi?: number; scorable?: number; [k: string]: unknown };
}

export interface SignalMeta {
  loaded: boolean;
  scope: string | null;
  pairs: number | null;
  withSafetyformer: boolean | null;
  loadedAt: string | null;
  evaluation: Record<string, unknown> | null;
  warningEb05: number;
  disclaimer: string;
}
