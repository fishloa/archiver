<script lang="ts">
	import { untrack } from 'svelte';
	import { enhance } from '$app/forms';
	import { t } from '$lib/i18n';
	import type { Crop, MediaItem } from '$lib/genealogy';
	import type { Figure } from '$lib/server/api';
	import CropSelector from './CropSelector.svelte';
	import PersonPicker from './PersonPicker.svelte';

	let {
		media,
		src = null,
		figures = []
	}: { media: MediaItem; src?: string | null; figures?: Figure[] } = $props();

	const initialCrop = (): Crop | null =>
		media.cropLeft != null && media.cropTop != null && media.cropWidth != null && media.cropHeight != null
			? { left: media.cropLeft, top: media.cropTop, width: media.cropWidth, height: media.cropHeight }
			: null;
	let crop: Crop | null = $state(untrack(initialCrop));
	let picked: string | null = $state(null);
</script>

{#snippet reason()}
	<input class="vui-input" name="reason" required placeholder={$t('family.reason')} />
{/snippet}

<div class="media-edit">
	<form method="POST" action="?/edit" use:enhance class="grid">
		<input type="hidden" name="_method" value="PUT" />
		<input type="hidden" name="_path" value={`media/${media.id}`} />
		<img class="thumb" src={media.imageUrl} alt={media.caption ?? ''} />
		{#if src}
			<div class="wide">
				<CropSelector {src} bind:crop {figures} bind:picked />
			</div>
		{/if}
		<input class="vui-input" name="caption" value={media.caption ?? ''} placeholder={$t('family.caption')} />
		<input class="vui-input" name="dateText" value={media.dateText ?? ''} placeholder={$t('family.dateHelp')} />
		{@render reason()}
		<button class="vui-btn vui-btn-secondary vui-btn-sm">{$t('family.mediaSave')}</button>
	</form>
	<form method="POST" action="?/edit" use:enhance class="inline">
		<input type="hidden" name="_method" value="PUT" />
		<input type="hidden" name="_path" value={`media-people/${media.mediaPersonId}`} />
		<input type="hidden" name="isPortrait" value={media.isPortrait ? 'false' : 'true'} />
		{@render reason()}
		<button class="vui-btn vui-btn-secondary vui-btn-sm">
			{media.isPortrait ? $t('family.unmakePortrait') : $t('family.makePortrait')}
		</button>
	</form>
	<form method="POST" action="?/edit" use:enhance class="inline">
		<input type="hidden" name="_method" value="DELETE" />
		<input type="hidden" name="_path" value={`media-people/${media.mediaPersonId}`} />
		{@render reason()}
		<button class="vui-btn vui-btn-danger vui-btn-sm">{$t('family.untag')}</button>
	</form>
	<form method="POST" action="?/edit" use:enhance class="grid">
		<input type="hidden" name="_method" value="POST" />
		<input type="hidden" name="_path" value="media-people" />
		<input type="hidden" name="mediaId" value={media.id} />
		<input type="hidden" name="isPortrait" value="false" />
		<PersonPicker name="personId" label={$t('family.tagPerson')} />
		{@render reason()}
		<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.tagged')}</button>
	</form>
	<form method="POST" action="?/edit" use:enhance class="inline">
		<input type="hidden" name="_method" value="DELETE" />
		<input type="hidden" name="_path" value={`media/${media.id}`} />
		{@render reason()}
		<button class="vui-btn vui-btn-danger vui-btn-sm">{$t('family.delete')}</button>
	</form>
</div>

<style>
	.media-edit {
		border-bottom: 1px solid var(--vui-border);
		padding-bottom: 8px;
		margin-bottom: 8px;
	}
	.grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
		gap: 8px;
		align-items: end;
		margin-bottom: 8px;
	}
	.inline {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 8px;
		margin-bottom: 8px;
	}
	.wide {
		grid-column: 1 / -1;
	}
	.thumb {
		max-height: 48px;
		border-radius: var(--vui-radius-sm);
	}
</style>
