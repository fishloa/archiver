import { fetchCurrentUser } from '$lib/server/api';
import { redirect } from '@sveltejs/kit';

export async function load({ locals, url }: { locals: App.Locals; url: URL }) {
	let user = null;
	if (locals.userEmail) {
		user = await fetchCurrentUser(locals.userEmail);
	}

	// A user who authenticated with Google/Apple but isn't on the allowlist has a
	// valid oauth2-proxy session yet no app account. Send them to /no-access instead
	// of /signin, which would just bounce them straight back (infinite redirect).
	if (locals.userEmail && user && !user.authenticated && url.pathname !== '/no-access') {
		throw redirect(307, '/no-access');
	}

	// If user is logged in and has a lang preference, use it
	const language = (user as any)?.lang ?? locals.language ?? 'en';

	return { user, language };
}
