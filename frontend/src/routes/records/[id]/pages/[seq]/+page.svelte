<script lang="ts">
	import { ArrowLeft, ArrowRight, ChevronDown } from 'lucide-svelte';

	let { data } = $props();
	let record = $derived(data.record);
	let page = $derived(data.page);
	let prev = $derived(data.prev);
	let next = $derived(data.next);
	let totalPages = $derived(data.totalPages);
	let pageText = $derived(data.pageText);

	let originalOpen = $state(false);
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
	{#if next}
		<a href="/records/{record.id}/pages/{next.seq}" class="vui-btn vui-btn-secondary vui-btn-sm">
			Next <ArrowRight size={13} strokeWidth={2} />
		</a>
	{/if}
</div>

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
