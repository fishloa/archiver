<script lang="ts">
	import { goto } from '$app/navigation';
	import { Search, ChevronLeft, ChevronRight, BrainCircuit, X, ExternalLink, ChevronDown } from 'lucide-svelte';
	import { language, t } from '$lib/i18n';

	let { data } = $props();
	let query = $state(data.q || '');
	let lang = $derived($language);

	function doSearch(e: Event) {
		e.preventDefault();
		if (!query.trim()) return;
		goto(`/?q=${encodeURIComponent(query.trim())}`);
	}

	const hasResults = $derived(data.results && data.results.length > 0);
	const showLanding = $derived(!data.q);

	// Build page numbers: show up to 10 pages, centered on current
	const maxPages = $derived(data.hasMore ? data.page + 2 : data.page + 1);
	const pageNumbers = $derived.by(() => {
		const pages: number[] = [];
		const start = Math.max(0, data.page - 4);
		const end = Math.min(maxPages, start + 10);
		for (let i = start; i < end; i++) pages.push(i);
		return pages;
	});

	function pageUrl(p: number): string {
		return `/?q=${encodeURIComponent(data.q)}&page=${p}`;
	}

	// --- Detail panel state ---
	interface PanelData {
		recordId: number;
		pageSeq: number | null;
		title: string;
		referenceCode: string;
		score: number;
		// Fetched async
		record: any | null;
		pages: any[] | null;
		pageText: any | null;
		loading: boolean;
	}

	let panel = $state<PanelData | null>(null);
	let selectedIdx = $state<number>(-1);
	let ocrOpen = $state(false);

	async function selectResult(r: any, idx: number) {
		selectedIdx = idx;
		ocrOpen = false;
		panel = {
			recordId: r.recordId,
			pageSeq: r.pageSeq,
			title: r.title,
			referenceCode: r.referenceCode,
			score: r.score,
			record: null,
			pages: null,
			pageText: null,
			loading: true
		};

		try {
			// Fetch record and pages in parallel
			const [recRes, pagesRes] = await Promise.all([
				fetch(`/api/records/${r.recordId}`),
				fetch(`/api/records/${r.recordId}/pages`)
			]);
			const record = recRes.ok ? await recRes.json() : null;
			const pages = pagesRes.ok ? await pagesRes.json() : [];

			if (panel && panel.recordId === r.recordId) {
				panel.record = record;
				panel.pages = pages;
			}

			// Fetch page text if we have a specific page
			if (r.pageSeq && pages.length > 0) {
				const page = pages.find((p: any) => p.seq === r.pageSeq);
				if (page) {
					const textRes = await fetch(`/api/pages/${page.id}/text`);
					if (textRes.ok && panel && panel.recordId === r.recordId) {
						panel.pageText = await textRes.json();
					}
				}
			}
		} catch (e) {
			console.error('Failed to load detail:', e);
		} finally {
			if (panel && panel.recordId === r.recordId) {
				panel.loading = false;
			}
		}
	}

	function closePanel() {
		panel = null;
		selectedIdx = -1;
	}

	async function navigateToPage(pg: any) {
		if (!panel) return;
		panel.pageSeq = pg.seq;
		panel.pageText = null;
		ocrOpen = false;
		try {
			const textRes = await fetch(`/api/pages/${pg.id}/text`);
			if (textRes.ok && panel) {
				panel.pageText = await textRes.json();
			}
		} catch (e) {
			console.error('Failed to load page text:', e);
		}
	}

	const panelPage = $derived(
		panel?.pages && panel.pageSeq
			? panel.pages.find((p: any) => p.seq === panel!.pageSeq)
			: null
	);

	// Language-aware text display for search panel
	function panelMainText(pt: any): string | null {
		if (!pt) return null;
		if (lang === 'de') return pt.text || pt.textEn || null;
		return pt.textEn || pt.text || null;
	}
	function panelMainLabel(): string {
		if (lang === 'de') return t('page.originalText');
		return t('search.englishTranslation');
	}
	function panelAltText(pt: any): string | null {
		if (!pt) return null;
		if (lang === 'de') return pt.textEn || null;
		return pt.text || null;
	}
	function panelAltLabel(): string {
		if (lang === 'de') return t('search.englishTranslation');
		return t('search.originalOcr');
	}
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
				placeholder={t('search.placeholder')}
				class="search-input"
				autofocus
			/>
		</form>
	</div>
{:else}
	<div class="search-layout">
		<!-- Left: results list -->
		<div class="results-col" class:has-panel={panel !== null}>
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
						<span>{t('search.aiOverview')}</span>
					</div>
					<p class="ai-text">{data.answer}</p>
				</div>
			{/if}

			{#if hasResults}
				{#snippet pager()}
					<nav class="pager">
						{#if data.page > 0}
							<a href={pageUrl(data.page - 1)} class="pager-arrow"><ChevronLeft size={16} /></a>
						{:else}
							<span class="pager-arrow disabled"><ChevronLeft size={16} /></span>
						{/if}

						{#each pageNumbers as p}
							{#if p === data.page}
								<span class="pager-num active">{p + 1}</span>
							{:else}
								<a href={pageUrl(p)} class="pager-num">{p + 1}</a>
							{/if}
						{/each}

						{#if data.hasMore}
							<a href={pageUrl(data.page + 1)} class="pager-arrow"><ChevronRight size={16} /></a>
						{:else}
							<span class="pager-arrow disabled"><ChevronRight size={16} /></span>
						{/if}
					</nav>
				{/snippet}

				{@render pager()}

				<div class="results">
					{#each data.results as r, i}
						<button
							class="result"
							class:selected={selectedIdx === i}
							onclick={() => selectResult(r, i)}
						>
							<div class="result-url">
								records/{r.recordId}{#if r.pageSeq} / {t('search.page')} {r.pageSeq}{/if}
								{#if r.referenceCode}&nbsp;&middot; {r.referenceCode}{/if}
								&nbsp;&middot; <span class="result-score">{(r.score * 100).toFixed(0)}%</span>
							</div>
							<div class="result-title">{r.title}{#if r.pageSeq} — {t('search.page')} {r.pageSeq}{/if}</div>
							<p class="result-snippet">{r.snippet}</p>
						</button>
					{/each}
				</div>

				{@render pager()}
			{:else if data.q}
				<p class="empty">{t('search.noResults')} <strong>{data.q}</strong></p>
			{/if}
		</div>

		<!-- Right: detail panel -->
		{#if panel}
			<div class="detail-panel">
				<div class="panel-header">
					<button class="panel-close" onclick={closePanel}><X size={18} /></button>
					<a href={panel.pageSeq ? `/records/${panel.recordId}/pages/${panel.pageSeq}` : `/records/${panel.recordId}`} class="panel-link">
						<ExternalLink size={14} />
						{t('search.openFullPage')}
					</a>
				</div>

				{#if panel.loading}
					<div class="panel-loading">{t('search.loading')}</div>
				{:else if panel.record}
					<div class="panel-body">
						<!-- Title -->
						<h2 class="panel-title">{panel.record.titleEn || panel.record.title}</h2>
						{#if panel.record.titleEn && panel.record.title !== panel.record.titleEn}
							<p class="panel-title-orig">{panel.record.title}</p>
						{/if}

						<!-- Meta -->
						<div class="panel-meta">
							{#if panel.record.referenceCode}
								<span class="meta-tag">{t('search.ref')}: {panel.record.referenceCode}</span>
							{/if}
							{#if panel.record.dateRangeText}
								<span class="meta-tag">{panel.record.dateRangeText}</span>
							{/if}
							<span class="meta-tag">{panel.record.pageCount} {t('search.pages')}</span>
							<span class="meta-tag status">{panel.record.status}</span>
						</div>

						<!-- Description -->
						{#if panel.record.descriptionEn}
							<p class="panel-desc">{panel.record.descriptionEn}</p>
						{/if}
						{#if panel.record.description && panel.record.description !== panel.record.descriptionEn}
							<p class="panel-desc-orig">{panel.record.description}</p>
						{/if}

						<!-- Page image -->
						{#if panelPage}
							<div class="panel-image-wrap">
								<img
									src="/api/files/{panelPage.attachmentId}"
									alt="{t('search.page')} {panel.pageSeq}"
									class="panel-image"
									loading="lazy"
								/>
							</div>
						{/if}

						<!-- Main text pane (language-aware) -->
						{#if panelMainText(panel.pageText)}
							<div class="panel-section">
								<h3 class="panel-section-title">{panelMainLabel()} — {t('search.page')} {panel.pageSeq}</h3>
								<pre class="panel-text">{panelMainText(panel.pageText)}</pre>
							</div>
						{/if}

						<!-- Alt text (collapsible) -->
						{#if panelAltText(panel.pageText)}
							<button class="ocr-toggle" onclick={() => ocrOpen = !ocrOpen}>
								<ChevronDown size={14} class={ocrOpen ? 'rotate' : ''} />
								{panelAltLabel()}
								{#if lang !== 'de' && panel.pageText?.confidence}
									<span class="ocr-confidence">{(panel.pageText.confidence * 100).toFixed(0)}%</span>
								{/if}
							</button>
							{#if ocrOpen}
								<pre class="panel-text ocr-text">{panelAltText(panel.pageText)}</pre>
							{/if}
						{/if}

						<!-- Page thumbnails -->
						{#if panel.pages && panel.pages.length > 1}
							<div class="panel-section">
								<h3 class="panel-section-title">{t('search.allPages')} ({panel.pages.length})</h3>
								<div class="thumb-grid">
									{#each panel.pages as pg}
										<button
											class="thumb"
											class:thumb-active={pg.seq === panel.pageSeq}
											onclick={() => navigateToPage(pg)}
										>
											<img
												src="/api/files/{pg.attachmentId}/thumbnail"
												alt="{t('search.page')} {pg.seq}"
												loading="lazy"
											/>
											<span class="thumb-label">{pg.seq}</span>
										</button>
									{/each}
								</div>
							</div>
						{/if}
					</div>
				{/if}
			</div>
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
	.results-form { margin-bottom: 24px; }

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

	/* ---- Split layout ---- */
	.search-layout {
		display: flex;
		gap: 0;
		min-height: calc(100vh - 80px);
	}

	.results-col {
		flex: 1;
		min-width: 0;
		padding-right: 16px;
	}

	.results-col.has-panel {
		flex: 0 0 40%;
		max-width: 480px;
	}

	/* ---- AI Overview ---- */
	.ai-card {
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
	.results {
		display: flex;
		flex-direction: column;
		gap: 8px;
	}

	.result {
		display: block;
		width: 100%;
		text-align: left;
		padding: 12px 14px;
		border: 1.5px solid transparent;
		border-radius: 10px;
		background: none;
		cursor: pointer;
		transition: background 0.15s, border-color 0.15s;
		font-family: inherit;
	}

	.result:hover {
		background: var(--vui-surface);
		border-color: var(--vui-border);
	}

	.result.selected {
		background: var(--vui-surface);
		border-color: var(--vui-accent);
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
		font-size: 17px;
		font-weight: 400;
		color: var(--vui-accent);
		line-height: 1.3;
	}

	.result-snippet {
		margin: 4px 0 0;
		font-size: 13px;
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
		gap: 4px;
		margin: 20px 0;
	}

	.pager-arrow {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 32px;
		height: 32px;
		border-radius: 50%;
		color: var(--vui-accent);
		text-decoration: none;
	}

	.pager-arrow:hover:not(.disabled) {
		background: var(--vui-surface-hover, rgba(0,0,0,0.05));
	}

	.pager-arrow.disabled {
		color: var(--vui-text-muted);
		opacity: 0.4;
		pointer-events: none;
	}

	.pager-num {
		display: flex;
		align-items: center;
		justify-content: center;
		min-width: 32px;
		height: 32px;
		border-radius: 50%;
		font-size: 14px;
		color: var(--vui-accent);
		text-decoration: none;
	}

	.pager-num:hover:not(.active) {
		background: var(--vui-surface-hover, rgba(0,0,0,0.05));
	}

	.pager-num.active {
		background: var(--vui-accent);
		color: white;
		font-weight: 600;
	}

	/* ---- Detail panel ---- */
	.detail-panel {
		flex: 1;
		min-width: 380px;
		border-left: 1px solid var(--vui-border);
		padding: 0 0 40px 24px;
		overflow-y: auto;
		max-height: calc(100vh - 80px);
		position: sticky;
		top: 80px;
	}

	.panel-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 12px 0;
		margin-bottom: 8px;
		border-bottom: 1px solid var(--vui-border);
	}

	.panel-close {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 32px;
		height: 32px;
		border: none;
		border-radius: 50%;
		background: none;
		color: var(--vui-text-muted);
		cursor: pointer;
	}

	.panel-close:hover {
		background: var(--vui-surface-hover, rgba(0,0,0,0.05));
		color: var(--vui-text);
	}

	.panel-link {
		display: flex;
		align-items: center;
		gap: 5px;
		font-size: 12px;
		color: var(--vui-accent);
		text-decoration: none;
	}

	.panel-link:hover { text-decoration: underline; }

	.panel-loading {
		padding: 40px 0;
		text-align: center;
		color: var(--vui-text-muted);
		font-size: 14px;
	}

	.panel-body {
		display: flex;
		flex-direction: column;
		gap: 16px;
	}

	.panel-title {
		font-size: 18px;
		font-weight: 600;
		line-height: 1.35;
		color: var(--vui-text);
		margin: 0;
	}

	.panel-title-orig {
		font-size: 13px;
		color: var(--vui-text-muted);
		font-style: italic;
		margin: -8px 0 0;
		line-height: 1.4;
	}

	.panel-meta {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
	}

	.meta-tag {
		font-size: 11px;
		padding: 3px 8px;
		border-radius: 6px;
		background: var(--vui-surface);
		border: 1px solid var(--vui-border);
		color: var(--vui-text-sub);
	}

	.meta-tag.status {
		color: var(--vui-accent);
		border-color: var(--vui-accent);
		font-weight: 600;
	}

	.panel-desc {
		font-size: 14px;
		line-height: 1.6;
		color: var(--vui-text);
		margin: 0;
	}

	.panel-desc-orig {
		font-size: 13px;
		line-height: 1.5;
		color: var(--vui-text-muted);
		font-style: italic;
		margin: -8px 0 0;
	}

	.panel-image-wrap {
		border-radius: 8px;
		overflow: hidden;
		border: 1px solid var(--vui-border);
	}

	.panel-image {
		width: 100%;
		height: auto;
		display: block;
	}

	.panel-section {
		display: flex;
		flex-direction: column;
		gap: 8px;
	}

	.panel-section-title {
		font-size: 13px;
		font-weight: 600;
		color: var(--vui-text-sub);
		margin: 0;
	}

	.panel-text {
		font-size: 13px;
		line-height: 1.6;
		white-space: pre-wrap;
		word-break: break-word;
		margin: 0;
		padding: 12px;
		border-radius: 8px;
		background: var(--vui-bg-deep, var(--vui-surface));
		border: 1px solid var(--vui-border);
		max-height: 400px;
		overflow-y: auto;
		font-family: inherit;
	}

	.ocr-toggle {
		display: flex;
		align-items: center;
		gap: 6px;
		font-size: 12px;
		color: var(--vui-text-muted);
		background: none;
		border: none;
		cursor: pointer;
		padding: 4px 0;
		font-family: inherit;
	}

	.ocr-toggle:hover { color: var(--vui-text-sub); }

	.ocr-toggle :global(.rotate) {
		transform: rotate(180deg);
	}

	.ocr-confidence {
		font-size: 11px;
		color: var(--vui-accent);
		font-weight: 500;
	}

	.ocr-text {
		font-size: 12px;
		color: var(--vui-text-muted);
	}

	/* ---- Thumbnail grid ---- */
	.thumb-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(70px, 1fr));
		gap: 6px;
	}

	.thumb {
		display: flex;
		flex-direction: column;
		align-items: center;
		text-decoration: none;
		border-radius: 6px;
		overflow: hidden;
		border: 2px solid transparent;
		transition: border-color 0.15s;
		background: none;
		padding: 0;
		cursor: pointer;
		font-family: inherit;
	}

	.thumb:hover { border-color: var(--vui-border); }

	.thumb-active { border-color: #34d399; }

	.thumb img {
		width: 100%;
		aspect-ratio: 3/4;
		object-fit: cover;
	}

	.thumb-label {
		font-size: 10px;
		color: var(--vui-text-muted);
		padding: 2px 0;
	}
</style>
