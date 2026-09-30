<script lang="ts">
	import { Download, Loader } from 'lucide-svelte';
	import { t } from '$lib/i18n';
	import {
		createUrl,
		describeState,
		errorFrom,
		exportRequest,
		fileUrl,
		nextPollDelay,
		statusUrl,
		type ExportView
	} from '$lib/pdf-export';

	let {
		recordId,
		variant = 'original',
		pages = '',
		label,
		class: klass = 'vui-btn vui-btn-primary vui-btn-sm'
	}: {
		recordId: number;
		variant?: string;
		pages?: string;
		label: string;
		class?: string;
	} = $props();

	// State is written only from the click handler below, never while rendering.
	let working = $state(false);
	let problem = $state<string | null>(null);

	async function readJson(res: Response): Promise<ExportView> {
		if (!res.ok) throw new Error(errorFrom(res.status, await res.text()));
		return (await res.json()) as ExportView;
	}

	async function start() {
		working = true;
		problem = null;
		try {
			let view = await readJson(
				await fetch(createUrl(recordId), {
					method: 'POST',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify(exportRequest(variant, pages))
				})
			);
			for (let attempt = 0; !describeState(view.state, view.error).terminal; attempt++) {
				await new Promise((resolve) => setTimeout(resolve, nextPollDelay(attempt)));
				view = await readJson(await fetch(statusUrl(view.id)));
			}
			const outcome = describeState(view.state, view.error);
			if (!outcome.ok) throw new Error(outcome.message);
			// An attachment, so the page stays where it is and the browser saves the file.
			window.location.assign(fileUrl(view.id));
		} catch (e) {
			problem = e instanceof Error ? e.message : String(e);
		} finally {
			working = false;
		}
	}
</script>

<button type="button" class={klass} disabled={working} onclick={start}>
	{#if working}
		<Loader size={13} strokeWidth={2} class="animate-spin" /> {$t('record.pdfPreparing')}
	{:else}
		<Download size={13} strokeWidth={2} /> {label}
	{/if}
</button>
{#if problem}
	<span class="text-danger text-[length:var(--vui-text-sm)]" role="alert">
		{$t('record.pdfFailed')}: {problem}
	</span>
{/if}
