<script lang="ts">
	import { ArrowLeft, ArrowRight } from 'lucide-svelte';

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
	<nav class="flex items-center gap-3 pt-6">
		{#if page > 0}
			<a href={href(page - 1)} class="vui-btn vui-btn-secondary vui-btn-sm">
				<ArrowLeft size={13} strokeWidth={2} /> Prev
			</a>
		{/if}
		<span class="text-text-muted text-[length:var(--vui-text-sm)] tabular-nums">
			Page {page + 1} of {totalPages}
		</span>
		{#if page < totalPages - 1}
			<a href={href(page + 1)} class="vui-btn vui-btn-secondary vui-btn-sm">
				Next <ArrowRight size={13} strokeWidth={2} />
			</a>
		{/if}
	</nav>
{/if}
