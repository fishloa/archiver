/**
 * Asking the backend for a PDF, waiting for it, and fetching it.
 *
 * Every PDF is built on request and takes the same path, whatever its size: POST to ask, poll the
 * export until it is ready, then navigate to its file. This holds the parts of that which are plain
 * logic, so they can be tested without a browser.
 */

export type ExportState = 'queued' | 'building' | 'ready' | 'failed' | 'expired';

export interface ExportView {
	id: string;
	state: ExportState;
	pageCount: number;
	variant: string;
	bytes: number | null;
	expiresAt: string | null;
	error: string | null;
}

export interface Described {
	/** Nothing more will happen to this export: stop polling. */
	terminal: boolean;
	/** It finished and the file can be fetched. */
	ok: boolean;
	/** What to tell the person when it did not. Empty otherwise. */
	message: string;
}

export function createUrl(recordId: number): string {
	return `/api/records/${recordId}/pdf-exports`;
}

export function statusUrl(id: string): string {
	return `/api/pdf-exports/${encodeURIComponent(id)}`;
}

export function fileUrl(id: string): string {
	return `${statusUrl(id)}/file`;
}

/** The request body: the whole record unless pages are given. */
export function exportRequest(variant: string, pages: string): { variant: string; pages?: string } {
	const trimmed = pages.trim();
	return trimmed ? { variant, pages: trimmed } : { variant };
}

/** Milliseconds to wait before asking again: brisk at first, then easing off to five seconds. */
export function nextPollDelay(attempt: number): number {
	if (attempt < 5) return 2000;
	return Math.min(5000, 2000 + (attempt - 4) * 500);
}

export function describeState(state: ExportState, error: string | null): Described {
	switch (state) {
		case 'ready':
			return { terminal: true, ok: true, message: '' };
		case 'failed':
			return { terminal: true, ok: false, message: error ?? 'The PDF could not be built' };
		case 'expired':
			return { terminal: true, ok: false, message: 'This PDF has expired; request it again' };
		default:
			return { terminal: false, ok: false, message: '' };
	}
}

/** The backend's error message if the body carries one, otherwise the status. */
export function errorFrom(status: number, body: string): string {
	try {
		const parsed = JSON.parse(body);
		if (parsed && typeof parsed.error === 'string') return parsed.error;
	} catch {
		// not JSON: fall through to the status
	}
	return `Request failed (${status})`;
}

/** How long a person is asked to wait for one PDF before being told to come back to it. */
export const MAX_WAIT_MS = 30 * 60 * 1000;

/** Whether a wait that began at `startedMs` has gone on too long at `nowMs`. */
export function timedOut(startedMs: number, nowMs: number): boolean {
	return nowMs - startedMs > MAX_WAIT_MS;
}
