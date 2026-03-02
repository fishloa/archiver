import {
	fetchRecord,
	fetchRecordPages,
	fetchRecordTimeline,
	fetchRecordPersonMatches,
	resetRecordPipeline,
} from '$lib/server/api';
import type { RecordPersonMatch } from '$lib/server/api';
import { error, fail } from '@sveltejs/kit';
import type { PageServerLoad, Actions } from './$types';

export const load: PageServerLoad = async ({ params }) => {
	const id = Number(params.id);
	if (isNaN(id)) error(400, 'Invalid record ID');

	try {
		const [record, pages, timeline] = await Promise.all([
			fetchRecord(id),
			fetchRecordPages(id),
			fetchRecordTimeline(id),
		]);

		// Fetch person matches (non-blocking)
		let personMatches: RecordPersonMatch[] = [];
		try {
			personMatches = await fetchRecordPersonMatches(id);
		} catch {
			// Person matching not available
		}

		return { record, pages, timeline, personMatches };
	} catch (e) {
		if (e && typeof e === 'object' && 'status' in e) throw e;
		error(404, 'Record not found');
	}
};

export const actions: Actions = {
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
