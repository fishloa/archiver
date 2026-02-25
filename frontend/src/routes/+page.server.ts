import { semanticSearch } from '$lib/server/api';
import { env } from '$env/dynamic/private';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ url }) => {
	const q = url.searchParams.get('q') ?? '';
	const page = Number(url.searchParams.get('page') ?? '0');

	if (!q.trim()) {
		return { q: '', results: null, answer: null, page: 0, hasMore: false };
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
								content: `You are a research assistant helping with historical archive documents. Answer the user's question based ONLY on the provided document excerpts. Be concise (2-4 sentences). Cite record numbers in parentheses. If the documents don't contain relevant information, say so briefly.

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
				// Claude synthesis is optional, don't fail the page
				console.error('Claude synthesis failed:', e);
			}
		}
	}

	return { q, results: sources, answer, page: page, hasMore };
};
