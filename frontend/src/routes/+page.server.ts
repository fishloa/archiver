import { semanticSearch, fetchRecordPersonMatches } from '$lib/server/api';
import type { RecordPersonMatch } from '$lib/server/api';
import { env } from '$env/dynamic/private';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ url }) => {
	const q = url.searchParams.get('q') ?? '';
	const page = Number(url.searchParams.get('page') ?? '0');

	if (!q.trim()) {
		return { q: '', results: null, answer: null, personRefs: [] as { name: string; personId: number }[], page: 0, hasMore: false };
	}

	const limit = 10;
	const offset = page * limit;

	// Semantic search
	const searchResult = await semanticSearch(q, limit + offset + 1);
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

	// Claude synthesis (only on first page)
	let answer: string | null = null;
	if (page === 0 && results.length > 0) {
		const anthropicKey = env.ANTHROPIC_API_KEY;
		if (anthropicKey) {
			try {
				const context = results
					.slice(0, 10)
					.map((c, i) => {
						const title = c.recordTitleEn || c.recordTitle || 'Untitled';
						const ref = c.referenceCode || '';
						return `[${i + 1}] Record "${title}" (${ref}, record #${c.recordId}${c.pageId ? `, page ${c.pageId}` : ''}):\n${c.content}`;
					})
					.join('\n\n---\n\n');

				const claudeResponse = await fetch('https://api.anthropic.com/v1/messages', {
					method: 'POST',
					headers: {
						'Content-Type': 'application/json',
						'x-api-key': anthropicKey,
						'anthropic-version': '2023-06-01'
					},
					body: JSON.stringify({
						model: 'claude-sonnet-4-20250514',
						max_tokens: 1024,
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
				});

				if (claudeResponse.ok) {
					const data = await claudeResponse.json();
					answer = data.content?.[0]?.text || null;
				}
			} catch (e) {
				console.error('Claude synthesis failed:', e);
			}
		}
	}

	// Person match fetching — deferred so it doesn't block page render.
	// Record #links work immediately; person name links appear when this resolves.
	const uniqueRecordIds = [...new Set(results.map((c) => c.recordId))];
	const personRefsPromise = (answer && uniqueRecordIds.length > 0)
		? Promise.all(uniqueRecordIds.map((rid) => fetchRecordPersonMatches(rid)))
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
