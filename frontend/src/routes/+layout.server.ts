import { fetchCurrentUser } from '$lib/server/api';

export async function load({ locals }: { locals: App.Locals }) {
	if (locals.userEmail) {
		const user = await fetchCurrentUser(locals.userEmail);
		return { user };
	}
	return { user: null };
}
