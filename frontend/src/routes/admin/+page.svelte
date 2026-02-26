<script lang="ts">
	import { enhance } from '$app/forms';
	import { invalidateAll } from '$app/navigation';
	import {
		ShieldCheck,
		Play,
		Loader,
		AlertTriangle,
		CircleCheckBig,
		Clock,
		RefreshCw
	} from 'lucide-svelte';

	let { data, form } = $props();
	let stats = $derived(data.stats);

	let auditing = $state(false);

	type StatusRow = { status: string; cnt: number };
	type JobRow = { kind: string; status: string; cnt: number };
	type EventRow = { record_id: number; stage: string; event: string; detail: string | null; created_at: string; record_title: string | null };

	let recordsByStatus = $derived((stats.recordsByStatus ?? []) as StatusRow[]);
	let jobsByKindAndStatus = $derived((stats.jobsByKindAndStatus ?? []) as JobRow[]);
	let recentEvents = $derived((stats.recentEvents ?? []) as EventRow[]);

	let anomalies = $derived([
		{ label: 'Stale claimed jobs (>1hr)', count: stats.staleClaimedJobs as number, severity: 'warning' },
		{ label: 'Failed jobs (retriable)', count: stats.failedRetriableJobs as number, severity: 'danger' },
		{ label: 'Stuck ingesting records', count: stats.stuckIngestingRecords as number, severity: 'danger' },
		{ label: 'OCR done, no post-OCR jobs', count: stats.ocrDoneNoPostOcrJobs as number, severity: 'warning' }
	]);

	let totalAnomalies = $derived(anomalies.reduce((sum, a) => sum + a.count, 0));

	function formatDate(iso: string): string {
		return new Date(iso).toLocaleString();
	}

	// Group jobs by kind
	let jobKinds = $derived.by(() => {
		const map = new Map<string, JobRow[]>();
		for (const row of jobsByKindAndStatus) {
			const list = map.get(row.kind) ?? [];
			list.push(row);
			map.set(row.kind, list);
		}
		return [...map.entries()];
	});
</script>

<svelte:head>
	<title>Admin &ndash; Archiver</title>
</svelte:head>

<div class="flex items-center justify-between mb-8">
	<h1 class="text-[length:var(--vui-text-2xl)] font-extrabold tracking-tight flex items-center gap-3">
		<ShieldCheck size={24} strokeWidth={2} class="text-accent" />
		Admin
	</h1>
	<button class="vui-btn vui-btn-ghost vui-btn-sm" onclick={() => invalidateAll()}>
		<RefreshCw size={13} strokeWidth={2} /> Refresh
	</button>
</div>

<!-- Audit Panel -->
<div class="vui-card mb-6 vui-animate-fade-in">
	<div class="flex items-center justify-between mb-4">
		<h2 class="text-[length:var(--vui-text-lg)] font-bold">Pipeline Auditor</h2>
		{#if totalAnomalies > 0}
			<span class="vui-badge vui-badge-warning">
				<AlertTriangle size={11} class="inline" /> {totalAnomalies} anomalies detected
			</span>
		{:else}
			<span class="vui-badge vui-badge-success">
				<CircleCheckBig size={11} class="inline" /> All clear
			</span>
		{/if}
	</div>

	<!-- Anomaly cards -->
	<div class="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
		{#each anomalies as anomaly}
			{@const hasIssue = anomaly.count > 0}
			<div class="px-3 py-2.5 rounded-md border {hasIssue ? 'border-yellow-500/30 bg-yellow-500/5' : 'border-border bg-bg-deep'}">
				<div class="text-[length:var(--vui-text-2xl)] font-extrabold tabular-nums {hasIssue ? 'text-yellow-400' : 'text-text-muted'}">
					{anomaly.count}
				</div>
				<div class="text-[length:var(--vui-text-xs)] text-text-sub">{anomaly.label}</div>
			</div>
		{/each}
	</div>

	<!-- Run Audit Button -->
	<form method="POST" action="?/audit" use:enhance={() => {
		auditing = true;
		return async ({ update }) => {
			await update();
			auditing = false;
			await invalidateAll();
		};
	}}>
		<button type="submit" class="vui-btn vui-btn-primary" disabled={auditing}>
			{#if auditing}
				<Loader size={13} strokeWidth={2} class="animate-spin" /> Running audit...
			{:else}
				<Play size={13} strokeWidth={2} /> Run Pipeline Audit
			{/if}
		</button>
	</form>

	{#if form?.fixed !== undefined}
		<div class="mt-3 px-3 py-2 rounded-md bg-green-500/10 border border-green-500/30 text-[length:var(--vui-text-sm)]">
			<CircleCheckBig size={13} class="inline text-green-400" />
			Audit complete: <strong>{form.fixed}</strong> record(s)/job(s) fixed
		</div>
	{/if}
</div>

<!-- Record Status Breakdown -->
<div class="grid grid-cols-1 md:grid-cols-2 gap-6 mb-6">
	<div class="vui-card vui-animate-fade-in">
		<h2 class="text-[length:var(--vui-text-lg)] font-bold mb-4">Records by Status</h2>
		<div class="space-y-2">
			{#each recordsByStatus as row}
				<div class="flex items-center justify-between py-1.5 px-3 rounded bg-bg-deep border border-border">
					<span class="text-[length:var(--vui-text-sm)] font-medium text-text">{row.status}</span>
					<span class="text-[length:var(--vui-text-sm)] font-bold tabular-nums text-accent">{row.cnt}</span>
				</div>
			{/each}
		</div>
	</div>

	<!-- Jobs by Kind -->
	<div class="vui-card vui-animate-fade-in">
		<h2 class="text-[length:var(--vui-text-lg)] font-bold mb-4">Jobs by Kind</h2>
		<div class="space-y-3">
			{#each jobKinds as [kind, rows]}
				<div>
					<div class="text-[length:var(--vui-text-sm)] font-semibold text-text mb-1">{kind.replace(/_/g, ' ')}</div>
					<div class="flex flex-wrap gap-2">
						{#each rows as row}
							<span class="px-2 py-0.5 rounded text-[length:var(--vui-text-xs)] tabular-nums border border-border
								{row.status === 'completed' ? 'text-green-400' : row.status === 'failed' ? 'text-red-400' : row.status === 'claimed' ? 'text-yellow-400' : 'text-text-sub'}">
								{row.cnt} {row.status}
							</span>
						{/each}
					</div>
				</div>
			{/each}
		</div>
	</div>
</div>

<!-- Recent Pipeline Events -->
<div class="vui-card vui-animate-fade-in">
	<h2 class="text-[length:var(--vui-text-lg)] font-bold mb-4 flex items-center gap-2">
		<Clock size={16} strokeWidth={2} />
		Recent Pipeline Events
	</h2>
	{#if recentEvents.length === 0}
		<p class="text-text-sub text-[length:var(--vui-text-sm)]">No pipeline events yet.</p>
	{:else}
		<div class="overflow-x-auto">
			<table class="w-full text-left text-[length:var(--vui-text-sm)]">
				<thead>
					<tr class="border-b border-border">
						<th class="px-3 py-2 text-[11px] font-semibold uppercase tracking-wide text-text-sub">Time</th>
						<th class="px-3 py-2 text-[11px] font-semibold uppercase tracking-wide text-text-sub">Record</th>
						<th class="px-3 py-2 text-[11px] font-semibold uppercase tracking-wide text-text-sub">Stage</th>
						<th class="px-3 py-2 text-[11px] font-semibold uppercase tracking-wide text-text-sub">Event</th>
						<th class="px-3 py-2 text-[11px] font-semibold uppercase tracking-wide text-text-sub">Detail</th>
					</tr>
				</thead>
				<tbody>
					{#each recentEvents as ev}
						<tr class="border-b border-border">
							<td class="px-3 py-2 text-text-sub whitespace-nowrap tabular-nums">{formatDate(ev.created_at)}</td>
							<td class="px-3 py-2 max-w-[280px]">
								<a href="/records/{ev.record_id}" class="text-accent hover:text-accent-hover hover:underline" title={ev.record_title ?? `#${ev.record_id}`}>
									<span class="text-text-muted">#{ev.record_id}</span>
									{#if ev.record_title}
										<span class="ml-1.5 truncate inline-block max-w-[200px] align-bottom">{ev.record_title}</span>
									{/if}
								</a>
							</td>
							<td class="px-3 py-2 text-text">{ev.stage}</td>
							<td class="px-3 py-2">
								<span class="{ev.event === 'completed' ? 'text-green-400' : ev.event === 'failed' ? 'text-red-400' : 'text-yellow-400'}">
									{ev.event}
								</span>
							</td>
							<td class="px-3 py-2 text-text-sub">{ev.detail ?? ''}</td>
						</tr>
					{/each}
				</tbody>
			</table>
		</div>
	{/if}
</div>
