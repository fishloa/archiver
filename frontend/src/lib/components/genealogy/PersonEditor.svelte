<script lang="ts">
	import { enhance } from '$app/forms';
	import { t } from '$lib/i18n';
	import {
		COUPLE_TYPES,
		COUPLE_TYPE_LABELS,
		EVENT_TAGS,
		LINK_TYPES,
		LINK_TYPE_LABELS,
		NAME_TYPES,
		NAME_TYPE_LABELS,
		QUALITY_LABELS,
		ROLES,
		ROLE_LABELS,
		SEXES,
		SEX_LABELS,
		eventLabel,
		fullName,
		type Crop,
		type PersonDetail,
		type Source
	} from '$lib/genealogy';
	import type { PageResponse } from '$lib/types';
	import CropSelector from './CropSelector.svelte';
	import PersonPicker from './PersonPicker.svelte';
	import RecordPagePicker from './RecordPagePicker.svelte';

	let {
		person,
		places,
		sources
	}: { person: PersonDetail; places: { id: number; name: string }[]; sources: Source[] } = $props();

	let mediaPage: PageResponse | null = $state(null);
	let crop: Crop | null = $state(null);
</script>

{#snippet reason()}
	<input class="vui-input" name="reason" required placeholder={$t('family.reason')} />
{/snippet}

{#snippet target(method: string, path: string)}
	<input type="hidden" name="_method" value={method} />
	<input type="hidden" name="_path" value={path} />
{/snippet}

<datalist id="gen-places">
	{#each places as p (p.id)}<option value={p.name}></option>{/each}
</datalist>

<div class="editor">
	<section class="vui-card">
		<h3 class="vui-section-header">{$t('family.person')}</h3>
		<form method="POST" action="?/edit" use:enhance class="grid">
			{@render target('PUT', `persons/${person.id}`)}
			<label class="field">
				<span>{$t('family.sex')}</span>
				<select class="vui-input" name="sex" value={person.sex}>
					{#each SEXES as s (s)}<option value={s}>{$t(SEX_LABELS[s])}</option>{/each}
				</select>
			</label>
			<label class="field checkbox">
				<input type="hidden" name="living" value="false" />
				<input class="vui-checkbox" type="checkbox" name="living" value="true" checked={person.living} />
				<span>{$t('family.living')}</span>
			</label>
			<label class="field wide">
				<span>{$t('family.notes')}</span>
				<textarea class="vui-input" name="notes">{person.notes ?? ''}</textarea>
			</label>
			<label class="field wide">
				<span>{$t('family.needsReview')}</span>
				<textarea class="vui-input" name="needsReview">{person.needsReview ?? ''}</textarea>
			</label>
			{@render reason()}
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.save')}</button>
		</form>
	</section>

	<section class="vui-card">
		<h3 class="vui-section-header">{$t('family.names')}</h3>
		{#each person.names as n (n.id)}
			<form method="POST" action="?/edit" use:enhance class="grid">
				{@render target('PUT', `names/${n.id}`)}
				<select class="vui-input" name="type" value={n.type}>
					{#each NAME_TYPES as nt (nt)}<option value={nt}>{$t(NAME_TYPE_LABELS[nt])}</option>{/each}
				</select>
				<input class="vui-input" name="prefix" value={n.prefix ?? ''} placeholder={$t('family.prefix')} />
				<input class="vui-input" name="given" value={n.given ?? ''} placeholder={$t('family.given')} />
				<input class="vui-input" name="surname" value={n.surname ?? ''} placeholder={$t('family.surname')} />
				<input class="vui-input" name="suffix" value={n.suffix ?? ''} placeholder={$t('family.suffix')} />
				<input class="vui-input" name="nickname" value={n.nickname ?? ''} placeholder={$t('family.nickname')} />
				<label class="field checkbox">
					<input type="hidden" name="isPrimary" value="false" />
					<input class="vui-checkbox" type="checkbox" name="isPrimary" value="true" checked={n.isPrimary} />
					<span>{$t('family.primary')}</span>
				</label>
				{@render reason()}
				<button class="vui-btn vui-btn-secondary vui-btn-sm">{$t('family.save')}</button>
			</form>
			{#if !n.isPrimary}
				<form method="POST" action="?/edit" use:enhance class="inline">
					{@render target('DELETE', `names/${n.id}`)}
					{@render reason()}
					<button class="vui-btn vui-btn-danger vui-btn-sm">{$t('family.delete')} {fullName(n)}</button>
				</form>
			{/if}
		{/each}
		<form method="POST" action="?/edit" use:enhance class="grid">
			{@render target('POST', `persons/${person.id}/names`)}
			<select class="vui-input" name="type">
				{#each NAME_TYPES as nt (nt)}<option value={nt}>{$t(NAME_TYPE_LABELS[nt])}</option>{/each}
			</select>
			<input class="vui-input" name="given" placeholder={$t('family.given')} />
			<input class="vui-input" name="surname" placeholder={$t('family.surname')} />
			<input class="vui-input" name="nickname" placeholder={$t('family.nickname')} />
			{@render reason()}
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.addName')}</button>
		</form>
	</section>

	<section class="vui-card">
		<h3 class="vui-section-header">{$t('family.events')}</h3>
		{#each person.events as ev (ev.id)}
			<div class="event-edit">
				<form method="POST" action="?/edit" use:enhance class="grid">
					{@render target('PUT', `events/${ev.id}`)}
					<select class="vui-input" name="type" value={ev.tag}>
						{#each EVENT_TAGS as tag (tag)}<option value={tag}>{$t(eventLabel(tag))}</option>{/each}
					</select>
					<input class="vui-input" name="dateText" value={ev.date ?? ''} placeholder={$t('family.dateHelp')} />
					<input class="vui-input" name="placeName" list="gen-places" value={ev.place ?? ''} placeholder={$t('family.place')} />
					<input class="vui-input" name="description" value={ev.description ?? ''} placeholder={$t('family.description')} />
					{@render reason()}
					<button class="vui-btn vui-btn-secondary vui-btn-sm">{$t('family.save')}</button>
				</form>
				<form method="POST" action="?/edit" use:enhance class="grid">
					{@render target('POST', 'citations')}
					<input type="hidden" name="eventId" value={ev.id} />
					<select class="vui-input" name="sourceId" required>
						<option value="">{$t('family.source')}</option>
						{#each sources as s (s.id)}<option value={s.id}>{s.title}</option>{/each}
					</select>
					<RecordPagePicker sendRecord={false} />
					<input class="vui-input" name="detail" placeholder={$t('family.detail')} />
					<select class="vui-input" name="quality">
						{#each QUALITY_LABELS as q, i (q)}<option value={i}>{$t(q)}</option>{/each}
					</select>
					{@render reason()}
					<button class="vui-btn vui-btn-secondary vui-btn-sm">{$t('family.cite')}</button>
				</form>
				<form method="POST" action="?/edit" use:enhance class="inline">
					{@render target('DELETE', `events/${ev.id}`)}
					{@render reason()}
					<button class="vui-btn vui-btn-danger vui-btn-sm">{$t('family.delete')}</button>
				</form>
			</div>
		{/each}
		<form method="POST" action="?/edit" use:enhance class="grid">
			{@render target('POST', 'events')}
			<select class="vui-input" name="type">
				{#each EVENT_TAGS as tag (tag)}<option value={tag}>{$t(eventLabel(tag))}</option>{/each}
			</select>
			<input type="hidden" name="personId" value={person.id} />
			<input class="vui-input" name="dateText" placeholder={$t('family.dateHelp')} />
			<input class="vui-input" name="placeName" list="gen-places" placeholder={$t('family.place')} />
			<input class="vui-input" name="description" placeholder={$t('family.description')} />
			{@render reason()}
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.addEvent')}</button>
		</form>
	</section>

	<section class="vui-card">
		<h3 class="vui-section-header">{$t('family.relations')}</h3>
		{#each person.couples as c (c.id)}
			<form method="POST" action="?/edit" use:enhance class="inline">
				{@render target('DELETE', `couples/${c.id}`)}
				<span>{c.spouseName}</span>
				{@render reason()}
				<button class="vui-btn vui-btn-danger vui-btn-sm">{$t('family.delete')}</button>
			</form>
			<form method="POST" action="?/edit" use:enhance class="grid">
				{@render target('POST', 'events')}
				<input type="hidden" name="coupleId" value={c.id} />
				<select class="vui-input" name="type">
					{#each ['MARR', 'DIV', 'EVEN'] as tag (tag)}<option value={tag}>{$t(eventLabel(tag))}</option>{/each}
				</select>
				<input class="vui-input" name="dateText" placeholder={$t('family.dateHelp')} />
				<input class="vui-input" name="placeName" list="gen-places" placeholder={$t('family.place')} />
				{@render reason()}
				<button class="vui-btn vui-btn-secondary vui-btn-sm">{$t('family.addEvent')}</button>
			</form>
		{/each}
		{#each person.parentLinks as l (l.id)}
			<form method="POST" action="?/edit" use:enhance class="inline">
				{@render target('DELETE', `parent-links/${l.id}`)}
				<span>{l.relation === 'parent' ? $t('family.parent') : $t('family.child')}: {l.otherName} ({$t(LINK_TYPE_LABELS[l.type as keyof typeof LINK_TYPE_LABELS])})</span>
				{@render reason()}
				<button class="vui-btn vui-btn-danger vui-btn-sm">{$t('family.delete')}</button>
			</form>
		{/each}
		<form method="POST" action="?/edit" use:enhance class="grid">
			{@render target('POST', 'couples')}
			<input type="hidden" name="person1Id" value={person.id} />
			<PersonPicker name="person2Id" label={$t('family.spouse')} />
			<select class="vui-input" name="type">
				{#each COUPLE_TYPES as ct (ct)}<option value={ct}>{$t(COUPLE_TYPE_LABELS[ct])}</option>{/each}
			</select>
			<input class="vui-input" name="sortOrder" inputmode="numeric" placeholder={$t('family.marriageNumber')} />
			{@render reason()}
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.addCouple')}</button>
		</form>
		<form method="POST" action="?/edit" use:enhance class="grid">
			{@render target('POST', 'parent-links')}
			<input type="hidden" name="childId" value={person.id} />
			<PersonPicker name="parentId" label={$t('family.parent')} />
			<select class="vui-input" name="type">
				{#each LINK_TYPES as lt (lt)}<option value={lt}>{$t(LINK_TYPE_LABELS[lt])}</option>{/each}
			</select>
			{@render reason()}
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.addParent')}</button>
		</form>
		<form method="POST" action="?/edit" use:enhance class="grid">
			{@render target('POST', 'parent-links')}
			<input type="hidden" name="parentId" value={person.id} />
			<PersonPicker name="childId" label={$t('family.child')} />
			<select class="vui-input" name="type">
				{#each LINK_TYPES as lt (lt)}<option value={lt}>{$t(LINK_TYPE_LABELS[lt])}</option>{/each}
			</select>
			{@render reason()}
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.addChild')}</button>
		</form>
	</section>

	<section class="vui-card">
		<h3 class="vui-section-header">{$t('family.records')}</h3>
		{#each person.recordLinks as r (r.id)}
			<form method="POST" action="?/edit" use:enhance class="inline">
				{@render target('DELETE', `record-links/${r.id}`)}
				<span>{r.titleEn ?? r.title ?? `#${r.recordId}`} ({$t(ROLE_LABELS[r.role as keyof typeof ROLE_LABELS])})</span>
				{@render reason()}
				<button class="vui-btn vui-btn-danger vui-btn-sm">{$t('family.delete')}</button>
			</form>
		{/each}
		<form method="POST" action="?/edit" use:enhance class="grid">
			{@render target('POST', 'record-links')}
			<input type="hidden" name="personId" value={person.id} />
			<RecordPagePicker />
			<select class="vui-input" name="role">
				{#each ROLES as role (role)}<option value={role}>{$t(ROLE_LABELS[role])}</option>{/each}
			</select>
			<input class="vui-input" name="note" placeholder={$t('family.note')} />
			{@render reason()}
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.linkRecord')}</button>
		</form>
	</section>

	<section class="vui-card">
		<h3 class="vui-section-header">{$t('family.media')}</h3>
		{#each person.media as m (m.id)}
			<form method="POST" action="?/edit" use:enhance class="inline">
				{@render target('DELETE', `media/${m.id}`)}
				<img class="thumb" src={m.imageUrl} alt={m.caption ?? ''} />
				{@render reason()}
				<button class="vui-btn vui-btn-danger vui-btn-sm">{$t('family.delete')}</button>
			</form>
		{/each}
		<form method="POST" action="?/edit" use:enhance class="grid">
			{@render target('POST', 'media')}
			<input type="hidden" name="personId" value={person.id} />
			<RecordPagePicker bind:page={mediaPage} requirePage sendRecord={false} />
			{#if mediaPage?.attachmentId}
				<div class="wide">
					<CropSelector src={`/api/files/${mediaPage.attachmentId}`} bind:crop />
				</div>
			{/if}
			<input class="vui-input" name="caption" placeholder={$t('family.caption')} />
			<input class="vui-input" name="dateText" placeholder={$t('family.dateHelp')} />
			<label class="field checkbox">
				<input type="hidden" name="isPortrait" value="false" />
				<input class="vui-checkbox" type="checkbox" name="isPortrait" value="true" />
				<span>{$t('family.portrait')}</span>
			</label>
			{@render reason()}
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.addMedia')}</button>
		</form>
	</section>

	<section class="vui-card">
		<h3 class="vui-section-header">{$t('family.sources')}</h3>
		<form method="POST" action="?/edit" use:enhance class="grid">
			{@render target('POST', 'sources')}
			<input class="vui-input" name="title" required placeholder={$t('family.sourceTitle')} />
			<input class="vui-input" name="author" placeholder={$t('family.author')} />
			<input class="vui-input" name="recordId" inputmode="numeric" placeholder={$t('family.record')} />
			<input class="vui-input" name="url" placeholder="https://" />
			{@render reason()}
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.addSource')}</button>
		</form>
	</section>

	<section class="vui-card">
		<h3 class="vui-section-header">{$t('family.merge')}</h3>
		<p class="muted">{$t('family.mergeHelp')}</p>
		<form method="POST" action="?/merge" use:enhance class="grid">
			<input type="hidden" name="keep" value={person.id} />
			<PersonPicker name="gone" label={$t('family.duplicate')} />
			{@render reason()}
			<button class="vui-btn vui-btn-danger vui-btn-sm">{$t('family.merge')}</button>
		</form>
		<form method="POST" action="?/edit" use:enhance class="inline">
			{@render target('DELETE', `persons/${person.id}`)}
			{@render reason()}
			<button class="vui-btn vui-btn-danger vui-btn-sm">{$t('family.deletePerson')}</button>
		</form>
	</section>
</div>

<style>
	.editor {
		display: flex;
		flex-direction: column;
		gap: 12px;
		margin-bottom: 16px;
	}
	.grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
		gap: 8px;
		align-items: end;
		margin-bottom: 8px;
	}
	.inline {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 8px;
		margin-bottom: 8px;
	}
	.wide {
		grid-column: 1 / -1;
	}
	.field {
		display: flex;
		flex-direction: column;
		gap: 4px;
		font-size: 12px;
		color: var(--vui-text-muted);
	}
	.checkbox {
		flex-direction: row;
		align-items: center;
	}
	.event-edit {
		border-bottom: 1px solid var(--vui-border);
		padding-bottom: 8px;
		margin-bottom: 8px;
	}
	.thumb {
		max-height: 48px;
		border-radius: var(--vui-radius-sm);
	}
	.muted {
		color: var(--vui-text-muted);
	}
</style>
