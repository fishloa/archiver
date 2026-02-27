import { fetchCurrentUser } from '$lib/server/api';

export async function load({ locals }: { locals: App.Locals }) {
	let user = null;
	if (locals.userEmail) {
		user = await fetchCurrentUser(locals.userEmail);
	}

	// If user is logged in and has a lang preference, use it
	const language = (user as any)?.lang ?? locals.language ?? 'en';

	return { user, language };
}
