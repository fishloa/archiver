import type { PageServerLoad } from './$types';
import { searchFamilyTree, relatePerson, fetchFamilyPerson } from '$lib/server/api';

export const load: PageServerLoad = async ({ url }) => {
	const q = url.searchParams.get('q') || '';
	const personId = url.searchParams.get('personId');

	let results: any[] = [];
	let relationship: any = null;
	let person: any = null;

	if (q) {
		results = await searchFamilyTree(q, 10);
	}

	if (personId) {
		const pid = parseInt(personId);
		[relationship, person] = await Promise.all([relatePerson(pid), fetchFamilyPerson(pid)]);
	}

	return { q, results, relationship, person, personId: personId ? parseInt(personId) : null };
};
