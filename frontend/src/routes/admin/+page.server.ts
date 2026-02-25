import { fetchAdminStats, runAudit } from '$lib/server/api';
import type { PageServerLoad, Actions } from './$types';

export const load: PageServerLoad = async () => {
	const stats = await fetchAdminStats();
	return { stats };
};

export const actions: Actions = {
	audit: async () => {
		const result = await runAudit();
		return { fixed: result.fixed };
	}
};
