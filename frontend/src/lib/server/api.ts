import { env } from "$env/dynamic/private";
import type { RecordResponse, PageResponse, SpringPage } from "$lib/types";

function backendUrl(): string {
  const url = env.BACKEND_URL;
  if (!url) throw new Error("BACKEND_URL env var is not set");
  return url;
}

function authHeaders(email?: string): Record<string, string> {
  if (email) return { "X-Auth-Email": email };
  return {};
}

export interface AuthUser {
  authenticated: boolean;
  email?: string;
  displayName?: string;
  role?: string;
  familyTreePersonId?: number;
  signedInAs?: string;
  // Set when we could not get a definitive answer from the backend (5xx or
  // a network failure), as opposed to a genuine "authenticated: false"
  // response, which the backend always returns as a 200 for a signed-in
  // user who simply isn't on the allowlist (see AuthController#me). Callers
  // must NOT treat backendUnavailable as "not allowlisted" -- it means
  // "couldn't ask", not "no".
  backendUnavailable?: boolean;
}

export async function fetchCurrentUser(email: string): Promise<AuthUser> {
  try {
    const res = await fetch(`${backendUrl()}/api/auth/me`, {
      headers: authHeaders(email),
    });
    if (res.status >= 500) return { authenticated: false, backendUnavailable: true };
    if (!res.ok) return { authenticated: false };
    return res.json();
  } catch {
    return { authenticated: false, backendUnavailable: true };
  }
}

export async function fetchRecords(
  email: string | undefined,
  page: number,
  size: number,
  sortBy: string,
  sortDir: string,
  status?: string,
  archiveId?: number,
): Promise<SpringPage<RecordResponse>> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
    sortBy,
    sortDir,
  });
  if (status) params.set("status", status);
  if (archiveId) params.set("archiveId", String(archiveId));
  const res = await fetch(`${backendUrl()}/api/records?${params}`, {
    headers: authHeaders(email),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export interface ArchiveInfo {
  id: number;
  name: string;
  country: string;
}

export async function fetchArchives(email?: string): Promise<ArchiveInfo[]> {
  const res = await fetch(`${backendUrl()}/api/records/archives`, {
    headers: authHeaders(email),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export async function fetchRecord(email: string | undefined, id: number): Promise<RecordResponse> {
  const res = await fetch(`${backendUrl()}/api/records/${id}`, {
    headers: authHeaders(email),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export async function fetchRecordPages(email: string | undefined, id: number): Promise<PageResponse[]> {
  const res = await fetch(`${backendUrl()}/api/records/${id}/pages`, {
    headers: authHeaders(email),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export interface PageTextResponse {
  pageId: number;
  text: string;
  confidence: number;
  engine: string;
  textEn: string;
}

export async function fetchPageText(email: string | undefined, pageId: number): Promise<PageTextResponse> {
  const res = await fetch(`${backendUrl()}/api/pages/${pageId}/text`, {
    headers: authHeaders(email),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export interface SearchResult {
  pageTextId: number;
  pageId: number;
  confidence: number;
  engine: string;
  snippet: string;
  seq: number;
  recordId: number;
  recordTitle: string | null;
  referenceCode: string | null;
}

export interface SearchResponse {
  results: SearchResult[];
  total: number;
  page: number;
  size: number;
}

export interface WorkerDetail {
  kind: string;
  label: string;
  workers: number;
  busy: number;
  pending: number;
  failed: number;
}

export interface PipelineStage {
  name: string;
  records: number;
  pages: number;
  jobsPending?: number;
  jobsRunning?: number;
  jobsCompleted?: number;
  jobsFailed?: number;
  workersConnected?: number;
  pagesDone?: number;
  pagesTotal?: number;
  workerDetails?: WorkerDetail[];
}

export interface ScraperInfo {
  scraperId: string;
  sourceSystem: string;
  sourceName: string;
  recordsIngested: number;
  pagesIngested: number;
  lastSeen: string;
}

export interface PipelineStats {
  stages: PipelineStage[];
  totals: { records: number; pages: number };
  scrapers?: ScraperInfo[];
}

export interface ScraperInstance {
  scraperId: string;
  sourceName: string;
  recordsIngested: number;
  pagesIngested: number;
}

export interface ScraperEntry {
  id: string;
  name: string;
  instances: ScraperInstance[];
}

export async function fetchSourceStatus(email?: string): Promise<ScraperEntry[]> {
  const res = await fetch(`${backendUrl()}/api/viewer/source-status`, {
    headers: authHeaders(email),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export async function fetchPipelineStats(email?: string): Promise<PipelineStats> {
  const res = await fetch(`${backendUrl()}/api/pipeline/stats`, {
    headers: authHeaders(email),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export interface PipelineEvent {
  stage: string;
  event: string;
  detail: string | null;
  created_at: string;
}

export interface JobStat {
  kind: string;
  status: string;
  cnt: number;
  first_created: string | null;
  first_started: string | null;
  last_finished: string | null;
}

export interface RecordTimeline {
  events: PipelineEvent[];
  jobs: JobStat[];
}

export async function fetchRecordTimeline(
  email: string | undefined,
  recordId: number,
): Promise<RecordTimeline> {
  const res = await fetch(`${backendUrl()}/api/records/${recordId}/timeline`, {
    headers: authHeaders(email),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  const data: { type: string; data: unknown }[] = await res.json();
  const events = (data.find((d) => d.type === "events")?.data ??
    []) as PipelineEvent[];
  const jobs = (data.find((d) => d.type === "jobs")?.data ?? []) as JobStat[];
  return { events, jobs };
}

export async function fetchAdminStats(email?: string): Promise<Record<string, unknown>> {
  const res = await fetch(`${backendUrl()}/api/admin/stats`, {
    headers: authHeaders(email),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export async function runAudit(email?: string): Promise<{ fixed: number }> {
  const res = await fetch(`${backendUrl()}/api/admin/audit`, {
    method: "POST",
    headers: authHeaders(email),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export async function searchPages(
  email: string | undefined,
  q: string,
  page: number = 0,
  size: number = 20,
): Promise<SearchResponse> {
  const params = new URLSearchParams({
    q,
    page: String(page),
    size: String(size),
  });
  const res = await fetch(`${backendUrl()}/api/search?${params}`, {
    headers: authHeaders(email),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export interface SemanticSearchResult {
  recordId: number;
  pageId: number | null;
  pageSeq: number | null;
  chunkIndex: number;
  content: string;
  score: number;
  recordTitle: string | null;
  recordTitleEn: string | null;
  referenceCode: string | null;
  descriptionEn: string | null;
}

export interface SemanticSearchResponse {
  results: SemanticSearchResult[];
}

export async function semanticSearch(
  email: string | undefined,
  query: string,
  limit: number = 10,
): Promise<SemanticSearchResponse> {
  const res = await fetch(`${backendUrl()}/api/search/semantic`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...authHeaders(email) },
    body: JSON.stringify({ query, limit }),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export async function searchFamilyTree(
  email: string | undefined,
  q: string,
  limit: number = 10,
): Promise<any[]> {
  const params = new URLSearchParams({ q, limit: String(limit) });
  const res = await fetch(`${backendUrl()}/api/family-tree/search?${params}`, {
    headers: authHeaders(email),
  });
  if (!res.ok) return [];
  return res.json();
}

export async function fetchTranslateCapabilities(email?: string): Promise<{
  pairs: { source: string; target: string }[];
}> {
  try {
    const res = await fetch(`${backendUrl()}/api/translate/capabilities`, {
      headers: authHeaders(email),
    });
    if (!res.ok) return { pairs: [] };
    return res.json();
  } catch {
    return { pairs: [] };
  }
}

export async function relatePerson(
  email: string | undefined,
  personId: number,
  refId?: number,
): Promise<any | null> {
  const params = new URLSearchParams({ personId: String(personId) });
  if (refId != null) params.set("refId", String(refId));
  const res = await fetch(
    `${backendUrl()}/api/family-tree/relate?${params}`,
    { headers: authHeaders(email) },
  );
  if (!res.ok) return null;
  return res.json();
}

export async function fetchFamilyPerson(email: string | undefined, personId: number): Promise<any | null> {
  const res = await fetch(`${backendUrl()}/api/family-tree/person/${personId}`, {
    headers: authHeaders(email),
  });
  if (!res.ok) return null;
  return res.json();
}

export interface PagePersonMatch {
  personId: number;
  personName: string;
  score: number;
  context: string | null;
  section: string | null;
  code: string | null;
  birthYear: number | null;
  deathYear: number | null;
}

export async function fetchPagePersonMatches(
  email: string | undefined,
  pageId: number,
): Promise<PagePersonMatch[]> {
  const res = await fetch(
    `${backendUrl()}/api/family-tree/page-matches/${pageId}`,
    { headers: authHeaders(email) },
  );
  if (!res.ok) return [];
  return res.json();
}

export interface RecordPersonMatch {
  personId: number;
  personName: string;
  maxScore: number;
  pageCount: number;
  pageSeqs: number[];
  section: string | null;
  code: string | null;
  birthYear: number | null;
  deathYear: number | null;
}

export async function fetchRecordPersonMatches(
  email: string | undefined,
  recordId: number,
): Promise<RecordPersonMatch[]> {
  const res = await fetch(
    `${backendUrl()}/api/family-tree/record-matches/${recordId}`,
    { headers: authHeaders(email) },
  );
  if (!res.ok) return [];
  return res.json();
}

// ── Admin Pipeline Reset ──

export interface ResetResult {
  recordId: number;
  targetStage: string;
  jobsEnqueued: number;
  jobsCancelled: number;
  error?: string;
}

export async function resetRecordPipeline(
  email: string,
  recordIds: number[],
  targetStage: string,
): Promise<{ results: ResetResult[] }> {
  const res = await fetch(`${backendUrl()}/api/admin/records/reset-pipeline`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...authHeaders(email) },
    body: JSON.stringify({ recordIds, targetStage }),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

// ── Self-service Profile ──

export interface ProfileEmail {
  id: number;
  email: string;
}

export interface UserProfile {
  id: number;
  displayName: string;
  role: string;
  loginEmail: string;
  familyTreePersonId?: number;
  emails: ProfileEmail[];
}

export async function fetchProfile(email: string): Promise<UserProfile> {
  const res = await fetch(`${backendUrl()}/api/profile`, {
    headers: authHeaders(email),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export async function updateProfile(
  email: string,
  body: { displayName?: string; familyTreePersonId?: number | null },
): Promise<{ id: number; displayName: string; familyTreePersonId?: number }> {
  const res = await fetch(`${backendUrl()}/api/profile`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", ...authHeaders(email) },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export async function addProfileEmail(
  email: string,
  newEmail: string,
): Promise<ProfileEmail> {
  const res = await fetch(`${backendUrl()}/api/profile/emails`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...authHeaders(email) },
    body: JSON.stringify({ email: newEmail }),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export async function removeProfileEmail(
  email: string,
  emailId: number,
): Promise<void> {
  const res = await fetch(`${backendUrl()}/api/profile/emails/${emailId}`, {
    method: "DELETE",
    headers: authHeaders(email),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error || `Backend error: ${res.status}`);
  }
}

// ── Admin User CRUD ──

export interface AdminUserEmail {
  id: number;
  email: string;
}

export interface AdminUser {
  id: number;
  display_name: string | null;
  role: string;
  created_at: string;
  updated_at: string;
  emails: AdminUserEmail[];
}

export async function fetchUsers(email: string): Promise<AdminUser[]> {
  const res = await fetch(`${backendUrl()}/api/admin/users`, {
    headers: authHeaders(email),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export async function createUser(
  email: string,
  body: { displayName: string; role: string; emails: string[] },
): Promise<any> {
  const res = await fetch(`${backendUrl()}/api/admin/users`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...authHeaders(email) },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export async function updateUser(
  email: string,
  id: number,
  body: { displayName?: string; role?: string },
): Promise<any> {
  const res = await fetch(`${backendUrl()}/api/admin/users/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", ...authHeaders(email) },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export async function deleteUser(email: string, id: number): Promise<void> {
  const res = await fetch(`${backendUrl()}/api/admin/users/${id}`, {
    method: "DELETE",
    headers: authHeaders(email),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
}

export async function addUserEmail(
  email: string,
  userId: number,
  newEmail: string,
): Promise<any> {
  const res = await fetch(`${backendUrl()}/api/admin/users/${userId}/emails`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...authHeaders(email) },
    body: JSON.stringify({ email: newEmail }),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export async function removeUserEmail(
  email: string,
  userId: number,
  emailId: number,
): Promise<void> {
  const res = await fetch(
    `${backendUrl()}/api/admin/users/${userId}/emails/${emailId}`,
    {
      method: "DELETE",
      headers: authHeaders(email),
    },
  );
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
}
