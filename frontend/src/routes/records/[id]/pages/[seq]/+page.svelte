<script lang="ts">
	import { ArrowLeft, ArrowRight } from 'lucide-svelte';

	let { data } = $props();
	let record = $derived(data.record);
	let page = $derived(data.page);
	let prev = $derived(data.prev);
	let next = $derived(data.next);
	let totalPages = $derived(data.totalPages);
</script>

<svelte:head>
	<title>{page.pageLabel ?? `Page ${page.seq}`} &ndash; {record.title ?? 'Record'} &ndash; Archiver</title>
</svelte:head>

<div class="mb-4 flex items-center justify-between">
	<a href="/records/{record.id}" class="vui-btn vui-btn-ghost vui-btn-sm">
		<ArrowLeft size={13} strokeWidth={2} /> {record.title ?? 'Back to record'}
	</a>
	<span class="text-[length:var(--vui-text-sm)] text-text-muted tabular-nums">
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
		<span class="text-text-dim">No image available</span>
	</div>
{/if}
