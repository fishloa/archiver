import type { PageServerLoad } from './$types';
import type { RequestEvent } from '@sveltejs/kit';
import { fail } from '@sveltejs/kit';
import {
	searchFamilyTree,
	relatePerson,
	fetchFamilyPerson,
	updateProfile,
	genealogyWrite,
	fetchGenealogyAdmin
} from '$lib/server/api';
import { formToBody, type Change, type Source } from '$lib/genealogy';

export const load: PageServerLoad = async ({ url, parent, locals }) => {
	const q = url.searchParams.get('q') || '';
	const personId = url.searchParams.get('personId');

	const { user } = await parent();
	const refId = (user as any)?.familyTreePersonId ?? undefined;

	let results: any[] = [];
	let relationship: any = null;
	let person: any = null;

	if (q) {
		results = await searchFamilyTree(locals.userEmail, q, 10);
	}

	if (personId) {
		const pid = parseInt(personId);
		[relationship, person] = await Promise.all([
			relatePerson(locals.userEmail, pid, refId),
			fetchFamilyPerson(locals.userEmail, pid)
		]);
	}

	const editing =
		(user as any)?.role === 'admin' && url.searchParams.get('edit') === '1' && !!person;
	let history: Change[] = [];
	let places: { id: number; name: string }[] = [];
	let sources: Source[] = [];
	if (editing && locals.userEmail) {
		[history, places, sources] = await Promise.all([
			fetchGenealogyAdmin<Change[]>(locals.userEmail, `changes?personId=${person.id}&limit=50`),
			fetchGenealogyAdmin<{ id: number; name: string }[]>(locals.userEmail, 'places?limit=1000'),
			fetchGenealogyAdmin<Source[]>(locals.userEmail, 'sources?limit=1000')
		]);
	}

	return {
		q,
		results,
		relationship,
		person,
		personId: personId ? parseInt(personId) : null,
		editing,
		history,
		places,
		sources
	};
};

export const actions = {
	setFamilyTreePerson: async ({ request, locals }: RequestEvent) => {
		if (!locals.userEmail) return { error: 'Not authenticated' };
		const form = await request.formData();
		const personId = form.get('personId');
		await updateProfile(locals.userEmail, {
			familyTreePersonId: personId ? Number(personId) : null,
		} as any);
		return { success: true };
	},
	edit: async ({ request, locals }: RequestEvent) => {
		if (!locals.userEmail) return fail(401, { error: 'Not authenticated' });
		let write;
		try {
			write = formToBody(await request.formData());
		} catch (e) {
			return fail(400, { error: (e as Error).message });
		}
		const r = await genealogyWrite(locals.userEmail, write.method, write.path, write.body);
		if (!r.ok) return fail(r.status, { error: String(r.data.error ?? `Backend error: ${r.status}`) });
		return { success: true };
	},
	merge: async ({ request, locals }: RequestEvent) => {
		if (!locals.userEmail) return fail(401, { error: 'Not authenticated' });
		const form = await request.formData();
		const keep = Number(form.get('keep'));
		const gone = Number(form.get('gone'));
		if (!Number.isInteger(keep) || !Number.isInteger(gone) || gone <= 0) {
			return fail(400, { error: 'Choose the duplicate' });
		}
		const r = await genealogyWrite(locals.userEmail, 'POST', `persons/${keep}/merge/${gone}`, {
			reason: String(form.get('reason') ?? '')
		});
		if (!r.ok) return fail(r.status, { error: String(r.data.error ?? `Backend error: ${r.status}`) });
		return { success: true };
	}
};
