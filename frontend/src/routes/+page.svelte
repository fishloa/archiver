<script lang="ts">
	import { onMount } from 'svelte';
	import { invalidateAll } from '$app/navigation';
	import Pagination from '$lib/components/Pagination.svelte';
	import StatusBadge from '$lib/components/StatusBadge.svelte';
	import { parseSourceMeta, nadTranslation } from '$lib/archives';

	let { data } = $props();

	let connected = $state(false);

	onMount(() => {
		let debounceTimer: ReturnType<typeof setTimeout> | null = null;
		const es = new EventSource('/api/records/events');
		es.addEventListener('record', () => {
			// Debounce rapid events (e.g. page-by-page uploads)
			if (debounceTimer) clearTimeout(debounceTimer);
			debounceTimer = setTimeout(() => invalidateAll(), 500);
		});
		es.onopen = () => {
			connected = true;
		};
		es.onerror = () => {
			connected = false;
		};
		return () => {
			if (debounceTimer) clearTimeout(debounceTimer);
			es.close();
		};
	});

	const columns = [
		{ key: 'title', label: 'Title' },
		{ key: 'dateRangeText', label: 'Date' },
		{ key: 'referenceCode', label: 'Ref Code' },
		{ key: 'pageCount', label: 'Pages' },
		{ key: 'status', label: 'Status' },
		{ key: 'createdAt', label: 'Added' }
	] as const;

	function sortHref(key: string): string {
		const dir = data.sortBy === key && data.sortDir === 'asc' ? 'desc' : 'asc';
		return `?sortBy=${key}&sortDir=${dir}`;
	}

	function sortIndicator(key: string): string {
		if (data.sortBy !== key) return '';
		return data.sortDir === 'asc' ? ' \u25B2' : ' \u25BC';
	}

	function formatDate(iso: string): string {
		return new Date(iso).toLocaleDateString();
	}

	function fondLabel(sourceSystem: string | null, raw: string | null): string {
		const meta = parseSourceMeta(raw);
		const nad = meta.nad_number ? String(meta.nad_number) : null;
		const en = nadTranslation(sourceSystem, nad);
		if (en && nad) return `${en} (NAD ${nad})`;
		if (meta.fond_name) return meta.fond_name;
		return '';
	}
</script>

<svelte:head>
	<title>Records &ndash; Archiver</title>
</svelte:head>

<div class="flex items-center justify-between mb-6">
	<h1 class="text-[length:var(--vui-text-2xl)] font-extrabold tracking-tight">Records</h1>
	<span class="flex items-center gap-1.5 text-[length:var(--vui-text-xs)] text-text-muted">
		<span class="inline-block w-1.5 h-1.5 rounded-full {connected ? 'bg-green-500' : 'bg-red-500'}"></span>
		{connected ? 'Live' : 'Connecting…'}
	</span>
</div>

{#if data.records.empty}
	<div class="vui-card">
		<p class="text-text-muted">No records found.</p>
	</div>
{:else}
	<div class="vui-card overflow-x-auto p-0">
		<table class="w-full text-left">
			<thead>
				<tr class="border-b border-border">
					{#each columns as col}
						<th class="px-5 py-3 text-[11px] font-semibold uppercase tracking-[0.04em] text-text-muted">
							<a href={sortHref(col.key)} class="vui-transition hover:text-text">
								{col.label}{sortIndicator(col.key)}
							</a>
						</th>
					{/each}
				</tr>
			</thead>
			<tbody>
				{#each data.records.content as record}
					{@const fond = fondLabel(record.sourceSystem, record.rawSourceMetadata)}
					<tr class="border-b border-border vui-transition hover:bg-[rgba(255,255,255,0.02)]">
						<td class="px-5 py-3.5">
							<a href="/records/{record.id}" class="font-medium text-accent vui-transition hover:text-accent-hover">
								{record.title ?? '(untitled)'}
							</a>
							{#if fond}
								<div class="text-[length:var(--vui-text-xs)] text-text-muted mt-0.5">{fond}</div>
							{/if}
						</td>
						<td class="px-5 py-3.5 whitespace-nowrap text-text">{record.dateRangeText ?? ''}</td>
						<td class="px-5 py-3.5 text-text">{record.referenceCode ?? ''}</td>
						<td class="px-5 py-3.5 text-text tabular-nums">{record.pageCount}</td>
						<td class="px-5 py-3.5"><StatusBadge status={record.status} /></td>
						<td class="px-5 py-3.5 whitespace-nowrap text-text-muted">{formatDate(record.createdAt)}</td>
					</tr>
				{/each}
			</tbody>
		</table>
	</div>

	<div class="flex items-center justify-between">
		<Pagination page={data.records.number} totalPages={data.records.totalPages} />
		<span class="text-[length:var(--vui-text-xs)] text-text-dim tabular-nums">
			{data.records.totalElements} records
		</span>
	</div>
{/if}
