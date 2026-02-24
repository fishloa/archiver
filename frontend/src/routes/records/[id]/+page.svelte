<script lang="ts">
	import StatusBadge from '$lib/components/StatusBadge.svelte';

	let { data } = $props();
	let record = $derived(data.record);
	let pages = $derived(data.pages);

	function formatDate(iso: string): string {
		return new Date(iso).toLocaleString();
	}

	let metaFields = $derived([
		{ label: 'Reference Code', value: record.referenceCode },
		{ label: 'Date Range', value: record.dateRangeText },
		{ label: 'Inventory Number', value: record.inventoryNumber },
		{ label: 'Call Number', value: record.callNumber },
		{ label: 'Container', value: [record.containerType, record.containerNumber].filter(Boolean).join(' ') || null },
		{ label: 'Finding Aid', value: record.findingAidNumber },
		{ label: 'Source', value: [record.sourceSystem, record.sourceRecordId].filter(Boolean).join(' / ') || null },
		{ label: 'Index Terms', value: record.indexTerms },
		{ label: 'Added', value: formatDate(record.createdAt) },
		{ label: 'Updated', value: formatDate(record.updatedAt) }
	]);
</script>

<svelte:head>
	<title>{record.title ?? 'Record'} &ndash; Archiver</title>
</svelte:head>

<div class="mb-4">
	<a href="/" class="text-sm text-blue-600 hover:underline dark:text-blue-400">&larr; All records</a>
</div>

<div class="mb-6">
	<div class="flex items-start gap-3">
		<h1 class="text-2xl font-bold">{record.title ?? '(untitled)'}</h1>
		<StatusBadge status={record.status} />
	</div>

	{#if record.description}
		<p class="mt-2 text-zinc-600 dark:text-zinc-400">{record.description}</p>
	{/if}
</div>

<div class="mb-8 grid grid-cols-1 gap-x-8 gap-y-2 text-sm sm:grid-cols-2">
	{#each metaFields as field}
		{#if field.value}
			<div class="flex gap-2">
				<span class="font-medium text-zinc-500 dark:text-zinc-400">{field.label}:</span>
				<span>{field.value}</span>
			</div>
		{/if}
	{/each}
</div>

{#if record.pdfAttachmentId}
	<div class="mb-6">
		<a
			href="/api/records/{record.id}/pdf"
			class="inline-block rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
			target="_blank"
		>
			Download PDF
		</a>
	</div>
{/if}

{#if pages.length > 0}
	<h2 class="mb-3 text-lg font-semibold">Pages ({pages.length})</h2>
	<div class="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5">
		{#each pages as page}
			<a
				href="/records/{record.id}/pages/{page.seq}"
				class="group overflow-hidden rounded border border-zinc-200 dark:border-zinc-700"
			>
				{#if page.attachmentId}
					<img
						loading="lazy"
						src="/api/files/{page.attachmentId}/thumbnail"
						alt={page.pageLabel ?? `Page ${page.seq}`}
						class="aspect-[3/4] w-full object-cover transition-opacity group-hover:opacity-80"
					/>
				{:else}
					<div class="flex aspect-[3/4] items-center justify-center bg-zinc-100 dark:bg-zinc-800">
						<span class="text-zinc-400">No image</span>
					</div>
				{/if}
				<div class="px-2 py-1 text-center text-xs text-zinc-500">
					{page.pageLabel ?? `Page ${page.seq}`}
				</div>
			</a>
		{/each}
	</div>
{/if}
