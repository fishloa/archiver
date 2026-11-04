<script lang="ts">
	import { t } from '$lib/i18n';
	import { History, X } from 'lucide-svelte';
	import type { ActivityEvent, ActivitySession, AdminUser } from '$lib/server/api';

	let { data } = $props();
	let users = $derived(data.users ?? []) as AdminUser[];
	let sessions = $derived(data.sessions ?? []) as ActivitySession[];
	let events = $derived(data.events ?? []) as ActivityEvent[];

	const kindKeys = {
		view_record: 'activity.kind.view_record',
		view_page: 'activity.kind.view_page',
		view_person: 'activity.kind.view_person',
		search: 'activity.kind.search',
		edit: 'activity.kind.edit',
		other: 'activity.kind.other'
	} as const;

	function kindLabel(kind: string): string {
		return $t(kindKeys[kind as keyof typeof kindKeys] ?? kindKeys.other);
	}

	function when(value: string): string {
		return new Date(value).toLocaleString();
	}

	function duration(seconds: number): string {
		const h = Math.floor(seconds / 3600);
		const m = Math.floor((seconds % 3600) / 60);
		const s = seconds % 60;
		if (h > 0) return `${h}h ${m}m`;
		if (m > 0) return `${m}m ${s}s`;
		return `${s}s`;
	}

	function who(s: ActivitySession): string {
		return s.display_name ?? s.email;
	}

	function selectHref(s: ActivitySession): string {
		const p = new URLSearchParams();
		if (data.filter.userId) p.set('userId', data.filter.userId);
		if (data.filter.from) p.set('from', data.filter.from);
		if (data.filter.to) p.set('to', data.filter.to);
		if (s.user_id !== null) p.set('sUser', String(s.user_id));
		p.set('sFrom', s.started_at);
		p.set('sTo', s.ended_at);
		return `?${p}`;
	}

	function isSelected(s: ActivitySession): boolean {
		return (
			data.selected.from === s.started_at &&
			data.selected.to === s.ended_at &&
			data.selected.user === (s.user_id === null ? '' : String(s.user_id))
		);
	}

	function closeHref(): string {
		const p = new URLSearchParams();
		if (data.filter.userId) p.set('userId', data.filter.userId);
		if (data.filter.from) p.set('from', data.filter.from);
		if (data.filter.to) p.set('to', data.filter.to);
		return `?${p}`;
	}

	function link(e: ActivityEvent): { href: string; text: string } | null {
		if (!e.target) return null;
		switch (e.kind) {
			case 'view_record':
				return { href: `/records/${e.target}`, text: `#${e.target}` };
			case 'view_page': {
				const [rec, seq] = e.target.split('/');
				return { href: `/records/${rec}/pages/${seq}`, text: `#${rec} / ${seq}` };
			}
			case 'view_person':
				return { href: `/family-tree?personId=${e.target}`, text: `#${e.target}` };
			case 'search': {
				const href = e.path.includes('family-tree')
					? `/family-tree?q=${encodeURIComponent(e.target)}`
					: `/?q=${encodeURIComponent(e.target)}`;
				return { href, text: e.target };
			}
			default:
				return null;
		}
	}

	function detail(e: ActivityEvent): string {
		if (e.kind === 'edit' || e.kind === 'other') {
			return `${e.method} ${e.path}${e.query ? '?' + e.query : ''}`;
		}
		return '';
	}
</script>

<div class="list-card">
	<div class="list-header">
		<h2 class="list-title"><History size={18} strokeWidth={2} /> {$t('activity.title')}</h2>
	</div>

	<form method="GET" class="filters">
		<label class="filter">
			<span class="field-label">{$t('activity.user')}</span>
			<select name="userId" class="field-input">
				<option value="">{$t('activity.allUsers')}</option>
				{#each users as u (u.id)}
					<option value={String(u.id)} selected={String(u.id) === data.filter.userId}>
						{u.display_name ?? u.emails[0]?.email ?? u.id}
					</option>
				{/each}
			</select>
		</label>
		<label class="filter">
			<span class="field-label">{$t('activity.from')}</span>
			<input type="date" name="from" value={data.filter.from} class="field-input" />
		</label>
		<label class="filter">
			<span class="field-label">{$t('activity.to')}</span>
			<input type="date" name="to" value={data.filter.to} class="field-input" />
		</label>
		<button type="submit" class="btn-submit">{$t('activity.filter')}</button>
	</form>
	<p class="hint">{$t('activity.hint')}</p>

	{#if sessions.length === 0}
		<p class="empty">{$t('activity.none')}</p>
	{:else}
		<table class="sessions">
			<thead>
				<tr>
					<th>{$t('activity.user')}</th>
					<th>{$t('activity.started')}</th>
					<th>{$t('activity.ended')}</th>
					<th>{$t('activity.duration')}</th>
					<th class="num">{$t('activity.events')}</th>
					<th class="num">{$t('activity.views')}</th>
					<th class="num">{$t('activity.edits')}</th>
				</tr>
			</thead>
			<tbody>
				{#each sessions as s (s.user_id + s.started_at)}
					<tr class:selected={isSelected(s)}>
						<td><a href={selectHref(s)} class="session-link">{who(s)}</a></td>
						<td>{when(s.started_at)}</td>
						<td>{when(s.ended_at)}</td>
						<td>{duration(s.duration_seconds)}</td>
						<td class="num">{s.event_count}</td>
						<td class="num">{s.view_count}</td>
						<td class="num">{s.edit_count}</td>
					</tr>
				{/each}
			</tbody>
		</table>
	{/if}
</div>

{#if data.selected.from}
	<div class="list-card timeline">
		<div class="list-header">
			<h2 class="list-title">{$t('activity.timeline')}</h2>
			<a href={closeHref()} class="close-link"><X size={16} /> {$t('activity.close')}</a>
		</div>
		{#if events.length === 0}
			<p class="empty">{$t('activity.noEvents')}</p>
		{:else}
			<ul class="events">
				{#each events as e (e.id)}
					{@const l = link(e)}
					<li class="event">
						<span class="event-time">{new Date(e.at).toLocaleTimeString()}</span>
						<span class="event-kind kind-{e.kind}">{kindLabel(e.kind)}</span>
						<span class="event-desc">
							{#if l}<a href={l.href}>{l.text}</a>{/if}
							{#if detail(e)}<code>{detail(e)}</code>{/if}
							{#if e.status >= 400}<span class="event-status">{e.status}</span>{/if}
						</span>
					</li>
				{/each}
			</ul>
		{/if}
	</div>
{/if}

<style>
	.list-card {
		background: var(--vui-surface);
		border: 1.5px solid var(--vui-border);
		border-radius: 12px;
		padding: 20px 24px;
		margin-bottom: 20px;
	}
	.list-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-bottom: 16px;
	}
	.list-title {
		display: flex;
		align-items: center;
		gap: 8px;
		font-size: 16px;
		font-weight: 700;
		color: var(--vui-text);
	}
	.filters {
		display: flex;
		flex-wrap: wrap;
		align-items: flex-end;
		gap: 12px;
	}
	.filter {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.field-label {
		font-size: 12px;
		font-weight: 600;
		color: var(--vui-text-muted);
	}
	.field-input {
		padding: 8px 10px;
		border: 1.5px solid var(--vui-border);
		border-radius: 8px;
		background: var(--vui-bg);
		color: var(--vui-text);
		font-size: 14px;
		font-family: inherit;
	}
	.btn-submit {
		padding: 9px 16px;
		border: none;
		border-radius: 8px;
		background: var(--vui-accent);
		color: var(--vui-bg-deep);
		font-size: 13px;
		font-weight: 600;
		cursor: pointer;
		font-family: inherit;
	}
	.btn-submit:hover {
		opacity: 0.9;
	}
	.hint {
		margin: 12px 0 16px;
		font-size: 12px;
		color: var(--vui-text-muted);
	}
	.empty {
		color: var(--vui-text-muted);
		font-size: 14px;
	}
	.sessions {
		width: 100%;
		border-collapse: collapse;
		font-size: 14px;
	}
	.sessions th {
		text-align: left;
		font-size: 12px;
		font-weight: 600;
		color: var(--vui-text-muted);
		padding: 6px 8px;
		border-bottom: 1.5px solid var(--vui-border);
	}
	.sessions td {
		padding: 8px;
		border-bottom: 1px solid var(--vui-border);
	}
	.sessions .num {
		text-align: right;
	}
	.sessions tr.selected td {
		background: var(--vui-bg);
	}
	.session-link {
		font-weight: 600;
		color: var(--vui-accent);
		text-decoration: none;
	}
	.close-link {
		display: flex;
		align-items: center;
		gap: 4px;
		font-size: 13px;
		color: var(--vui-text-muted);
		text-decoration: none;
	}
	.events {
		list-style: none;
		margin: 0;
		padding: 0;
	}
	.event {
		display: flex;
		gap: 12px;
		align-items: baseline;
		padding: 6px 0;
		border-bottom: 1px solid var(--vui-border);
		font-size: 14px;
	}
	.event-time {
		flex: 0 0 90px;
		font-variant-numeric: tabular-nums;
		color: var(--vui-text-muted);
	}
	.event-kind {
		flex: 0 0 150px;
		font-weight: 600;
		color: var(--vui-text);
	}
	.kind-edit {
		color: var(--vui-accent);
	}
	.event-desc {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
		min-width: 0;
		overflow-wrap: anywhere;
	}
	.event-desc a {
		color: var(--vui-accent);
	}
	.event-desc code {
		font-size: 12px;
		color: var(--vui-text-muted);
	}
	.event-status {
		font-size: 12px;
		color: var(--vui-text-muted);
	}

	@media (max-width: 700px) {
		.sessions {
			display: block;
			overflow-x: auto;
		}
		.event {
			flex-wrap: wrap;
		}
	}
</style>
