import type { PageServerLoad } from './$types';
import type { RequestEvent } from '@sveltejs/kit';
import { searchFamilyTree, relatePerson, fetchFamilyPerson, updateProfile } from '$lib/server/api';

export const load: PageServerLoad = async ({ url, parent }) => {
	const q = url.searchParams.get('q') || '';
	const personId = url.searchParams.get('personId');

	const { user } = await parent();
	const refId = (user as any)?.familyTreePersonId ?? undefined;

	let results: any[] = [];
	let relationship: any = null;
	let person: any = null;

	if (q) {
		results = await searchFamilyTree(q, 10);
	}

	if (personId) {
		const pid = parseInt(personId);
		[relationship, person] = await Promise.all([relatePerson(pid, refId), fetchFamilyPerson(pid)]);
	}

	return { q, results, relationship, person, personId: personId ? parseInt(personId) : null };
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
};
