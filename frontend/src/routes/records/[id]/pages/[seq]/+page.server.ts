import { fetchRecord, fetchRecordPages, fetchPageText, fetchPagePersonMatches } from '$lib/server/api';
import type { PagePersonMatch } from '$lib/server/api';
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
		let pageText = { pageId: page.id, text: '', confidence: 0, engine: '', textEn: '' };
		try {
			pageText = await fetchPageText(page.id);
		} catch {
			// OCR text not available yet
		}

		// Fetch person matches (non-blocking)
		let personMatches: PagePersonMatch[] = [];
		try {
			personMatches = await fetchPagePersonMatches(page.id);
		} catch {
			// Person matching not available
		}

		return { record, page, prev, next, totalPages: pages.length, pageText, personMatches };
	} catch (e) {
		if (e && typeof e === 'object' && 'status' in e) throw e;
		error(404, 'Record not found');
	}
};
