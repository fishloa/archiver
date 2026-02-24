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
