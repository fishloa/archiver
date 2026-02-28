import { fetchAdminStats, runAudit } from '$lib/server/api';
import type { PageServerLoad, Actions } from './$types';

export const load: PageServerLoad = async () => {
	const stats = await fetchAdminStats();
	return { stats };
};

export const actions: Actions = {
	audit: async ({ locals }) => {
		const result = await runAudit(locals.userEmail);
		return { fixed: result.fixed };
	}
};
