import { fetchPipelineStats } from '$lib/server/api';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ depends }) => {
	depends('app:pipeline');
	const stats = await fetchPipelineStats();
	return { stats };
};
