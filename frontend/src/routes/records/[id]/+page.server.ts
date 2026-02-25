import { fetchRecord, fetchRecordPages, fetchRecordTimeline } from '$lib/server/api';
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
		return { record, pages, timeline };
	} catch {
		error(404, 'Record not found');
	}
};
