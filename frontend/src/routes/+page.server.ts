import { semanticSearch, fetchRecordPersonMatches } from '$lib/server/api';
import type { RecordPersonMatch } from '$lib/server/api';
import { env } from '$env/dynamic/private';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ url, locals }) => {
	const q = url.searchParams.get('q') ?? '';
	const page = Number(url.searchParams.get('page') ?? '0');

	if (!q.trim()) {
		return { q: '', results: null, answer: null, personRefs: [] as { name: string; personId: number }[], page: 0, hasMore: false };
	}

	const limit = 10;
	const offset = page * limit;

	// Semantic search
	const searchResult = await semanticSearch(locals.userEmail, q, limit + offset + 1);
	const allResults = searchResult.results || [];
	const results = allResults.slice(offset, offset + limit);
	const hasMore = allResults.length > offset + limit;

	// Build sources for display
	const sources = results.map((c) => ({
		recordId: c.recordId,
		pageId: c.pageId,
		pageSeq: c.pageSeq,
		title: c.recordTitleEn || c.recordTitle || 'Untitled',
		referenceCode: c.referenceCode || '',
		descriptionEn: c.descriptionEn || '',
		score: c.score,
		snippet: c.content.substring(0, 300)
	}));

	// Answer synthesis over the retrieved passages (first page only).
	//
	// Mistral, like every other model call in this system. This used to go to Anthropic with a
	// dated model snapshot; when claude-sonnet-4-20250514 was retired the request began failing
	// with not_found_error, and because the caller only acted on a 200 the answer box simply
	// stopped appearing with nothing logged.
	let answer: string | null = null;
	if (page === 0 && results.length > 0) {
		const mistralKey = env.MISTRAL_API_KEY;
		if (mistralKey) {
			try {
				const context = results
					.slice(0, 10)
					.map((c, i) => {
						const title = c.recordTitleEn || c.recordTitle || 'Untitled';
						const ref = c.referenceCode || '';
						return `[${i + 1}] Record "${title}" (${ref}, record #${c.recordId}${c.pageId ? `, page ${c.pageId}` : ''}):\n${c.content}`;
					})
					.join('\n\n---\n\n');

				const res = await fetch(
					`${env.MISTRAL_BASE_URL || 'https://api.mistral.ai'}/v1/chat/completions`,
					{
						method: 'POST',
						headers: {
							'Content-Type': 'application/json',
							Authorization: `Bearer ${mistralKey}`
						},
						body: JSON.stringify({
							model: env.SEARCH_ANSWER_MODEL || 'mistral-medium-latest',
							max_tokens: 1024,
							temperature: 0.1,
							messages: [
								{
									role: 'user',
									content: `You are a research assistant helping with historical archive documents. Answer the user's question based ONLY on the provided document excerpts. Be concise (2-4 sentences). Cite record numbers using the format #NNNN (e.g. #3360). If the documents don't contain relevant information, say so briefly.

Document excerpts:
${context}

Question: ${q}`
								}
							]
						})
					}
				);

				if (res.ok) {
					const data = await res.json();
					answer = data.choices?.[0]?.message?.content?.trim() || null;
				} else {
					// Never swallow this: a silent non-200 is exactly how the previous provider's
					// retirement went unnoticed for three months.
					console.error('Search answer synthesis failed:', res.status, await res.text());
				}
			} catch (e) {
				console.error('Search answer synthesis failed:', e);
			}
		}
	}

	// Person match fetching — deferred so it doesn't block page render.
	// Record #links work immediately; person name links appear when this resolves.
	const uniqueRecordIds = [...new Set(results.map((c) => c.recordId))];
	const personRefsPromise = (answer && uniqueRecordIds.length > 0)
		? Promise.all(uniqueRecordIds.map((rid) => fetchRecordPersonMatches(locals.userEmail, rid)))
			.then((matchArrays) => {
				const seen = new Set<number>();
				const refs: { name: string; personId: number }[] = [];
				for (const matches of matchArrays) {
					for (const m of matches) {
						if (!seen.has(m.personId) && answer!.includes(m.personName)) {
							seen.add(m.personId);
							refs.push({ name: m.personName, personId: m.personId });
						}
					}
				}
				refs.sort((a, b) => b.name.length - a.name.length);
				return refs;
			})
			.catch(() => [] as { name: string; personId: number }[])
		: Promise.resolve([] as { name: string; personId: number }[]);

	return {
		q,
		results: sources,
		answer,
		personRefs: personRefsPromise,
		page: page,
		hasMore
	};
};
