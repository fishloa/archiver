<script lang="ts">
	import { goto } from '$app/navigation';
	import { Search, ChevronLeft, ChevronRight, BrainCircuit } from 'lucide-svelte';

	let { data } = $props();
	let query = $state(data.q || '');

	function doSearch(e: Event) {
		e.preventDefault();
		if (!query.trim()) return;
		goto(`/?q=${encodeURIComponent(query.trim())}`);
	}

	const hasResults = $derived(data.results && data.results.length > 0);
	const showLanding = $derived(!data.q);
</script>

<svelte:head>
	<title>{data.q ? `${data.q} - Archiver` : 'Archiver'}</title>
</svelte:head>

{#if showLanding}
	<div class="landing">
		<h1 class="logo">Archiver</h1>
		<form onsubmit={doSearch} class="search-form landing-form">
			<Search size={18} class="search-icon" />
			<input
				type="text"
				bind:value={query}
				placeholder="Ask me anything..."
				class="search-input"
				autofocus
			/>
		</form>
	</div>
{:else}
	<div class="results-page">
		<form onsubmit={doSearch} class="search-form results-form">
			<Search size={16} class="search-icon" />
			<input
				type="text"
				bind:value={query}
				class="search-input"
			/>
		</form>

		{#if data.answer}
			<div class="ai-card">
				<div class="ai-head">
					<BrainCircuit size={14} />
					<span>AI Overview</span>
				</div>
				<p class="ai-text">{data.answer}</p>
			</div>
		{/if}

		{#if hasResults}
			<div class="results">
				{#each data.results as r}
					<div class="result">
						<div class="result-url">
							records/{r.recordId}{#if r.pageSeq} / page {r.pageSeq}{/if}
							{#if r.referenceCode}&nbsp;&middot; {r.referenceCode}{/if}
							&nbsp;&middot; <span class="result-score">{(r.score * 100).toFixed(0)}% match</span>
						</div>
						<a href={r.pageSeq ? `/records/${r.recordId}/pages/${r.pageSeq}` : `/records/${r.recordId}`} class="result-title">{r.title}{#if r.pageSeq} — Page {r.pageSeq}{/if}</a>
						<p class="result-snippet">{r.snippet}</p>
					</div>
				{/each}
			</div>

			<nav class="pager">
				{#if data.page > 0}
					<a href="/?q={encodeURIComponent(data.q)}&page={data.page - 1}" class="pager-link">
						<ChevronLeft size={16} /> Previous
					</a>
				{/if}
				<span class="pager-current">Page {data.page + 1}</span>
				{#if data.hasMore}
					<a href="/?q={encodeURIComponent(data.q)}&page={data.page + 1}" class="pager-link">
						Next <ChevronRight size={16} />
					</a>
				{/if}
			</nav>
		{:else if data.q}
			<p class="empty">No results found for <strong>{data.q}</strong></p>
		{/if}
	</div>
{/if}

<style>
	/* ---- Landing ---- */
	.landing {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		min-height: calc(100vh - 200px);
		padding-bottom: 140px;
	}

	.logo {
		font-size: 56px;
		font-weight: 300;
		letter-spacing: -0.03em;
		color: var(--vui-text);
		margin-bottom: 32px;
	}

	/* ---- Search bar ---- */
	.search-form {
		position: relative;
		width: 100%;
	}

	.landing-form { max-width: 580px; }
	.results-form { max-width: 560px; margin-bottom: 24px; }

	.search-icon {
		position: absolute;
		left: 16px;
		top: 50%;
		transform: translateY(-50%);
		color: var(--vui-text-muted);
		pointer-events: none;
	}

	.search-input {
		width: 100%;
		padding: 14px 16px 14px 44px;
		border: 1.5px solid var(--vui-border);
		border-radius: 24px;
		background: var(--vui-surface);
		color: var(--vui-text);
		font-size: 16px;
		outline: none;
	}

	.search-input:focus {
		border-color: var(--vui-accent);
	}

	.search-input::placeholder { color: var(--vui-text-muted); }

	/* ---- AI Overview ---- */
	.ai-card {
		max-width: 560px;
		margin-bottom: 28px;
		padding: 14px;
		border: 1px solid var(--vui-border);
		border-radius: 10px;
		background: var(--vui-surface);
	}

	.ai-head {
		display: flex;
		align-items: center;
		gap: 6px;
		font-size: 12px;
		font-weight: 600;
		color: var(--vui-accent);
		margin-bottom: 8px;
	}

	.ai-text {
		font-size: 14px;
		line-height: 1.65;
		color: var(--vui-text);
		white-space: pre-wrap;
		margin: 0;
	}

	/* ---- Results ---- */
	.results-page { max-width: 660px; }

	.results {
		display: flex;
		flex-direction: column;
		gap: 28px;
	}

	.result-url {
		font-size: 12px;
		color: var(--vui-text-muted);
		margin-bottom: 1px;
	}

	.result-score {
		color: var(--vui-accent);
		font-weight: 500;
	}

	.result-title {
		font-size: 20px;
		font-weight: 400;
		color: var(--vui-accent);
		text-decoration: none;
		line-height: 1.3;
	}

	.result-title:hover { text-decoration: underline; }

	.result-snippet {
		margin: 4px 0 0;
		font-size: 14px;
		line-height: 1.55;
		color: var(--vui-text-sub);
	}

	.empty {
		margin-top: 40px;
		color: var(--vui-text-sub);
	}

	/* ---- Pagination ---- */
	.pager {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 16px;
		margin: 36px 0 24px;
	}

	.pager-link {
		display: flex;
		align-items: center;
		gap: 4px;
		color: var(--vui-accent);
		text-decoration: none;
		font-size: 14px;
	}

	.pager-link:hover { text-decoration: underline; }

	.pager-current {
		font-size: 13px;
		color: var(--vui-text-muted);
	}
</style>
