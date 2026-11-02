<script lang="ts">
	import { enhance } from '$app/forms';
	import { t } from '$lib/i18n';
	import { changeSummary, type Change } from '$lib/genealogy';

	let { changes }: { changes: Change[] } = $props();
</script>

<section>
	<h3 class="vui-section-header">{$t('family.history')}</h3>
	{#if changes.length === 0}
		<p class="muted">{$t('family.noHistory')}</p>
	{:else}
		<ol class="history">
			{#each changes as c (c.id)}
				<li class="vui-card change">
					<div class="change-head">
						<span class="vui-badge">{c.op}</span>
						<span>{c.entity} #{c.entityId}</span>
						<span class="muted">{c.changedBy} · {new Date(c.changedAt).toLocaleString()}</span>
					</div>
					<div class="muted">{c.reason}</div>
					<div class="summary">{changeSummary(c)}</div>
					<form method="POST" action="?/edit" use:enhance class="undo">
						<input type="hidden" name="_method" value="POST" />
						<input type="hidden" name="_path" value={`changes/${c.id}/undo`} />
						<input class="vui-input" name="reason" required placeholder={$t('family.reason')} />
						<button class="vui-btn vui-btn-secondary vui-btn-sm">{$t('family.undo')}</button>
					</form>
				</li>
			{/each}
		</ol>
	{/if}
</section>

<style>
	.history {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.change-head,
	.undo {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 8px;
	}
	.summary {
		font-family: var(--vui-font-mono);
		font-size: 12px;
		word-break: break-word;
	}
	.muted {
		color: var(--vui-text-muted);
	}
</style>
