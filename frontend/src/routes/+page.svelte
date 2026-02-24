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

<h1 class="mb-4 text-2xl font-bold">Records</h1>

{#if data.records.empty}
	<p class="text-zinc-500">No records found.</p>
{:else}
	<div class="overflow-x-auto">
		<table class="w-full text-left text-sm">
			<thead>
				<tr class="border-b border-zinc-200 dark:border-zinc-700">
					{#each columns as col}
						<th class="px-3 py-2 font-medium">
							<a href={sortHref(col.key)} class="hover:underline">
								{col.label}{sortIndicator(col.key)}
							</a>
						</th>
					{/each}
				</tr>
			</thead>
			<tbody>
				{#each data.records.content as record}
					<tr class="border-b border-zinc-100 hover:bg-zinc-50 dark:border-zinc-800 dark:hover:bg-zinc-900">
						<td class="px-3 py-2">
							<a href="/records/{record.id}" class="text-blue-600 hover:underline dark:text-blue-400">
								{record.title ?? '(untitled)'}
							</a>
						</td>
						<td class="px-3 py-2 whitespace-nowrap">{record.dateRangeText ?? ''}</td>
						<td class="px-3 py-2">{record.referenceCode ?? ''}</td>
						<td class="px-3 py-2">{record.pageCount}</td>
						<td class="px-3 py-2"><StatusBadge status={record.status} /></td>
						<td class="px-3 py-2 whitespace-nowrap">{formatDate(record.createdAt)}</td>
					</tr>
				{/each}
			</tbody>
		</table>
	</div>

	<Pagination page={data.records.number} totalPages={data.records.totalPages} />
{/if}
