<script lang="ts">
	import { t } from '$lib/i18n';
	import { cropFromPoints, type Crop } from '$lib/genealogy';

	let { src, crop = $bindable(null) }: { src: string; crop?: Crop | null } = $props();

	let natural = $state({ width: 0, height: 0 });
	let start: { x: number; y: number } | null = null;

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
		(e.currentTarget as Element).setPointerCapture?.(e.pointerId);
	}

	function move(e: PointerEvent) {
		if (start) crop = cropFromPoints(start, point(e), natural.width, natural.height);
	}

	function up(e: PointerEvent) {
		move(e);
		start = null;
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
	.crop-status {
		display: flex;
		align-items: center;
		gap: 8px;
		font-size: 12px;
		color: var(--vui-text-muted);
	}
</style>
