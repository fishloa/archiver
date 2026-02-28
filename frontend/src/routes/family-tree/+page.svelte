<script lang="ts">
	import { enhance } from '$app/forms';
	import { goto, invalidateAll } from '$app/navigation';
	import { Search, ChevronRight, Baby, X, HandHeart, UserCheck } from 'lucide-svelte';
	import { language, t } from '$lib/i18n';

	let { data } = $props();
	let query = $state('');

	// Sync query when data.q changes (e.g. navigation)
	$effect(() => {
		query = data.q || '';
	});

	function doSearch(e: Event) {
		e.preventDefault();
		if (!query.trim()) return;
		goto(`/family-tree?q=${encodeURIComponent(query.trim())}`);
	}

	const hasResults = $derived(data.results && data.results.length > 0);
	const showLanding = $derived(!data.q && !data.personId);

	function selectPerson(personId: number) {
		goto(`/family-tree?q=${encodeURIComponent(data.q)}&personId=${personId}`);
	}

	function navigatePerson(personId: number) {
		goto(`/family-tree?q=${encodeURIComponent(data.q || data.person?.name || '')}&personId=${personId}`);
	}

	const user = $derived(data.user);
	const hasRefPerson = $derived(!!user?.familyTreePersonId);
	let settingMe = $state(false);

	const eventColor: Record<string, string> = {
		birth: 'var(--vui-accent)',
		death: '#f87171',
		marriage: '#a78bfa',
		'marriage & divorce': '#fbbf24'
	};

	const eventIconComponent: Record<string, typeof Baby> = {
		birth: Baby,
		death: X,
		marriage: HandHeart,
		'marriage & divorce': HandHeart
	};
</script>

<svelte:head>
	<title>{data.q ? `Family Tree \u2013 ${data.q}` : 'Family Tree \u2013 Archiver'}</title>
</svelte:head>

{#if showLanding}
	<div class="landing">
		<h1 class="heading">{$t('family.title')}</h1>
		<p class="subtitle">{$t('family.subtitle')}</p>
		<form onsubmit={doSearch} class="search-form landing-form">
			<Search size={18} class="search-icon" />
			<!-- svelte-ignore a11y_autofocus -->
			<input type="text" bind:value={query} placeholder={$t('family.searchPlaceholder')} class="search-input" autofocus />
		</form>
	</div>
{:else}
	<div class="search-layout">
		<form onsubmit={doSearch} class="search-form results-form">
			<Search size={16} class="search-icon" />
			<input type="text" bind:value={query} class="search-input" />
		</form>

		<!-- Person detail panel -->
		{#if data.person}
			{@const p = data.person}
			{@const rel = data.relationship}
			<div class="detail-panel">
				<div class="detail-header">
					<div>
						<h2 class="detail-name">{p.name}</h2>
						<div class="detail-meta">
							<span class="detail-section">{p.section}</span>
							{#if p.code}<span class="detail-code">{p.code}</span>{/if}
							{#if p.birthYear || p.deathYear}
								<span class="detail-lifespan">
									{p.birthYear ?? '?'} &ndash; {p.deathYear ?? '?'}
								</span>
							{/if}
						</div>
					</div>
					{#if rel}
						<div class="detail-kinship">
							<span class="kinship-badge">{rel.kinshipLabel}</span>
							<span class="kinship-ref">{hasRefPerson ? $t('family.toYou') : $t('family.toAlexander')}</span>
						</div>
					{/if}
				</div>

				<!-- Timeline -->
				{#if p.events && p.events.length > 0}
					<div class="timeline">
						{#each p.events as ev, i}
							<div class="tl-row">
								<div class="tl-dot-col">
									<span class="tl-dot" style="background: {eventColor[ev.type] ?? 'var(--vui-text-muted)'}">
										{#if eventIconComponent[ev.type]}
											{@const EvIcon = eventIconComponent[ev.type]}
											<EvIcon size={14} strokeWidth={2.5} />
										{/if}
									</span>
									{#if i < p.events.length - 1}
										<span class="tl-line"></span>
									{/if}
								</div>
								<div class="tl-content">
									<span class="tl-type" style="color: {eventColor[ev.type] ?? 'var(--vui-text-muted)'}">
										{ev.type === 'marriage & divorce' ? 'Marriage (divorced)' : ev.type.charAt(0).toUpperCase() + ev.type.slice(1)}
									</span>
									{#if ev.year}
										<span class="tl-year">{ev.year}</span>
									{/if}
									<div class="tl-text">{ev.text}</div>
								</div>
							</div>
						{/each}
					</div>
				{/if}

				<!-- Family links -->
				<div class="detail-family">
					{#if p.parent}
						<div class="family-row">
							<span class="family-label">{$t('family.parent')}</span>
							<button class="family-link" onclick={() => navigatePerson(p.parent.id)}>
								{p.parent.name} <ChevronRight size={14} />
							</button>
						</div>
					{/if}
					{#if p.children && p.children.length > 0}
						<div class="family-row">
							<span class="family-label">{$t('family.children')}</span>
							<div class="family-chips">
								{#each p.children as child}
									<button class="family-link" onclick={() => navigatePerson(child.id)}>
										{child.name} <ChevronRight size={14} />
									</button>
								{/each}
							</div>
						</div>
					{/if}
				</div>

				{#if rel?.commonAncestorName}
					<div class="detail-ancestor">
						{$t('family.commonAncestor')}: <strong>{rel.commonAncestorName}</strong>
						<span class="ancestor-steps">
							({rel.stepsFromPerson} {$t('family.genFromPerson')}, {rel.stepsFromRef} {hasRefPerson ? $t('family.genFromYou') : $t('family.genFromAlexander')})
						</span>
					</div>
				{/if}

				{#if rel?.pathDescription}
					<p class="detail-path">{rel.pathDescription}</p>
				{/if}

				{#if user?.authenticated}
					{@const isMe = user.familyTreePersonId === p.id}
					<form
						method="POST"
						action="?/setFamilyTreePerson"
						use:enhance={() => {
							settingMe = true;
							return async ({ update }) => {
								await update();
								settingMe = false;
								await invalidateAll();
							};
						}}
					>
						<input type="hidden" name="personId" value={p.id} />
						<button
							type="submit"
							class="this-is-me-btn"
							class:is-me={isMe}
							disabled={isMe || settingMe}
						>
							<UserCheck size={16} strokeWidth={2} />
							{isMe ? $t('family.thisIsYou') : $t('family.thisIsMe')}
						</button>
					</form>
				{/if}
			</div>
		{/if}

		<!-- Search results -->
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
									{#if result.birthYear}<span class="date-birth">*{result.birthYear}</span>{/if}
									{#if result.deathYear}<span class="date-death">&dagger;{result.deathYear}</span>{/if}
								</span>
							{/if}
						</div>
					</button>
				{/each}
			</div>
		{:else if data.q}
			<p class="empty">{$t('family.noResults')} <strong>{data.q}</strong></p>
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
		color: var(--vui-text-muted);
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

	/* ── Detail panel ── */
	.detail-panel {
		padding: 24px;
		border: 1.5px solid var(--vui-accent);
		border-radius: var(--vui-radius-md);
		background: var(--vui-accent-dim);
		margin-bottom: 24px;
	}

	.detail-header {
		display: flex;
		justify-content: space-between;
		align-items: flex-start;
		gap: 16px;
		flex-wrap: wrap;
		margin-bottom: 12px;
	}

	.detail-name {
		font-size: 22px;
		font-weight: 700;
		color: var(--vui-text);
		margin: 0 0 6px;
	}

	.detail-meta {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 8px;
		font-size: 13px;
	}

	.detail-section {
		padding: 2px 8px;
		background: var(--vui-surface);
		border-radius: 4px;
		border: 1px solid var(--vui-border);
		color: var(--vui-text-muted);
		font-weight: 500;
	}

	.detail-code {
		font-family: monospace;
		color: var(--vui-text-muted);
	}

	.detail-lifespan {
		font-weight: 700;
		color: var(--vui-text);
		font-size: 14px;
	}

	.detail-kinship {
		text-align: right;
		flex-shrink: 0;
	}

	.kinship-badge {
		display: block;
		font-size: 18px;
		font-weight: 700;
		color: var(--vui-accent);
		text-transform: capitalize;
	}

	.kinship-ref {
		font-size: 16px;
		font-weight: 600;
		color: var(--vui-accent);
	}

	.detail-path {
		font-size: 14px;
		color: var(--vui-text-muted);
		line-height: 1.6;
		margin: 0;
		padding-top: 12px;
		border-top: 1px solid color-mix(in srgb, var(--vui-border) 60%, transparent);
	}

	/* ── Timeline ── */
	.timeline {
		display: flex;
		flex-direction: column;
		margin-bottom: 16px;
	}

	.tl-row {
		display: flex;
		gap: 14px;
		min-height: 48px;
	}

	.tl-dot-col {
		display: flex;
		flex-direction: column;
		align-items: center;
		width: 28px;
		flex-shrink: 0;
	}

	.tl-dot {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 28px;
		height: 28px;
		border-radius: 50%;
		font-size: 14px;
		font-weight: 700;
		color: var(--vui-bg-deep);
		flex-shrink: 0;
	}

	.tl-line {
		flex: 1;
		width: 2px;
		background: color-mix(in srgb, var(--vui-border) 80%, transparent);
		min-height: 12px;
	}

	.tl-content {
		padding-bottom: 14px;
		flex: 1;
		min-width: 0;
	}

	.tl-type {
		font-size: 11px;
		font-weight: 700;
		text-transform: uppercase;
		letter-spacing: 0.05em;
	}

	.tl-year {
		font-size: 11px;
		font-weight: 600;
		color: var(--vui-text);
		margin-left: 8px;
	}

	.tl-text {
		font-size: 14px;
		color: var(--vui-text);
		margin-top: 2px;
		line-height: 1.5;
		word-break: break-word;
	}

	/* ── Family links ── */
	.detail-family {
		display: flex;
		flex-direction: column;
		gap: 8px;
		padding: 14px 0;
		border-top: 1px solid color-mix(in srgb, var(--vui-border) 60%, transparent);
	}

	.family-row {
		display: flex;
		align-items: baseline;
		gap: 10px;
	}

	.family-label {
		font-size: 11px;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: var(--vui-text-muted);
		min-width: 60px;
		flex-shrink: 0;
	}

	.family-chips {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
	}

	.family-link {
		display: inline-flex;
		align-items: center;
		gap: 2px;
		padding: 3px 10px;
		border: 1px solid var(--vui-border);
		border-radius: 16px;
		background: var(--vui-surface);
		color: var(--vui-accent);
		font-size: 13px;
		font-weight: 500;
		cursor: pointer;
		font-family: inherit;
		transition: all 0.15s ease;
	}

	.family-link:hover {
		border-color: var(--vui-accent);
		background: var(--vui-accent-dim);
	}

	.detail-ancestor {
		font-size: 12px;
		color: var(--vui-text-muted);
		padding-top: 12px;
		border-top: 1px solid color-mix(in srgb, var(--vui-border) 60%, transparent);
	}

	.detail-ancestor strong {
		color: var(--vui-accent);
	}

	.ancestor-steps {
		color: var(--vui-text-muted);
	}

	/* ── Results ── */
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
		align-items: center;
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

	.result-dates {
		display: flex;
		gap: 6px;
		font-size: 13px;
		font-weight: 700;
	}

	.date-birth {
		color: var(--vui-accent);
	}

	.date-death {
		color: #f87171;
	}

	.this-is-me-btn {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		margin-top: 14px;
		padding: 8px 16px;
		background: var(--vui-surface);
		border: 1.5px solid var(--vui-border);
		border-radius: var(--vui-radius-md);
		color: var(--vui-text-muted);
		font-size: 13px;
		font-weight: 600;
		cursor: pointer;
		font-family: inherit;
		transition: all 0.15s ease;
	}

	.this-is-me-btn:hover:not(:disabled) {
		border-color: var(--vui-accent);
		color: var(--vui-accent);
	}

	.this-is-me-btn.is-me {
		border-color: var(--vui-accent);
		color: var(--vui-accent);
		cursor: default;
	}

	.this-is-me-btn:disabled:not(.is-me) {
		opacity: 0.5;
		cursor: wait;
	}

	.empty {
		margin-top: 32px;
		text-align: center;
		color: var(--vui-text-muted);
	}
</style>
