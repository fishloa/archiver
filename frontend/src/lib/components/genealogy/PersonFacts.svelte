<script lang="ts">
	import { t } from '$lib/i18n';
	import {
		COUPLE_TYPE_LABELS,
		LINK_TYPE_LABELS,
		NAME_TYPE_LABELS,
		ROLE_LABELS,
		eventLabel,
		fullName,
		qualityLabel,
		type PersonDetail
	} from '$lib/genealogy';

	let { person, onNavigate }: { person: PersonDetail; onNavigate: (id: number) => void } =
		$props();

	const otherNames = $derived(person.names.filter((n) => !n.isPrimary));
	const parents = $derived(
		person.parentLinks.filter((l) => l.relation === 'parent' && l.type !== 'birth')
	);
	const gallery = $derived(person.media.filter((m) => !m.isPortrait));
</script>

<div class="facts">
	{#if person.portrait}
		<figure class="portrait">
			<img src={person.portrait.imageUrl} alt={person.portrait.caption ?? person.name} />
			{#if person.portrait.caption}<figcaption>{person.portrait.caption}</figcaption>{/if}
		</figure>
	{/if}

	{#if otherNames.length > 0}
		<section>
			<h3 class="vui-section-header">{$t('family.otherNames')}</h3>
			<ul class="plain">
				{#each otherNames as n (n.id)}
					<li>
						{fullName(n)}
						{#if n.nickname}<span class="muted">“{n.nickname}”</span>{/if}
						<span class="vui-badge">{$t(NAME_TYPE_LABELS[n.type as keyof typeof NAME_TYPE_LABELS])}</span>
					</li>
				{/each}
			</ul>
		</section>
	{/if}

	<section>
		<h3 class="vui-section-header">{$t('family.events')}</h3>
		{#if person.events.length === 0}
			<p class="muted">{$t('family.noEvents')}</p>
		{:else}
			<ol class="plain events">
				{#each person.events as ev (ev.id)}
					<li class="event">
						<span class="event-tag">{$t(eventLabel(ev.tag))}</span>
						{#if ev.date}<span class="event-date">{ev.date}</span>{/if}
						{#if ev.place}<span>{ev.place}</span>{/if}
						{#if ev.description}<span class="muted">{ev.description}</span>{/if}
						{#if ev.spouseId}
							<button class="vui-btn vui-btn-ghost vui-btn-sm" onclick={() => onNavigate(ev.spouseId!)}>
								{person.couples.find((c) => c.spouseId === ev.spouseId)?.spouseName}
							</button>
						{/if}
						{#each ev.citations as c (c.id)}
							<span class="vui-badge vui-badge-info">{c.sourceTitle}{c.detail ? ` – ${c.detail}` : ''} · {$t(qualityLabel(c.quality))}</span>
						{/each}
					</li>
				{/each}
			</ol>
		{/if}
	</section>

	{#if person.couples.length > 0}
		<section>
			<h3 class="vui-section-header">{$t('family.spouses')}</h3>
			<ul class="plain">
				{#each person.couples as c (c.id)}
					<li>
						<button class="vui-btn vui-btn-ghost vui-btn-sm" onclick={() => onNavigate(c.spouseId)}>{c.spouseName}</button>
						<span class="vui-badge">{$t(COUPLE_TYPE_LABELS[c.type as keyof typeof COUPLE_TYPE_LABELS])}</span>
					</li>
				{/each}
			</ul>
		</section>
	{/if}

	{#if parents.length > 0}
		<section>
			<h3 class="vui-section-header">{$t('family.otherParents')}</h3>
			<ul class="plain">
				{#each parents as l (l.id)}
					<li>
						<button class="vui-btn vui-btn-ghost vui-btn-sm" onclick={() => onNavigate(l.parentId)}>{l.otherName}</button>
						<span class="vui-badge">{$t(LINK_TYPE_LABELS[l.type as keyof typeof LINK_TYPE_LABELS])}</span>
					</li>
				{/each}
			</ul>
		</section>
	{/if}

	{#if person.recordLinks.length > 0}
		<section>
			<h3 class="vui-section-header">{$t('family.records')}</h3>
			<ul class="plain">
				{#each person.recordLinks as r (r.id)}
					<li>
						<a href={r.pageSeq ? `/records/${r.recordId}/pages/${r.pageSeq}` : `/records/${r.recordId}`}>
							{r.titleEn ?? r.title ?? r.referenceCode ?? `#${r.recordId}`}
						</a>
						<span class="vui-badge">{$t(ROLE_LABELS[r.role as keyof typeof ROLE_LABELS])}</span>
						{#if r.note}<span class="muted">{r.note}</span>{/if}
					</li>
				{/each}
			</ul>
		</section>
	{/if}

	{#if gallery.length > 0}
		<section>
			<h3 class="vui-section-header">{$t('family.media')}</h3>
			<div class="gallery">
				{#each gallery as m (m.id)}
					<figure>
						<img src={m.imageUrl} alt={m.caption ?? ''} loading="lazy" />
						{#if m.caption}<figcaption>{m.caption}</figcaption>{/if}
					</figure>
				{/each}
			</div>
		</section>
	{/if}

	{#if person.sources.length > 0}
		<section>
			<h3 class="vui-section-header">{$t('family.sources')}</h3>
			<ul class="plain">
				{#each person.sources as s (s.id)}
					<li>
						{#if s.recordId}<a href={`/records/${s.recordId}`}>{s.title}</a>{:else}{s.title}{/if}
						{#if s.author}<span class="muted">{s.author}</span>{/if}
					</li>
				{/each}
			</ul>
		</section>
	{/if}
</div>

<style>
	.facts {
		display: flex;
		flex-direction: column;
		gap: 16px;
		margin-bottom: 16px;
	}
	.portrait {
		margin: 0;
		max-width: 220px;
	}
	.portrait img,
	.gallery img {
		max-width: 100%;
		border-radius: var(--vui-radius-md);
		border: 1px solid var(--vui-border);
	}
	figcaption {
		font-size: 12px;
		color: var(--vui-text-muted);
	}
	.plain {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.event {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 8px;
	}
	.event-tag {
		font-size: 11px;
		font-weight: 700;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: var(--vui-accent);
		min-width: 90px;
	}
	.event-date {
		font-weight: 600;
	}
	.muted {
		color: var(--vui-text-muted);
	}
	.gallery {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
		gap: 8px;
	}
	.gallery figure {
		margin: 0;
	}
</style>
