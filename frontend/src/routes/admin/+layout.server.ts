import { redirect } from '@sveltejs/kit';
import { fetchCurrentUser } from '$lib/server/api';
import type { LayoutServerLoad } from './$types';

export const load: LayoutServerLoad = async ({ locals }) => {
	if (!locals.userEmail) {
		redirect(307, '/');
	}

	const user = await fetchCurrentUser(locals.userEmail);

	// A backend blip (5xx or thrown fetch error) reports authenticated: false
	// even for a genuine admin. Don't bounce them out of /admin on that basis --
	// see routes/+layout.server.ts for the same reasoning.
	if (user.backendUnavailable) {
		return;
	}

	if (!user.authenticated || user.role !== 'admin') {
		redirect(307, '/');
	}
};
