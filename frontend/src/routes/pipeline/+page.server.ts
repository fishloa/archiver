import { fetchPipelineStats, fetchSourceStatus } from '$lib/server/api';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ depends, locals }) => {
	depends('app:pipeline');
	const [stats, sources] = await Promise.all([
		fetchPipelineStats(locals.userEmail),
		fetchSourceStatus(locals.userEmail)
	]);
	return { stats, sources };
};
