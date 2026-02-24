<script lang="ts">
	import StatusBadge from '$lib/components/StatusBadge.svelte';
	import { parseSourceMeta, nadTranslation } from '$lib/archives';
	import { ArrowLeft, Download, ChevronDown } from 'lucide-svelte';

	let { data } = $props();
	let record = $derived(data.record);
	let pages = $derived(data.pages);

	let sourceMeta = $derived(parseSourceMeta(record.rawSourceMetadata));
	let nadNumber = $derived(sourceMeta.nad_number ? String(sourceMeta.nad_number) : null);
	let fondName = $derived(sourceMeta.fond_name ?? null);
	let nadEnglish = $derived(nadTranslation(record.sourceSystem, nadNumber));

	let rawOpen = $state(false);
	let rawFormatted = $derived(
		record.rawSourceMetadata
			? JSON.stringify(JSON.parse(record.rawSourceMetadata), null, 2)
			: null
	);

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

	{#if fondName || nadNumber}
		<div class="mb-4 px-3 py-2 rounded-md bg-surface-alt border border-border">
			{#if fondName}
				<div class="text-[length:var(--vui-text-sm)] font-medium text-text">{fondName}</div>
			{/if}
			{#if nadEnglish}
				<div class="text-[length:var(--vui-text-sm)] font-semibold text-accent">{nadEnglish}</div>
			{/if}
			{#if nadNumber}
				<div class="text-[length:var(--vui-text-xs)] text-text-dim mt-0.5">NAD {nadNumber}</div>
			{/if}
		</div>
	{/if}

	{#if record.description}
		<p class="text-text mb-6">{record.description}</p>
	{/if}

	<div class="grid grid-cols-1 gap-x-8 gap-y-2 sm:grid-cols-2">
		{#each metaFields as field}
			{#if field.value}
				<div class="flex gap-2 text-[length:var(--vui-text-sm)]">
					<span class="font-semibold text-accent">{field.label}:</span>
					<span class="text-text">{field.value}</span>
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

	{#if rawFormatted}
		<div class="mt-6 pt-4 border-t border-border">
			<button
				class="flex items-center gap-1.5 text-[length:var(--vui-text-sm)] font-medium text-text-muted vui-transition hover:text-text cursor-pointer"
				onclick={() => rawOpen = !rawOpen}
			>
				<ChevronDown
					size={14}
					strokeWidth={2}
					class="vui-transition {rawOpen ? 'rotate-0' : '-rotate-90'}"
				/>
				Source Metadata
			</button>
			{#if rawOpen}
				<pre class="mt-2 p-3 rounded-md bg-bg-deep border border-border text-[length:var(--vui-text-xs)] text-text-dim overflow-x-auto font-mono">{rawFormatted}</pre>
			{/if}
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
