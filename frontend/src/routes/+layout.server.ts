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
	//
	// Only do this when the backend gave us a definitive answer. The backend
	// always answers a genuine "not allowlisted" with a 200 and
	// authenticated: false (see AuthController#me) -- it never needs a 5xx
	// or a thrown error to say that. So if fetchCurrentUser reports
	// backendUnavailable, `authenticated: false` here means "couldn't ask",
	// not "no". Redirecting to /no-access in that case would tell every
	// legitimate signed-in user they've been de-authorized during, say, a
	// backend container restart mid-deploy -- which is false and needlessly
	// alarming. Let the page render (or fail with its own honest error)
	// instead.
	if (
		locals.userEmail &&
		user &&
		!user.authenticated &&
		!user.backendUnavailable &&
		url.pathname !== '/no-access'
	) {
		throw redirect(307, '/no-access');
	}

	// If user is logged in and has a lang preference, use it
	const language = (user as any)?.lang ?? locals.language ?? 'en';

	return { user, language };
}
