<script lang="ts">
	import Pagination from '$lib/components/Pagination.svelte';
	import StatusBadge from '$lib/components/StatusBadge.svelte';

	let { data } = $props();

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
</script>

<svelte:head>
	<title>Records &ndash; Archiver</title>
</svelte:head>

<h1 class="mb-6 text-[var(--vui-text-2xl)] font-extrabold tracking-tight">Records</h1>

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
						<th class="px-4 py-3 text-[11px] font-semibold uppercase tracking-[0.04em] text-text-muted">
							<a href={sortHref(col.key)} class="vui-transition hover:text-text">
								{col.label}{sortIndicator(col.key)}
							</a>
						</th>
					{/each}
				</tr>
			</thead>
			<tbody>
				{#each data.records.content as record}
					<tr class="border-b border-border vui-transition hover:bg-[rgba(255,255,255,0.02)]">
						<td class="px-4 py-3">
							<a href="/records/{record.id}" class="font-medium text-accent vui-transition hover:text-accent-hover">
								{record.title ?? '(untitled)'}
							</a>
						</td>
						<td class="px-4 py-3 whitespace-nowrap text-text-sub">{record.dateRangeText ?? ''}</td>
						<td class="px-4 py-3 text-text-sub">{record.referenceCode ?? ''}</td>
						<td class="px-4 py-3 text-text-sub tabular-nums">{record.pageCount}</td>
						<td class="px-4 py-3"><StatusBadge status={record.status} /></td>
						<td class="px-4 py-3 whitespace-nowrap text-text-muted">{formatDate(record.createdAt)}</td>
					</tr>
				{/each}
			</tbody>
		</table>
	</div>

	<Pagination page={data.records.number} totalPages={data.records.totalPages} />
{/if}
