<script lang="ts">
	import StatusBadge from '$lib/components/StatusBadge.svelte';
	import { ArrowLeft, Download } from 'lucide-svelte';

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

<div class="mb-6">
	<a href="/" class="vui-btn vui-btn-ghost vui-btn-sm">
		<ArrowLeft size={13} strokeWidth={2} /> All records
	</a>
</div>

<div class="vui-card mb-6 vui-animate-fade-in">
	<div class="flex items-start gap-3 mb-4">
		<h1 class="text-[length:var(--vui-text-2xl)] font-extrabold tracking-tight">{record.title ?? '(untitled)'}</h1>
		<StatusBadge status={record.status} />
	</div>

	{#if record.description}
		<p class="text-text-sub mb-6">{record.description}</p>
	{/if}

	<div class="grid grid-cols-1 gap-x-8 gap-y-2 sm:grid-cols-2">
		{#each metaFields as field}
			{#if field.value}
				<div class="flex gap-2 text-[length:var(--vui-text-sm)]">
					<span class="font-medium text-text-muted">{field.label}:</span>
					<span class="text-text-sub">{field.value}</span>
				</div>
			{/if}
		{/each}
	</div>

	{#if record.pdfAttachmentId}
		<div class="mt-6 pt-4 border-t border-border">
			<a href="/api/records/{record.id}/pdf" class="vui-btn vui-btn-primary" target="_blank">
				<Download size={13} strokeWidth={2} /> Download PDF
			</a>
		</div>
	{/if}
</div>

{#if pages.length > 0}
	<div class="vui-section-header">Pages ({pages.length})</div>
	<div class="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 vui-stagger">
		{#each pages as page}
			<a
				href="/records/{record.id}/pages/{page.seq}"
				class="group vui-card vui-hover-lift overflow-hidden p-0"
			>
				{#if page.attachmentId}
					<img
						loading="lazy"
						src="/api/files/{page.attachmentId}/thumbnail"
						alt={page.pageLabel ?? `Page ${page.seq}`}
						class="aspect-[3/4] w-full object-cover vui-transition group-hover:opacity-80"
					/>
				{:else}
					<div class="flex aspect-[3/4] items-center justify-center bg-surface">
						<span class="text-text-dim text-[length:var(--vui-text-xs)]">No image</span>
					</div>
				{/if}
				<div class="px-2 py-1.5 text-center text-[length:var(--vui-text-xs)] text-text-muted">
					{page.pageLabel ?? `Page ${page.seq}`}
				</div>
			</a>
		{/each}
	</div>
{/if}
