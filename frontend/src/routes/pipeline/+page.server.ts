import { fetchPipelineStats, fetchSourceStatus } from '$lib/server/api';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ depends }) => {
	depends('app:pipeline');
	const [stats, sources] = await Promise.all([fetchPipelineStats(), fetchSourceStatus()]);
	return { stats, sources };
};
