import {
	createAiImplementation,
	deleteAiImplementation,
	fetchAiImplementations,
	setAiOrder,
	updateAiImplementation
} from '$lib/server/api';
import { fail } from '@sveltejs/kit';
import type { Actions, PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ locals }) => {
	return { implementations: await fetchAiImplementations(locals.userEmail) };
};

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
		await updateAiImplementation(locals.userEmail, id, {
			baseUrl: String(form.get('baseUrl') ?? ''),
			endpointPath: String(form.get('endpointPath') ?? ''),
			credentialEnv: String(form.get('credentialEnv') ?? ''),
			maxBatchSize: Number(form.get('maxBatchSize') ?? 1)
		});
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
				maxBatchSize: Number(form.get('maxBatchSize') ?? 1)
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
