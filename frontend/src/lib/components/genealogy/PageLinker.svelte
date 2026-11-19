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
	let natural = $state({ width: 0, height: 0 });
	let selected: string | null = $state(null);
	let reason = $derived($t('link.reasonDefault', recordId, seq));
	let reasonEdit: string | null = $state(null);

	function scanLoaded(e: Event) {
		const img = e.target as HTMLImageElement;
		if (img instanceof HTMLImageElement) {
			natural = { width: img.naturalWidth, height: img.naturalHeight };
		}
	}

	function choose(f: Figure) {
		selected = f.id;
		crop = { left: f.left, top: f.top, width: f.width, height: f.height };
	}
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
			<div class="scan" onloadcapture={scanLoaded}>
				<CropSelector {src} bind:crop />
				{#if natural.width > 0 && figures.length > 0}
					<svg class="figures" viewBox="0 0 {natural.width} {natural.height}" preserveAspectRatio="none">
						{#each figures as f (f.id)}
							<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
							<g class="figure" class:picked={selected === f.id} onclick={() => choose(f)}>
								<rect x={f.left} y={f.top} width={f.width} height={f.height} />
								<text x={f.left + 8} y={f.top + 8} dominant-baseline="hanging" font-size={Math.max(24, natural.width / 40)}>{f.number}</text>
							</g>
						{/each}
					</svg>
				{/if}
			</div>
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
				<input class="vui-input" name="reason" value={reasonEdit ?? reason} oninput={(e) => (reasonEdit = e.currentTarget.value)} required />
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
				<input class="vui-input" name="reason" value={reasonEdit ?? reason} oninput={(e) => (reasonEdit = e.currentTarget.value)} required />
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
	.scan {
		position: relative;
		display: inline-block;
		max-width: 100%;
	}
	.figures {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
		pointer-events: none;
	}
	.figure {
		pointer-events: all;
		cursor: pointer;
	}
	.figure rect {
		fill: transparent;
		stroke: var(--vui-accent);
		stroke-width: 2;
		vector-effect: non-scaling-stroke;
	}
	.figure.picked rect {
		fill: color-mix(in srgb, var(--vui-accent) 25%, transparent);
	}
	.figure text {
		fill: var(--vui-accent);
		font-weight: 700;
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
