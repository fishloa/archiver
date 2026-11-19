<script lang="ts">
	import { t } from '$lib/i18n';
	import { ROLES, ROLE_LABELS, type Crop } from '$lib/genealogy';
	import type { Figure } from '$lib/server/api';
	import CropSelector from './CropSelector.svelte';
	import PersonPicker from './PersonPicker.svelte';

	let {
		recordId,
		pageId,
		seq,
		src,
		figures
	}: { recordId: number; pageId: number; seq: number; src: string; figures: Figure[] } = $props();

	let mode: 'picture' | 'person' = $state('picture');
	let crop: Crop | null = $state(null);
	let picked: string | null = $state(null);
	let reason = $derived($t('link.reasonDefault', recordId, seq));
</script>

<section class="linker">
	<h3>{$t('link.title')}</h3>
	<div class="modes">
		<button type="button" class="vui-btn vui-btn-sm {mode === 'picture' ? 'vui-btn-primary' : 'vui-btn-secondary'}" onclick={() => (mode = 'picture')}>
			{$t('link.modePicture')}
		</button>
		<button type="button" class="vui-btn vui-btn-sm {mode === 'person' ? 'vui-btn-primary' : 'vui-btn-secondary'}" onclick={() => (mode = 'person')}>
			{$t('link.modePerson')}
		</button>
	</div>

	{#if mode === 'picture'}
		<form method="POST" action="?/linkMedia">
			<p class="help">{$t('link.help')}</p>
			<input type="hidden" name="pageId" value={pageId} />
			<CropSelector {src} {figures} bind:crop bind:picked />
			<PersonPicker name="personId" label={$t('family.person')} />
			<label class="check">
				<input type="checkbox" name="isPortrait" />
				<span>{$t('family.portrait')}</span>
			</label>
			<label class="field">
				<span>{$t('family.caption')}</span>
				<input class="vui-input" name="caption" />
			</label>
			<label class="field">
				<span>{$t('family.reason')}</span>
				<input class="vui-input" name="reason" value={reason} required />
			</label>
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('link.save')}</button>
		</form>
	{:else}
		<form method="POST" action="?/linkPerson">
			<input type="hidden" name="pageId" value={pageId} />
			<input type="hidden" name="recordId" value={recordId} />
			<PersonPicker name="personId" label={$t('family.person')} />
			<label class="field">
				<span>{$t('link.role')}</span>
				<select class="vui-input" name="role">
					{#each ROLES as r (r)}
						<option value={r} selected={r === 'mentioned'}>{$t(ROLE_LABELS[r])}</option>
					{/each}
				</select>
			</label>
			<label class="field">
				<span>{$t('link.note')}</span>
				<input class="vui-input" name="note" />
			</label>
			<label class="field">
				<span>{$t('family.reason')}</span>
				<input class="vui-input" name="reason" value={reason} required />
			</label>
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('link.save')}</button>
		</form>
	{/if}
</section>

<style>
	.linker,
	form {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}
	h3 {
		font-size: 0.875rem;
		font-weight: 600;
		color: var(--vui-text);
	}
	.modes {
		display: flex;
		gap: 0.5rem;
	}
	.help {
		font-size: 0.75rem;
		color: var(--vui-text-muted);
	}
	.field,
	.check {
		display: flex;
		flex-direction: column;
		gap: 4px;
		font-size: 0.75rem;
		color: var(--vui-text-muted);
	}
	.check {
		flex-direction: row;
		align-items: center;
		gap: 0.5rem;
	}
</style>
