import { json } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { env } from '$env/dynamic/private';

function backendUrl(): string {
	const url = env.BACKEND_URL;
	if (!url) throw new Error('BACKEND_URL env var is not set');
	return url;
}

export const POST: RequestHandler = async ({ request, locals }) => {
	if (!locals.userEmail) {
		return json({ error: 'Authentication required' }, { status: 401 });
	}

	const { text, sourceLang, targetLang } = await request.json();
	if (!text?.trim()) {
		return json({ error: 'No text provided' }, { status: 400 });
	}

	const res = await fetch(`${backendUrl()}/api/translate/claude`, {
		method: 'POST',
		headers: {
			'Content-Type': 'application/json',
			'X-Auth-Email': locals.userEmail
		},
		body: JSON.stringify({ text, sourceLang, targetLang: targetLang || 'en' })
	});

	if (!res.ok) {
		const body = await res.json().catch(() => ({ error: 'Translation failed' }));
		return json(body, { status: res.status });
	}

	return json(await res.json());
};
