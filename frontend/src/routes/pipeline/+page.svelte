<script lang="ts">
	import { onMount } from 'svelte';
	import { invalidate } from '$app/navigation';
	import {
		Inbox,
		ScanText,
		FileText,
		Languages,
		BrainCircuit,
		CircleCheckBig,
		AlertTriangle,
		Radio
	} from 'lucide-svelte';
	import type { PipelineStage, ScraperEntry } from '$lib/server/api';
	import { language, t } from '$lib/i18n';

	let { data } = $props();
	let connected = $state(false);

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
		{ icon: Inbox, color: '#a78bfa', dimBg: 'rgba(167,139,250,0.08)', borderColor: 'rgba(167,139,250,0.35)', desc: $t('pipeline.desc.inbox') },
		{ icon: ScanText, color: '#f59e0b', dimBg: 'rgba(245,158,11,0.08)', borderColor: 'rgba(245,158,11,0.35)', desc: $t('pipeline.desc.ocr') },
		{ icon: FileText, color: '#f472b6', dimBg: 'rgba(244,114,182,0.08)', borderColor: 'rgba(244,114,182,0.35)', desc: $t('pipeline.desc.pdf') },
		{ icon: Languages, color: '#38bdf8', dimBg: 'rgba(56,189,248,0.08)', borderColor: 'rgba(56,189,248,0.35)', desc: $t('pipeline.desc.translation') },
		{ icon: BrainCircuit, color: '#c084fc', dimBg: 'rgba(192,132,252,0.08)', borderColor: 'rgba(192,132,252,0.35)', desc: $t('pipeline.desc.embedding') },
		{ icon: CircleCheckBig, color: '#34d399', dimBg: 'rgba(52,211,153,0.08)', borderColor: 'rgba(52,211,153,0.35)', desc: $t('pipeline.desc.completed') }
	]);

	/** Backend stages[0] is "Scraping" — skip it, Sources card replaces it */
	const displayStages = $derived(data.stats.stages.slice(1));

	function fmt(n: number): string {
		return n.toLocaleString();
	}
</script>

<svelte:head>
	<title>Pipeline &ndash; Archiver</title>
</svelte:head>

<div class="flex items-center justify-between mb-8">
	<h1 class="text-[length:var(--vui-text-2xl)] font-extrabold tracking-tight">
		{$t('pipeline.title')}
	</h1>
	<div class="flex items-center gap-4">
		<span class="flex items-center gap-1.5 text-[length:var(--vui-text-xs)] text-text-sub">
			<span
				class="inline-block w-1.5 h-1.5 rounded-full {connected ? 'bg-green-500' : 'bg-red-500'}"
			></span>
			{connected ? $t('pipeline.live') : $t('pipeline.connecting')}
		</span>
		<span class="text-[length:var(--vui-text-xs)] text-text-sub tabular-nums">
			{fmt(data.stats.totals.records)} {$t('pipeline.records')} &middot; {fmt(data.stats.totals.pages)} {$t('pipeline.pages')}
		</span>
	</div>
</div>

<!-- Vertical pipeline -->
<div class="pipeline vui-animate-fade-in">
	<!-- Scrapers node -->
	<div class="stage" style="--delay: 0ms">
		<div class="stage-rail">
			<div class="node-ring" style="border-color: #6ec6f0; box-shadow: 0 0 12px rgba(110,198,240,0.08)">
				<div class="node-dot" style="background: #6ec6f0"></div>
			</div>
			<div class="connector" style="border-color: #6ec6f0"></div>
		</div>
		<div class="stage-card" style="border-color: rgba(110,198,240,0.35)">
			<div class="accent-bar" style="background: #6ec6f0"></div>
			<div class="card-body">
				<div class="card-header" style="margin-bottom: 10px">
					<div class="card-icon" style="background: rgba(110,198,240,0.08)">
						<Radio size={16} color="#6ec6f0" strokeWidth={2} />
					</div>
					<div>
						<div class="card-title" style="color: #6ec6f0">{$t('pipeline.scrapers')}</div>
					</div>
				</div>
				{#if data.sources && data.sources.length > 0}
					<div class="flex flex-col gap-1.5">
						{#each data.sources as scraper}
							{@const isActive = scraper.instances.length > 0}
							<div class="source-card" class:source-active={isActive}>
								<div class="flex items-center gap-2.5">
									<span class="source-dot" class:source-dot-active={isActive}></span>
									<span class="source-name">{scraper.name}</span>
									<span class="source-status-label" class:source-status-active={isActive}>
										{isActive ? $t('pipeline.active') : $t('pipeline.idle')}
									</span>
								</div>
								{#if isActive}
									<div class="source-instances">
										{#each scraper.instances as instance}
											<div class="instance-row">
												<span class="instance-id">{instance.scraperId.slice(0, 8)}</span>
												<span class="instance-stats">
													<span class="instance-num">{fmt(instance.recordsIngested)}</span>
													<span class="instance-label">{$t('pipeline.records')}</span>
													<span class="count-sep">&middot;</span>
													<span class="instance-num">{fmt(instance.pagesIngested)}</span>
													<span class="instance-label">{$t('pipeline.pages')}</span>
												</span>
											</div>
										{/each}
									</div>
								{/if}
							</div>
						{/each}
					</div>
				{:else}
					<p class="text-[length:var(--vui-text-xs)] text-text-sub opacity-60">{$t('pipeline.noScrapers')}</p>
				{/if}
			</div>
		</div>
	</div>
	{#each displayStages as stage, i}
		{@const cfg = stageConfig[i]}
		{@const hasJobs = stage.jobsPending !== undefined}
		{@const isLast = i === displayStages.length - 1}
		{@const running = stage.jobsRunning ?? 0}
		{@const pending = stage.jobsPending ?? 0}
		{@const failed = stage.jobsFailed ?? 0}
		{@const workers = stage.workersConnected ?? 0}
		{@const busy = Math.min(running, workers)}
		{@const idle = Math.max(0, workers - busy)}
		{@const StageIcon = cfg.icon}

		<div class="stage" style="--delay: {(i + 1) * 60}ms">
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
							<StageIcon size={16} color={cfg.color} strokeWidth={2} />
						</div>
						<div>
							<div class="card-title" style="color: {cfg.color}">{stage.name}</div>
							<div class="card-desc">{cfg.desc}</div>
						</div>
						<div class="card-counts">
							<span class="count-num" style="color: {cfg.color}">{fmt(stage.records)}</span>
							<span class="count-label">{$t('pipeline.records')}</span>
							<span class="count-sep">&middot;</span>
							<span class="count-num count-pages">{fmt(stage.pages)}</span>
							<span class="count-label">{$t('pipeline.pages')}</span>
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
							{#if stage.workerDetails && stage.workerDetails.length > 0}
								<!-- Per-kind worker entries (e.g. OCR with PaddleOCR + Qwen) -->
								<div class="worker-detail-list">
									{#each stage.workerDetails as wd}
										{@const wdIdle = Math.max(0, wd.workers - wd.busy)}
										<div class="worker-detail-row">
											<div class="worker-detail-header">
												<span class="worker-detail-dot" class:worker-detail-dot-active={wd.workers > 0 && (wd.busy > 0 || wd.pending > 0)}></span>
												<span class="worker-detail-label">{wd.label}</span>
												{#if wd.workers > 0}
													<span class="worker-detail-count" style="color: {cfg.color}">{wd.busy}/{wd.workers}</span>
													<span class="worker-detail-status">{$t('pipeline.busy')}</span>
												{:else}
													<span class="worker-detail-status text-muted">{$t('pipeline.noWorkers')}</span>
												{/if}
												{#if wd.pending > 0}
													<span class="worker-detail-pending">{fmt(wd.pending)} pending</span>
												{/if}
												{#if wd.failed > 0}
													<span class="failed-label">
														<AlertTriangle size={10} class="inline -mt-0.5" />
														{fmt(wd.failed)} {$t('pipeline.failed')}
													</span>
												{/if}
											</div>
											<!-- Worker dots for this kind -->
											<div class="worker-dots">
												{#each Array(wd.workers) as _, w}
													{#if w < wd.busy}
														<div class="worker-dot worker-busy" style="--dot-color: {cfg.color}">
															<svg viewBox="0 0 20 20" class="worker-spinner">
																<circle cx="10" cy="10" r="7" fill="none" stroke={cfg.color} stroke-width="2.5" opacity="0.2" />
																<circle cx="10" cy="10" r="7" fill="none" stroke={cfg.color} stroke-width="2.5" stroke-dasharray="20 24" stroke-linecap="round" class="spin-arc" />
															</svg>
														</div>
													{:else}
														<div class="worker-dot worker-idle" style="background: {cfg.color}"></div>
													{/if}
												{/each}
											</div>
										</div>
									{/each}
								</div>
							{:else}
								<!-- Generic worker dots (non-OCR stages) -->
								<div class="worker-dots">
									{#each Array(MAX_WORKER_SLOTS) as _, w}
										{#if w < busy}
											<div class="worker-dot worker-busy" style="--dot-color: {cfg.color}">
												<svg viewBox="0 0 20 20" class="worker-spinner">
													<circle cx="10" cy="10" r="7" fill="none" stroke={cfg.color} stroke-width="2.5" opacity="0.2" />
													<circle cx="10" cy="10" r="7" fill="none" stroke={cfg.color} stroke-width="2.5" stroke-dasharray="20 24" stroke-linecap="round" class="spin-arc" />
												</svg>
											</div>
										{:else if w < busy + idle}
											<div class="worker-dot worker-idle" style="background: {cfg.color}"></div>
										{:else}
											<div class="worker-dot worker-empty"></div>
										{/if}
									{/each}
								</div>
								<div class="worker-label">
									{#if workers > 0}
										<span style="color: {cfg.color}">{busy}/{workers}</span> {$t('pipeline.busy')}
									{:else}
										<span class="text-muted">{$t('pipeline.noWorkers')}</span>
									{/if}
									{#if failed > 0}
										<span class="failed-label">
											<AlertTriangle size={10} class="inline -mt-0.5" />
											{fmt(failed)} {$t('pipeline.failed')}
										</span>
									{/if}
								</div>
							{/if}
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
		color: var(--vui-text-muted);
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
		color: var(--vui-text-muted);
		display: flex;
		align-items: center;
		gap: 6px;
	}

	.text-muted {
		color: var(--vui-text-muted);
	}

	.failed-label {
		color: var(--vui-danger);
	}

	/* Per-kind worker detail rows */
	.worker-detail-list {
		display: flex;
		flex-direction: column;
		gap: 8px;
	}

	.worker-detail-row {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}

	.worker-detail-header {
		display: flex;
		align-items: center;
		gap: 6px;
		font-size: 11px;
		font-variant-numeric: tabular-nums;
	}

	.worker-detail-dot {
		width: 6px;
		height: 6px;
		border-radius: 50%;
		background: var(--vui-text-muted);
		opacity: 0.4;
		flex-shrink: 0;
	}

	.worker-detail-dot-active {
		background: #34d399;
		opacity: 1;
		animation: source-pulse 2s ease-in-out infinite;
	}

	.worker-detail-label {
		font-weight: 600;
		color: var(--vui-text);
	}

	.worker-detail-count {
		font-weight: 600;
	}

	.worker-detail-status {
		color: var(--vui-text-muted);
	}

	.worker-detail-pending {
		color: var(--vui-text-muted);
		margin-left: auto;
	}

	/* Source cards */
	.source-card {
		border: 1.5px solid var(--vui-border);
		border-radius: 8px;
		background: var(--vui-surface);
		padding: 8px 14px;
	}

	.source-card.source-active {
		border-color: rgba(52, 211, 153, 0.4);
	}

	.source-dot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: var(--vui-text-muted);
		opacity: 0.4;
		flex-shrink: 0;
	}

	.source-dot-active {
		background: #34d399;
		opacity: 1;
		animation: source-pulse 2s ease-in-out infinite;
	}

	@keyframes source-pulse {
		0%, 100% { opacity: 1; }
		50% { opacity: 0.4; }
	}

	.source-name {
		font-size: 13px;
		font-weight: 600;
		color: var(--vui-text);
	}

	.source-status-label {
		margin-left: auto;
		font-size: 11px;
		color: var(--vui-text-muted);
		flex-shrink: 0;
	}

	.source-status-active {
		color: #34d399;
		font-weight: 600;
	}

	.source-instances {
		margin-top: 6px;
		padding-top: 6px;
		border-top: 1px solid var(--vui-border);
		display: flex;
		flex-direction: column;
		gap: 3px;
		padding-left: 20px;
	}

	.instance-row {
		display: flex;
		align-items: baseline;
		gap: 8px;
		font-size: 11px;
	}

	.instance-id {
		color: var(--vui-text-muted);
		font-family: monospace;
		font-size: 10px;
	}

	.instance-stats {
		display: flex;
		align-items: baseline;
		gap: 3px;
		font-variant-numeric: tabular-nums;
	}

	.instance-num {
		font-size: 12px;
		font-weight: 600;
		color: var(--vui-text);
	}

	.instance-label {
		font-size: 10px;
		color: var(--vui-text-muted);
	}
</style>
