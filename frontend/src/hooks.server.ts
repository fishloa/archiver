import type { Handle } from '@sveltejs/kit';
import type { Lang } from '$lib/i18n';

export const handle: Handle = async ({ event, resolve }) => {
	const email = event.request.headers.get('x-auth-email');
	if (email) {
		event.locals.userEmail = email;
	}

	// Language detection: cookie > Accept-Language > default 'en'
	let lang: Lang = 'en';
	const cookieVal = event.cookies.get('archiver_lang');
	if (cookieVal === 'en' || cookieVal === 'de') {
		lang = cookieVal;
	} else {
		const accept = event.request.headers.get('accept-language') ?? '';
		// Match de, de-DE, de-AT, de-CH etc. — Austria (at) is German-speaking
		if (/\bde\b/i.test(accept)) {
			lang = 'de';
		}
	}
	event.locals.language = lang;

	return resolve(event);
};
