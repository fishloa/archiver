<script lang="ts">
	import { onMount } from 'svelte';
	import { invalidate } from '$app/navigation';
	import {
		CloudDownload,
		Inbox,
		ScanText,
		FileText,
		Languages,
		BrainCircuit,
		CircleCheckBig,
		AlertTriangle
	} from 'lucide-svelte';
	import type { PipelineStage } from '$lib/server/api';
	import { language, t } from '$lib/i18n';

	let { data } = $props();
	let connected = $state(false);
	let lang = $derived($language);

	onMount(() => {
		let debounceTimer: ReturnType<typeof setTimeout> | null = null;
		const es = new EventSource('/api/records/events');
		es.addEventListener('record', () => {
			if (debounceTimer) clearTimeout(debounceTimer);
			debounceTimer = setTimeout(() => invalidate('app:pipeline'), 500);
		});
		es.onopen = () => { connected = true; };
		es.onerror = () => { connected = false; };
		return () => {
			if (debounceTimer) clearTimeout(debounceTimer);
			es.close();
		};
	});

	const MAX_WORKER_SLOTS = 12;

	const stageConfig = $derived([
		{ icon: CloudDownload, color: '#6ec6f0', dimBg: 'rgba(110,198,240,0.08)', borderColor: 'rgba(110,198,240,0.35)', desc: t('pipeline.desc.downloading') },
		{ icon: Inbox, color: '#a78bfa', dimBg: 'rgba(167,139,250,0.08)', borderColor: 'rgba(167,139,250,0.35)', desc: t('pipeline.desc.inbox') },
		{ icon: ScanText, color: '#f59e0b', dimBg: 'rgba(245,158,11,0.08)', borderColor: 'rgba(245,158,11,0.35)', desc: t('pipeline.desc.ocr') },
		{ icon: FileText, color: '#f472b6', dimBg: 'rgba(244,114,182,0.08)', borderColor: 'rgba(244,114,182,0.35)', desc: t('pipeline.desc.pdf') },
		{ icon: Languages, color: '#38bdf8', dimBg: 'rgba(56,189,248,0.08)', borderColor: 'rgba(56,189,248,0.35)', desc: t('pipeline.desc.translation') },
		{ icon: BrainCircuit, color: '#c084fc', dimBg: 'rgba(192,132,252,0.08)', borderColor: 'rgba(192,132,252,0.35)', desc: t('pipeline.desc.embedding') },
		{ icon: CircleCheckBig, color: '#34d399', dimBg: 'rgba(52,211,153,0.08)', borderColor: 'rgba(52,211,153,0.35)', desc: t('pipeline.desc.completed') }
	]);

	function fmt(n: number): string {
		return n.toLocaleString();
	}
</script>

<svelte:head>
	<title>Pipeline &ndash; Archiver</title>
</svelte:head>

<div class="flex items-center justify-between mb-8">
	<h1 class="text-[length:var(--vui-text-2xl)] font-extrabold tracking-tight">
		{t('pipeline.title')}
	</h1>
	<div class="flex items-center gap-4">
		<span class="flex items-center gap-1.5 text-[length:var(--vui-text-xs)] text-text-sub">
			<span
				class="inline-block w-1.5 h-1.5 rounded-full {connected ? 'bg-green-500' : 'bg-red-500'}"
			></span>
			{connected ? t('pipeline.live') : t('pipeline.connecting')}
		</span>
		<span class="text-[length:var(--vui-text-xs)] text-text-sub tabular-nums">
			{fmt(data.stats.totals.records)} {t('pipeline.records')} &middot; {fmt(data.stats.totals.pages)} {t('pipeline.pages')}
		</span>
	</div>
</div>

<!-- Vertical pipeline -->
<div class="pipeline vui-animate-fade-in">
	{#each data.stats.stages as stage, i}
		{@const cfg = stageConfig[i]}
		{@const hasJobs = stage.jobsPending !== undefined}
		{@const isLast = i === data.stats.stages.length - 1}
		{@const running = stage.jobsRunning ?? 0}
		{@const pending = stage.jobsPending ?? 0}
		{@const failed = stage.jobsFailed ?? 0}
		{@const workers = stage.workersConnected ?? 0}
		{@const busy = Math.min(running, workers)}
		{@const idle = Math.max(0, workers - busy)}

		<div class="stage" style="--delay: {i * 60}ms">
			<!-- Node + connector column -->
			<div class="stage-rail">
				<div class="node-ring" style="border-color: {cfg.color}; box-shadow: 0 0 12px {cfg.dimBg}">
					<div class="node-dot {running > 0 || pending > 0 ? 'animate-pulse' : ''}" style="background: {cfg.color}"></div>
				</div>
				{#if !isLast}
					<div class="connector" style="border-color: {cfg.color}"></div>
				{/if}
			</div>

			<!-- Card -->
			<div class="stage-card" style="border-color: {cfg.borderColor}">
				<div class="accent-bar" style="background: {cfg.color}"></div>

				<div class="card-body">
					<!-- Header: icon + name + record/page counts -->
					<div class="card-header">
						<div class="card-icon" style="background: {cfg.dimBg}">
							<svelte:component this={cfg.icon} size={16} color={cfg.color} strokeWidth={2} />
						</div>
						<div>
							<div class="card-title" style="color: {cfg.color}">{stage.name}</div>
							<div class="card-desc">{cfg.desc}</div>
						</div>
						<div class="card-counts">
							<span class="count-num" style="color: {cfg.color}">{fmt(stage.records)}</span>
							<span class="count-label">{t('pipeline.records')}</span>
							<span class="count-sep">&middot;</span>
							<span class="count-num count-pages">{fmt(stage.pages)}</span>
							<span class="count-label">{t('pipeline.pages')}</span>
						</div>
					</div>

					<!-- Page progress bar -->
					{#if stage.pagesTotal && stage.pagesTotal > 0}
						{@const pct = Math.round((stage.pagesDone ?? 0) / stage.pagesTotal * 100)}
						<div class="progress-row">
							<div class="progress-track">
								<div class="progress-fill" style="width: {pct}%; background: {cfg.color}"></div>
							</div>
							<span class="progress-label">
								<span style="color: {cfg.color}">{fmt(stage.pagesDone ?? 0)}</span>
								/ {fmt(stage.pagesTotal)} pages
								<span class="progress-pct" style="color: {cfg.color}">{pct}%</span>
							</span>
						</div>
					{/if}

					<!-- Workers row -->
					{#if hasJobs}
						<div class="workers-row">
							<!-- Worker dots -->
							<div class="worker-dots">
								{#each Array(MAX_WORKER_SLOTS) as _, w}
									{#if w < busy}
										<!-- Busy worker: spinning -->
										<div class="worker-dot worker-busy" style="--dot-color: {cfg.color}">
											<svg viewBox="0 0 20 20" class="worker-spinner">
												<circle cx="10" cy="10" r="7" fill="none" stroke={cfg.color} stroke-width="2.5" opacity="0.2" />
												<circle cx="10" cy="10" r="7" fill="none" stroke={cfg.color} stroke-width="2.5" stroke-dasharray="20 24" stroke-linecap="round" class="spin-arc" />
											</svg>
										</div>
									{:else if w < busy + idle}
										<!-- Idle worker: solid dot -->
										<div class="worker-dot worker-idle" style="background: {cfg.color}"></div>
									{:else}
										<!-- Empty slot -->
										<div class="worker-dot worker-empty"></div>
									{/if}
								{/each}
							</div>

							<!-- Worker label -->
							<div class="worker-label">
								{#if workers > 0}
									<span style="color: {cfg.color}">{busy}/{workers}</span> {t('pipeline.busy')}
								{:else}
									<span class="text-muted">{t('pipeline.noWorkers')}</span>
								{/if}
								{#if failed > 0}
									<span class="failed-label">
										<AlertTriangle size={10} class="inline -mt-0.5" />
										{fmt(failed)} {t('pipeline.failed')}
									</span>
								{/if}
							</div>
						</div>
					{/if}
				</div>
			</div>
		</div>
	{/each}
</div>

<style>
	.pipeline {
		max-width: 640px;
	}

	.stage {
		display: flex;
		gap: 16px;
		animation: stage-in 0.4s ease-out both;
		animation-delay: var(--delay, 0ms);
	}

	@keyframes stage-in {
		from { opacity: 0; transform: translateY(8px); }
		to { opacity: 1; transform: translateY(0); }
	}

	/* Rail: node + vertical connector */
	.stage-rail {
		display: flex;
		flex-direction: column;
		align-items: center;
		flex-shrink: 0;
		width: 40px;
		padding-top: 14px;
	}

	.node-ring {
		width: 32px;
		height: 32px;
		border-radius: 50%;
		border: 2.5px solid;
		background: var(--vui-surface);
		display: flex;
		align-items: center;
		justify-content: center;
		flex-shrink: 0;
		z-index: 1;
	}

	.node-dot {
		width: 10px;
		height: 10px;
		border-radius: 50%;
	}

	.connector {
		flex: 1;
		width: 0;
		min-height: 16px;
		border-left: 2px dashed;
		opacity: 0.4;
	}

	/* Card */
	.stage-card {
		flex: 1;
		min-width: 0;
		border: 1.5px solid;
		border-radius: 10px;
		background: var(--vui-surface);
		overflow: hidden;
		margin-bottom: 8px;
	}

	.accent-bar {
		height: 4px;
	}

	.card-body {
		padding: 12px 16px;
	}

	.card-header {
		display: flex;
		align-items: center;
		gap: 10px;
	}

	.card-icon {
		width: 32px;
		height: 32px;
		border-radius: 8px;
		display: flex;
		align-items: center;
		justify-content: center;
		flex-shrink: 0;
	}

	.card-title {
		font-size: 14px;
		font-weight: 700;
		white-space: nowrap;
	}

	.card-desc {
		font-size: 11px;
		color: var(--vui-text-muted);
		line-height: 1.4;
		margin-top: 2px;
	}

	.card-counts {
		margin-left: auto;
		display: flex;
		align-items: baseline;
		gap: 4px;
		font-variant-numeric: tabular-nums;
		flex-shrink: 0;
	}

	.count-num {
		font-size: 20px;
		font-weight: 800;
		line-height: 1;
	}

	.count-pages {
		color: var(--vui-text);
	}

	.count-label {
		font-size: 10px;
		color: var(--vui-text-muted);
	}

	.count-sep {
		font-size: 10px;
		color: var(--vui-text-muted);
		margin: 0 2px;
	}

	/* Page progress */
	.progress-row {
		margin-top: 10px;
		padding-top: 10px;
		border-top: 1px solid var(--vui-border);
	}

	.progress-track {
		height: 6px;
		border-radius: 3px;
		background: var(--vui-border);
		overflow: hidden;
	}

	.progress-fill {
		height: 100%;
		border-radius: 3px;
		transition: width 0.6s ease;
	}

	.progress-label {
		display: flex;
		align-items: center;
		gap: 4px;
		margin-top: 4px;
		font-size: 11px;
		font-variant-numeric: tabular-nums;
		color: var(--vui-text-sub);
	}

	.progress-pct {
		margin-left: auto;
		font-weight: 600;
	}

	/* Workers row */
	.workers-row {
		margin-top: 10px;
		padding-top: 10px;
		border-top: 1px solid var(--vui-border);
	}

	.worker-dots {
		display: flex;
		gap: 5px;
		flex-wrap: wrap;
	}

	.worker-dot {
		width: 18px;
		height: 18px;
		border-radius: 50%;
		flex-shrink: 0;
	}

	/* Busy: animated spinner */
	.worker-busy {
		position: relative;
	}

	.worker-spinner {
		width: 18px;
		height: 18px;
	}

	.spin-arc {
		animation: worker-spin 0.8s linear infinite;
		transform-origin: center;
	}

	@keyframes worker-spin {
		to { transform: rotate(360deg); }
	}

	/* Idle: solid filled dot */
	.worker-idle {
		opacity: 0.4;
	}

	/* Empty slot: faint ring */
	.worker-empty {
		border: 1.5px dashed var(--vui-border);
		opacity: 0.4;
	}

	/* Worker label text */
	.worker-label {
		margin-top: 6px;
		font-size: 11px;
		font-variant-numeric: tabular-nums;
		color: var(--vui-text-sub);
		display: flex;
		align-items: center;
		gap: 6px;
	}

	.text-muted {
		color: var(--vui-text-muted);
	}

	.queue-label {
		color: var(--vui-text-muted);
	}

	.failed-label {
		color: var(--vui-danger);
	}
</style>
