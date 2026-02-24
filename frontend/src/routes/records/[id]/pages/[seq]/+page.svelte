<script lang="ts">
	import { ArrowLeft, ArrowRight, ChevronDown } from 'lucide-svelte';

	let { data } = $props();
	let record = $derived(data.record);
	let page = $derived(data.page);
	let prev = $derived(data.prev);
	let next = $derived(data.next);
	let totalPages = $derived(data.totalPages);
	let pageText = $derived(data.pageText);

	let textOpen = $state(true);
</script>

<svelte:head>
	<title>{page.pageLabel ?? `Page ${page.seq}`} &ndash; {record.title ?? 'Record'} &ndash; Archiver</title>
</svelte:head>

<div class="mb-4 flex items-center justify-between">
	<a href="/records/{record.id}" class="vui-btn vui-btn-ghost vui-btn-sm">
		<ArrowLeft size={13} strokeWidth={2} /> {record.title ?? 'Back to record'}
	</a>
	<span class="text-[length:var(--vui-text-sm)] text-text tabular-nums">
		{page.pageLabel ?? `Page ${page.seq}`} &middot; {page.seq} of {totalPages}
	</span>
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

{#if page.attachmentId}
	<div class="flex justify-center vui-animate-fade-in">
		<img
			src="/api/files/{page.attachmentId}"
			alt={page.pageLabel ?? `Page ${page.seq}`}
			class="max-h-[85vh] max-w-full rounded-lg object-contain"
		/>
	</div>
{:else}
	<div class="vui-card flex h-96 items-center justify-center">
		<span class="text-text-sub">No image available</span>
	</div>
{/if}

{#if pageText.text}
	<div class="mt-6 vui-card vui-animate-fade-in">
		<button
			class="flex items-center gap-1.5 text-[length:var(--vui-text-sm)] font-semibold text-accent vui-transition hover:text-accent-hover cursor-pointer w-full"
			onclick={() => textOpen = !textOpen}
		>
			<ChevronDown
				size={14}
				strokeWidth={2}
				class="vui-transition {textOpen ? 'rotate-0' : '-rotate-90'}"
			/>
			OCR Text
			<span class="text-text-sub font-normal ml-1">
				({pageText.engine}, {(pageText.confidence * 100).toFixed(0)}% confidence)
			</span>
		</button>
		{#if textOpen}
			<pre class="mt-3 p-4 rounded-md bg-bg-deep border border-border text-[length:var(--vui-text-sm)] text-text overflow-x-auto font-mono whitespace-pre-wrap leading-relaxed">{pageText.text}</pre>
		{/if}
	</div>
{/if}
