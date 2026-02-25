<script lang="ts">
	import { onMount } from 'svelte';
	import { invalidateAll } from '$app/navigation';
	import {
		CloudUpload,
		Inbox,
		ScanText,
		FileText,
		Languages,
		Tags,
		CircleCheckBig,
		Loader,
		AlertTriangle
	} from 'lucide-svelte';
	import type { PipelineStage } from '$lib/server/api';

	let { data } = $props();
	let connected = $state(false);

	onMount(() => {
		let debounceTimer: ReturnType<typeof setTimeout> | null = null;
		const es = new EventSource('/api/records/events');
		es.addEventListener('record', () => {
			if (debounceTimer) clearTimeout(debounceTimer);
			debounceTimer = setTimeout(() => invalidateAll(), 2000);
		});
		es.onopen = () => { connected = true; };
		es.onerror = () => { connected = false; };
		return () => {
			if (debounceTimer) clearTimeout(debounceTimer);
			es.close();
		};
	});

	const stageConfig = [
		{ icon: CloudUpload, color: '#6ec6f0', dimBg: 'rgba(110,198,240,0.08)', borderColor: 'rgba(110,198,240,0.35)' },
		{ icon: Inbox, color: '#a78bfa', dimBg: 'rgba(167,139,250,0.08)', borderColor: 'rgba(167,139,250,0.35)' },
		{ icon: ScanText, color: '#f59e0b', dimBg: 'rgba(245,158,11,0.08)', borderColor: 'rgba(245,158,11,0.35)' },
		{ icon: FileText, color: '#f472b6', dimBg: 'rgba(244,114,182,0.08)', borderColor: 'rgba(244,114,182,0.35)' },
		{ icon: Languages, color: '#38bdf8', dimBg: 'rgba(56,189,248,0.08)', borderColor: 'rgba(56,189,248,0.35)' },
		{ icon: Tags, color: '#c084fc', dimBg: 'rgba(192,132,252,0.08)', borderColor: 'rgba(192,132,252,0.35)' },
		{ icon: CircleCheckBig, color: '#34d399', dimBg: 'rgba(52,211,153,0.08)', borderColor: 'rgba(52,211,153,0.35)' }
	];

	function fmt(n: number): string {
		return n.toLocaleString();
	}
</script>

<svelte:head>
	<title>Pipeline &ndash; Archiver</title>
</svelte:head>

<div class="flex items-center justify-between mb-8">
	<h1 class="text-[length:var(--vui-text-2xl)] font-extrabold tracking-tight">
		Document Pipeline
	</h1>
	<div class="flex items-center gap-4">
		<span class="flex items-center gap-1.5 text-[length:var(--vui-text-xs)] text-text-sub">
			<span
				class="inline-block w-1.5 h-1.5 rounded-full {connected ? 'bg-green-500' : 'bg-red-500'}"
			></span>
			{connected ? 'Live' : 'Connecting\u2026'}
		</span>
		<span class="text-[length:var(--vui-text-xs)] text-text-muted tabular-nums">
			{fmt(data.stats.totals.records)} records &middot; {fmt(data.stats.totals.pages)} pages
		</span>
	</div>
</div>

<!-- Vertical pipeline -->
<div class="pipeline vui-animate-fade-in">
	{#each data.stats.stages as stage, i}
		{@const cfg = stageConfig[i]}
		{@const hasJobs = stage.jobsPending !== undefined}
		{@const isLast = i === data.stats.stages.length - 1}

		<div class="stage" style="--delay: {i * 60}ms">
			<!-- Node + connector column -->
			<div class="stage-rail">
				<div class="node-ring" style="border-color: {cfg.color}; box-shadow: 0 0 12px {cfg.dimBg}">
					<div class="node-dot {stage.records > 0 && !isLast ? 'animate-pulse' : ''}" style="background: {cfg.color}"></div>
				</div>
				{#if !isLast}
					<div class="connector" style="border-color: {cfg.color}"></div>
				{/if}
			</div>

			<!-- Card -->
			<div class="stage-card" style="border-color: {cfg.borderColor}">
				<!-- Accent bar -->
				<div class="accent-bar" style="background: {cfg.color}"></div>

				<div class="card-body">
					<!-- Header row: icon + name + record/page counts -->
					<div class="card-header">
						<div class="card-icon" style="background: {cfg.dimBg}">
							<svelte:component this={cfg.icon} size={16} color={cfg.color} strokeWidth={2} />
						</div>
						<div class="card-title" style="color: {cfg.color}">{stage.name}</div>
						<div class="card-counts">
							<span class="count-num" style="color: {cfg.color}">{fmt(stage.records)}</span>
							<span class="count-label">records</span>
							<span class="count-sep">&middot;</span>
							<span class="count-num count-pages">{fmt(stage.pages)}</span>
							<span class="count-label">pages</span>
						</div>
					</div>

					<!-- Job stats row -->
					{#if hasJobs}
						<div class="job-stats">
							{#if (stage.jobsCompleted ?? 0) > 0}
								<span class="job-done">
									<CircleCheckBig size={11} class="inline -mt-0.5" /> {fmt(stage.jobsCompleted ?? 0)} done
								</span>
							{/if}
							{#if (stage.jobsRunning ?? 0) > 0}
								<span class="job-active">
									<Loader size={11} class="inline -mt-0.5 animate-spin" /> {fmt(stage.jobsRunning ?? 0)} active
								</span>
							{/if}
							{#if (stage.jobsPending ?? 0) > 0}
								<span class="job-queued">
									{fmt(stage.jobsPending ?? 0)} queued
								</span>
							{/if}
							{#if (stage.jobsFailed ?? 0) > 0}
								<span class="job-failed">
									<AlertTriangle size={11} class="inline -mt-0.5" /> {fmt(stage.jobsFailed ?? 0)} failed
								</span>
							{/if}
							{#if (stage.jobsCompleted ?? 0) === 0 && (stage.jobsRunning ?? 0) === 0 && (stage.jobsPending ?? 0) === 0 && (stage.jobsFailed ?? 0) === 0}
								<span class="job-none">No jobs</span>
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

	/* Job stats */
	.job-stats {
		display: flex;
		flex-wrap: wrap;
		gap: 4px 12px;
		margin-top: 8px;
		padding-top: 8px;
		border-top: 1px solid var(--vui-border);
		font-size: 11px;
		font-variant-numeric: tabular-nums;
	}

	.job-done { color: #34d399; }
	.job-active { color: #f59e0b; }
	.job-queued { color: var(--vui-text-sub); }
	.job-failed { color: var(--vui-danger); }
	.job-none { color: var(--vui-text-muted); }
</style>
