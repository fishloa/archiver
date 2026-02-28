<script lang="ts">
	import { t } from '$lib/i18n';
	import { Clock } from 'lucide-svelte';

	let { data } = $props();
	let stats = $derived(data.stats);

	type EventRow = { record_id: number; stage: string; event: string; detail: string | null; created_at: string; record_title: string | null };

	let recentEvents = $derived((stats.recentEvents ?? []) as EventRow[]);

	function relativeTime(iso: string): string {
		const now = Date.now();
		const then = new Date(iso).getTime();
		const diffMs = now - then;
		const diffSec = Math.floor(diffMs / 1000);
		if (diffSec < 60) return `${diffSec}s ago`;
		const diffMin = Math.floor(diffSec / 60);
		if (diffMin < 60) return `${diffMin}m ago`;
		const diffHr = Math.floor(diffMin / 60);
		if (diffHr < 24) return `${diffHr}h ago`;
		const diffDay = Math.floor(diffHr / 24);
		return `${diffDay}d ago`;
	}

	function absoluteTime(iso: string): string {
		return new Date(iso).toLocaleString();
	}

	function eventColor(event: string): string {
		if (event === 'completed') return 'text-green-400 border-green-500/30 bg-green-500/5';
		if (event === 'failed') return 'text-red-400 border-red-500/30 bg-red-500/5';
		return 'text-yellow-400 border-yellow-500/30 bg-yellow-500/5';
	}
</script>

<div class="vui-card vui-animate-fade-in">
	<h2 class="text-[length:var(--vui-text-lg)] font-bold mb-4 flex items-center gap-2">
		<Clock size={16} strokeWidth={2} />
		{$t('admin.recentEvents')}
	</h2>
	{#if recentEvents.length === 0}
		<p class="text-text-sub text-[length:var(--vui-text-sm)]">{$t('admin.noEvents')}</p>
	{:else}
		<div class="overflow-x-auto">
			<table class="w-full text-left text-[length:var(--vui-text-sm)]">
				<thead>
					<tr class="border-b border-border">
						<th class="px-4 py-3 text-[11px] font-semibold uppercase tracking-wide text-text-sub">{$t('admin.col.time')}</th>
						<th class="px-4 py-3 text-[11px] font-semibold uppercase tracking-wide text-text-sub">{$t('admin.col.stage')}</th>
						<th class="px-4 py-3 text-[11px] font-semibold uppercase tracking-wide text-text-sub">{$t('admin.col.event')}</th>
						<th class="px-4 py-3 text-[11px] font-semibold uppercase tracking-wide text-text-sub">{$t('admin.col.detail')}</th>
						<th class="px-4 py-3 text-[11px] font-semibold uppercase tracking-wide text-text-sub">{$t('admin.col.record')}</th>
					</tr>
				</thead>
				<tbody>
					{#each recentEvents as ev}
						<tr class="border-b border-border hover:bg-surface/50 transition-colors">
							<td class="px-4 py-3.5 text-text-sub whitespace-nowrap tabular-nums" title={absoluteTime(ev.created_at)}>
								{relativeTime(ev.created_at)}
							</td>
							<td class="px-4 py-3.5 text-text">{ev.stage}</td>
							<td class="px-4 py-3.5">
								<span class="inline-block px-2 py-0.5 rounded text-[length:var(--vui-text-xs)] font-medium border {eventColor(ev.event)}">
									{ev.event}
								</span>
							</td>
							<td class="px-4 py-3.5 text-text-sub">{ev.detail ?? ''}</td>
							<td class="px-4 py-3.5 max-w-[280px]">
								<a href="/records/{ev.record_id}" class="text-accent hover:text-accent-hover hover:underline" title={ev.record_title ?? `#${ev.record_id}`}>
									<span class="font-mono text-text-sub">#{ev.record_id}</span>
									{#if ev.record_title}
										<div class="text-[length:var(--vui-text-xs)] text-text-muted truncate max-w-[250px]">{ev.record_title}</div>
									{/if}
								</a>
							</td>
						</tr>
					{/each}
				</tbody>
			</table>
		</div>
	{/if}
</div>
