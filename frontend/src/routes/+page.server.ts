import { fetchRecords } from '$lib/server/api';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ url }) => {
	const page = Number(url.searchParams.get('page') ?? '0');
	const size = Number(url.searchParams.get('size') ?? '20');
	const sortBy = url.searchParams.get('sortBy') ?? 'createdAt';
	const sortDir = url.searchParams.get('sortDir') ?? 'desc';

	const records = await fetchRecords(page, size, sortBy, sortDir);
	return { records, sortBy, sortDir };
};
