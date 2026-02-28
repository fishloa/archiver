import { fetchAdminStats } from '$lib/server/api';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async () => {
	const stats = await fetchAdminStats();
	return { stats };
};
