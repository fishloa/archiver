<script lang="ts">
	import { onMount } from 'svelte';
	import { invalidateAll } from '$app/navigation';
	import {
		CloudUpload,
		Inbox,
		ScanText,
		FileText,
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
		es.onopen = () => {
			connected = true;
		};
		es.onerror = () => {
			connected = false;
		};
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
				class="inline-block w-1.5 h-1.5 rounded-full {connected
					? 'bg-green-500'
					: 'bg-red-500'}"
			></span>
			{connected ? 'Live' : 'Connecting\u2026'}
		</span>
		<span class="text-[length:var(--vui-text-xs)] text-text-muted tabular-nums">
			{fmt(data.stats.totals.records)} records &middot; {fmt(data.stats.totals.pages)} pages
		</span>
	</div>
</div>

<!-- Pipeline SVG Visualization -->
<div class="pipeline-wrapper vui-animate-fade-in">
	<svg
		viewBox="0 0 1100 420"
		xmlns="http://www.w3.org/2000/svg"
		class="w-full"
		style="max-height: 480px"
	>
		<defs>
			<!-- Glow filter for nodes -->
			{#each stageConfig as cfg, i}
				<filter id="glow-{i}" x="-50%" y="-50%" width="200%" height="200%">
					<feGaussianBlur stdDeviation="6" result="blur" />
					<feFlood flood-color={cfg.color} flood-opacity="0.3" />
					<feComposite in2="blur" operator="in" />
					<feMerge>
						<feMergeNode />
						<feMergeNode in="SourceGraphic" />
					</feMerge>
				</filter>
			{/each}

			<!-- Road gradient -->
			<linearGradient id="road-grad" x1="0%" y1="0%" x2="100%" y2="0%">
				<stop offset="0%" stop-color="rgba(255,255,255,0.06)" />
				<stop offset="50%" stop-color="rgba(255,255,255,0.1)" />
				<stop offset="100%" stop-color="rgba(255,255,255,0.06)" />
			</linearGradient>

			<!-- Dashed line pattern -->
			<pattern id="road-dash" width="16" height="4" patternUnits="userSpaceOnUse">
				<rect width="10" height="4" fill="rgba(255,255,255,0.15)" rx="2" />
			</pattern>
		</defs>

		<!-- Road / connecting path -->
		{@const nodeY = 240}
		{@const nodeSpacing = 180}
		{@const startX = 100}

		<!-- Road surface -->
		<path
			d="M {startX} {nodeY}
			   C {startX + 60} {nodeY - 40}, {startX + nodeSpacing - 60} {nodeY + 40}, {startX + nodeSpacing} {nodeY}
			   C {startX + nodeSpacing + 60} {nodeY - 40}, {startX + 2 * nodeSpacing - 60} {nodeY + 40}, {startX + 2 * nodeSpacing} {nodeY}
			   C {startX + 2 * nodeSpacing + 60} {nodeY - 40}, {startX + 3 * nodeSpacing - 60} {nodeY + 40}, {startX + 3 * nodeSpacing} {nodeY}
			   C {startX + 3 * nodeSpacing + 60} {nodeY - 40}, {startX + 4 * nodeSpacing - 60} {nodeY + 40}, {startX + 4 * nodeSpacing} {nodeY}
			   C {startX + 4 * nodeSpacing + 60} {nodeY - 40}, {startX + 5 * nodeSpacing - 60} {nodeY + 40}, {startX + 5 * nodeSpacing} {nodeY}"
			fill="none"
			stroke="url(#road-grad)"
			stroke-width="28"
			stroke-linecap="round"
		/>

		<!-- Dashed center line -->
		<path
			d="M {startX} {nodeY}
			   C {startX + 60} {nodeY - 40}, {startX + nodeSpacing - 60} {nodeY + 40}, {startX + nodeSpacing} {nodeY}
			   C {startX + nodeSpacing + 60} {nodeY - 40}, {startX + 2 * nodeSpacing - 60} {nodeY + 40}, {startX + 2 * nodeSpacing} {nodeY}
			   C {startX + 2 * nodeSpacing + 60} {nodeY - 40}, {startX + 3 * nodeSpacing - 60} {nodeY + 40}, {startX + 3 * nodeSpacing} {nodeY}
			   C {startX + 3 * nodeSpacing + 60} {nodeY - 40}, {startX + 4 * nodeSpacing - 60} {nodeY + 40}, {startX + 4 * nodeSpacing} {nodeY}
			   C {startX + 4 * nodeSpacing + 60} {nodeY - 40}, {startX + 5 * nodeSpacing - 60} {nodeY + 40}, {startX + 5 * nodeSpacing} {nodeY}"
			fill="none"
			stroke="rgba(255,255,255,0.08)"
			stroke-width="2"
			stroke-dasharray="10,8"
		/>

		<!-- Stage nodes and cards -->
		{#each data.stats.stages as stage, i}
			{@const x = startX + i * nodeSpacing}
			{@const cfg = stageConfig[i]}
			{@const cardAbove = i % 2 === 0}
			{@const cardY = cardAbove ? 30 : 290}
			{@const connY1 = cardAbove ? 140 : nodeY + 22}
			{@const connY2 = cardAbove ? nodeY - 22 : 290}

			<!-- Connector dashed line -->
			<line
				x1={x}
				y1={connY1}
				x2={x}
				y2={connY2}
				stroke={cfg.color}
				stroke-width="2"
				stroke-dasharray="6,4"
				opacity="0.5"
			/>

			<!-- Node circle (outer glow) -->
			<circle cx={x} cy={nodeY} r="22" fill={cfg.dimBg} filter="url(#glow-{i})" />

			<!-- Node circle -->
			<circle
				cx={x}
				cy={nodeY}
				r="18"
				fill="var(--vui-surface)"
				stroke={cfg.color}
				stroke-width="2.5"
			/>

			<!-- Node inner dot -->
			<circle cx={x} cy={nodeY} r="6" fill={cfg.color} opacity="0.8">
				{#if stage.records > 0 && i < 5}
					<animate
						attributeName="opacity"
						values="0.4;1;0.4"
						dur="2s"
						repeatCount="indefinite"
					/>
				{/if}
			</circle>

			<!-- Card -->
			<g class="pipeline-card" style="--delay: {i * 80}ms">
				<!-- Card background -->
				<rect
					x={x - 75}
					y={cardY}
					width="150"
					height={stage.jobsPending !== undefined ? 110 : 80}
					rx="10"
					fill="var(--vui-surface)"
					stroke={cfg.borderColor}
					stroke-width="1.5"
				/>

				<!-- Card accent bar -->
				<rect
					x={x - 75}
					y={cardY}
					width="150"
					height="4"
					rx="10"
					fill={cfg.color}
				/>
				<!-- Cover bottom radius of accent bar -->
				<rect
					x={x - 75}
					y={cardY + 2}
					width="150"
					height="2"
					fill={cfg.color}
				/>

				<!-- Stage name -->
				<text
					x={x}
					y={cardY + 24}
					text-anchor="middle"
					fill={cfg.color}
					font-size="13"
					font-weight="700"
					font-family="var(--vui-font-sans)"
				>
					{stage.name}
				</text>

				<!-- Records count -->
				<text
					x={x}
					y={cardY + 46}
					text-anchor="middle"
					fill="var(--vui-text)"
					font-size="22"
					font-weight="800"
					font-family="var(--vui-font-sans)"
				>
					{fmt(stage.records)}
				</text>

				<!-- Label -->
				<text
					x={x}
					y={cardY + 62}
					text-anchor="middle"
					fill="var(--vui-text-muted)"
					font-size="10"
					font-family="var(--vui-font-sans)"
				>
					{stage.records === 1 ? 'record' : 'records'} &middot; {fmt(stage.pages)} pg
				</text>

				<!-- Job stats (if applicable) -->
				{#if stage.jobsPending !== undefined}
					<text
						x={x}
						y={cardY + 82}
						text-anchor="middle"
						fill="var(--vui-text-sub)"
						font-size="10"
						font-family="var(--vui-font-sans)"
					>
						{#if (stage.jobsRunning ?? 0) > 0}
							<tspan fill="#f59e0b">{stage.jobsRunning} running</tspan>
						{/if}
						{#if (stage.jobsPending ?? 0) > 0}
							<tspan fill="var(--vui-text-muted)">
								{(stage.jobsRunning ?? 0) > 0 ? ' \u00B7 ' : ''}{stage.jobsPending} queued
							</tspan>
						{/if}
					</text>
					{#if (stage.jobsFailed ?? 0) > 0}
						<text
							x={x}
							y={cardY + 96}
							text-anchor="middle"
							fill="var(--vui-danger)"
							font-size="10"
							font-family="var(--vui-font-sans)"
						>
							{stage.jobsFailed} failed
						</text>
					{/if}
				{/if}
			</g>
		{/each}
	</svg>
</div>

<!-- Detailed stats cards below -->
<div class="grid grid-cols-2 md:grid-cols-3 gap-4 mt-8 vui-stagger">
	{#each data.stats.stages as stage, i}
		{@const cfg = stageConfig[i]}
		<div
			class="vui-card vui-animate-fade-in p-5"
			style="border-color: {cfg.borderColor}; --delay: {i * 60}ms"
		>
			<div class="flex items-center gap-3 mb-3">
				<div
					class="w-9 h-9 rounded-lg flex items-center justify-center"
					style="background: {cfg.dimBg}"
				>
					<svelte:component this={cfg.icon} size={18} color={cfg.color} strokeWidth={2} />
				</div>
				<div>
					<div class="font-semibold text-[length:var(--vui-text-sm)]" style="color: {cfg.color}">
						{stage.name}
					</div>
				</div>
			</div>

			<div class="grid grid-cols-2 gap-3">
				<div>
					<div
						class="text-[length:var(--vui-text-2xl)] font-extrabold tabular-nums"
						style="color: {cfg.color}"
					>
						{fmt(stage.records)}
					</div>
					<div class="text-[length:var(--vui-text-xs)] text-text-muted">records</div>
				</div>
				<div>
					<div class="text-[length:var(--vui-text-2xl)] font-extrabold tabular-nums text-text">
						{fmt(stage.pages)}
					</div>
					<div class="text-[length:var(--vui-text-xs)] text-text-muted">pages</div>
				</div>
			</div>

			{#if stage.jobsPending !== undefined}
				<div class="mt-3 pt-3 border-t border-border">
					<div class="flex flex-wrap gap-x-4 gap-y-1 text-[length:var(--vui-text-xs)] tabular-nums">
						{#if (stage.jobsCompleted ?? 0) > 0}
							<span class="text-[#34d399]">
								<CircleCheckBig size={11} class="inline -mt-0.5" /> {fmt(stage.jobsCompleted ?? 0)} done
							</span>
						{/if}
						{#if (stage.jobsRunning ?? 0) > 0}
							<span class="text-[#f59e0b]">
								<Loader size={11} class="inline -mt-0.5 animate-spin" /> {fmt(stage.jobsRunning ?? 0)} active
							</span>
						{/if}
						{#if (stage.jobsPending ?? 0) > 0}
							<span class="text-text-sub">
								{fmt(stage.jobsPending ?? 0)} queued
							</span>
						{/if}
						{#if (stage.jobsFailed ?? 0) > 0}
							<span class="text-danger">
								<AlertTriangle size={11} class="inline -mt-0.5" /> {fmt(stage.jobsFailed ?? 0)} failed
							</span>
						{/if}
						{#if (stage.jobsCompleted ?? 0) === 0 && (stage.jobsRunning ?? 0) === 0 && (stage.jobsPending ?? 0) === 0 && (stage.jobsFailed ?? 0) === 0}
							<span class="text-text-muted">No jobs</span>
						{/if}
					</div>
				</div>
			{/if}
		</div>
	{/each}
</div>

<style>
	.pipeline-wrapper {
		overflow-x: auto;
		-webkit-overflow-scrolling: touch;
	}

	.pipeline-card {
		animation: card-fade-in 0.5s ease-out both;
		animation-delay: var(--delay, 0ms);
	}

	@keyframes card-fade-in {
		from {
			opacity: 0;
			transform: translateY(8px);
		}
		to {
			opacity: 1;
			transform: translateY(0);
		}
	}
</style>
