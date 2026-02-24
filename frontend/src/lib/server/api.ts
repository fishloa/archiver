import { env } from '$env/dynamic/private';
import type { RecordResponse, PageResponse, SpringPage } from '$lib/types';

function backendUrl(): string {
	const url = env.BACKEND_URL;
	if (!url) throw new Error('BACKEND_URL env var is not set');
	return url;
}

export async function fetchRecords(
	page: number,
	size: number,
	sortBy: string,
	sortDir: string
): Promise<SpringPage<RecordResponse>> {
	const params = new URLSearchParams({
		page: String(page),
		size: String(size),
		sortBy,
		sortDir
	});
	const res = await fetch(`${backendUrl()}/api/records?${params}`);
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

export async function searchPages(q: string, page: number = 0, size: number = 20): Promise<SearchResponse> {
	const params = new URLSearchParams({ q, page: String(page), size: String(size) });
	const res = await fetch(`${backendUrl()}/api/search?${params}`);
	if (!res.ok) throw new Error(`Backend error: ${res.status}`);
	return res.json();
}
