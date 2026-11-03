<script lang="ts">
	import { t } from '$lib/i18n';

	let { data } = $props();
</script>

<svelte:head>
	<title>{$t('family.reviewTitle')}</title>
</svelte:head>

<h1 class="vui-page-title">{$t('family.reviewTitle')}</h1>
{#if data.items.length === 0}
	<p>{$t('family.reviewEmpty')}</p>
{:else}
	<ul class="review">
		{#each data.items as item (item.id)}
			<li class="vui-card">
				<a href={`/family-tree?personId=${item.id}&edit=1`}>{item.name} #{item.id}</a>
				<p class="reason">{item.needsReview}</p>
				{#if item.legacyEntry}<p class="legacy">{item.legacyEntry}</p>{/if}
			</li>
		{/each}
	</ul>
{/if}

<style>
	.review {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.reason {
		margin: 4px 0 0;
	}
	.legacy {
		margin: 4px 0 0;
		font-family: var(--vui-font-mono);
		font-size: 12px;
		color: var(--vui-text-muted);
	}
</style>
