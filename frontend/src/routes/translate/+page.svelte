<script lang="ts">
	import { Languages } from 'lucide-svelte';
	import { t } from '$lib/i18n';

	let { data } = $props();

	let sourceLang = $state('de');
	let sourceText = $state('');
	let translatedText = $state('');
	let loading = $state(false);
	let error = $state('');

	const langNames: Record<string, string> = {
		de: 'Deutsch',
		cs: 'Čeština',
		en: 'English'
	};

	let pairs = $derived(data.pairs);

	async function translate() {
		if (!sourceText.trim()) return;

		loading = true;
		error = '';
		translatedText = '';

		try {
			const res = await fetch('/translate', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({
					text: sourceText,
					sourceLang,
					targetLang: 'en'
				})
			});

			if (!res.ok) {
				const body = await res.json().catch(() => ({}));
				throw new Error(body.error || `Translation failed (${res.status})`);
			}

			const result = await res.json();
			translatedText = result.translatedText;
		} catch (e: any) {
			error = e.message || 'Translation failed';
		} finally {
			loading = false;
		}
	}
</script>

<div class="translate-page">
	<div class="page-header">
		<Languages size={24} strokeWidth={1.8} class="text-accent" />
		<h1>{$t('translate.title')}</h1>
	</div>
	<p class="subtitle">{$t('translate.subtitle')}</p>

	<div class="lang-selector">
		<label for="source-lang">{$t('translate.from')}</label>
		<select id="source-lang" bind:value={sourceLang}>
			{#each pairs as pair}
				<option value={pair.source}>{langNames[pair.source] ?? pair.source}</option>
			{/each}
		</select>
		<span class="arrow">→</span>
		<span class="target-lang">{langNames['en']}</span>
	</div>

	<div class="translation-area">
		<div class="text-panel">
			<label for="source-text">{$t('translate.sourceText')}</label>
			<textarea
				id="source-text"
				bind:value={sourceText}
				placeholder={$t('translate.sourcePlaceholder')}
	
			></textarea>
		</div>

		<div class="text-panel">
			<label for="result-text">{$t('translate.result')}</label>
			<textarea
				id="result-text"
				value={translatedText}
				readonly
				placeholder={$t('translate.resultPlaceholder')}
	
			></textarea>
		</div>
	</div>

	{#if error}
		<div class="error-msg">{error}</div>
	{/if}

	<button class="translate-btn" onclick={translate} disabled={loading || !sourceText.trim()}>
		{#if loading}
			<span class="spinner"></span>
			{$t('translate.translating')}
		{:else}
			{$t('translate.translateBtn')}
		{/if}
	</button>
</div>

<style>
	.translate-page {
		max-width: 960px;
		margin: 0;
		display: flex;
		flex-direction: column;
		height: calc(100vh - 80px);
	}

	.page-header {
		display: flex;
		align-items: center;
		gap: 12px;
		margin-bottom: 4px;
	}

	.page-header h1 {
		font-size: var(--vui-text-2xl);
		font-weight: 700;
		margin: 0;
	}

	.subtitle {
		color: var(--vui-text-muted);
		font-size: var(--vui-text-sm);
		margin-bottom: 24px;
	}

	.lang-selector {
		display: flex;
		align-items: center;
		gap: 12px;
		margin-bottom: 20px;
	}

	.lang-selector label {
		font-size: var(--vui-text-sm);
		font-weight: 500;
		color: var(--vui-text-muted);
	}

	.lang-selector select {
		padding: 6px 12px;
		border: 1px solid var(--vui-border);
		border-radius: var(--vui-radius-md);
		background: var(--vui-surface);
		color: var(--vui-text);
		font-size: var(--vui-text-sm);
	}

	.arrow {
		color: var(--vui-text-muted);
		font-size: var(--vui-text-lg);
	}

	.target-lang {
		font-size: var(--vui-text-sm);
		font-weight: 500;
		color: var(--vui-text);
	}

	.translation-area {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 16px;
		margin-bottom: 16px;
		flex: 1;
		min-height: 0;
	}

	.text-panel {
		display: flex;
		flex-direction: column;
		min-height: 0;
	}

	.text-panel label {
		display: block;
		font-size: var(--vui-text-xs);
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: var(--vui-text-muted);
		margin-bottom: 6px;
	}

	.text-panel textarea {
		width: 100%;
		flex: 1;
		min-height: 0;
		padding: 12px;
		border: 1px solid var(--vui-border);
		border-radius: var(--vui-radius-md);
		background: var(--vui-surface);
		color: var(--vui-text);
		font-family: inherit;
		font-size: var(--vui-text-sm);
		line-height: 1.6;
		resize: vertical;
	}

	.text-panel textarea:focus {
		outline: none;
		border-color: var(--vui-accent);
	}

	.text-panel textarea[readonly] {
		background: var(--vui-bg-deep);
		cursor: default;
	}

	.error-msg {
		color: var(--vui-error, #ef4444);
		font-size: var(--vui-text-sm);
		margin-bottom: 12px;
	}

	.translate-btn {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		padding: 10px 24px;
		background: var(--vui-accent);
		color: var(--vui-accent-text, #fff);
		border: none;
		border-radius: var(--vui-radius-md);
		font-size: var(--vui-text-sm);
		font-weight: 600;
		cursor: pointer;
		transition: opacity 0.15s ease;
	}

	.translate-btn:hover:not(:disabled) {
		opacity: 0.9;
	}

	.translate-btn:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}

	.spinner {
		display: inline-block;
		width: 14px;
		height: 14px;
		border: 2px solid currentColor;
		border-right-color: transparent;
		border-radius: 50%;
		animation: spin 0.6s linear infinite;
	}

	@keyframes spin {
		to {
			transform: rotate(360deg);
		}
	}

	@media (max-width: 768px) {
		.translation-area {
			grid-template-columns: 1fr;
		}
	}
</style>
