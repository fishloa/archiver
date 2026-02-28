import { redirect } from '@sveltejs/kit';
import { fetchCurrentUser } from '$lib/server/api';
import type { LayoutServerLoad } from './$types';

export const load: LayoutServerLoad = async ({ locals }) => {
	if (!locals.userEmail) {
		redirect(307, '/');
	}

	const user = await fetchCurrentUser(locals.userEmail);
	if (!user.authenticated || user.role !== 'admin') {
		redirect(307, '/');
	}
};
