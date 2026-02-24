<script lang="ts">
	import { Search } from 'lucide-svelte';
	import Pagination from '$lib/components/Pagination.svelte';

	let { data } = $props();

	let query = $state(data.q);

	function highlight(snippet: string, q: string): string {
		if (!q) return snippet;
		const escaped = q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
		return snippet.replace(
			new RegExp(`(${escaped})`, 'gi'),
			'<mark class="bg-accent/30 text-text rounded px-0.5">$1</mark>'
		);
	}
</script>

<svelte:head>
	<title>{data.q ? `"${data.q}" — Search` : 'Search'} &ndash; Archiver</title>
</svelte:head>

<h1 class="text-[length:var(--vui-text-2xl)] font-extrabold tracking-tight mb-6">Search OCR Text</h1>

<form method="get" class="mb-6 flex gap-3">
	<div class="relative flex-1">
		<Search size={16} strokeWidth={2} class="absolute left-3 top-1/2 -translate-y-1/2 text-text-sub" />
		<input
			type="text"
			name="q"
			bind:value={query}
			placeholder="Search page text..."
			class="w-full pl-10 pr-4 py-2.5 rounded-lg bg-surface border border-border text-text placeholder:text-text-sub focus:outline-none focus:ring-2 focus:ring-accent/50 vui-transition"
		/>
	</div>
	<button type="submit" class="vui-btn vui-btn-primary">Search</button>
</form>

{#if data.q && data.total > 0}
	<p class="text-text-sub text-[length:var(--vui-text-sm)] mb-4 tabular-nums">
		{data.total} result{data.total !== 1 ? 's' : ''} for "{data.q}"
	</p>

	<div class="space-y-3">
		{#each data.results as result}
			<a
				href="/records/{result.recordId}/pages/{result.seq}"
				class="vui-card block vui-transition hover:border-accent/40"
			>
				<div class="flex items-baseline gap-2 mb-1.5">
					<span class="font-semibold text-accent">{result.recordTitle ?? '(untitled)'}</span>
					<span class="text-[length:var(--vui-text-xs)] text-text-sub tabular-nums">
						Page {result.seq}
					</span>
					{#if result.referenceCode}
						<span class="text-[length:var(--vui-text-xs)] text-text-sub">{result.referenceCode}</span>
					{/if}
					<span class="text-[length:var(--vui-text-xs)] text-text-sub ml-auto tabular-nums">
						{((result.confidence ?? 0) * 100).toFixed(0)}%
					</span>
				</div>
				<p class="text-[length:var(--vui-text-sm)] text-text leading-relaxed">
					{@html highlight(result.snippet, data.q)}
				</p>
			</a>
		{/each}
	</div>

	{@const totalPages = Math.ceil(data.total / data.size)}
	{#if totalPages > 1}
		<Pagination page={data.page} {totalPages} baseUrl="/search?q={encodeURIComponent(data.q)}" />
	{/if}
{:else if data.q}
	<div class="vui-card">
		<p class="text-text-sub">No results found for "{data.q}".</p>
	</div>
{/if}
