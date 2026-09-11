<script lang="ts">
	import { enhance } from '$app/forms';
	import {
		ArrowDown,
		ArrowUp,
		Ban,
		CheckCircle2,
		KeyRound,
		Plus,
		Trash2,
		TriangleAlert
	} from 'lucide-svelte';
	import type { AiImplementation } from '$lib/server/api';

	let { data, form } = $props();

	/**
	 * What each capability is for, and what changing it costs.
	 *
	 * Embedding is called out because it is the one where swapping the model is not reversible by
	 * a config change: every stored vector was produced by one model, and mixing two leaves an
	 * index that returns plausible nonsense with no error anywhere.
	 */
	const CAPABILITIES: { key: string; label: string; note: string; warn?: string }[] = [
		{
			key: 'OCR',
			label: 'Transcription',
			note: 'Turns a scanned page into text. Re-running costs a page charge per page.'
		},
		{
			key: 'TRANSLATION',
			label: 'Translation',
			note: 'Order decides which model new work goes to, and which stored translation is shown. Every translation is kept, so raising a model here shows its work without re-translating.'
		},
		{
			key: 'EMBEDDING',
			label: 'Embedding',
			note: 'Turns text into vectors for search.',
			warn: 'Changing the model means re-embedding everything. Vectors from two models cannot be compared, and a mixed index degrades silently — no error is raised anywhere.'
		}
	];

	let addingTo = $state<string | null>(null);
	let editing = $state<string | null>(null);

	function rows(capability: string): AiImplementation[] {
		return (data.implementations[capability] ?? []) as AiImplementation[];
	}

	function settingsOf(row: AiImplementation): string {
		try {
			const parsed = JSON.parse(row.settings ?? '{}');
			return Object.entries(parsed)
				.map(([k, v]) => `${k}: ${String(v).slice(0, 40)}`)
				.join(' · ');
		} catch {
			return '';
		}
	}
</script>

<div class="stack">
	<header>
		<h1>Models</h1>
		<p class="lede">
			Which model does which job, and which is preferred. The order is the preference: the
			topmost usable implementation takes new work. Credentials are not held here — a row names
			an environment variable and the value stays in the deployment.
		</p>
	</header>

	{#if form?.message}
		<div class="notice" role="alert">
			<TriangleAlert size={14} strokeWidth={2} />
			<span>{form.message}</span>
		</div>
	{/if}

	{#each CAPABILITIES as capability (capability.key)}
		{@const list = rows(capability.key)}
		<section class="capability">
			<div class="capability-head">
				<h2>{capability.label}</h2>
				<button class="vui-btn vui-btn-ghost vui-btn-sm"
					onclick={() => (addingTo = addingTo === capability.key ? null : capability.key)}>
					<Plus size={13} strokeWidth={2} /> Add
				</button>
			</div>
			<p class="note">{capability.note}</p>
			{#if capability.warn}
				<p class="warn"><TriangleAlert size={13} strokeWidth={2} /> {capability.warn}</p>
			{/if}

			<ol class="rows">
				{#each list as row, i (row.id)}
					<li class="row" class:disabled={!row.enabled}>
						<div class="order">
							<form method="POST" action="?/reorder" use:enhance>
								<input type="hidden" name="capability" value={capability.key} />
								<input type="hidden" name="id" value={row.id} />
								<input type="hidden" name="direction" value="up" />
								<button class="move" disabled={i === 0} title="Prefer this more">
									<ArrowUp size={13} strokeWidth={2} />
								</button>
							</form>
							<span class="rank">{i + 1}</span>
							<form method="POST" action="?/reorder" use:enhance>
								<input type="hidden" name="capability" value={capability.key} />
								<input type="hidden" name="id" value={row.id} />
								<input type="hidden" name="direction" value="down" />
								<button class="move" disabled={i === list.length - 1} title="Prefer this less">
									<ArrowDown size={13} strokeWidth={2} />
								</button>
							</form>
						</div>

						<div class="what">
							<div class="title">
								<strong>{row.model}</strong>
								<span class="provider">{row.provider}</span>
								{#if i === 0 && row.enabled && row.credentialPresent}
									<span class="badge preferred">preferred</span>
								{/if}
								{#if !row.enabled}
									<span class="badge off">disabled</span>
								{/if}
								{#if !row.credentialPresent}
									<span class="badge missing" title="{row.credential_env} is not set in the deployment">
										<KeyRound size={11} strokeWidth={2} /> key missing
									</span>
								{/if}
							</div>
							<div class="detail">
								{row.base_url}{row.endpoint_path ?? ''}
								· batch {row.max_batch_size === 1 ? 'one at a time' : row.max_batch_size}
								{#if row.credential_env}· key from {row.credential_env}{/if}
							</div>
							{#if settingsOf(row)}
								<div class="settings">{settingsOf(row)}</div>
							{/if}
						</div>

						<div class="actions">
							<form method="POST" action="?/toggle" use:enhance>
								<input type="hidden" name="id" value={row.id} />
								<input type="hidden" name="enabled" value={(!row.enabled).toString()} />
								<button class="vui-btn vui-btn-ghost vui-btn-sm">
									{#if row.enabled}
										<Ban size={13} strokeWidth={2} /> Disable
									{:else}
										<CheckCircle2 size={13} strokeWidth={2} /> Enable
									{/if}
								</button>
							</form>
							<button class="vui-btn vui-btn-ghost vui-btn-sm"
								onclick={() => (editing = editing === row.id ? null : row.id)}>Edit</button>
							<form method="POST" action="?/remove" use:enhance>
								<input type="hidden" name="id" value={row.id} />
								<button class="vui-btn vui-btn-ghost vui-btn-sm danger" title="Remove">
									<Trash2 size={13} strokeWidth={2} />
								</button>
							</form>
						</div>

						{#if editing === row.id}
							<form class="editor" method="POST" action="?/update" use:enhance>
								<input type="hidden" name="id" value={row.id} />
								<label>Endpoint<input name="baseUrl" value={row.base_url} /></label>
								<label>Path<input name="endpointPath" value={row.endpoint_path ?? ''} /></label>
								<label>Key variable<input name="credentialEnv" value={row.credential_env ?? ''}
									placeholder="none for a local endpoint" /></label>
								<label>Max batch<input name="maxBatchSize" type="number" min="1"
									value={row.max_batch_size} /></label>
								<button class="vui-btn vui-btn-primary vui-btn-sm">Save</button>
							</form>
						{/if}
					</li>
				{/each}
			</ol>

			{#if addingTo === capability.key}
				<form class="editor add" method="POST" action="?/create" use:enhance>
					<input type="hidden" name="capability" value={capability.key} />
					<label>Provider<input name="provider" placeholder="mistral, local, openai…" required /></label>
					<label>Model<input name="model" placeholder="model name as the provider calls it" required /></label>
					<label>Endpoint<input name="baseUrl" placeholder="https://api.example.com" /></label>
					<label>Path<input name="endpointPath" placeholder="/v1/chat/completions" /></label>
					<label>Key variable<input name="credentialEnv" placeholder="leave empty for a local endpoint" /></label>
					<label>Max batch<input name="maxBatchSize" type="number" min="1" value="1" /></label>
					<button class="vui-btn vui-btn-primary vui-btn-sm">Register</button>
				</form>
			{/if}
		</section>
	{/each}
</div>

<style>
	.stack { display: flex; flex-direction: column; gap: 1.75rem; }
	h1 { font-size: var(--vui-text-xl); font-weight: 600; margin: 0; }
	.lede { color: var(--vui-text-sub); font-size: var(--vui-text-sm); margin: 0.4rem 0 0; max-width: 62ch; }
	.capability { border: 1px solid var(--vui-border); border-radius: 0.6rem; padding: 1rem 1.1rem; background: var(--vui-surface); }
	.capability-head { display: flex; align-items: center; justify-content: space-between; }
	h2 { font-size: var(--vui-text-base); font-weight: 600; margin: 0; }
	.note { color: var(--vui-text-sub); font-size: var(--vui-text-xs); margin: 0.3rem 0 0; max-width: 78ch; }
	.warn { display: flex; align-items: flex-start; gap: 0.4rem; color: var(--vui-warning, #b45309); font-size: var(--vui-text-xs); margin: 0.45rem 0 0; max-width: 78ch; }
	.rows { list-style: none; margin: 0.9rem 0 0; padding: 0; display: flex; flex-direction: column; gap: 0.45rem; }
	.row { display: grid; grid-template-columns: auto 1fr auto; gap: 0.75rem; align-items: center; padding: 0.6rem 0.7rem; border: 1px solid var(--vui-border); border-radius: 0.5rem; background: var(--vui-bg-deep); }
	.row.disabled { opacity: 0.55; }
	.order { display: flex; flex-direction: column; align-items: center; gap: 0.1rem; }
	.move { background: none; border: none; color: var(--vui-text-sub); cursor: pointer; padding: 0.1rem; line-height: 0; }
	.move:disabled { opacity: 0.25; cursor: default; }
	.rank { font-size: var(--vui-text-xs); color: var(--vui-text-sub); font-variant-numeric: tabular-nums; }
	.title { display: flex; align-items: center; gap: 0.45rem; flex-wrap: wrap; font-size: var(--vui-text-sm); }
	.provider { color: var(--vui-text-sub); font-size: var(--vui-text-xs); }
	.detail, .settings { color: var(--vui-text-sub); font-size: var(--vui-text-xs); margin-top: 0.15rem; word-break: break-all; }
	.badge { font-size: 0.68rem; padding: 0.05rem 0.4rem; border-radius: 0.3rem; border: 1px solid var(--vui-border); display: inline-flex; align-items: center; gap: 0.2rem; }
	.badge.preferred { border-color: var(--vui-accent); color: var(--vui-accent); }
	.badge.off { color: var(--vui-text-sub); }
	.badge.missing { border-color: var(--vui-danger, #b91c1c); color: var(--vui-danger, #b91c1c); }
	.actions { display: flex; align-items: center; gap: 0.3rem; }
	.actions :global(.danger) { color: var(--vui-danger, #b91c1c); }
	.editor { grid-column: 1 / -1; display: flex; flex-wrap: wrap; gap: 0.6rem; align-items: flex-end; padding-top: 0.7rem; margin-top: 0.4rem; border-top: 1px solid var(--vui-border); }
	.editor.add { border: 1px dashed var(--vui-border); border-radius: 0.5rem; padding: 0.9rem; margin-top: 0.9rem; }
	.editor label { display: flex; flex-direction: column; gap: 0.2rem; font-size: var(--vui-text-xs); color: var(--vui-text-sub); }
	.editor input { padding: 0.35rem 0.5rem; border-radius: 0.35rem; border: 1px solid var(--vui-border); background: var(--vui-bg-deep); color: var(--vui-text); font-size: var(--vui-text-sm); min-width: 11rem; }
	.notice { display: flex; align-items: center; gap: 0.5rem; padding: 0.6rem 0.8rem; border-radius: 0.5rem; border: 1px solid var(--vui-danger, #b91c1c); color: var(--vui-danger, #b91c1c); font-size: var(--vui-text-sm); }
</style>
