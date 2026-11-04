import { fetchActivity, fetchUsers } from '$lib/server/api';
import type { ActivityEvent, ActivitySession } from '$lib/server/api';
import type { PageServerLoad } from './$types';

const PAD_MS = 1000;

export const load: PageServerLoad = async ({ locals, url }) => {
	const userId = url.searchParams.get('userId') ?? '';
	const from = url.searchParams.get('from') ?? '';
	const to = url.searchParams.get('to') ?? '';
	const selUser = url.searchParams.get('sUser') ?? '';
	const selFrom = url.searchParams.get('sFrom') ?? '';
	const selTo = url.searchParams.get('sTo') ?? '';

	const [users, sessions] = await Promise.all([
		fetchUsers(locals.userEmail!).catch(() => []),
		fetchActivity<ActivitySession>(locals.userEmail!, 'sessions', {
			userId,
			from,
			to,
			limit: 200
		}).catch(() => [] as ActivitySession[])
	]);

	let events: ActivityEvent[] = [];
	if (selFrom && selTo) {
		const lo = new Date(new Date(selFrom).getTime() - PAD_MS).toISOString();
		const hi = new Date(new Date(selTo).getTime() + PAD_MS).toISOString();
		events = (
			await fetchActivity<ActivityEvent>(locals.userEmail!, 'events', {
				userId: selUser,
				from: lo,
				to: hi,
				limit: 1000
			}).catch(() => [] as ActivityEvent[])
		).reverse();
	}

	return {
		users,
		sessions,
		events,
		filter: { userId, from, to },
		selected: { user: selUser, from: selFrom, to: selTo }
	};
};
