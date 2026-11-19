import {
	fetchRecord,
	fetchRecordPages,
	fetchRecordTimeline,
	fetchRecordPeople,
	fetchHints,
	resetRecordPipeline,
	fetchTranslationStatus,
	upgradeTranslation,
} from '$lib/server/api';
import type { Hint } from '$lib/server/api';
import { hintActions } from '$lib/server/link-actions';
import { error, fail } from '@sveltejs/kit';
import type { PageServerLoad, Actions } from './$types';

export const load: PageServerLoad = async ({ params, locals, parent }) => {
	const id = Number(params.id);
	if (isNaN(id)) error(400, 'Invalid record ID');

	try {
		const [record, pages, timeline] = await Promise.all([
			fetchRecord(locals.userEmail, id),
			fetchRecordPages(locals.userEmail, id),
			fetchRecordTimeline(locals.userEmail, id),
		]);

		const people = await fetchRecordPeople(locals.userEmail, id);

		let hints: Hint[] = [];
		const { user } = await parent();
		if (locals.userEmail && user?.role === 'admin') {
			hints = await fetchHints(locals.userEmail, { recordId: id }).catch(() => []);
		}

		const translationStatus = await fetchTranslationStatus(locals.userEmail, id);

		return { record, pages, timeline, people, hints, translationStatus };
	} catch (e) {
		if (e && typeof e === 'object' && 'status' in e) throw e;
		error(404, 'Record not found');
	}
};

export const actions: Actions = {
	...hintActions,
	resetOcr: async ({ params, locals }) => {
		const id = Number(params.id);
		if (!locals.userEmail) return fail(401, { error: 'Not authenticated' });
		try {
			const res = await resetRecordPipeline(locals.userEmail, [id], 'ocr_pending');
			return { success: true, results: res.results };
		} catch (e) {
			return fail(500, { error: String(e) });
		}
	},
	resetTranslate: async ({ params, locals }) => {
		const id = Number(params.id);
		if (!locals.userEmail) return fail(401, { error: 'Not authenticated' });
		try {
			const res = await resetRecordPipeline(locals.userEmail, [id], 'translating');
			return { success: true, results: res.results };
		} catch (e) {
			return fail(500, { error: String(e) });
		}
	},
	upgradeTranslation: async ({ params, locals }) => {
		const id = Number(params.id);
		if (!locals.userEmail) return fail(401, { error: 'Not authenticated' });
		try {
			const res = await upgradeTranslation(locals.userEmail, id);
			return { upgraded: true, ...res };
		} catch (e) {
			return fail(500, { error: String(e) });
		}
	},
	resetEmbed: async ({ params, locals }) => {
		const id = Number(params.id);
		if (!locals.userEmail) return fail(401, { error: 'Not authenticated' });
		try {
			const res = await resetRecordPipeline(locals.userEmail, [id], 'embedding');
			return { success: true, results: res.results };
		} catch (e) {
			return fail(500, { error: String(e) });
		}
	},
};
