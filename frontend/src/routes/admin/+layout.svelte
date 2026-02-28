<script lang="ts">
	import { page } from '$app/state';
	import { invalidateAll } from '$app/navigation';
	import { t } from '$lib/i18n';
	import { ShieldCheck, RefreshCw, Users, Activity, Clock } from 'lucide-svelte';
	import type { Snippet } from 'svelte';

	let { children }: { children: Snippet } = $props();

	const tabs = [
		{ href: '/admin/users', labelKey: 'admin.tab.users' as const, icon: Users },
		{ href: '/admin/audit', labelKey: 'admin.tab.audit' as const, icon: Activity },
		{ href: '/admin/events', labelKey: 'admin.tab.events' as const, icon: Clock }
	];

	function isActive(href: string): boolean {
		return page.url.pathname.startsWith(href);
	}
</script>

<div class="flex items-center justify-between mb-6">
	<h1 class="text-[length:var(--vui-text-2xl)] font-extrabold tracking-tight flex items-center gap-3">
		<ShieldCheck size={24} strokeWidth={2} class="text-accent" />
		{$t('admin.title')}
	</h1>
	<button class="vui-btn vui-btn-ghost vui-btn-sm" onclick={() => invalidateAll()}>
		<RefreshCw size={13} strokeWidth={2} /> {$t('admin.refresh')}
	</button>
</div>

<nav class="admin-tabs">
	{#each tabs as tab}
		{@const active = isActive(tab.href)}
		{@const Icon = tab.icon}
		<a href={tab.href} class="admin-tab" class:active>
			<Icon size={18} strokeWidth={active ? 2.2 : 1.8} />
			{$t(tab.labelKey)}
		</a>
	{/each}
</nav>

<style>
	.admin-tabs {
		display: flex;
		gap: 4px;
		margin-bottom: 28px;
		border-bottom: 2px solid var(--vui-border);
		padding-bottom: 0;
	}

	.admin-tab {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 12px 20px;
		font-size: 15px;
		font-weight: 600;
		color: var(--vui-text-sub);
		text-decoration: none;
		border-bottom: 3px solid transparent;
		margin-bottom: -2px;
		transition: all 0.15s ease;
	}

	.admin-tab:hover {
		color: var(--vui-text);
		text-decoration: none;
	}

	.admin-tab.active {
		color: var(--vui-accent);
		border-bottom-color: var(--vui-accent);
	}
</style>

{@render children()}
