<script lang="ts">
	import { t } from '$lib/i18n';
	import type { PagePerson } from '$lib/server/api';
	import PersonAvatar from './PersonAvatar.svelte';

	let { people, recordId = null }: { people: PagePerson[]; recordId?: number | null } = $props();
</script>

{#if people.length === 0}
	<p class="none">{$t('people.none')}</p>
{/if}
<ul class="people">
	{#each people as p (p.personId + ':' + p.via + ':' + (p.mediaId ?? ''))}
		<li>
			<PersonAvatar url={p.portraitUrl} name={p.name} />
			<div class="who">
				<a href="/family-tree?personId={p.personId}">{p.name}</a>
				<span class="meta">
					{#if p.birthYear || p.deathYear}{p.birthYear ?? '?'}–{p.deathYear ?? ''}{/if}
					{p.role}
					{#if p.via === 'record'}
						<span class="tag">{$t('people.viaRecord')}</span>
					{/if}
					{#if recordId != null && p.pageSeq != null}
						<a class="pg" href="/records/{recordId}/pages/{p.pageSeq}">{$t('people.page', p.pageSeq)}</a>
					{/if}
				</span>
			</div>
		</li>
	{/each}
</ul>

<style>
	.people {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
	}
	li {
		display: flex;
		align-items: center;
		gap: 0.75rem;
	}
	.who {
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
	.who a {
		color: var(--vui-text);
		font-weight: 600;
	}
	.who a:hover {
		color: var(--vui-accent);
	}
	.meta {
		font-size: 0.75rem;
		color: var(--vui-text-muted);
	}
	.tag {
		margin-left: 0.25rem;
		padding: 0 0.4rem;
		border: 1px solid var(--vui-border);
		border-radius: 999px;
	}
	.pg {
		margin-left: 0.25rem;
		color: var(--vui-accent);
		font-weight: 400;
	}
</style>
