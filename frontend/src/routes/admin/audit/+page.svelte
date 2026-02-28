<script lang="ts">
	import { enhance } from '$app/forms';
	import { invalidateAll } from '$app/navigation';
	import { t } from '$lib/i18n';
	import { Play, Loader, AlertTriangle, CircleCheckBig } from 'lucide-svelte';

	let { data, form } = $props();
	let stats = $derived(data.stats);

	let auditing = $state(false);

	type StatusRow = { status: string; cnt: number };
	type JobRow = { kind: string; status: string; cnt: number };

	let recordsByStatus = $derived((stats.recordsByStatus ?? []) as StatusRow[]);
	let jobsByKindAndStatus = $derived((stats.jobsByKindAndStatus ?? []) as JobRow[]);

	let anomalies = $derived([
		{ label: $t('admin.staleJobs'), count: stats.staleClaimedJobs as number, severity: 'warning' },
		{ label: $t('admin.failedJobs'), count: stats.failedRetriableJobs as number, severity: 'danger' },
		{ label: $t('admin.stuckIngesting'), count: stats.stuckIngestingRecords as number, severity: 'danger' },
		{ label: $t('admin.ocrNoPostJobs'), count: stats.ocrDoneNoPostOcrJobs as number, severity: 'warning' }
	]);

	let totalAnomalies = $derived(anomalies.reduce((sum, a) => sum + a.count, 0));

	let totalRecords = $derived(recordsByStatus.reduce((sum, r) => sum + r.cnt, 0));

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

<!-- Run Audit Button -->
<div class="flex items-center justify-between mb-6">
	<div class="flex items-center gap-3">
		<h2 class="text-[length:var(--vui-text-lg)] font-bold">{$t('admin.pipelineAuditor')}</h2>
		{#if totalAnomalies > 0}
			<span class="vui-badge vui-badge-warning">
				<AlertTriangle size={11} class="inline" /> {$t('admin.anomaliesDetected', totalAnomalies)}
			</span>
		{:else}
			<span class="vui-badge vui-badge-success">
				<CircleCheckBig size={11} class="inline" /> {$t('admin.allClear')}
			</span>
		{/if}
	</div>
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
				<Loader size={13} strokeWidth={2} class="animate-spin" /> {$t('admin.runningAudit')}
			{:else}
				<Play size={13} strokeWidth={2} /> {$t('admin.runAudit')}
			{/if}
		</button>
	</form>
</div>

{#if form?.fixed !== undefined}
	<div class="mb-6 px-4 py-3 rounded-md bg-green-500/10 border border-green-500/30 text-[length:var(--vui-text-sm)]">
		<CircleCheckBig size={13} class="inline text-green-400" />
		{$t('admin.auditComplete', form.fixed)}
	</div>
{/if}

<!-- Anomaly cards -->
<div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
	{#each anomalies as anomaly}
		{@const hasIssue = anomaly.count > 0}
		<div class="vui-card !p-4 {hasIssue ? '!border-yellow-500/30 !bg-yellow-500/5' : ''}">
			<div class="text-[length:var(--vui-text-2xl)] font-extrabold tabular-nums {hasIssue ? 'text-yellow-400' : 'text-text-sub'}">
				{anomaly.count}
			</div>
			<div class="text-[length:var(--vui-text-xs)] text-text-sub mt-1">{anomaly.label}</div>
		</div>
	{/each}
</div>

<!-- Record Status Breakdown -->
<div class="grid grid-cols-1 md:grid-cols-2 gap-6">
	<div class="vui-card vui-animate-fade-in">
		<h2 class="text-[length:var(--vui-text-lg)] font-bold mb-4">{$t('admin.recordsByStatus')}</h2>
		<div class="space-y-2">
			{#each recordsByStatus as row}
				{@const pct = totalRecords > 0 ? (row.cnt / totalRecords) * 100 : 0}
				<div class="flex items-center gap-3 py-2 px-3 rounded bg-bg-deep border border-border">
					<span class="text-[length:var(--vui-text-sm)] font-medium text-text-sub w-32">{row.status}</span>
					<div class="flex-1 h-2 rounded-full bg-border overflow-hidden">
						<div class="h-full rounded-full bg-accent transition-all" style="width: {pct}%"></div>
					</div>
					<span class="text-[length:var(--vui-text-sm)] font-bold tabular-nums text-accent w-12 text-right">{row.cnt}</span>
				</div>
			{/each}
		</div>
	</div>

	<!-- Jobs by Kind -->
	<div class="vui-card vui-animate-fade-in">
		<h2 class="text-[length:var(--vui-text-lg)] font-bold mb-4">{$t('admin.jobsByKind')}</h2>
		<div class="space-y-4">
			{#each jobKinds as [kind, rows]}
				<div>
					<div class="text-[length:var(--vui-text-sm)] font-semibold text-text-sub mb-2">{kind.replace(/_/g, ' ')}</div>
					<div class="flex flex-wrap gap-2">
						{#each rows as row}
							<span class="px-2.5 py-1 rounded text-[length:var(--vui-text-xs)] font-medium tabular-nums border
								{row.status === 'completed' ? 'text-green-400 border-green-500/30 bg-green-500/5' :
								 row.status === 'failed' ? 'text-red-400 border-red-500/30 bg-red-500/5' :
								 row.status === 'claimed' ? 'text-yellow-400 border-yellow-500/30 bg-yellow-500/5' :
								 'text-text-sub border-border'}">
								{row.cnt} {row.status}
							</span>
						{/each}
					</div>
				</div>
			{/each}
		</div>
	</div>
</div>
