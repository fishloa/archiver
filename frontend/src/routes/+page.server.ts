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

	// Run Claude synthesis and person match fetching in parallel
	let answer: string | null = null;
	let personRefs: { name: string; personId: number }[] = [];

	if (page === 0 && results.length > 0) {
		const anthropicKey = env.ANTHROPIC_API_KEY;

		// Start both tasks concurrently
		const uniqueRecordIds = [...new Set(results.map((c) => c.recordId))];

		const personMatchPromise = uniqueRecordIds.length > 0
			? Promise.all(uniqueRecordIds.map((rid) => fetchRecordPersonMatches(rid)))
				.then((matchArrays) => {
					const deduped: RecordPersonMatch[] = [];
					const seen = new Set<number>();
					for (const matches of matchArrays) {
						for (const m of matches) {
							if (!seen.has(m.personId)) {
								seen.add(m.personId);
								deduped.push(m);
							}
						}
					}
					return deduped;
				})
				.catch(() => [] as RecordPersonMatch[])
			: Promise.resolve([] as RecordPersonMatch[]);

		const claudePromise = anthropicKey
			? (async () => {
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
						return data.content?.[0]?.text || null;
					}
					return null;
				})().catch((e) => {
					console.error('Claude synthesis failed:', e);
					return null;
				})
			: Promise.resolve(null);

		const [allPersonMatches, claudeAnswer] = await Promise.all([personMatchPromise, claudePromise]);
		answer = claudeAnswer;

		// Post-process: find which person names appear in the answer
		if (answer && allPersonMatches.length > 0) {
			for (const m of allPersonMatches) {
				if (answer.includes(m.personName)) {
					personRefs.push({ name: m.personName, personId: m.personId });
				}
			}
			// Sort longest name first to avoid partial replacement issues
			personRefs.sort((a, b) => b.name.length - a.name.length);
		}
	}

	return { q, results: sources, answer, personRefs, page: page, hasMore };
};
