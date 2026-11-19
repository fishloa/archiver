<script lang="ts">
	import { t } from '$lib/i18n';
	import type { Hint } from '$lib/server/api';

	let {
		hints,
		scope,
		scopeId,
		pagesQueued = null
	}: {
		hints: Hint[];
		scope: 'page' | 'record';
		scopeId: number;
		pagesQueued?: number | null;
	} = $props();

	let open = $state(false);
</script>

<div class="hints">
	<div class="bar">
		<button type="button" class="toggle vui-btn vui-btn-secondary vui-btn-sm" onclick={() => (open = !open)}>
			{open ? $t('hints.hide') : $t('hints.show')}
		</button>
		<form method="POST" action="?/runHints">
			<input type="hidden" name={scope === 'page' ? 'pageId' : 'recordId'} value={scopeId} />
			<button class="vui-btn vui-btn-ghost vui-btn-sm">{$t('hints.suggest')}</button>
		</form>
		{#if pagesQueued != null}
			<span class="queued">{$t('hints.queued', pagesQueued)}</span>
		{/if}
	</div>
	{#if open}
		{#if hints.length === 0}
			<p class="empty">{$t('hints.none')}</p>
		{:else}
			<ul>
				{#each hints as h (h.id)}
					<li>
						<div class="what">
							<span class="name">{h.name}</span>
							<span class="meta">
								{#if h.birthYear || h.deathYear}{h.birthYear ?? '?'}–{h.deathYear ?? ''}{/if}
								{#if h.figureNumber != null}{$t('link.figure', h.figureNumber)}{/if}
								{#if scope === 'record'}
									<a href="/records/{scopeId}/pages/{h.pageSeq}">{$t('people.page', h.pageSeq)}</a>
								{/if}
							</span>
							{#if h.context}<span class="context">{h.context}</span>{/if}
						</div>
						<form method="POST" action="?/confirmHint">
							<input type="hidden" name="hintId" value={h.id} />
							<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('hints.confirm')}</button>
						</form>
					</li>
				{/each}
			</ul>
		{/if}
	{/if}
</div>

<style>
	.hints {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
	}
	.bar {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		flex-wrap: wrap;
	}
	.queued,
	.empty,
	.meta,
	.context {
		font-size: 0.75rem;
		color: var(--vui-text-muted);
	}
	ul {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
	}
	li {
		display: flex;
		justify-content: space-between;
		align-items: center;
		gap: 0.75rem;
		padding: 0.5rem 0.75rem;
		border: 1px solid var(--vui-border);
		border-radius: 0.5rem;
	}
	.what {
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	.name {
		font-weight: 600;
		color: var(--vui-text);
	}
</style>
