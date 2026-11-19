import { fail } from '@sveltejs/kit';
import type { ActionFailure, RequestEvent } from '@sveltejs/kit';
import { confirmHint, fetchCurrentUser, genealogyWrite, runHints } from '$lib/server/api';
import type { GenealogyWriteResult } from '$lib/server/api';

type Guarded = { email: string; form: FormData };
type Failed = ActionFailure<{ error: string }>;
type Outcome = Failed | { ok: true; pagesQueued?: number };

async function admin(event: RequestEvent): Promise<Guarded | Failed> {
	const email = event.locals.userEmail;
	if (!email) return fail(401, { error: 'Not authenticated' });
	const user = await fetchCurrentUser(email).catch(() => null);
	if (user?.role !== 'admin') return fail(403, { error: 'administrators only' });
	return { email, form: await event.request.formData() };
}

function isGuarded(g: Guarded | Failed): g is Guarded {
	return 'email' in g;
}

function text(form: FormData, key: string): string {
	return String(form.get(key) ?? '').trim();
}

function number(form: FormData, key: string): number | null {
	const v = text(form, key);
	if (v === '') return null;
	const n = Number(v);
	return Number.isInteger(n) ? n : NaN;
}

function done(r: GenealogyWriteResult, extra: { pagesQueued?: number } = {}): Outcome {
	if (!r.ok) return fail(r.status, { error: String(r.data.error ?? `Backend error: ${r.status}`) });
	return { ok: true as const, ...extra };
}

async function guarded(
	event: RequestEvent,
	run: (g: Guarded) => Promise<Outcome>
) {
	const g = await admin(event);
	return isGuarded(g) ? run(g) : g;
}

export const hintActions = {
	runHints: (event: RequestEvent) =>
		guarded(event, async ({ email, form }) => {
			const pageId = number(form, 'pageId');
			const recordId = number(form, 'recordId');
			if (Number.isNaN(pageId) || Number.isNaN(recordId) || (pageId == null) === (recordId == null)) {
				return fail(400, { error: 'Give a page or a record' });
			}
			const r = await runHints(email, pageId != null ? { pageId } : { recordId: recordId! });
			return done(r, { pagesQueued: Number(r.data.pages ?? 0) });
		}),
	confirmHint: (event: RequestEvent) =>
		guarded(event, async ({ email, form }) => {
			const id = number(form, 'hintId');
			if (id == null || Number.isNaN(id)) return fail(400, { error: 'Choose a suggestion' });
			return done(await confirmHint(email, id, { role: text(form, 'role') || undefined }));
		})
};

export const linkActions = {
	linkMedia: (event: RequestEvent) =>
		guarded(event, async ({ email, form }) => {
			const pageId = number(form, 'pageId');
			const personId = number(form, 'personId');
			if (pageId == null || Number.isNaN(pageId)) return fail(400, { error: 'No page' });
			if (personId == null || Number.isNaN(personId)) return fail(400, { error: 'Choose a person' });
			const body: Record<string, unknown> = {
				pageId,
				personId,
				isPortrait: form.get('isPortrait') === 'on',
				reason: text(form, 'reason')
			};
			const caption = text(form, 'caption');
			if (caption) body.caption = caption;
			for (const key of ['cropLeft', 'cropTop', 'cropWidth', 'cropHeight']) {
				const v = number(form, key);
				if (v != null && !Number.isNaN(v)) body[key] = v;
			}
			return done(await genealogyWrite(email, 'POST', 'media', body));
		}),
	linkPerson: (event: RequestEvent) =>
		guarded(event, async ({ email, form }) => {
			const pageId = number(form, 'pageId');
			const recordId = number(form, 'recordId');
			const personId = number(form, 'personId');
			if (recordId == null || Number.isNaN(recordId)) return fail(400, { error: 'No record' });
			if (personId == null || Number.isNaN(personId)) return fail(400, { error: 'Choose a person' });
			const body: Record<string, unknown> = {
				personId,
				recordId,
				role: text(form, 'role') || 'mentioned',
				reason: text(form, 'reason')
			};
			if (pageId != null && !Number.isNaN(pageId)) body.pageId = pageId;
			const note = text(form, 'note');
			if (note) body.note = note;
			return done(await genealogyWrite(email, 'POST', 'record-links', body));
		})
};
