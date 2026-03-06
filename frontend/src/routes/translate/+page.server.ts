import { redirect } from '@sveltejs/kit';
import { fetchTranslateCapabilities } from '$lib/server/api';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ locals, cookies }) => {
	if (!locals.userEmail) {
		redirect(302, '/signin');
	}

	const userLang = cookies.get('archiver_lang') || 'en';
	const capabilities = await fetchTranslateCapabilities();

	return {
		pairs: capabilities.pairs,
		defaultTargetLang: userLang
	};
};
