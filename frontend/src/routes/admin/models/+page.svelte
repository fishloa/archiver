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
	import type { AiImplementation, HtrModel, ProviderApi } from '$lib/server/api';

	let { data, form } = $props();

	/**
	 * Which provider the add form is set to, per capability.
	 *
	 * The form is built from what the backend says each provider needs, so choosing one changes
	 * the fields below it. A provider is a protocol with an adapter behind it — the list is the
	 * backend's to state, and adding one there needs no change here.
	 */
	let chosenProvider = $state<Record<string, string>>({});

	/** Providers that have an adapter for this capability. Nothing else may be offered. */
	function providersFor(capability: string): ProviderApi[] {
		return (data.providers ?? []).filter((p: ProviderApi) => p.capabilities?.[capability]);
	}

	function providerFor(capability: string): ProviderApi | undefined {
		const available = providersFor(capability);
		const chosen = chosenProvider[capability];
		return available.find((p) => p.id === chosen) ?? available[0];
	}

	/** A setting already stored on a row, so editing shows what is actually configured. */
	function settingValue(row: AiImplementation, key: string): unknown {
		try {
			const parsed = typeof row.settings === 'string' ? JSON.parse(row.settings) : row.settings;
			return parsed?.[key];
		} catch {
			return undefined;
		}
	}

	/**
	 * The language rows being edited, per row and setting.
	 *
	 * Held in state rather than read from the DOM so a language can be added or removed without the
	 * form losing what is already typed.
	 */
	let langRows = $state<Record<string, { lang: string; htrId: string }[]>>({});

	function mapKey(rowId: string, settingKey: string): string {
		return `${rowId}::${settingKey}`;
	}

	/** The stored map, as rows, the first time this setting is opened. */
	function rowsFor(row: AiImplementation, settingKey: string) {
		const key = mapKey(row.id, settingKey);
		if (!langRows[key]) {
			const stored = (settingValue(row, settingKey) ?? {}) as Record<string, unknown>;
			langRows[key] = Object.entries(stored).map(([lang, htrId]) => ({
				lang,
				htrId: String(htrId)
			}));
		}
		return langRows[key];
	}

	function addLang(row: AiImplementation, settingKey: string) {
		const key = mapKey(row.id, settingKey);
		langRows[key] = [...rowsFor(row, settingKey), { lang: '', htrId: '' }];
	}

	function removeLang(row: AiImplementation, settingKey: string, at: number) {
		const key = mapKey(row.id, settingKey);
		langRows[key] = rowsFor(row, settingKey).filter((_, i) => i !== at);
	}

	/** ISO 639-1 as the archive stores it, against ISO 639-2 as Transkribus publishes it. */
	const ISO2TO3: Record<string, string> = {
		de: 'deu', cs: 'ces', en: 'eng', fr: 'fra', it: 'ita', la: 'lat', nl: 'nld',
		pl: 'pol', hu: 'hun', sk: 'slk', es: 'spa', pt: 'por', da: 'dan', sv: 'swe',
		no: 'nor', fi: 'fin'
	};

	function models(): HtrModel[] {
		return data.htrModels ?? [];
	}

	function modelById(htrId: string): HtrModel | undefined {
		return models().find((m) => String(m.htrId) === String(htrId));
	}

	/** What to show in the picker: name, error rate and how much it was trained on. */
	function modelLabel(m: HtrModel): string {
		const cer = m.cer === null || m.cer === undefined ? '' : ` · CER ${(m.cer * 100).toFixed(1)}%`;
		const words = m.trainWords ? ` · ${(m.trainWords / 1e6).toFixed(1)}M words` : '';
		return `${m.name} (${m.htrId})${cer}${words}`;
	}

	/**
	 * Whether a chosen model actually covers the language it has been paired with.
	 *
	 * Text Titan II reads twelve languages and Czech is not one of them; asking it for a Czech page
	 * returns confident nonsense and still spends a credit. Said here, before it is saved.
	 */
	function languageWarning(lang: string, htrId: string): string {
		if (!lang || !htrId) return '';
		const model = modelById(htrId);
		if (!model || !model.languages?.length) return '';
		const want = lang.trim().toLowerCase();
		const three = ISO2TO3[want] ?? want;
		const covered = model.languages.some((l) => l.toLowerCase() === want || l.toLowerCase() === three);
		return covered ? '' : `${model.name} does not list ${want} — it covers ${model.languages.join(', ')}`;
	}

	/** How this provider takes more than one item, said plainly. */
	function batchNote(provider: ProviderApi | undefined): string {
		if (!provider) return '';
		switch (provider.batchStyle) {
			case 'ASYNC_JOB':
				return `Submitted as a batch job — up to ${provider.maxBatchSize} requests per submission.`;
			case 'INLINE_ARRAY':
				return `Up to ${provider.maxBatchSize} inputs in a single request.`;
			default:
				return 'One item per request; this provider has no batch facility.';
		}
	}

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
							{@const rowProvider = (data.providers ?? []).find((p: ProviderApi) => p.id === row.provider)}
							<form class="editor" method="POST" action="?/update" use:enhance>
								<input type="hidden" name="id" value={row.id} />
								<dl class="fields">
									<dt><label for="e-base-{row.id}">Endpoint</label></dt>
									<dd><input id="e-base-{row.id}" name="baseUrl" value={row.base_url} /></dd>

									<dt><label for="e-path-{row.id}">Path</label></dt>
									<dd><input id="e-path-{row.id}" name="endpointPath" value={row.endpoint_path ?? ''} /></dd>

									<dt><label for="e-cred-{row.id}">Key variable</label></dt>
									<dd>
										<input id="e-cred-{row.id}" name="credentialEnv" value={row.credential_env ?? ''}
											placeholder="none for a local endpoint" />
									</dd>

									<dt><label for="e-batch-{row.id}">Max batch</label></dt>
									<dd>
										<input id="e-batch-{row.id}" name="maxBatchSize" type="number"
											min={rowProvider?.minBatchSize ?? 1} max={rowProvider?.maxBatchSize ?? 100000}
											value={row.max_batch_size} />
										{#if rowProvider}<p class="hint">{batchNote(rowProvider)}</p>{/if}
									</dd>

									{#each rowProvider?.capabilities?.[capability.key]?.settings ?? [] as setting (setting.key)}
										{@const current = settingValue(row, setting.key) ?? setting.default ?? ''}
										<dt><label for="es-{row.id}-{setting.key}">{setting.label}</label></dt>
										<dd>
											{#if setting.type === 'choice'}
												<select id="es-{row.id}-{setting.key}" name="setting.{setting.key}">
													{#each setting.options as option (option)}
														<option value={option} selected={option === current}>{option}</option>
													{/each}
												</select>
											{:else if setting.type === 'model' && models().length}
												<select id="es-{row.id}-{setting.key}" name="setting.{setting.key}">
													{#each models() as m (m.htrId)}
														<option value={m.htrId} selected={String(m.htrId) === String(current)}>
															{modelLabel(m)}
														</option>
													{/each}
												</select>
											{:else if setting.type === 'modelByLang'}
												<input type="hidden" name="setting-map" value={setting.key} />
												<table class="langmap">
													<thead>
														<tr><th>Language</th><th>Model</th><th></th></tr>
													</thead>
													<tbody>
														{#each rowsFor(row, setting.key) as pair, i (i)}
															<tr>
																<td>
																	<input
																		class="lang"
																		name="setting.{setting.key}.{pair.lang}"
																		type="hidden"
																		value={pair.htrId}
																	/>
																	<input
																		class="lang"
																		aria-label="Language code"
																		placeholder="de"
																		bind:value={pair.lang}
																	/>
																</td>
																<td>
																	{#if models().length}
																		<select bind:value={pair.htrId} aria-label="Model">
																			<option value="">— choose a model —</option>
																			{#each models() as m (m.htrId)}
																				<option value={String(m.htrId)}>{modelLabel(m)}</option>
																			{/each}
																		</select>
																	{:else}
																		<input bind:value={pair.htrId} placeholder="model id" />
																	{/if}
																	{#if languageWarning(pair.lang, pair.htrId)}
																		<p class="warn">
																			<TriangleAlert size="14" /> {languageWarning(pair.lang, pair.htrId)}
																		</p>
																	{/if}
																</td>
																<td>
																	<button
																		type="button"
																		class="vui-btn vui-btn-sm"
																		onclick={() => removeLang(row, setting.key, i)}
																		aria-label="Remove this language"
																	>
																		<Trash2 size="14" />
																	</button>
																</td>
															</tr>
														{/each}
													</tbody>
												</table>
												<button
													type="button"
													class="vui-btn vui-btn-sm"
													onclick={() => addLang(row, setting.key)}
												>
													<Plus size="14" /> Add a language
												</button>
											{:else}
												<input
													id="es-{row.id}-{setting.key}"
													name="setting.{setting.key}"
													type={setting.type === 'integer' ? 'number' : 'text'}
													value={current}
												/>
											{/if}
											{#if setting.help}<p class="hint">{setting.help}</p>{/if}
										</dd>
									{/each}
								</dl>
								<button class="vui-btn vui-btn-primary vui-btn-sm">Save</button>
							</form>
						{/if}
					</li>
				{/each}
			</ol>

			{#if addingTo === capability.key}
				{@const available = providersFor(capability.key)}
				{@const provider = providerFor(capability.key)}
				{#if available.length === 0}
					<p class="note">No provider in this build has an adapter for {capability.label.toLowerCase()}.</p>
				{:else}
					<form class="editor add" method="POST" action="?/create" use:enhance>
						<input type="hidden" name="capability" value={capability.key} />
						<dl class="fields">
							<dt><label for="provider-{capability.key}">Provider</label></dt>
							<dd>
								<select
									id="provider-{capability.key}"
									name="provider"
									bind:value={
										() => provider?.id ?? '',
										(v) => (chosenProvider = { ...chosenProvider, [capability.key]: v })
									}
								>
									{#each available as option (option.id)}
										<option value={option.id}>{option.label}</option>
									{/each}
								</select>
								<p class="hint">{batchNote(provider)}</p>
							</dd>

							<dt><label for="model-{capability.key}">Model</label></dt>
							<dd>
								<input id="model-{capability.key}" name="model" required
									placeholder="model name as the provider calls it" />
							</dd>

							<dt><label for="baseurl-{capability.key}">Endpoint</label></dt>
							<dd>
								<input id="baseurl-{capability.key}" name="baseUrl"
									value={provider?.defaultBaseUrl ?? ''}
									placeholder="https://api.example.com" />
							</dd>

							<dt><label for="path-{capability.key}">Path</label></dt>
							<dd>
								<input id="path-{capability.key}" name="endpointPath"
									value={provider?.capabilities?.[capability.key]?.endpointPath ?? ''} />
							</dd>

							<dt><label for="cred-{capability.key}">Key variable</label></dt>
							<dd>
								<input id="cred-{capability.key}" name="credentialEnv"
									placeholder="leave empty for a local endpoint" />
								<p class="hint">The environment variable holding the key. The key itself is never stored here.</p>
							</dd>

							<dt><label for="batch-{capability.key}">Max batch</label></dt>
							<dd>
								<input id="batch-{capability.key}" name="maxBatchSize" type="number"
									min={provider?.minBatchSize ?? 1} max={provider?.maxBatchSize ?? 1}
									value={provider?.minBatchSize ?? 1} />
							</dd>

							{#each provider?.capabilities?.[capability.key]?.settings ?? [] as setting (setting.key)}
								<dt><label for="s-{capability.key}-{setting.key}">{setting.label}</label></dt>
								<dd>
									{#if setting.type === 'choice'}
										<select id="s-{capability.key}-{setting.key}" name="setting.{setting.key}">
											{#each setting.options as option (option)}
												<option value={option} selected={option === setting.default}>{option}</option>
											{/each}
										</select>
									{:else}
										<input
											id="s-{capability.key}-{setting.key}"
											name="setting.{setting.key}"
											type={setting.type === 'integer' ? 'number' : 'text'}
											value={setting.default ?? ''}
										/>
									{/if}
									{#if setting.help}<p class="hint">{setting.help}</p>{/if}
								</dd>
							{/each}
						</dl>
						<button class="vui-btn vui-btn-primary vui-btn-sm">Register</button>
					</form>
				{/if}
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
	.editor { grid-column: 1 / -1; padding-top: 0.7rem; margin-top: 0.4rem; border-top: 1px solid var(--vui-border); }
	.editor.add { border: 1px dashed var(--vui-border); border-radius: 0.5rem; padding: 0.9rem; margin-top: 0.9rem; }

	/* One field per row. These were laid out as a wrapping flex line, which put six inputs of
	   different lengths on one line and made the form unreadable as soon as a provider added
	   settings of its own. */
	.fields { display: grid; grid-template-columns: minmax(7rem, max-content) minmax(0, 1fr); gap: 0.5rem 0.9rem; align-items: start; margin: 0 0 0.9rem; max-width: 46rem; }
	.fields dt { font-size: var(--vui-text-xs); color: var(--vui-text-sub); padding-top: 0.42rem; }
	.fields dd { margin: 0; min-width: 0; }
	.fields label { font-size: var(--vui-text-xs); color: var(--vui-text-sub); }
	.editor input, .editor select { width: 100%; padding: 0.35rem 0.5rem; border-radius: 0.35rem; border: 1px solid var(--vui-border); background: var(--vui-bg-deep); color: var(--vui-text); font-size: var(--vui-text-sm); }
	.hint { font-size: var(--vui-text-xs); color: var(--vui-text-sub); margin: 0.25rem 0 0; }
	.notice { display: flex; align-items: center; gap: 0.5rem; padding: 0.6rem 0.8rem; border-radius: 0.5rem; border: 1px solid var(--vui-danger, #b91c1c); color: var(--vui-danger, #b91c1c); font-size: var(--vui-text-sm); }

	.langmap { width: 100%; border-collapse: collapse; margin-bottom: 0.4rem; }
	.langmap th { text-align: left; font-size: var(--vui-text-xs); color: var(--vui-text-sub); font-weight: 500; padding: 0 0.4rem 0.2rem 0; }
	.langmap td { padding: 0.15rem 0.4rem 0.15rem 0; vertical-align: top; }
	.langmap td:last-child, .langmap th:last-child { width: 2.5rem; padding-right: 0; }
	.langmap select { width: 100%; }
	.langmap input.lang { width: 5rem; }
	.warn { display: flex; align-items: center; gap: 0.3rem; margin: 0.25rem 0 0; font-size: var(--vui-text-xs); color: var(--vui-warning, #b45309); }
</style>
