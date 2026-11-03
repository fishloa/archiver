<script lang="ts">
	import { t } from '$lib/i18n';
	import type { PageResponse } from '$lib/types';

	let {
		page = $bindable(null),
		requirePage = false,
		sendRecord = true
	}: { page?: PageResponse | null; requirePage?: boolean; sendRecord?: boolean } = $props();

	let recordId = $state('');
	let seq = $state('');
	let pages: PageResponse[] = $state([]);

	async function load() {
		pages = [];
		seq = '';
		page = null;
		if (!/^\d+$/.test(recordId)) return;
		const res = await fetch(`/api/records/${recordId}/pages`);
		pages = res.ok ? await res.json() : [];
	}

	function choose() {
		page = pages.find((p) => String(p.seq) === seq) ?? null;
	}
</script>

<div class="record-page">
	<label class="field">
		<span>{$t('family.record')}</span>
		<input class="vui-input" inputmode="numeric" bind:value={recordId} onchange={load} />
	</label>
	<label class="field">
		<span>{$t('family.page')}</span>
		<select class="vui-input" bind:value={seq} onchange={choose} required={requirePage}>
			<option value="">{requirePage ? $t('family.choosePage') : $t('family.wholeRecord')}</option>
			{#each pages as p (p.id)}
				<option value={String(p.seq)}>{p.seq}{p.pageLabel ? ` (${p.pageLabel})` : ''}</option>
			{/each}
		</select>
	</label>
	{#if sendRecord}<input type="hidden" name="recordId" value={recordId} />{/if}
	<input type="hidden" name="pageId" value={page?.id ?? ''} />
</div>

<style>
	.record-page {
		display: flex;
		gap: 8px;
		flex-wrap: wrap;
	}
</style>
