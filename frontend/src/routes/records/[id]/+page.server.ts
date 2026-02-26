import { fetchRecord, fetchRecordPages, fetchRecordTimeline, fetchRecordPersonMatches } from '$lib/server/api';
import type { RecordPersonMatch } from '$lib/server/api';
import { error } from '@sveltejs/kit';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ params }) => {
	const id = Number(params.id);
	if (isNaN(id)) error(400, 'Invalid record ID');

	try {
		const [record, pages, timeline] = await Promise.all([
			fetchRecord(id),
			fetchRecordPages(id),
			fetchRecordTimeline(id)
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
