import { fetchRecord, fetchRecordPages, fetchPageText } from '$lib/server/api';
import { error } from '@sveltejs/kit';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ params }) => {
	const recordId = Number(params.id);
	const seq = Number(params.seq);
	if (isNaN(recordId) || isNaN(seq)) error(400, 'Invalid parameters');

	try {
		const [record, pages] = await Promise.all([fetchRecord(recordId), fetchRecordPages(recordId)]);
		const page = pages.find((p) => p.seq === seq);
		if (!page) error(404, 'Page not found');

		const pageIndex = pages.indexOf(page);
		const prev = pageIndex > 0 ? pages[pageIndex - 1] : null;
		const next = pageIndex < pages.length - 1 ? pages[pageIndex + 1] : null;

		// Fetch OCR text (non-blocking — don't fail if no text yet)
		let pageText = { pageId: page.id, text: '', confidence: 0, engine: '' };
		try {
			pageText = await fetchPageText(page.id);
		} catch {
			// OCR text not available yet
		}

		return { record, page, prev, next, totalPages: pages.length, pageText };
	} catch (e) {
		if (e && typeof e === 'object' && 'status' in e) throw e;
		error(404, 'Record not found');
	}
};
