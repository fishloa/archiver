<script lang="ts">
	import StatusBadge from '$lib/components/StatusBadge.svelte';
	import { parseSourceMeta, nadTranslation } from '$lib/archives';
	import { ArrowLeft, Download, ChevronDown, Clock, CircleCheckBig, AlertTriangle, Play } from 'lucide-svelte';
	import type { PipelineEvent, JobStat } from '$lib/server/api';

	let { data } = $props();
	let record = $derived(data.record);
	let pages = $derived(data.pages);
	let timeline = $derived(data.timeline);

	let sourceMeta = $derived(parseSourceMeta(record.rawSourceMetadata));
	let nadNumber = $derived(sourceMeta.nad_number ? String(sourceMeta.nad_number) : null);
	let fondName = $derived(sourceMeta.fond_name ?? null);
	let nadEnglish = $derived(nadTranslation(record.sourceSystem, nadNumber));

	let rawOpen = $state(false);
	let timelineOpen = $state(false);
	let rawFormatted = $derived(
		record.rawSourceMetadata
			? JSON.stringify(JSON.parse(record.rawSourceMetadata), null, 2)
			: null
	);

	function formatDate(iso: string): string {
		return new Date(iso).toLocaleString();
	}

	function formatDuration(startIso: string, endIso: string): string {
		const ms = new Date(endIso).getTime() - new Date(startIso).getTime();
		if (ms < 1000) return `${ms}ms`;
		const secs = Math.floor(ms / 1000);
		if (secs < 60) return `${secs}s`;
		const mins = Math.floor(secs / 60);
		const remSecs = secs % 60;
		if (mins < 60) return `${mins}m ${remSecs}s`;
		const hrs = Math.floor(mins / 60);
		const remMins = mins % 60;
		return `${hrs}h ${remMins}m`;
	}

	const stageOrder = ['ingest', 'ocr', 'pdf_build', 'translation', 'entities'];
	const stageLabels: Record<string, string> = {
		ingest: 'Ingest',
		ocr: 'OCR',
		pdf_build: 'PDF Build',
		translation: 'Translation',
		entities: 'Entities'
	};
	const stageColors: Record<string, string> = {
		ingest: '#6ec6f0',
		ocr: '#f59e0b',
		pdf_build: '#f472b6',
		translation: '#38bdf8',
		entities: '#c084fc'
	};

	interface StageSummary {
		stage: string;
		label: string;
		color: string;
		started: string | null;
		completed: string | null;
		failed: string | null;
		duration: string | null;
		detail: string | null;
	}

	let stageSummaries = $derived.by(() => {
		const events = timeline?.events ?? [];
		const summaries: StageSummary[] = [];
		for (const stage of stageOrder) {
			const stageEvents = events.filter((e: PipelineEvent) => e.stage === stage);
			if (stageEvents.length === 0) continue;
			const started = stageEvents.find((e: PipelineEvent) => e.event === 'started');
			const completed = stageEvents.find((e: PipelineEvent) => e.event === 'completed');
			const failed = stageEvents.find((e: PipelineEvent) => e.event === 'failed');
			const startedAt = started?.created_at ?? null;
			const completedAt = completed?.created_at ?? null;
			const failedAt = failed?.created_at ?? null;
			let duration: string | null = null;
			if (startedAt && completedAt) {
				duration = formatDuration(startedAt, completedAt);
			} else if (startedAt && failedAt) {
				duration = formatDuration(startedAt, failedAt);
			}
			summaries.push({
				stage,
				label: stageLabels[stage] ?? stage,
				color: stageColors[stage] ?? '#888',
				started: startedAt,
				completed: completedAt,
				failed: failedAt,
				duration,
				detail: completed?.detail ?? started?.detail ?? failed?.detail ?? null
			});
		}
		return summaries;
	});

	let metaFields = $derived([
		{ label: 'Reference Code', value: record.referenceCode },
		{ label: 'Date Range', value: record.dateRangeText },
		{ label: 'Inventory Number', value: record.inventoryNumber },
		{ label: 'Call Number', value: record.callNumber },
		{ label: 'Container', value: [record.containerType, record.containerNumber].filter(Boolean).join(' ') || null },
		{ label: 'Finding Aid', value: record.findingAidNumber },
		{ label: 'Source', value: [record.sourceSystem, record.sourceRecordId].filter(Boolean).join(' / ') || null },
		{ label: 'Index Terms', value: record.indexTerms },
		{ label: 'Added', value: formatDate(record.createdAt) },
		{ label: 'Updated', value: formatDate(record.updatedAt) }
	]);
</script>

<svelte:head>
	<title>{record.title ?? 'Record'} &ndash; Archiver</title>
</svelte:head>

<div class="mb-6">
	<a href="/" class="vui-btn vui-btn-ghost vui-btn-sm">
		<ArrowLeft size={13} strokeWidth={2} /> All records
	</a>
</div>

<div class="vui-card mb-6 vui-animate-fade-in">
	<div class="flex items-start gap-3 mb-4">
		<h1 class="text-[length:var(--vui-text-2xl)] font-extrabold tracking-tight">{record.titleEn ?? record.title ?? '(untitled)'}</h1>
		<StatusBadge status={record.status} />
	</div>
	{#if record.titleEn && record.title}
		<p class="text-text-sub text-[length:var(--vui-text-sm)] mb-2 italic">{record.title}</p>
	{/if}

	{#if fondName || nadNumber}
		<div class="mb-4 px-3 py-2 rounded-md bg-surface-alt border border-border">
			{#if fondName}
				<div class="text-[length:var(--vui-text-sm)] font-medium text-text">{fondName}</div>
			{/if}
			{#if nadEnglish}
				<div class="text-[length:var(--vui-text-sm)] font-semibold text-accent">{nadEnglish}</div>
			{/if}
			{#if nadNumber}
				<div class="text-[length:var(--vui-text-xs)] text-text-sub mt-0.5">NAD {nadNumber}</div>
			{/if}
		</div>
	{/if}

	{#if record.descriptionEn || record.description}
		<p class="text-text mb-2">{record.descriptionEn ?? record.description}</p>
		{#if record.descriptionEn && record.description}
			<p class="text-text-sub text-[length:var(--vui-text-sm)] mb-6 italic">{record.description}</p>
		{:else}
			<div class="mb-6"></div>
		{/if}
	{/if}

	<div class="grid grid-cols-1 gap-x-8 gap-y-2 sm:grid-cols-2">
		{#each metaFields as field}
			{#if field.value}
				<div class="flex gap-2 text-[length:var(--vui-text-sm)]">
					<span class="font-semibold text-accent">{field.label}:</span>
					<span class="text-text">{field.value}</span>
				</div>
			{/if}
		{/each}
	</div>

	{#if record.pdfAttachmentId}
		<div class="mt-6 pt-4 border-t border-border">
			<a href="/api/records/{record.id}/pdf" class="vui-btn vui-btn-primary" target="_blank">
				<Download size={13} strokeWidth={2} /> Download PDF
			</a>
		</div>
	{/if}

	{#if rawFormatted}
		<div class="mt-6 pt-4 border-t border-border">
			<button
				class="flex items-center gap-1.5 text-[length:var(--vui-text-sm)] font-medium text-text-sub vui-transition hover:text-text cursor-pointer"
				onclick={() => rawOpen = !rawOpen}
			>
				<ChevronDown
					size={14}
					strokeWidth={2}
					class="vui-transition {rawOpen ? 'rotate-0' : '-rotate-90'}"
				/>
				Source Metadata
			</button>
			{#if rawOpen}
				<pre class="mt-2 p-3 rounded-md bg-bg-deep border border-border text-[length:var(--vui-text-xs)] text-text-sub overflow-x-auto font-mono">{rawFormatted}</pre>
			{/if}
		</div>
	{/if}
</div>

{#if stageSummaries.length > 0}
	<div class="vui-card mb-6 vui-animate-fade-in">
		<button
			class="flex items-center gap-1.5 text-[length:var(--vui-text-sm)] font-semibold text-text-sub vui-transition hover:text-text cursor-pointer w-full"
			onclick={() => timelineOpen = !timelineOpen}
		>
			<ChevronDown
				size={14}
				strokeWidth={2}
				class="vui-transition {timelineOpen ? 'rotate-0' : '-rotate-90'}"
			/>
			<Clock size={14} strokeWidth={2} />
			Pipeline Timeline
			<span class="ml-auto text-[length:var(--vui-text-xs)] text-text-muted font-normal">
				{stageSummaries.filter(s => s.completed).length}/{stageSummaries.length} stages complete
			</span>
		</button>

		{#if timelineOpen}
			<div class="mt-4 space-y-1">
				{#each stageSummaries as stage}
					<div class="flex items-center gap-3 py-2 px-3 rounded-md bg-bg-deep border border-border">
						<!-- Status icon -->
						<div class="flex-shrink-0">
							{#if stage.failed}
								<AlertTriangle size={14} color="var(--vui-danger)" strokeWidth={2} />
							{:else if stage.completed}
								<CircleCheckBig size={14} color="#34d399" strokeWidth={2} />
							{:else if stage.started}
								<Play size={14} color={stage.color} strokeWidth={2} class="animate-pulse" />
							{/if}
						</div>

						<!-- Stage name -->
						<div class="w-24 flex-shrink-0">
							<span class="font-semibold text-[length:var(--vui-text-sm)]" style="color: {stage.color}">
								{stage.label}
							</span>
						</div>

						<!-- Timing -->
						<div class="flex-1 flex items-center gap-4 text-[length:var(--vui-text-xs)] text-text-sub tabular-nums">
							{#if stage.started}
								<span>Started {formatDate(stage.started)}</span>
							{/if}
							{#if stage.completed}
								<span class="text-[#34d399]">Completed {formatDate(stage.completed)}</span>
							{/if}
							{#if stage.failed}
								<span class="text-danger">Failed {formatDate(stage.failed)}</span>
							{/if}
						</div>

						<!-- Duration -->
						{#if stage.duration}
							<div class="flex-shrink-0 text-[length:var(--vui-text-xs)] font-medium text-text tabular-nums">
								{stage.duration}
							</div>
						{/if}
					</div>
				{/each}
			</div>

			<!-- Job breakdown -->
			{#if timeline.jobs.length > 0}
				<div class="mt-4 pt-3 border-t border-border">
					<div class="text-[length:var(--vui-text-xs)] font-semibold text-text-sub mb-2">Job Breakdown</div>
					<div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-2">
						{#each timeline.jobs as job}
							<div class="px-2 py-1.5 rounded bg-bg-deep border border-border text-[length:var(--vui-text-xs)]">
								<div class="font-medium text-text">{job.kind.replace(/_/g, ' ')}</div>
								<div class="flex items-center gap-2 text-text-sub">
									<span class="{job.status === 'completed' ? 'text-[#34d399]' : job.status === 'failed' ? 'text-danger' : 'text-text-sub'}">
										{job.cnt} {job.status}
									</span>
									{#if job.last_finished && job.first_started}
										<span class="tabular-nums">{formatDuration(job.first_started, job.last_finished)}</span>
									{/if}
								</div>
							</div>
						{/each}
					</div>
				</div>
			{/if}
		{/if}
	</div>
{/if}

{#if pages.length > 0}
	<div class="vui-section-header">Pages ({pages.length})</div>
	<div class="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 vui-stagger">
		{#each pages as page}
			<a
				href="/records/{record.id}/pages/{page.seq}"
				class="group vui-card vui-hover-lift overflow-hidden p-0"
			>
				{#if page.attachmentId}
					<img
						loading="lazy"
						src="/api/files/{page.attachmentId}/thumbnail"
						alt={page.pageLabel ?? `Page ${page.seq}`}
						class="aspect-[3/4] w-full object-cover vui-transition group-hover:opacity-80"
					/>
				{:else}
					<div class="flex aspect-[3/4] items-center justify-center bg-surface">
						<span class="text-text-sub text-[length:var(--vui-text-xs)]">No image</span>
					</div>
				{/if}
				<div class="px-2 py-1.5 text-center text-[length:var(--vui-text-xs)] text-text-sub">
					{page.pageLabel ?? `Page ${page.seq}`}
				</div>
			</a>
		{/each}
	</div>
{/if}
