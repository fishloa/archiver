<script lang="ts">
	import { enhance } from '$app/forms';
	import { PauseCircle, PlayCircle, AlertTriangle } from 'lucide-svelte';
	import type { PipelineGate } from '$lib/server/api';

	let { data } = $props();

	/**
	 * Stages that can be held. A gate stops workers claiming that kind, so its jobs queue up
	 * as pending rather than being cancelled — opening the gate releases the backlog untouched.
	 */
	/**
	 * Stages that can be held, in pipeline order.
	 *
	 * Held by stage, not by job kind. Kinds are the mechanism, but holding one of a stage's
	 * kinds left the others running — holding "Translation (metadata)" did not stop page
	 * translation — so the control appeared to be ignored. An operator holds a stage.
	 */
	const STAGES: { stage: string; note: string }[] = [
		{ stage: 'OCR', note: 'Batched at the provider; held work stays queued at our end' },
		{ stage: 'PDF Build', note: 'Rebuilds the invisible text layer over the scans' },
		{ stage: 'Embedding', note: 'Powers semantic search; built from the original text, not the translation' },
		{ stage: 'Translation', note: 'Pages, on-demand upgrades and record metadata' },
		{ stage: 'Person matching', note: 'Heuristic pass against the family tree' }
	];

	let gates = $derived((data.gates ?? []) as PipelineGate[]);
	let jobRows = $derived(
		((data.stats?.jobsByKindAndStatus ?? []) as { kind: string; status: string; cnt: number }[])
	);

	let stageKinds = $derived((data.stages ?? {}) as Record<string, string[]>);
	let pausedKinds = $derived((data.pausedKinds ?? []) as string[]);

	function kindsOf(stage: string): string[] {
		return stageKinds[stage] ?? [];
	}

	/** A stage is held when every kind it runs is held. */
	function isHeld(stage: string): boolean {
		const kinds = kindsOf(stage);
		return kinds.length > 0 && kinds.every((k) => pausedKinds.includes(k));
	}

	/** Some but not all — worth showing, since it means work is still going through. */
	function isPartiallyHeld(stage: string): boolean {
		const kinds = kindsOf(stage);
		return !isHeld(stage) && kinds.some((k) => pausedKinds.includes(k));
	}

	function reasonFor(stage: string): string | null {
		const g = gates.find((x) => kindsOf(stage).includes(x.kind) && x.paused);
		return g?.reason ?? null;
	}

	function heldBy(stage: string): string | null {
		const g = gates.find((x) => kindsOf(stage).includes(x.kind) && x.paused);
		return g?.updated_by ?? null;
	}

	function queued(stage: string): number {
		const kinds = kindsOf(stage);
		return jobRows
			.filter((r) => kinds.includes(r.kind) && (r.status === 'pending' || r.status === 'claimed'))
			.reduce((n, r) => n + r.cnt, 0);
	}

	let reasons = $state<Record<string, string>>({});
</script>

<div class="gates-intro">
	<p>
		Holding a stage stops every job kind it runs. Nothing is cancelled — work queues up and is
		released, untouched, when the stage is opened again.
	</p>
</div>

<div class="gate-list">
	{#each STAGES as s}
		{@const held = isHeld(s.stage)}
		{@const partial = isPartiallyHeld(s.stage)}
		{@const n = queued(s.stage)}
		<div class="gate-card" class:paused={held || partial}>
			<div class="gate-head">
				<div class="gate-title">
					<span class="gate-label">{s.stage}</span>
					{#if held}
						<span class="badge badge-held"><AlertTriangle size={11} strokeWidth={2.2} /> held</span>
					{:else if partial}
						<span class="badge badge-partial">partly held</span>
					{:else}
						<span class="badge badge-open">running</span>
					{/if}
				</div>
				<div class="gate-queue">
					<span class="queue-num" class:queue-growing={(held || partial) && n > 0}>{n.toLocaleString()}</span>
					<span class="queue-label">queued</span>
				</div>
			</div>

			<div class="gate-note">{s.note}</div>
			<div class="gate-kind">{kindsOf(s.stage).join('  ')}</div>

			{#if (held || partial) && reasonFor(s.stage)}
				<div class="gate-reason">
					Held: {reasonFor(s.stage)}
					{#if heldBy(s.stage)}<span class="gate-by">— {heldBy(s.stage)}</span>{/if}
				</div>
			{/if}

			<form method="POST" action="?/toggle" use:enhance class="gate-form">
				<input type="hidden" name="stage" value={s.stage} />
				<input type="hidden" name="paused" value={(!(held || partial)).toString()} />
				{#if held || partial}
					<button class="vui-btn vui-btn-sm vui-btn-primary" type="submit">
						<PlayCircle size={13} strokeWidth={2} /> Release
					</button>
				{:else}
					<input
						class="vui-input gate-reason-input"
						name="reason"
						placeholder="Why hold this stage?"
						bind:value={reasons[s.stage]}
					/>
					<button class="vui-btn vui-btn-sm vui-btn-danger" type="submit">
						<PauseCircle size={13} strokeWidth={2} /> Hold
					</button>
				{/if}
			</form>
		</div>
	{/each}
</div>

<style>
	.gates-intro {
		margin-bottom: 20px;
		color: var(--vui-text-muted);
		font-size: var(--vui-text-sm);
		max-width: 62ch;
	}
	.gate-list {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
		gap: 14px;
	}
	.gate-card {
		border: 1px solid var(--vui-border);
		border-radius: 10px;
		padding: 14px 16px;
		background: var(--vui-surface, transparent);
	}
	.gate-card.paused {
		border-color: var(--vui-danger, #b45309);
	}
	.gate-head {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 10px;
	}
	.gate-title {
		display: flex;
		align-items: center;
		gap: 8px;
		min-width: 0;
	}
	.gate-label {
		font-weight: 650;
	}
	.badge {
		display: inline-flex;
		align-items: center;
		gap: 3px;
		font-size: 0.66rem;
		font-weight: 600;
		padding: 1px 7px;
		border-radius: 999px;
		text-transform: uppercase;
		letter-spacing: 0.03em;
	}
	.badge-held {
		background: color-mix(in srgb, var(--vui-danger, #b45309) 15%, transparent);
		color: var(--vui-danger, #b45309);
	}
	.badge-partial {
		background: color-mix(in srgb, var(--vui-warning, #d97706) 15%, transparent);
		color: var(--vui-warning, #d97706);
	}
	.badge-open {
		background: color-mix(in srgb, var(--vui-accent, #2563eb) 12%, transparent);
		color: var(--vui-accent, #2563eb);
	}
	.gate-queue {
		text-align: right;
		white-space: nowrap;
	}
	.queue-num {
		font-weight: 700;
		font-variant-numeric: tabular-nums;
	}
	.queue-growing {
		color: var(--vui-danger, #b45309);
	}
	.queue-label {
		font-size: 0.68rem;
		color: var(--vui-text-muted);
		margin-left: 4px;
	}
	.gate-note {
		margin-top: 6px;
		font-size: 0.72rem;
		color: var(--vui-text-muted);
		line-height: 1.4;
	}
	.gate-kind {
		margin-top: 2px;
		font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
		font-size: 0.66rem;
		opacity: 0.55;
	}
	.gate-reason {
		margin-top: 8px;
		font-size: 0.72rem;
		color: var(--vui-danger, #b45309);
	}
	.gate-by {
		opacity: 0.75;
	}
	.gate-form {
		display: flex;
		gap: 6px;
		margin-top: 12px;
	}
	.gate-reason-input {
		flex: 1;
		min-width: 0;
		font-size: 0.75rem;
	}
</style>
