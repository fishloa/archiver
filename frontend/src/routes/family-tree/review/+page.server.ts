import { error } from '@sveltejs/kit';
import type { PageServerLoad } from './$types';
import { fetchGenealogyAdmin } from '$lib/server/api';

export interface ReviewItem {
	id: number;
	name: string;
	needsReview: string;
	legacyEntry: string | null;
}

export const load: PageServerLoad = async ({ parent, locals }) => {
	const { user } = await parent();
	if ((user as any)?.role !== 'admin' || !locals.userEmail) error(403, 'Administrators only');
	return { items: await fetchGenealogyAdmin<ReviewItem[]>(locals.userEmail, 'review') };
};
