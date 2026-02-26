import type { Handle } from '@sveltejs/kit';

export const handle: Handle = async ({ event, resolve }) => {
	const email = event.request.headers.get('x-auth-email');
	if (email) {
		event.locals.userEmail = email;
	}
	return resolve(event);
};
