import type { Actions, PageServerLoad } from './$types';
import { fail, redirect } from '@sveltejs/kit';
import { fetchMyAccessRequest, submitAccessRequest } from '$lib/server/api';

export const load: PageServerLoad = async ({ locals, parent }) => {
	if (!locals.userEmail) throw redirect(307, '/signin');

	// Reuse the user already fetched by +layout.server.ts instead of calling
	// fetchCurrentUser again for the same request.
	const { user } = await parent();
	if (user?.authenticated) throw redirect(307, '/');

	// A direct navigation here while the backend is unavailable would show
	// "you are not on the access list", which we don't actually know to be
	// true -- backendUnavailable means the backend couldn't answer, not
	// that it answered "not allowlisted". Bounce back rather than assert it.
	if (user?.backendUnavailable) throw redirect(307, '/');

	const request = await fetchMyAccessRequest(locals.userEmail).catch(() => null);
	return {
		signedInAs: user?.signedInAs ?? locals.userEmail,
		request
	};
};

export const actions: Actions = {
	request: async ({ request, locals }) => {
		if (!locals.userEmail) throw redirect(307, '/signin');
		const data = await request.formData();
		const name = String(data.get('name') ?? '').trim();
		const message = String(data.get('message') ?? '').trim();
		if (!name) return fail(400, { error: 'Please give your name.', name, message });

		const status = await submitAccessRequest(locals.userEmail, { name, message });
		if (status === 409) {
			return fail(409, { error: 'Your request has already been decided.', name, message });
		}
		if (status >= 400) {
			return fail(status, { error: 'The request could not be sent. Please try again.', name, message });
		}
		return { sent: true };
	}
};
