<script lang="ts">
	import StatusBadge from '$lib/components/StatusBadge.svelte';
	import { parseSourceMeta, nadTranslation } from '$lib/archives';
	import { language, t } from '$lib/i18n';
	import {
		ArrowLeft, Download, FileDown, ChevronDown, Clock,
		CircleCheckBig, AlertTriangle, Play, ExternalLink,
		FileText, Hash, Calendar, Archive, Bookmark, Layers,
		BookmarkCheck, X, Users, RotateCcw, ShieldAlert, Baby, Skull
	} from 'lucide-svelte';
	import type { PipelineEvent, JobStat } from '$lib/server/api';
	import { isKept, keptCount, keptPagesParam, clearKept } from '$lib/kept-pages.svelte';
	import { enhance } from '$app/forms';

	let { data, form } = $props();
	let record = $derived(data.record);
	let pages = $derived(data.pages);
	let timeline = $derived(data.timeline);
	let personMatches = $derived(data.personMatches ?? []);

	let sourceMeta = $derived(parseSourceMeta(record.rawSourceMetadata));
	let nadNumber = $derived(sourceMeta.nad_number ? String(sourceMeta.nad_number) : null);
	let fondName = $derived(sourceMeta.fond_name ?? null);
	let nadEnglish = $derived(nadTranslation(record.sourceSystem, nadNumber));

	let rawOpen = $state(false);
	let timelineOpen = $state(false);
	let exportPages = $state('');
	let adminDropdownOpen = $state(false);
	let confirmAction = $state<string | null>(null);
	let isAdmin = $derived(data.user?.role === 'admin');

	let kCount = $derived(keptCount(record.id));
	let kParam = $derived(keptPagesParam(record.id));
	let rawFormatted = $derived(
		record.rawSourceMetadata
			? JSON.stringify(JSON.parse(record.rawSourceMetadata), null, 2)
			: null
	);

	function formatDate(iso: string): string {
		return new Date(iso).toLocaleString();
	}

	function formatShortDate(iso: string): string {
		return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
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
		ingest: 'Ingest', ocr: 'OCR', pdf_build: 'PDF Build',
		translation: 'Translation', entities: 'Entities'
	};
	const stageColors: Record<string, string> = {
		ingest: '#6ec6f0', ocr: '#f59e0b', pdf_build: '#f472b6',
		translation: '#38bdf8', entities: '#c084fc'
	};

	interface StageSummary {
		stage: string; label: string; color: string;
		started: string | null; completed: string | null; failed: string | null;
		duration: string | null; detail: string | null;
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
			if (startedAt && completedAt) duration = formatDuration(startedAt, completedAt);
			else if (startedAt && failedAt) duration = formatDuration(startedAt, failedAt);
			summaries.push({
				stage, label: stageLabels[stage] ?? stage, color: stageColors[stage] ?? '#888',
				started: startedAt, completed: completedAt, failed: failedAt, duration,
				detail: completed?.detail ?? started?.detail ?? failed?.detail ?? null
			});
		}
		return summaries;
	});

	// Metadata as structured groups
	let metaItems = $derived([
		{ icon: Hash, label: $t('record.reference'), value: record.referenceCode },
		{ icon: Calendar, label: $t('record.dates'), value: record.dateRangeText },
		{ icon: Bookmark, label: $t('record.inventoryNo'), value: record.inventoryNumber },
		{ icon: Layers, label: $t('record.container'), value: [record.containerType, record.containerNumber].filter(Boolean).join(' ') || null },
		{ icon: FileText, label: $t('record.findingAid'), value: record.findingAidNumber },
	].filter(f => f.value));
</script>

<svelte:head>
	<title>{record.title ?? 'Record'} &ndash; Archiver</title>
</svelte:head>

<!-- Header -->
<div class="mb-6 vui-animate-fade-in">
	<!-- Back + breadcrumb -->
	<div class="flex items-center gap-2 mb-3 text-[length:var(--vui-text-xs)] text-text-sub">
		<a href="/" class="vui-btn vui-btn-ghost vui-btn-sm !px-2">
			<ArrowLeft size={13} strokeWidth={2} />
		</a>
		{#if nadEnglish || fondName}
			<span class="truncate">{nadEnglish ?? fondName}</span>
			{#if nadNumber}<span class="text-text-sub">(NAD {nadNumber})</span>{/if}
			<span class="text-text-sub">/</span>
		{/if}
		<span class="text-text-sub truncate">{record.referenceCode ?? `Record ${record.id}`}</span>
	</div>

	<!-- Title row -->
	<div class="flex items-start gap-3">
		<div class="flex-1 min-w-0">
			<h1 class="text-[length:var(--vui-text-2xl)] font-extrabold tracking-tight leading-tight">
				{record.titleEn ?? record.title ?? $t('record.untitled')}
			</h1>
			{#if record.titleEn && record.title}
				<p class="text-text-sub text-[length:var(--vui-text-sm)] mt-1 italic truncate">{record.title}</p>
			{/if}
		</div>
		<StatusBadge status={record.status} />
	</div>

	<!-- Description -->
	{#if record.descriptionEn || record.description}
		<div class="mt-3 text-[length:var(--vui-text-sm)] leading-relaxed">
			<p class="text-text">{record.descriptionEn ?? record.description}</p>
			{#if record.descriptionEn && record.description}
				<p class="text-text-sub mt-1 italic">{record.description}</p>
			{/if}
		</div>
	{/if}
</div>

<!-- Action bar -->
{#if record.sourceUrl || record.pdfAttachmentId || pages.length > 0}
	<div class="flex flex-wrap items-center gap-3 mb-6 py-3 px-4 rounded-lg bg-surface border border-border vui-animate-fade-in">
		{#if record.sourceUrl}
			<a href={record.sourceUrl} class="vui-btn vui-btn-ghost vui-btn-sm" target="_blank" rel="noopener noreferrer">
				<ExternalLink size={13} strokeWidth={2} /> {$t('record.source')}
			</a>
		{/if}
		{#if record.pdfAttachmentId}
			<a href="/api/records/{record.id}/pdf" class="vui-btn vui-btn-primary vui-btn-sm" target="_blank">
				<Download size={13} strokeWidth={2} /> {$t('record.downloadPdf')}
			</a>
		{/if}
		{#if kCount > 0}
			<div class="flex items-center gap-2">
				<a
					href="/api/records/{record.id}/export-pdf?pages={encodeURIComponent(kParam)}"
					class="vui-btn vui-btn-sm !bg-emerald-600 !border-emerald-600 !text-white"
					target="_blank"
				>
					<Download size={13} strokeWidth={2} /> Download {kCount} kept
				</a>
				<button
					class="vui-btn vui-btn-ghost vui-btn-sm text-text-sub"
					onclick={() => clearKept(record.id)}
					title="Clear kept pages"
				>
					<X size={13} strokeWidth={2} />
				</button>
			</div>
		{/if}
		{#if pages.length > 0}
			<div class="flex items-center gap-2 ml-auto">
				<input
					type="text"
					bind:value={exportPages}
					placeholder={$t('record.pagesExportPlaceholder')}
					class="px-2.5 py-1.5 rounded-md border border-border bg-bg-deep text-text text-[length:var(--vui-text-sm)] placeholder:text-text-sub focus:outline-none focus:ring-1 focus:ring-accent w-40"
				/>
				<a
					href={exportPages.trim() ? `/api/records/${record.id}/export-pdf?pages=${encodeURIComponent(exportPages.trim())}` : undefined}
					class="vui-btn vui-btn-ghost vui-btn-sm {exportPages.trim() ? '' : 'opacity-40 pointer-events-none'}"
					target="_blank"
				>
					<FileDown size={13} strokeWidth={2} /> {$t('record.export')}
				</a>
			</div>
		{/if}
	</div>
{/if}

<!-- Admin pipeline reset -->
{#if isAdmin}
	<div class="mb-6 py-3 px-4 rounded-lg bg-surface border border-border vui-animate-fade-in">
		<div class="flex items-center gap-2">
			<ShieldAlert size={14} strokeWidth={2} class="text-text-sub" />
			<span class="text-[length:var(--vui-text-xs)] font-semibold text-text-sub uppercase tracking-wider">{$t('admin.adminActions')}</span>

			<div class="relative ml-auto">
				<button
					class="vui-btn vui-btn-ghost vui-btn-sm"
					onclick={() => adminDropdownOpen = !adminDropdownOpen}
				>
					<RotateCcw size={13} strokeWidth={2} />
					{$t('admin.resetPipeline')}
					<ChevronDown size={12} strokeWidth={2} class="ml-1" />
				</button>

				{#if adminDropdownOpen}
					<!-- svelte-ignore a11y_no_static_element_interactions -->
					<div
						class="absolute right-0 top-full mt-1 w-64 rounded-lg border border-border bg-surface shadow-lg z-20 p-1"
						onmouseleave={() => adminDropdownOpen = false}
					>
						<button
							class="w-full text-left px-3 py-2 rounded-md text-[length:var(--vui-text-sm)] hover:bg-bg-deep vui-transition"
							onclick={() => { confirmAction = 'resetOcr'; adminDropdownOpen = false; }}
						>
							<div class="font-medium">{$t('admin.rerunOcr')}</div>
							<div class="text-[length:var(--vui-text-xs)] text-text-sub">{$t('admin.rerunOcrDesc')}</div>
						</button>
						<button
							class="w-full text-left px-3 py-2 rounded-md text-[length:var(--vui-text-sm)] hover:bg-bg-deep vui-transition"
							onclick={() => { confirmAction = 'resetTranslate'; adminDropdownOpen = false; }}
						>
							<div class="font-medium">{$t('admin.retranslate')}</div>
							<div class="text-[length:var(--vui-text-xs)] text-text-sub">{$t('admin.retranslateDesc')}</div>
						</button>
						<button
							class="w-full text-left px-3 py-2 rounded-md text-[length:var(--vui-text-sm)] hover:bg-bg-deep vui-transition"
							onclick={() => { confirmAction = 'resetEmbed'; adminDropdownOpen = false; }}
						>
							<div class="font-medium">{$t('admin.reembed')}</div>
							<div class="text-[length:var(--vui-text-xs)] text-text-sub">{$t('admin.reembedDesc')}</div>
						</button>
					</div>
				{/if}
			</div>
		</div>

		{#if confirmAction}
			<div class="mt-3 p-3 rounded-md bg-bg-deep border border-border">
				<p class="text-[length:var(--vui-text-sm)] mb-3">
					{#if confirmAction === 'resetOcr'}
						<strong class="text-danger">{$t('admin.destructive')}</strong> {$t('admin.confirmResetOcr')}
					{:else if confirmAction === 'resetTranslate'}
						{$t('admin.confirmResetTranslate')}
					{:else}
						{$t('admin.confirmResetEmbed')}
					{/if}
				</p>
				<div class="flex items-center gap-2">
					<form method="POST" action="?/{confirmAction}" use:enhance={() => {
						return async ({ update }) => {
							confirmAction = null;
							await update();
						};
					}}>
						<button type="submit" class="vui-btn vui-btn-sm {confirmAction === 'resetOcr' ? '!bg-danger !border-danger !text-white' : 'vui-btn-primary'}">
							{$t('admin.confirm')}
						</button>
					</form>
					<button class="vui-btn vui-btn-ghost vui-btn-sm" onclick={() => confirmAction = null}>
						{$t('admin.cancel')}
					</button>
				</div>
			</div>
		{/if}

		{#if form?.success}
			<div class="mt-3 p-2 rounded-md bg-emerald-950/30 border border-emerald-800 text-[length:var(--vui-text-sm)] text-emerald-400">
				{$t('admin.resetSuccess')}
			</div>
		{/if}
		{#if form?.error}
			<div class="mt-3 p-2 rounded-md bg-red-950/30 border border-red-800 text-[length:var(--vui-text-sm)] text-danger">
				{$t('admin.resetError', form.error)}
			</div>
		{/if}
	</div>
{/if}

<!-- Two-column body -->
<div class="flex flex-col lg:flex-row gap-6 vui-animate-fade-in">
	<!-- Main: page thumbnails -->
	<div class="lg:flex-[2] min-w-0">
		{#if pages.length > 0}
			<div class="flex items-baseline justify-between mb-3">
				<h2 class="text-[length:var(--vui-text-sm)] font-semibold text-text-sub">
					{$t('record.pages')} <span class="text-text-sub font-normal">({pages.length})</span>
				</h2>
			</div>
			<div class="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-3 xl:grid-cols-4">
				{#each pages as pg, i}
					<a
						href="/records/{record.id}/pages/{pg.seq}"
						class="group vui-card vui-hover-lift overflow-hidden p-0 relative"
						style="--delay: {Math.min(i * 30, 300)}ms"
					>
						{#if isKept(record.id, pg.seq)}
							<div class="absolute top-1.5 right-1.5 z-10 rounded-full bg-emerald-600 p-0.5 shadow-sm">
								<BookmarkCheck size={12} strokeWidth={2.5} class="text-white" />
							</div>
						{/if}
						{#if pg.attachmentId}
							<img
								loading="lazy"
								src="/api/files/{pg.attachmentId}/thumbnail"
								alt={pg.pageLabel ?? `Page ${pg.seq}`}
								class="aspect-[3/4] w-full object-cover vui-transition group-hover:opacity-80"
							/>
						{:else}
							<div class="flex aspect-[3/4] items-center justify-center bg-surface">
								<span class="text-text-sub text-[length:var(--vui-text-xs)]">{$t('record.noImage')}</span>
							</div>
						{/if}
						<div class="px-2 py-1.5 text-center text-[length:var(--vui-text-xs)] text-text-sub tabular-nums">
							{pg.pageLabel ?? `Page ${pg.seq}`}
						</div>
					</a>
				{/each}
			</div>
		{:else}
			<div class="vui-card flex items-center justify-center h-48">
				<span class="text-text-sub">{$t('record.noPages')}</span>
			</div>
		{/if}
	</div>

	<!-- Sidebar -->
	<div class="lg:flex-1 min-w-0 lg:max-w-xs space-y-4">
		<!-- Metadata -->
		{#if metaItems.length > 0}
			<div class="vui-card p-4">
				<h3 class="text-[length:var(--vui-text-xs)] font-semibold text-text-sub uppercase tracking-wider mb-3">{$t('record.details')}</h3>
				<div class="space-y-2.5">
					{#each metaItems as item}
						{@const MetaIcon = item.icon}
						<div class="flex items-start gap-2.5">
							<MetaIcon size={14} strokeWidth={1.8} class="text-text-sub mt-0.5 flex-shrink-0" />
							<div class="min-w-0">
								<div class="text-[length:var(--vui-text-xs)] text-text-sub">{item.label}</div>
								<div class="text-[length:var(--vui-text-sm)] text-text-sub break-words">{item.value}</div>
							</div>
						</div>
					{/each}
				</div>
				{#if record.indexTerms}
					<div class="mt-3 pt-3 border-t border-border">
						<div class="text-[length:var(--vui-text-xs)] text-text-sub mb-1.5">{$t('record.indexTerms')}</div>
						<div class="flex flex-wrap gap-1.5">
							{#each record.indexTerms as term}
								<span class="px-2 py-0.5 rounded-full bg-bg-deep border border-border text-[length:var(--vui-text-xs)] text-text-sub">{term}</span>
							{/each}
						</div>
					</div>
				{/if}
			</div>
		{/if}

		<!-- People Mentioned -->
		{#if personMatches.length > 0}
			<div class="vui-card p-4">
				<h3 class="text-[length:var(--vui-text-xs)] font-semibold text-text-sub uppercase tracking-wider mb-3 flex items-center gap-1.5">
					<Users size={14} strokeWidth={2} />
					{$t('record.peopleMentioned')}
				</h3>
				<div class="space-y-2.5">
					{#each personMatches as match}
						<div class="px-2.5 py-1.5 -mx-1 rounded-md">
							<a
								href="/family-tree?personId={match.personId}"
								class="group flex items-center gap-2 vui-transition"
							>
								<span class="text-[length:var(--vui-text-sm)] text-text font-medium group-hover:text-accent vui-transition truncate">
									{match.personName}
								</span>
								{#if match.birthYear}
									<span class="flex items-center gap-0.5 text-[length:var(--vui-text-xs)] text-text-sub">
										<Baby size={12} strokeWidth={2} class="text-accent" />
										{match.birthYear}
									</span>
								{/if}
								{#if match.deathYear}
									<span class="flex items-center gap-0.5 text-[length:var(--vui-text-xs)] text-text-sub">
										<Skull size={12} strokeWidth={2} class="text-red-400" />
										{match.deathYear}
									</span>
								{/if}
							</a>
							{#if match.pageSeqs && match.pageSeqs.length > 0}
								<div class="flex items-center gap-1 mt-1 flex-wrap">
									<span class="text-[length:var(--vui-text-xs)] text-text-sub">pg:</span>
									{#each match.pageSeqs as seq}
										<a
											href="/records/{data.record.id}/pages/{seq}"
											class="text-[length:var(--vui-text-xs)] text-accent hover:underline tabular-nums"
										>{seq}</a>
									{/each}
								</div>
							{/if}
						</div>
					{/each}
				</div>
			</div>
		{/if}

		<!-- Source info -->
		<div class="vui-card p-4">
			<h3 class="text-[length:var(--vui-text-xs)] font-semibold text-text-sub uppercase tracking-wider mb-3">{$t('record.sourceInfo')}</h3>
			<div class="space-y-2 text-[length:var(--vui-text-xs)]">
				{#if fondName}
					<div>
						<div class="text-text-sub">{$t('record.fond')}</div>
						<div class="text-text-sub">{fondName}</div>
						{#if nadEnglish}<div class="text-accent font-medium">{nadEnglish}</div>{/if}
					</div>
				{/if}
				<div>
					<div class="text-text-sub">{$t('record.archiveId')}</div>
					<div class="text-text-sub font-mono break-all">{record.sourceRecordId}</div>
				</div>
				<div class="flex gap-4">
					<div>
						<div class="text-text-sub">{$t('record.added')}</div>
						<div class="text-text-sub">{formatShortDate(record.createdAt)}</div>
					</div>
					<div>
						<div class="text-text-sub">{$t('record.updated')}</div>
						<div class="text-text-sub">{formatShortDate(record.updatedAt)}</div>
					</div>
				</div>
			</div>
		</div>

		<!-- Pipeline timeline -->
		{#if stageSummaries.length > 0}
			<div class="vui-card p-4">
				<button
					class="flex items-center gap-1.5 text-[length:var(--vui-text-xs)] font-semibold text-text-sub uppercase tracking-wider vui-transition hover:text-text cursor-pointer w-full"
					onclick={() => timelineOpen = !timelineOpen}
				>
					<ChevronDown size={12} strokeWidth={2} class="vui-transition {timelineOpen ? 'rotate-0' : '-rotate-90'}" />
					<Clock size={12} strokeWidth={2} />
					{$t('record.pipeline')}
					<span class="ml-auto font-normal normal-case tracking-normal">
						{stageSummaries.filter(s => s.completed).length}/{stageSummaries.length}
					</span>
				</button>
				{#if timelineOpen}
					<div class="mt-3 space-y-1.5">
						{#each stageSummaries as stage}
							<div class="flex items-center gap-2 py-1.5 px-2 rounded bg-bg-deep text-[length:var(--vui-text-xs)]">
								<div class="flex-shrink-0">
									{#if stage.failed}
										<AlertTriangle size={12} color="var(--vui-danger)" strokeWidth={2} />
									{:else if stage.completed}
										<CircleCheckBig size={12} color="#34d399" strokeWidth={2} />
									{:else if stage.started}
										<Play size={12} color={stage.color} strokeWidth={2} class="animate-pulse" />
									{/if}
								</div>
								<span class="font-medium" style="color: {stage.color}">{stage.label}</span>
								<span class="ml-auto tabular-nums text-text-sub">{stage.duration ?? ''}</span>
							</div>
						{/each}
					</div>
					{#if timeline.jobs.length > 0}
						<div class="mt-3 pt-2 border-t border-border space-y-1">
							{#each timeline.jobs as job}
								<div class="flex items-center justify-between text-[length:var(--vui-text-xs)] text-text-sub">
									<span>{job.kind.replace(/_/g, ' ')}</span>
									<span class="tabular-nums {job.status === 'completed' ? 'text-[#34d399]' : job.status === 'failed' ? 'text-danger' : ''}">
										{job.cnt} {job.status}
									</span>
								</div>
							{/each}
						</div>
					{/if}
				{/if}
			</div>
		{/if}

		<!-- Raw metadata -->
		{#if rawFormatted}
			<div class="vui-card p-4">
				<button
					class="flex items-center gap-1.5 text-[length:var(--vui-text-xs)] font-semibold text-text-sub uppercase tracking-wider vui-transition hover:text-text cursor-pointer w-full"
					onclick={() => rawOpen = !rawOpen}
				>
					<ChevronDown size={12} strokeWidth={2} class="vui-transition {rawOpen ? 'rotate-0' : '-rotate-90'}" />
					{$t('record.rawMetadata')}
				</button>
				{#if rawOpen}
					<pre class="mt-2 p-2 rounded bg-bg-deep border border-border text-[length:10px] text-text-sub overflow-x-auto font-mono leading-relaxed max-h-64 overflow-y-auto">{rawFormatted}</pre>
				{/if}
			</div>
		{/if}
	</div>
</div>
