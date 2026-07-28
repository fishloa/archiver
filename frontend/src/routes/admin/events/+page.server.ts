import { fetchAdminStats } from '$lib/server/api';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ locals }) => {
	const stats = await fetchAdminStats(locals.userEmail);
	return { stats };
};
