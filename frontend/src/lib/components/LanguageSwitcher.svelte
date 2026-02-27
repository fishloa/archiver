<script lang="ts">
	import { language, setLanguage, type Lang } from '$lib/i18n';

	let { user = null }: { user?: any } = $props();
	let current = $derived($language);

	async function switchLang(lang: Lang) {
		setLanguage(lang);
		if (user?.authenticated) {
			try {
				await fetch('/api/profile', {
					method: 'PUT',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify({ lang })
				});
			} catch (_) {
				// Best-effort save
			}
		}
	}
</script>

<div class="lang-switcher">
	<button
		class="lang-btn"
		class:active={current === 'en'}
		onclick={() => switchLang('en')}
	>EN</button>
	<button
		class="lang-btn"
		class:active={current === 'de'}
		onclick={() => switchLang('de')}
	>DE</button>
</div>

<style>
	.lang-switcher {
		display: flex;
		border: 1px solid var(--vui-border);
		border-radius: var(--vui-radius-md);
		overflow: hidden;
	}

	.lang-btn {
		padding: 3px 8px;
		font-size: 11px;
		font-weight: 600;
		letter-spacing: 0.03em;
		background: none;
		border: none;
		color: var(--vui-text-muted);
		cursor: pointer;
		transition: all 0.15s ease;
		font-family: inherit;
	}

	.lang-btn:hover {
		color: var(--vui-text);
		background: var(--vui-surface);
	}

	.lang-btn.active {
		color: var(--vui-accent);
		background: var(--vui-accent-dim);
	}

	.lang-btn + .lang-btn {
		border-left: 1px solid var(--vui-border);
	}
</style>
