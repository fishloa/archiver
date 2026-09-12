import {
	createAiImplementation,
	deleteAiImplementation,
	fetchAiImplementations,
	fetchAiProviders,
	setAiOrder,
	updateAiImplementation
} from '$lib/server/api';
import { fail } from '@sveltejs/kit';
import type { Actions, PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ locals }) => {
	const [implementations, providers] = await Promise.all([
		fetchAiImplementations(locals.userEmail),
		fetchAiProviders(locals.userEmail)
	]);
	return { implementations, providers };
};


/**
 * Collects the provider-declared settings out of the submitted form.
 *
 * The form does not know what any given provider needs; it renders whatever the backend described
 * and posts each one back as `setting.<key>`. This turns those into the JSON the API stores, so a
 * new provider setting needs no change here either.
 */
function settingsFrom(form: FormData): string {
	const settings: Record<string, unknown> = {};
	for (const [key, value] of form.entries()) {
		if (!key.startsWith('setting.')) continue;
		const name = key.slice('setting.'.length);
		const raw = String(value).trim();
		if (raw === '') continue;
		// Integers must not be stored as strings: the backend reads them with asInt.
		settings[name] = /^-?\d+$/.test(raw) ? Number(raw) : raw;
	}
	return JSON.stringify(settings);
}

export const actions: Actions = {
	/** Moves one implementation up or down, sending the whole resulting order. */
	reorder: async ({ request, locals }) => {
		const form = await request.formData();
		const capability = String(form.get('capability'));
		const id = String(form.get('id'));
		const direction = String(form.get('direction'));

		const all = await fetchAiImplementations(locals.userEmail);
		const order = (all[capability] ?? []).map((i) => i.id);
		const at = order.indexOf(id);
		const to = direction === 'up' ? at - 1 : at + 1;
		if (at < 0 || to < 0 || to >= order.length) {
			return fail(400, { message: 'Already at the end of the order' });
		}
		[order[at], order[to]] = [order[to], order[at]];

		await setAiOrder(locals.userEmail, capability, order);
		return { reordered: id };
	},

	toggle: async ({ request, locals }) => {
		const form = await request.formData();
		const id = String(form.get('id'));
		const enabled = form.get('enabled') === 'true';
		await updateAiImplementation(locals.userEmail, id, { enabled });
		return { toggled: id };
	},

	update: async ({ request, locals }) => {
		const form = await request.formData();
		const id = String(form.get('id'));
		try {
			await updateAiImplementation(locals.userEmail, id, {
				baseUrl: String(form.get('baseUrl') ?? ''),
				endpointPath: String(form.get('endpointPath') ?? ''),
				credentialEnv: String(form.get('credentialEnv') ?? ''),
				maxBatchSize: Number(form.get('maxBatchSize') ?? 1),
				settings: settingsFrom(form)
			});
		} catch (e) {
			return fail(400, { message: e instanceof Error ? e.message : 'Could not save it' });
		}
		return { updated: id };
	},

	create: async ({ request, locals }) => {
		const form = await request.formData();
		const provider = String(form.get('provider') ?? '').trim();
		const model = String(form.get('model') ?? '').trim();
		if (!provider || !model) {
			return fail(400, { message: 'Provider and model are required' });
		}
		try {
			await createAiImplementation(locals.userEmail, {
				id: `${provider}:${model}`,
				capability: String(form.get('capability')),
				provider,
				model,
				baseUrl: String(form.get('baseUrl') ?? ''),
				endpointPath: String(form.get('endpointPath') ?? '') || null,
				credentialEnv: String(form.get('credentialEnv') ?? '') || null,
				maxBatchSize: Number(form.get('maxBatchSize') ?? 1),
				settings: settingsFrom(form)
			});
		} catch (e) {
			return fail(400, { message: e instanceof Error ? e.message : 'Could not register it' });
		}
		return { created: `${provider}:${model}` };
	},

	remove: async ({ request, locals }) => {
		const form = await request.formData();
		const id = String(form.get('id'));
		const result = await deleteAiImplementation(locals.userEmail, id);
		// Refused while stored output still refers to the model — the message says why.
		return result.ok ? { removed: id } : fail(409, { message: result.message });
	}
};
