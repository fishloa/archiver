import { fetchRecords, fetchArchives } from '$lib/server/api';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ url }) => {
	const page = Number(url.searchParams.get('page') ?? '0');
	const size = Number(url.searchParams.get('size') ?? '20');
	const sortBy = url.searchParams.get('sortBy') ?? 'createdAt';
	const sortDir = url.searchParams.get('sortDir') ?? 'desc';
	const status = url.searchParams.get('status') ?? undefined;
	const archiveIdParam = url.searchParams.get('archiveId');
	const archiveId = archiveIdParam ? Number(archiveIdParam) : undefined;

	const [records, archives] = await Promise.all([
		fetchRecords(page, size, sortBy, sortDir, status, archiveId),
		fetchArchives()
	]);
	return { records, archives, sortBy, sortDir, status: status ?? '', archiveId: archiveId ?? 0 };
};
