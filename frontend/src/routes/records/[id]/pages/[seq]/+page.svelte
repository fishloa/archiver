<script lang="ts">
	import { ArrowLeft, ArrowRight, ChevronDown, Bookmark, BookmarkCheck, Download, X, Users } from 'lucide-svelte';
	import { isKept, toggleKept, keptCount, keptPagesParam, clearKept } from '$lib/kept-pages.svelte';

	let { data } = $props();
	let record = $derived(data.record);
	let page = $derived(data.page);
	let prev = $derived(data.prev);
	let next = $derived(data.next);
	let totalPages = $derived(data.totalPages);
	let pageText = $derived(data.pageText);
	let personMatches = $derived(data.personMatches ?? []);

	let originalOpen = $state(false);
	let peopleOpen = $state(true);

	let kept = $derived(isKept(record.id, page.seq));
	let count = $derived(keptCount(record.id));
	let pagesParam = $derived(keptPagesParam(record.id));

	function lifespan(birth: number | null, death: number | null): string {
		if (birth && death) return `${birth}–${death}`;
		if (birth) return `*${birth}`;
		if (death) return `†${death}`;
		return '';
	}

	function scorePercent(score: number): string {
		return `${Math.round(score * 100)}%`;
	}
</script>

<svelte:head>
	<title>{page.pageLabel ?? `Page ${page.seq}`} &ndash; {record.title ?? 'Record'} &ndash; Archiver</title>
</svelte:head>

<div class="mb-4">
	<div class="flex items-center justify-between">
		<a href="/records/{record.id}" class="vui-btn vui-btn-ghost vui-btn-sm">
			<ArrowLeft size={13} strokeWidth={2} /> {record.title ?? 'Back to record'}
		</a>
		<span class="text-[length:var(--vui-text-sm)] text-text tabular-nums">
			{page.pageLabel ?? `Page ${page.seq}`} &middot; {page.seq} of {totalPages}
		</span>
	</div>
	{#if record.titleEn && record.titleEn !== record.title}
		<p class="mt-1 ml-9 text-[length:var(--vui-text-sm)] text-emerald-400 italic">{record.titleEn}</p>
	{/if}
</div>

<div class="flex justify-center gap-3 mb-4">
	{#if prev}
		<a href="/records/{record.id}/pages/{prev.seq}" class="vui-btn vui-btn-secondary vui-btn-sm">
			<ArrowLeft size={13} strokeWidth={2} /> Prev
		</a>
	{/if}
	<button
		class="vui-btn vui-btn-sm {kept ? 'vui-btn-primary !bg-emerald-600 !border-emerald-600' : 'vui-btn-secondary'}"
		onclick={() => toggleKept(record.id, page.seq)}
		title={kept ? 'Remove from kept pages' : 'Keep this page'}
	>
		{#if kept}
			<BookmarkCheck size={13} strokeWidth={2} /> Kept
		{:else}
			<Bookmark size={13} strokeWidth={2} /> Keep
		{/if}
	</button>
	{#if next}
		<a href="/records/{record.id}/pages/{next.seq}" class="vui-btn vui-btn-secondary vui-btn-sm">
			Next <ArrowRight size={13} strokeWidth={2} />
		</a>
	{/if}
</div>

{#if count > 0}
	<div class="flex items-center justify-between gap-3 mb-4 py-2 px-4 rounded-lg bg-surface border border-border text-[length:var(--vui-text-sm)]">
		<span class="text-text-sub tabular-nums">{count} page{count === 1 ? '' : 's'} kept</span>
		<div class="flex items-center gap-2">
			<a
				href="/api/records/{record.id}/export-pdf?pages={encodeURIComponent(pagesParam)}"
				class="vui-btn vui-btn-primary vui-btn-sm !bg-emerald-600 !border-emerald-600"
				target="_blank"
			>
				<Download size={13} strokeWidth={2} /> Download kept
			</a>
			<button
				class="vui-btn vui-btn-ghost vui-btn-sm text-text-muted"
				onclick={() => clearKept(record.id)}
				title="Clear kept pages"
			>
				<X size={13} strokeWidth={2} /> Clear
			</button>
		</div>
	</div>
{/if}

<!-- Main layout: image left, translation right -->
<div class="flex flex-col lg:flex-row gap-6 vui-animate-fade-in">
	<!-- Image -->
	<div class="lg:flex-1 min-w-0">
		{#if page.attachmentId}
			<img
				src="/api/files/{page.attachmentId}"
				alt={page.pageLabel ?? `Page ${page.seq}`}
				class="max-h-[85vh] max-w-full rounded-lg object-contain"
			/>
		{:else}
			<div class="vui-card flex h-96 items-center justify-center">
				<span class="text-text-sub">No image available</span>
			</div>
		{/if}
	</div>

	<!-- English translation pane -->
	{#if pageText.textEn}
		<div class="lg:flex-1 min-w-0">
			<div class="vui-card h-full">
				<h2 class="text-[length:var(--vui-text-sm)] font-semibold text-accent mb-3">English Translation</h2>
				<pre class="p-4 rounded-md bg-bg-deep border border-border text-[length:var(--vui-text-sm)] text-text overflow-x-auto font-mono whitespace-pre-wrap leading-relaxed max-h-[80vh] overflow-y-auto">{pageText.textEn}</pre>
			</div>
		</div>
	{/if}
</div>

<!-- People Mentioned -->
{#if personMatches.length > 0}
	<div class="mt-6 vui-card vui-animate-fade-in">
		<button
			class="flex items-center gap-1.5 text-[length:var(--vui-text-sm)] font-semibold text-text-sub vui-transition hover:text-text cursor-pointer w-full"
			onclick={() => peopleOpen = !peopleOpen}
		>
			<ChevronDown
				size={14}
				strokeWidth={2}
				class="vui-transition {peopleOpen ? 'rotate-0' : '-rotate-90'}"
			/>
			<Users size={14} strokeWidth={2} />
			People Mentioned
			<span class="text-text-muted font-normal ml-1">({personMatches.length})</span>
		</button>
		{#if peopleOpen}
			<div class="mt-3 flex flex-wrap gap-2">
				{#each personMatches as match}
					<a
						href="/family-tree?personId={match.personId}"
						class="group flex items-center gap-2 px-3 py-2 rounded-lg bg-bg-deep border border-border vui-transition hover:border-accent hover:bg-surface"
					>
						<div class="min-w-0">
							<div class="text-[length:var(--vui-text-sm)] font-medium text-text group-hover:text-accent vui-transition">
								{match.personName}
							</div>
							<div class="flex items-center gap-2 text-[length:var(--vui-text-xs)] text-text-muted">
								{#if match.birthYear || match.deathYear}
									<span>{lifespan(match.birthYear, match.deathYear)}</span>
								{/if}
								<span class="tabular-nums">{scorePercent(match.score)}</span>
							</div>
						</div>
					</a>
				{/each}
			</div>
			{#each personMatches.filter(m => m.context) as match}
				<div class="mt-2 px-3 py-1.5 rounded bg-bg-deep text-[length:var(--vui-text-xs)] text-text-sub">
					<span class="font-medium text-text">{match.personName}:</span>
					<span class="italic">&ldquo;{match.context}&rdquo;</span>
				</div>
			{/each}
		{/if}
	</div>
{/if}

<!-- Original OCR text (collapsed) -->
{#if pageText.text}
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
			Original OCR Text
			<span class="text-text-muted font-normal ml-1">
				({pageText.engine}, {(pageText.confidence * 100).toFixed(0)}% confidence)
			</span>
		</button>
		{#if originalOpen}
			<pre class="mt-3 p-4 rounded-md bg-bg-deep border border-border text-[length:var(--vui-text-sm)] text-text overflow-x-auto font-mono whitespace-pre-wrap leading-relaxed">{pageText.text}</pre>
		{/if}
	</div>
{/if}
