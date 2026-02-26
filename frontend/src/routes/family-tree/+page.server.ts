import type { PageServerLoad } from './$types';
import { searchFamilyTree, relatePerson } from '$lib/server/api';

export const load: PageServerLoad = async ({ url }) => {
	const q = url.searchParams.get('q') || '';
	const personId = url.searchParams.get('personId');

	let results: any[] = [];
	let relationship: any = null;

	if (q) {
		results = await searchFamilyTree(q, 10);
	}

	if (personId) {
		relationship = await relatePerson(parseInt(personId));
	}

	return { q, results, relationship, personId: personId ? parseInt(personId) : null };
};
