<script lang="ts">
	interface Props {
		page: number;
		totalPages: number;
		baseUrl?: string;
	}

	let { page, totalPages, baseUrl = '' }: Props = $props();

	function href(p: number): string {
		const params = new URLSearchParams(typeof window !== 'undefined' ? window.location.search : '');
		params.set('page', String(p));
		return `${baseUrl}?${params}`;
	}
</script>

{#if totalPages > 1}
	<nav class="flex items-center gap-3 py-4">
		{#if page > 0}
			<a href={href(page - 1)} class="rounded bg-zinc-200 px-3 py-1 text-sm hover:bg-zinc-300 dark:bg-zinc-700 dark:hover:bg-zinc-600">&larr; Prev</a>
		{/if}
		<span class="text-sm text-zinc-500">Page {page + 1} of {totalPages}</span>
		{#if page < totalPages - 1}
			<a href={href(page + 1)} class="rounded bg-zinc-200 px-3 py-1 text-sm hover:bg-zinc-300 dark:bg-zinc-700 dark:hover:bg-zinc-600">Next &rarr;</a>
		{/if}
	</nav>
{/if}
