import { fetchAdminStats, fetchGates, setGate } from '$lib/server/api';
import type { PageServerLoad, Actions } from './$types';

export const load: PageServerLoad = async ({ locals }) => {
	// Stats come along so each gate can show how much work is queued behind it — a closed
	// gate with a growing queue is the thing an operator actually needs to see.
	const [gates, stats] = await Promise.all([
		fetchGates(locals.userEmail),
		fetchAdminStats(locals.userEmail)
	]);
	return { gates: gates.gates, pausedKinds: gates.pausedKinds, stats };
};

export const actions: Actions = {
	toggle: async ({ request, locals }) => {
		const form = await request.formData();
		const kind = String(form.get('kind'));
		const paused = form.get('paused') === 'true';
		const reason = String(form.get('reason') ?? '').trim();
		await setGate(locals.userEmail, kind, paused, reason || undefined);
		return { kind, paused };
	}
};
