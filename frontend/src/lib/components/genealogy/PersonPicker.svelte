<script lang="ts">
	import { t } from '$lib/i18n';

	interface Hit {
		personId: number;
		name: string;
		birthYear: number | null;
		deathYear: number | null;
	}

	let { name, label }: { name: string; label: string } = $props();

	let query = $state('');
	let hits: Hit[] = $state([]);
	let chosen: { id: number; name: string } | null = $state(null);
	let timer: ReturnType<typeof setTimeout> | undefined;

	function search() {
		clearTimeout(timer);
		timer = setTimeout(async () => {
			if (query.trim().length < 2) {
				hits = [];
				return;
			}
			const params = new URLSearchParams({ q: query, limit: '8', all: 'true' });
			const res = await fetch(`/api/family-tree/search?${params}`);
			hits = res.ok ? await res.json() : [];
		}, 250);
	}
</script>

<div class="picker">
	<label class="field">
		<span>{label}</span>
		{#if chosen}
			<span class="chosen">
				{chosen.name} #{chosen.id}
				<button type="button" class="vui-btn vui-btn-ghost vui-btn-sm" onclick={() => (chosen = null)}>
					{$t('family.change')}
				</button>
			</span>
		{:else}
			<input
				class="vui-input"
				type="search"
				bind:value={query}
				oninput={search}
				placeholder={$t('family.searchPerson')}
			/>
		{/if}
	</label>
	{#if !chosen && hits.length > 0}
		<ul class="hits">
			{#each hits as h (h.personId)}
				<li>
					<button
						type="button"
						class="vui-btn vui-btn-ghost vui-btn-sm"
						onclick={() => {
							chosen = { id: h.personId, name: h.name };
							hits = [];
						}}
					>
						{h.name}
						{#if h.birthYear || h.deathYear}({h.birthYear ?? '?'}–{h.deathYear ?? '?'}){/if}
						#{h.personId}
					</button>
				</li>
			{/each}
		</ul>
	{/if}
	<input type="hidden" {name} value={chosen?.id ?? ''} />
</div>

<style>
	.picker {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.hits {
		list-style: none;
		margin: 0;
		padding: 0;
	}
	.chosen {
		display: flex;
		align-items: center;
		gap: 8px;
	}
</style>
