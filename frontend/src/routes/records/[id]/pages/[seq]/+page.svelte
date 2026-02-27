<script lang="ts">
	import { ArrowLeft, ArrowRight, ChevronDown, Bookmark, BookmarkCheck, Download, X, Users, Baby, Skull } from 'lucide-svelte';
	import { isKept, toggleKept, keptCount, keptPagesParam, clearKept } from '$lib/kept-pages.svelte';
	import { language, t } from '$lib/i18n';

	let { data } = $props();
	let record = $derived(data.record);
	let page = $derived(data.page);
	let prev = $derived(data.prev);
	let next = $derived(data.next);
	let totalPages = $derived(data.totalPages);
	let pageText = $derived(data.pageText);
	let personMatches = $derived(data.personMatches ?? []);

	let lang = $derived($language);
	let originalOpen = $state(false);
	let peopleOpen = $state(true);

	let kept = $derived(isKept(record.id, page.seq));
	let count = $derived(keptCount(record.id));
	let pagesParam = $derived(keptPagesParam(record.id));

	// Language-aware text display
	// German users see raw OCR as main text; English users see English translation
	let mainText = $derived(
		lang === 'de'
			? (pageText?.text || pageText?.textEn || null)
			: (pageText?.textEn || pageText?.text || null)
	);
	let mainLabel = $derived(
		lang === 'de' ? $t('page.originalText') : $t('page.englishTranslation')
	);
	let altText = $derived(
		lang === 'de'
			? (pageText?.textEn || null)
			: (pageText?.text || null)
	);
	let altLabel = $derived(
		lang === 'de' ? $t('page.englishTranslation') : $t('page.originalOcrText')
	);
</script>

<svelte:head>
	<title>{page.pageLabel ?? `${$t('search.page')} ${page.seq}`} &ndash; {record.title ?? 'Record'} &ndash; Archiver</title>
</svelte:head>

<div class="mb-4">
	<div class="flex items-center justify-between">
		<a href="/records/{record.id}" class="vui-btn vui-btn-ghost vui-btn-sm">
			<ArrowLeft size={13} strokeWidth={2} /> {record.title ?? $t('page.backToRecord')}
		</a>
		<span class="text-[length:var(--vui-text-sm)] text-text tabular-nums">
			{page.pageLabel ?? `${$t('search.page')} ${page.seq}`} &middot; {page.seq} {$t('page.of')} {totalPages}
		</span>
	</div>
	{#if record.titleEn && record.titleEn !== record.title}
		<p class="mt-1 ml-9 text-[length:var(--vui-text-sm)] text-emerald-400 italic">{record.titleEn}</p>
	{/if}
</div>

<div class="flex justify-center gap-3 mb-4">
	{#if prev}
		<a href="/records/{record.id}/pages/{prev.seq}" class="vui-btn vui-btn-secondary vui-btn-sm">
			<ArrowLeft size={13} strokeWidth={2} /> {$t('page.prev')}
		</a>
	{/if}
	<button
		class="vui-btn vui-btn-sm {kept ? 'vui-btn-primary !bg-emerald-600 !border-emerald-600' : 'vui-btn-secondary'}"
		onclick={() => toggleKept(record.id, page.seq)}
		title={kept ? $t('page.removeKept') : $t('page.keepThis')}
	>
		{#if kept}
			<BookmarkCheck size={13} strokeWidth={2} /> {$t('page.kept')}
		{:else}
			<Bookmark size={13} strokeWidth={2} /> {$t('page.keep')}
		{/if}
	</button>
	{#if next}
		<a href="/records/{record.id}/pages/{next.seq}" class="vui-btn vui-btn-secondary vui-btn-sm">
			{$t('page.next')} <ArrowRight size={13} strokeWidth={2} />
		</a>
	{/if}
</div>

{#if count > 0}
	<div class="flex items-center justify-between gap-3 mb-4 py-2 px-4 rounded-lg bg-surface border border-border text-[length:var(--vui-text-sm)]">
		<span class="text-text-sub tabular-nums">{$t('page.pagesKept', count)}</span>
		<div class="flex items-center gap-2">
			<a
				href="/api/records/{record.id}/export-pdf?pages={encodeURIComponent(pagesParam)}"
				class="vui-btn vui-btn-primary vui-btn-sm !bg-emerald-600 !border-emerald-600"
				target="_blank"
			>
				<Download size={13} strokeWidth={2} /> {$t('page.downloadKept')}
			</a>
			<button
				class="vui-btn vui-btn-ghost vui-btn-sm text-text-sub"
				onclick={() => clearKept(record.id)}
				title={$t('page.clearKept')}
			>
				<X size={13} strokeWidth={2} /> {$t('page.clear')}
			</button>
		</div>
	</div>
{/if}

<!-- Main layout: image left, info right -->
<div class="flex flex-col lg:flex-row gap-6 vui-animate-fade-in">
	<!-- Image -->
	<div class="lg:flex-1 min-w-0">
		{#if page.attachmentId}
			<img
				src="/api/files/{page.attachmentId}"
				alt={page.pageLabel ?? `${$t('search.page')} ${page.seq}`}
				class="max-h-[85vh] max-w-full rounded-lg object-contain"
			/>
		{:else}
			<div class="vui-card flex h-96 items-center justify-center">
				<span class="text-text-sub">{$t('page.noImage')}</span>
			</div>
		{/if}
	</div>

	<!-- Right-hand side: People Mentioned + Main text -->
	<div class="lg:flex-1 min-w-0 flex flex-col gap-4">
		<!-- People Mentioned -->
		{#if personMatches.length > 0}
			<div class="vui-card">
				<button
					class="flex items-center gap-1.5 text-[length:var(--vui-text-sm)] font-semibold text-text vui-transition hover:text-text cursor-pointer w-full"
					onclick={() => peopleOpen = !peopleOpen}
				>
					<ChevronDown
						size={16}
						strokeWidth={2}
						class="vui-transition {peopleOpen ? 'rotate-0' : '-rotate-90'}"
					/>
					<Users size={16} strokeWidth={2} />
					{$t('page.peopleMentioned')}
					<span class="text-text-sub font-normal ml-1">({personMatches.length})</span>
				</button>
				{#if peopleOpen}
					<div class="mt-3 flex flex-col gap-2">
						{#each personMatches as match}
							<a
								href="/family-tree?personId={match.personId}"
								class="group block px-3 py-2 rounded-lg bg-bg-deep border border-border vui-transition hover:border-accent hover:bg-surface"
							>
								<div class="text-[length:var(--vui-text-sm)] font-semibold text-text">
									{match.personName}
								</div>
								<div class="flex items-center gap-3 mt-1 text-[length:var(--vui-text-xs)] text-text-sub">
									{#if match.birthYear}
										<span class="flex items-center gap-1">
											<Baby size={14} strokeWidth={2} class="text-accent" />
											{match.birthYear}
										</span>
									{/if}
									{#if match.deathYear}
										<span class="flex items-center gap-1">
											<Skull size={14} strokeWidth={2} class="text-red-400" />
											{match.deathYear}
										</span>
									{/if}
									{#if match.section}
										<span class="text-text-sub">{match.section}</span>
									{/if}
								</div>
							</a>
						{/each}
					</div>
				{/if}
			</div>
		{/if}

		<!-- Main text pane (language-aware) -->
		{#if mainText}
			<div class="vui-card flex-1">
				<h2 class="text-[length:var(--vui-text-sm)] font-semibold text-accent mb-3">{mainLabel}</h2>
				<pre class="p-4 rounded-md bg-bg-deep border border-border text-[length:var(--vui-text-sm)] text-text overflow-x-auto font-mono whitespace-pre-wrap leading-relaxed max-h-[80vh] overflow-y-auto">{mainText}</pre>
			</div>
		{/if}
	</div>
</div>

<!-- Alt text (collapsible) -->
{#if altText}
	<div class="mt-6 vui-card vui-animate-fade-in">
		<button
			class="flex items-center gap-1.5 text-[length:var(--vui-text-sm)] font-semibold text-text-sub vui-transition hover:text-text cursor-pointer w-full"
			onclick={() => originalOpen = !originalOpen}
		>
			<ChevronDown
				size={14}
				strokeWidth={2}
				class="vui-transition {originalOpen ? 'rotate-0' : '-rotate-90'}"
			/>
			{altLabel}
			{#if lang !== 'de' && pageText?.confidence}
				<span class="text-text-sub font-normal ml-1">
					({pageText.engine}, {(pageText.confidence * 100).toFixed(0)}% {$t('page.confidence')})
				</span>
			{/if}
			{#if lang === 'de' && pageText?.confidence}
				<span class="text-text-sub font-normal ml-1">
					({pageText.engine}, {(pageText.confidence * 100).toFixed(0)}% {$t('page.confidence')})
				</span>
			{/if}
		</button>
		{#if originalOpen}
			<pre class="mt-3 p-4 rounded-md bg-bg-deep border border-border text-[length:var(--vui-text-sm)] text-text overflow-x-auto font-mono whitespace-pre-wrap leading-relaxed">{altText}</pre>
		{/if}
	</div>
{/if}
