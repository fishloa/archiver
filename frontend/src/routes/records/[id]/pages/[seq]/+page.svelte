<script lang="ts">
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
	<a href="/records/{record.id}" class="text-sm text-blue-600 hover:underline dark:text-blue-400">
		&larr; {record.title ?? 'Back to record'}
	</a>
	<span class="text-sm text-zinc-500">
		{page.pageLabel ?? `Page ${page.seq}`} &middot; {page.seq} of {totalPages}
	</span>
</div>

<div class="flex justify-center gap-4 mb-4">
	{#if prev}
		<a
			href="/records/{record.id}/pages/{prev.seq}"
			class="rounded bg-zinc-200 px-3 py-1 text-sm hover:bg-zinc-300 dark:bg-zinc-700 dark:hover:bg-zinc-600"
		>
			&larr; Prev
		</a>
	{/if}
	{#if next}
		<a
			href="/records/{record.id}/pages/{next.seq}"
			class="rounded bg-zinc-200 px-3 py-1 text-sm hover:bg-zinc-300 dark:bg-zinc-700 dark:hover:bg-zinc-600"
		>
			Next &rarr;
		</a>
	{/if}
</div>

{#if page.attachmentId}
	<div class="flex justify-center">
		<img
			src="/api/files/{page.attachmentId}"
			alt={page.pageLabel ?? `Page ${page.seq}`}
			class="max-h-[85vh] max-w-full object-contain"
		/>
	</div>
{:else}
	<div class="flex h-96 items-center justify-center rounded bg-zinc-100 dark:bg-zinc-800">
		<span class="text-zinc-400">No image available</span>
	</div>
{/if}
