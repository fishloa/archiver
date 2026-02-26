import { env } from '$env/dynamic/private';
import type { RecordResponse, PageResponse, SpringPage } from '$lib/types';

function backendUrl(): string {
	const url = env.BACKEND_URL;
	if (!url) throw new Error('BACKEND_URL env var is not set');
	return url;
}

function authHeaders(email?: string): Record<string, string> {
	if (email) return { 'X-Auth-Email': email };
	return {};
}

export interface AuthUser {
	authenticated: boolean;
	email?: string;
	displayName?: string;
	role?: string;
}

export async function fetchCurrentUser(email: string): Promise<AuthUser> {
	const res = await fetch(`${backendUrl()}/api/auth/me`, {
		headers: authHeaders(email)
	});
	if (!res.ok) return { authenticated: false };
	return res.json();
}

export async function fetchRecords(
	page: number,
	size: number,
	sortBy: string,
	sortDir: string,
	status?: string,
	archiveId?: number
): Promise<SpringPage<RecordResponse>> {
	const params = new URLSearchParams({
		page: String(page),
		size: String(size),
		sortBy,
		sortDir
	});
	if (status) params.set('status', status);
	if (archiveId) params.set('archiveId', String(archiveId));
	const res = await fetch(`${backendUrl()}/api/records?${params}`);
	if (!res.ok) throw new Error(`Backend error: ${res.status}`);
	return res.json();
}

export interface ArchiveInfo {
	id: number;
	name: string;
	country: string;
}

export async function fetchArchives(): Promise<ArchiveInfo[]> {
	const res = await fetch(`${backendUrl()}/api/records/archives`);
	if (!res.ok) throw new Error(`Backend error: ${res.status}`);
	return res.json();
}

export async function fetchRecord(id: number): Promise<RecordResponse> {
	const res = await fetch(`${backendUrl()}/api/records/${id}`);
	if (!res.ok) throw new Error(`Backend error: ${res.status}`);
	return res.json();
}

export async function fetchRecordPages(id: number): Promise<PageResponse[]> {
	const res = await fetch(`${backendUrl()}/api/records/${id}/pages`);
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

export async function fetchPageText(pageId: number): Promise<PageTextResponse> {
	const res = await fetch(`${backendUrl()}/api/pages/${pageId}/text`);
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
}

export interface PipelineStats {
	stages: PipelineStage[];
	totals: { records: number; pages: number };
}

export async function fetchPipelineStats(): Promise<PipelineStats> {
	const res = await fetch(`${backendUrl()}/api/pipeline/stats`);
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

export async function fetchRecordTimeline(recordId: number): Promise<RecordTimeline> {
	const res = await fetch(`${backendUrl()}/api/records/${recordId}/timeline`);
	if (!res.ok) throw new Error(`Backend error: ${res.status}`);
	const data: { type: string; data: unknown }[] = await res.json();
	const events = (data.find((d) => d.type === 'events')?.data ?? []) as PipelineEvent[];
	const jobs = (data.find((d) => d.type === 'jobs')?.data ?? []) as JobStat[];
	return { events, jobs };
}

export async function fetchAdminStats(): Promise<Record<string, unknown>> {
	const res = await fetch(`${backendUrl()}/api/admin/stats`);
	if (!res.ok) throw new Error(`Backend error: ${res.status}`);
	return res.json();
}

export async function runAudit(email?: string): Promise<{ fixed: number }> {
	const res = await fetch(`${backendUrl()}/api/admin/audit`, {
		method: 'POST',
		headers: authHeaders(email)
	});
	if (!res.ok) throw new Error(`Backend error: ${res.status}`);
	return res.json();
}

export async function searchPages(q: string, page: number = 0, size: number = 20): Promise<SearchResponse> {
	const params = new URLSearchParams({ q, page: String(page), size: String(size) });
	const res = await fetch(`${backendUrl()}/api/search?${params}`);
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

export async function semanticSearch(query: string, limit: number = 10): Promise<SemanticSearchResponse> {
	const res = await fetch(`${backendUrl()}/api/search/semantic`, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ query, limit })
	});
	if (!res.ok) throw new Error(`Backend error: ${res.status}`);
	return res.json();
}

export async function searchFamilyTree(q: string, limit: number = 10): Promise<any[]> {
	const params = new URLSearchParams({ q, limit: String(limit) });
	const res = await fetch(`${backendUrl()}/api/family-tree/search?${params}`);
	if (!res.ok) return [];
	return res.json();
}

export async function relatePerson(personId: number): Promise<any | null> {
	const res = await fetch(`${backendUrl()}/api/family-tree/relate?personId=${personId}`);
	if (!res.ok) return null;
	return res.json();
}

export async function fetchFamilyPerson(personId: number): Promise<any | null> {
	const res = await fetch(`${backendUrl()}/api/family-tree/person/${personId}`);
	if (!res.ok) return null;
	return res.json();
}

// ── Admin User CRUD ──

export async function fetchUsers(email: string): Promise<any[]> {
	const res = await fetch(`${backendUrl()}/api/admin/users`, {
		headers: authHeaders(email)
	});
	if (!res.ok) throw new Error(`Backend error: ${res.status}`);
	return res.json();
}

export async function createUser(
	email: string,
	body: { displayName: string; role: string; emails: string[] }
): Promise<any> {
	const res = await fetch(`${backendUrl()}/api/admin/users`, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json', ...authHeaders(email) },
		body: JSON.stringify(body)
	});
	if (!res.ok) throw new Error(`Backend error: ${res.status}`);
	return res.json();
}

export async function updateUser(
	email: string,
	id: number,
	body: { displayName?: string; role?: string }
): Promise<any> {
	const res = await fetch(`${backendUrl()}/api/admin/users/${id}`, {
		method: 'PUT',
		headers: { 'Content-Type': 'application/json', ...authHeaders(email) },
		body: JSON.stringify(body)
	});
	if (!res.ok) throw new Error(`Backend error: ${res.status}`);
	return res.json();
}

export async function deleteUser(email: string, id: number): Promise<void> {
	const res = await fetch(`${backendUrl()}/api/admin/users/${id}`, {
		method: 'DELETE',
		headers: authHeaders(email)
	});
	if (!res.ok) throw new Error(`Backend error: ${res.status}`);
}

export async function addUserEmail(email: string, userId: number, newEmail: string): Promise<any> {
	const res = await fetch(`${backendUrl()}/api/admin/users/${userId}/emails`, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json', ...authHeaders(email) },
		body: JSON.stringify({ email: newEmail })
	});
	if (!res.ok) throw new Error(`Backend error: ${res.status}`);
	return res.json();
}

export async function removeUserEmail(
	email: string,
	userId: number,
	emailId: number
): Promise<void> {
	const res = await fetch(`${backendUrl()}/api/admin/users/${userId}/emails/${emailId}`, {
		method: 'DELETE',
		headers: authHeaders(email)
	});
	if (!res.ok) throw new Error(`Backend error: ${res.status}`);
}
