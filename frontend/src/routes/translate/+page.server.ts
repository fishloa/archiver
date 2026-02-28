import { redirect } from '@sveltejs/kit';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ locals }) => {
	if (!locals.userEmail) {
		redirect(302, '/signin');
	}

	return {
		pairs: [
			{ source: 'de', target: 'en' },
			{ source: 'cs', target: 'en' }
		]
	};
};
