import {
	fetchRecord,
	fetchRecordPages,
	fetchPageText,
	fetchPagePeople,
	fetchPageFigures,
	fetchHints
} from '$lib/server/api';
import type { Figure, Hint } from '$lib/server/api';
import { hintActions, linkActions } from '$lib/server/link-actions';
import { error } from '@sveltejs/kit';
import type { PageServerLoad, Actions } from './$types';

export const load: PageServerLoad = async ({ params, locals, parent }) => {
	const recordId = Number(params.id);
	const seq = Number(params.seq);
	if (isNaN(recordId) || isNaN(seq)) error(400, 'Invalid parameters');

	try {
		const [record, pages] = await Promise.all([
			fetchRecord(locals.userEmail, recordId),
			fetchRecordPages(locals.userEmail, recordId)
		]);
		const page = pages.find((p) => p.seq === seq);
		if (!page) error(404, 'Page not found');

		const pageIndex = pages.indexOf(page);
		const prev = pageIndex > 0 ? pages[pageIndex - 1] : null;
		const next = pageIndex < pages.length - 1 ? pages[pageIndex + 1] : null;

		// Fetch OCR text (non-blocking — don't fail if no text yet)
		let pageText = {
			pageId: page.id,
			text: '',
			confidence: 0,
			engine: '',
			textEn: '',
			contentType: 'text/plain'
		};
		try {
			pageText = await fetchPageText(locals.userEmail, page.id);
		} catch {
			// OCR text not available yet
		}

		const people = await fetchPagePeople(locals.userEmail, page.id);

		let figures: Figure[] = [];
		let hints: Hint[] = [];
		const { user } = await parent();
		if (locals.userEmail && user?.role === 'admin') {
			figures = await fetchPageFigures(locals.userEmail, page.id);
			hints = await fetchHints(locals.userEmail, { pageId: page.id }).catch(() => []);
		}

		return { record, page, prev, next, totalPages: pages.length, pageText, people, figures, hints };
	} catch (e) {
		if (e && typeof e === 'object' && 'status' in e) throw e;
		error(404, 'Record not found');
	}
};

export const actions: Actions = { ...hintActions, ...linkActions };
