<script lang="ts">
	import { t } from '$lib/i18n';
	import { cropFromPoints, type Crop } from '$lib/genealogy';
	import type { Figure } from '$lib/server/api';

	let {
		src,
		crop = $bindable(null),
		figures = [],
		picked = $bindable(null)
	}: { src: string; crop?: Crop | null; figures?: Figure[]; picked?: string | null } = $props();

	const CLICK_PX = 4;

	let natural = $state({ width: 0, height: 0 });
	let start: { x: number; y: number } | null = null;
	let startClient: { x: number; y: number } | null = null;
	let dragged = false;

	function loaded(e: Event) {
		const img = e.currentTarget as HTMLImageElement;
		natural = { width: img.naturalWidth, height: img.naturalHeight };
	}

	function point(e: PointerEvent) {
		const r = (e.currentTarget as Element).getBoundingClientRect();
		return {
			x: ((e.clientX - r.left) / r.width) * natural.width,
			y: ((e.clientY - r.top) / r.height) * natural.height
		};
	}

	function down(e: PointerEvent) {
		start = point(e);
		startClient = { x: e.clientX, y: e.clientY };
		dragged = false;
		(e.currentTarget as Element).setPointerCapture?.(e.pointerId);
	}

	function moved(e: PointerEvent) {
		if (!startClient) return false;
		if (!dragged) {
			dragged =
				figures.length === 0 ||
				Math.hypot(e.clientX - startClient.x, e.clientY - startClient.y) > CLICK_PX;
		}
		return dragged;
	}

	function move(e: PointerEvent) {
		if (start && moved(e)) {
			crop = cropFromPoints(start, point(e), natural.width, natural.height);
			picked = null;
		}
	}

	function up(e: PointerEvent) {
		if (!start) return;
		move(e);
		if (!dragged) {
			const f = figures.find(
				(g) =>
					start!.x >= g.left &&
					start!.x <= g.left + g.width &&
					start!.y >= g.top &&
					start!.y <= g.top + g.height
			);
			if (f) {
				crop = { left: f.left, top: f.top, width: f.width, height: f.height };
				picked = f.id;
			}
		}
		start = null;
		startClient = null;
	}
</script>

<div class="cropper">
	<img {src} alt={$t('family.cropImage')} onload={loaded} />
	{#if natural.width > 0}
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<svg
			class="overlay"
			viewBox="0 0 {natural.width} {natural.height}"
			preserveAspectRatio="none"
			onpointerdown={down}
			onpointermove={move}
			onpointerup={up}
		>
			{#each figures as f (f.id)}
				<g class="figure" class:picked={picked === f.id}>
					<rect x={f.left} y={f.top} width={f.width} height={f.height} />
					<text
						x={f.left + 8}
						y={f.top + 8}
						dominant-baseline="hanging"
						font-size={Math.max(24, natural.width / 40)}>{f.number}</text
					>
				</g>
			{/each}
			{#if crop}
				<rect class="selection" x={crop.left} y={crop.top} width={crop.width} height={crop.height} />
			{/if}
		</svg>
	{/if}
</div>
<div class="crop-status">
	<span>
		{crop
			? `${crop.left}, ${crop.top} · ${crop.width} × ${crop.height} px`
			: $t('family.wholePage')}
	</span>
	{#if crop}
		<button type="button" class="vui-btn vui-btn-ghost vui-btn-sm" onclick={() => (crop = null)}>
			{$t('family.wholePage')}
		</button>
	{/if}
</div>
<input type="hidden" name="cropLeft" value={crop?.left ?? ''} />
<input type="hidden" name="cropTop" value={crop?.top ?? ''} />
<input type="hidden" name="cropWidth" value={crop?.width ?? ''} />
<input type="hidden" name="cropHeight" value={crop?.height ?? ''} />

<style>
	.cropper {
		position: relative;
		display: inline-block;
		max-width: 100%;
	}
	.cropper img {
		display: block;
		max-width: 100%;
		user-select: none;
		image-orientation: none;
	}
	.overlay {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
		cursor: crosshair;
		touch-action: none;
	}
	.selection {
		fill: color-mix(in srgb, var(--vui-accent) 25%, transparent);
		stroke: var(--vui-accent);
		stroke-width: 2;
		vector-effect: non-scaling-stroke;
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
	.crop-status {
		display: flex;
		align-items: center;
		gap: 8px;
		font-size: 12px;
		color: var(--vui-text-muted);
	}
</style>
