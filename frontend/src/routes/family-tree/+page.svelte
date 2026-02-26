<script lang="ts">
	import { goto } from '$app/navigation';
	import { Search } from 'lucide-svelte';

	let { data } = $props();
	let query = $state(data.q || '');

	function doSearch(e: Event) {
		e.preventDefault();
		if (!query.trim()) return;
		goto(`/family-tree?q=${encodeURIComponent(query.trim())}`);
	}

	const hasResults = $derived(data.results && data.results.length > 0);
	const showLanding = $derived(!data.q);

	function selectPerson(personId: number) {
		goto(`/family-tree?q=${encodeURIComponent(data.q)}&personId=${personId}`);
	}
</script>

<svelte:head>
	<title>{data.q ? `Family Tree - ${data.q}` : 'Family Tree - Archiver'}</title>
</svelte:head>

{#if showLanding}
	<div class="landing">
		<h1 class="heading">Family Tree</h1>
		<p class="subtitle">Search the Czernin genealogy and discover relationships to Alexander</p>
		<form onsubmit={doSearch} class="search-form landing-form">
			<Search size={18} class="search-icon" />
			<input
				type="text"
				bind:value={query}
				placeholder="Search by name..."
				class="search-input"
				autofocus
			/>
		</form>
	</div>
{:else}
	<div class="search-layout">
		<form onsubmit={doSearch} class="search-form results-form">
			<Search size={16} class="search-icon" />
			<input
				type="text"
				bind:value={query}
				class="search-input"
			/>
		</form>

		{#if data.relationship}
			<div class="relationship-card">
				<div class="kinship-label">{data.relationship.kinshipLabel}</div>
				<h3 class="rel-person-name">{data.relationship.personName}</h3>
				<p class="rel-ref">Relationship to Alexander Friedrich Josef Paul Maria Czernin (Lucki)</p>
				{#if data.relationship.pathDescription}
					<p class="rel-path">{data.relationship.pathDescription}</p>
				{/if}
				{#if data.relationship.commonAncestorName}
					<div class="rel-ancestor">
						<span class="rel-ancestor-label">Common ancestor:</span>
						<span class="rel-ancestor-name">{data.relationship.commonAncestorName}</span>
						<span class="rel-steps">
							({data.relationship.stepsFromPerson} gen. from person, {data.relationship.stepsFromAlexander} from Alexander)
						</span>
					</div>
				{/if}
			</div>
		{/if}

		{#if hasResults}
			<div class="results">
				{#each data.results as result}
					<button
						class="result-card"
						class:active={data.personId === result.personId}
						onclick={() => selectPerson(result.personId)}
					>
						<div class="result-header">
							<span class="result-name">{result.name}</span>
							<span class="result-score">{(result.score * 100).toFixed(0)}%</span>
						</div>
						<div class="result-meta">
							<span class="result-section">{result.section}</span>
							{#if result.code}
								<span class="result-code">{result.code}</span>
							{/if}
							{#if result.birthYear || result.deathYear}
								<span class="result-dates">
									{#if result.birthYear}*{result.birthYear}{/if}
									{#if result.deathYear} +{result.deathYear}{/if}
								</span>
							{/if}
						</div>
					</button>
				{/each}
			</div>
		{:else if data.q}
			<p class="empty">No results found for <strong>{data.q}</strong></p>
		{/if}
	</div>
{/if}

<style>
	.landing {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		min-height: calc(100vh - 200px);
		padding-bottom: 140px;
	}

	.heading {
		font-size: 48px;
		font-weight: 300;
		letter-spacing: -0.03em;
		color: var(--vui-text);
		margin-bottom: 8px;
	}

	.subtitle {
		font-size: 16px;
		color: var(--vui-text-sub);
		margin-bottom: 32px;
		max-width: 500px;
		text-align: center;
		line-height: 1.6;
	}

	.search-form {
		position: relative;
		width: 100%;
	}

	.landing-form { max-width: 580px; }
	.results-form { margin-bottom: 24px; }

	:global(.search-icon) {
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

	.search-input:focus { border-color: var(--vui-accent); }
	.search-input::placeholder { color: var(--vui-text-muted); }

	.search-layout {
		max-width: 700px;
	}

	/* Relationship card */
	.relationship-card {
		padding: 20px;
		border: 1.5px solid var(--vui-accent);
		border-radius: var(--vui-radius-md);
		background: var(--vui-accent-dim);
		margin-bottom: 24px;
	}

	.kinship-label {
		font-size: 24px;
		font-weight: 700;
		color: var(--vui-accent);
		margin-bottom: 8px;
		text-transform: capitalize;
	}

	.rel-person-name {
		font-size: 16px;
		font-weight: 600;
		color: var(--vui-text);
		margin: 0 0 4px;
	}

	.rel-ref {
		font-size: 12px;
		color: var(--vui-text-muted);
		margin: 0 0 12px;
	}

	.rel-path {
		font-size: 14px;
		color: var(--vui-text-sub);
		line-height: 1.6;
		margin: 0 0 12px;
	}

	.rel-ancestor {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 6px;
		padding-top: 12px;
		border-top: 1px solid var(--vui-border);
		font-size: 13px;
	}

	.rel-ancestor-label { color: var(--vui-text-muted); }
	.rel-ancestor-name { color: var(--vui-accent); font-weight: 600; }
	.rel-steps { color: var(--vui-text-muted); font-size: 12px; }

	/* Results */
	.results {
		display: flex;
		flex-direction: column;
		gap: 8px;
	}

	.result-card {
		display: block;
		width: 100%;
		text-align: left;
		padding: 14px 16px;
		border: 1.5px solid var(--vui-border);
		border-radius: 10px;
		background: var(--vui-surface);
		cursor: pointer;
		transition: all 0.15s ease;
		font-family: inherit;
	}

	.result-card:hover {
		border-color: var(--vui-accent);
	}

	.result-card.active {
		border-color: var(--vui-accent);
		background: var(--vui-accent-dim);
	}

	.result-header {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		margin-bottom: 6px;
	}

	.result-name {
		font-size: 15px;
		font-weight: 600;
		color: var(--vui-text);
	}

	.result-score {
		font-size: 13px;
		color: var(--vui-accent);
		font-weight: 600;
	}

	.result-meta {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
		font-size: 12px;
		color: var(--vui-text-muted);
	}

	.result-section {
		padding: 2px 6px;
		background: var(--vui-bg-deep, var(--vui-surface));
		border-radius: 4px;
		border: 1px solid var(--vui-border);
	}

	.result-code {
		font-family: monospace;
	}

	.empty {
		margin-top: 32px;
		text-align: center;
		color: var(--vui-text-sub);
	}
</style>
