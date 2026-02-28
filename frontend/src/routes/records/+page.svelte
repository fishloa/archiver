<script lang="ts">
	import { onMount } from 'svelte';
	import { invalidateAll } from '$app/navigation';
	import Pagination from '$lib/components/Pagination.svelte';
	import StatusBadge from '$lib/components/StatusBadge.svelte';
	import { parseSourceMeta, nadTranslation } from '$lib/archives';
	import { language, t } from '$lib/i18n';

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

	const statusKeys: Record<string, string> = {
		'': 'records.statusAll',
		'ingesting': 'records.statusIngesting',
		'ingested': 'records.statusIngested',
		'ocr_pending': 'records.statusOcrPending',
		'ocr_done': 'records.statusOcrDone',
		'pdf_pending': 'records.statusPdfPending',
		'pdf_done': 'records.statusPdfDone',
		'translating': 'records.statusTranslating',
		'complete': 'records.statusComplete',
		'error': 'records.statusError'
	};

	const statuses = $derived(
		Object.entries(statusKeys).map(([value, key]) => ({ value, label: $t(key as any) }))
	);

	const columnKeys = [
		{ key: 'title', labelKey: 'records.col.title' as const },
		{ key: 'dateRangeText', labelKey: 'records.col.date' as const },
		{ key: 'referenceCode', labelKey: 'records.col.refCode' as const },
		{ key: 'status', labelKey: 'records.col.status' as const },
		{ key: 'createdAt', labelKey: 'records.col.added' as const },
		{ key: 'pageCount', labelKey: 'records.col.pages' as const }
	];

	function buildParams(overrides: Record<string, string | number> = {}): URLSearchParams {
		const params = new URLSearchParams();
		const vals = {
			status: data.status,
			sortBy: data.sortBy,
			sortDir: data.sortDir,
			archiveId: data.archiveId ? String(data.archiveId) : '',
			...overrides
		};
		for (const [k, v] of Object.entries(vals)) {
			if (v) params.set(k, String(v));
		}
		return params;
	}

	function sortHref(key: string): string {
		const dir = data.sortBy === key && data.sortDir === 'asc' ? 'desc' : 'asc';
		return `?${buildParams({ sortBy: key, sortDir: dir })}`;
	}

	function statusHref(status: string): string {
		return `?${buildParams({ status })}`;
	}

	function archiveHref(archiveId: number): string {
		return `?${buildParams({ archiveId: archiveId || '' })}`;
	}

	function sortIndicator(key: string): string {
		if (data.sortBy !== key) return '';
		return data.sortDir === 'asc' ? ' \u25B2' : ' \u25BC';
	}

	function formatDate(iso: string): string {
		return new Date(iso).toLocaleDateString();
	}

	function pageDisplay(record: { pageCount: number; status: string; rawSourceMetadata: string | null }): string {
		if (record.status === 'ingesting') {
			const meta = parseSourceMeta(record.rawSourceMetadata);
			const total = meta.scans;
			if (typeof total === 'number' && total > 0) {
				return `${record.pageCount}/${total}`;
			}
		}
		return String(record.pageCount);
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
	<title>{$t('records.title')} &ndash; Archiver</title>
</svelte:head>

<div class="flex items-center justify-between mb-6">
	<h1 class="text-[length:var(--vui-text-2xl)] font-extrabold tracking-tight">{$t('records.title')}</h1>
	<span class="flex items-center gap-1.5 text-[length:var(--vui-text-xs)] text-text-sub">
		<span class="inline-block w-1.5 h-1.5 rounded-full {connected ? 'bg-green-500' : 'bg-red-500'}"></span>
		{connected ? $t('records.live') : $t('records.connecting')}
	</span>
</div>

<div class="flex items-center gap-3 mb-4">
	<label class="flex items-center gap-2 text-[length:var(--vui-text-xs)] text-text-sub">
		<span class="font-medium">{$t('records.status')}</span>
		<select
			class="filter-select"
			onchange={(e) => { window.location.href = statusHref(e.currentTarget.value); }}
		>
			{#each statuses as s}
				<option value={s.value} selected={data.status === s.value}>{s.label}</option>
			{/each}
		</select>
	</label>

	{#if data.archives?.length > 1}
		<label class="flex items-center gap-2 text-[length:var(--vui-text-xs)] text-text-sub">
			<span class="font-medium">{$t('records.archive')}</span>
			<select
				class="filter-select"
				onchange={(e) => { window.location.href = archiveHref(Number(e.currentTarget.value)); }}
			>
				<option value="0" selected={!data.archiveId}>{$t('records.allArchives')}</option>
				{#each data.archives as archive}
					<option value={archive.id} selected={data.archiveId === archive.id}>{archive.name}</option>
				{/each}
			</select>
		</label>
	{/if}
</div>

{#if data.records.empty}
	<div class="vui-card">
		<p class="text-text-sub">{$t('records.noRecords')}</p>
	</div>
{:else}
	<div class="vui-card overflow-x-auto p-0">
		<table class="w-full text-left">
			<thead>
				<tr class="border-b border-border">
					{#each columnKeys as col}
						<th class="px-5 py-3 text-[11px] font-semibold uppercase tracking-[0.04em] text-text-sub">
							<a href={sortHref(col.key)} class="vui-transition hover:text-text">
								{$t(col.labelKey)}{sortIndicator(col.key)}
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
								{record.title ?? $t('records.untitled')}
							</a>
							{#if fond}
								<div class="text-[length:var(--vui-text-xs)] text-text-sub mt-0.5">{fond}</div>
							{/if}
						</td>
						<td class="px-5 py-3.5 whitespace-nowrap text-text-sub">{record.dateRangeText ?? ''}</td>
						<td class="px-5 py-3.5 text-text-sub">{record.referenceCode ?? ''}</td>
						<td class="px-5 py-3.5"><StatusBadge status={record.status} /></td>
						<td class="px-5 py-3.5 whitespace-nowrap text-text-sub">{formatDate(record.createdAt)}</td>
						<td class="px-5 py-3.5 text-text-sub tabular-nums text-right">{pageDisplay(record)}</td>
					</tr>
				{/each}
			</tbody>
		</table>
	</div>

	<div class="flex items-center justify-between">
		<Pagination page={data.records.number} totalPages={data.records.totalPages} />
		<span class="text-[length:var(--vui-text-xs)] text-text-sub tabular-nums">
			{data.records.totalElements} {$t('records.records')}
		</span>
	</div>
{/if}

<style>
	.filter-select {
		appearance: none;
		background-color: var(--vui-surface);
		border: 1px solid var(--vui-border);
		border-radius: var(--vui-radius-md);
		padding: 0.375rem 2rem 0.375rem 0.625rem;
		font-size: var(--vui-text-xs);
		font-weight: 500;
		color: var(--vui-text);
		cursor: pointer;
		transition: border-color 0.15s, box-shadow 0.15s;
		background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%23888' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
		background-repeat: no-repeat;
		background-position: right 0.5rem center;
	}
	.filter-select:hover {
		border-color: var(--vui-border-hover);
	}
	.filter-select:focus {
		outline: none;
		border-color: var(--vui-accent);
		box-shadow: 0 0 0 2px var(--vui-accent-glow);
	}
	.filter-select option {
		background: var(--vui-bg);
		color: var(--vui-text);
	}
</style>
