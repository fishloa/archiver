import type { PageServerLoad } from './$types';
import { redirect } from '@sveltejs/kit';

export const load: PageServerLoad = async ({ locals, parent }) => {
	if (!locals.userEmail) throw redirect(307, '/signin');

	// Reuse the user already fetched by +layout.server.ts instead of calling
	// fetchCurrentUser again for the same request.
	const { user } = await parent();
	if (user?.authenticated) throw redirect(307, '/');

	return { signedInAs: user?.signedInAs ?? locals.userEmail };
};
