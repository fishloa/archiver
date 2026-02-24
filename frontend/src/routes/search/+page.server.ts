import { searchPages } from '$lib/server/api';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ url }) => {
	const q = url.searchParams.get('q') ?? '';
	const page = Number(url.searchParams.get('page') ?? '0');

	if (!q.trim()) {
		return { q, results: [], total: 0, page: 0, size: 20 };
	}

	const data = await searchPages(q, page, 20);
	return { q, ...data };
};
