<script lang="ts">
	import { enhance } from '$app/forms';
	import { PauseCircle, PlayCircle, AlertTriangle } from 'lucide-svelte';
	import type { PipelineGate } from '$lib/server/api';

	let { data } = $props();

	/**
	 * Stages that can be held. A gate stops workers claiming that kind, so its jobs queue up
	 * as pending rather than being cancelled — opening the gate releases the backlog untouched.
	 */
	const KINDS: { kind: string; label: string; note: string }[] = [
		{ kind: 'ocr_page_mistral', label: 'OCR', note: 'Batched — held work stays queued at our end, not the provider’s' },
		{ kind: 'translate_page', label: 'Translation (pages)', note: 'The slowest stage; safe to hold without blocking search' },
		{ kind: 'translate_record', label: 'Translation (metadata)', note: 'Titles and descriptions' },
		{ kind: 'build_searchable_pdf', label: 'PDF build', note: 'Rebuilds the invisible text layer' },
		{ kind: 'embed_record', label: 'Embedding', note: 'Powers semantic search; built from the original text' },
		{ kind: 'match_persons', label: 'Person matching', note: 'Heuristic pass against the family tree' }
	];

	let gates = $derived((data.gates ?? []) as PipelineGate[]);
	let jobRows = $derived(
		((data.stats?.jobsByKindAndStatus ?? []) as { kind: string; status: string; cnt: number }[])
	);

	function gateFor(kind: string): PipelineGate | undefined {
		return gates.find((g) => g.kind === kind);
	}

	function queued(kind: string): number {
		return jobRows
			.filter((r) => r.kind === kind && (r.status === 'pending' || r.status === 'claimed'))
			.reduce((n, r) => n + r.cnt, 0);
	}

	let reasons = $state<Record<string, string>>({});
</script>

<div class="gates-intro">
	<p>
		Holding a stage stops workers claiming its jobs. Nothing is cancelled — work queues up and
		is released, untouched, when the gate is opened again.
	</p>
</div>

<div class="gate-list">
	{#each KINDS as k}
		{@const gate = gateFor(k.kind)}
		{@const paused = gate?.paused ?? false}
		{@const n = queued(k.kind)}
		<div class="gate-card" class:paused>
			<div class="gate-head">
				<div class="gate-title">
					<span class="gate-label">{k.label}</span>
					{#if paused}
						<span class="badge badge-held">
							<AlertTriangle size={11} strokeWidth={2.2} /> held
						</span>
					{:else}
						<span class="badge badge-open">running</span>
					{/if}
				</div>
				<div class="gate-queue">
					<span class="queue-num" class:queue-growing={paused && n > 0}>{n.toLocaleString()}</span>
					<span class="queue-label">queued</span>
				</div>
			</div>

			<div class="gate-note">{k.note}</div>
			<div class="gate-kind">{k.kind}</div>

			{#if paused && gate?.reason}
				<div class="gate-reason">
					Held: {gate.reason}
					{#if gate.updated_by}<span class="gate-by">— {gate.updated_by}</span>{/if}
				</div>
			{/if}

			<form method="POST" action="?/toggle" use:enhance class="gate-form">
				<input type="hidden" name="kind" value={k.kind} />
				<input type="hidden" name="paused" value={(!paused).toString()} />
				{#if !paused}
					<input
						class="vui-input gate-reason-input"
						name="reason"
						placeholder="Why hold this stage?"
						bind:value={reasons[k.kind]}
					/>
					<button class="vui-btn vui-btn-sm vui-btn-danger" type="submit">
						<PauseCircle size={13} strokeWidth={2} /> Hold
					</button>
				{:else}
					<button class="vui-btn vui-btn-sm vui-btn-primary" type="submit">
						<PlayCircle size={13} strokeWidth={2} /> Release
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
