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

<nav class="flex gap-1 mb-6 border-b border-border">
	{#each tabs as tab}
		{@const active = isActive(tab.href)}
		<a
			href={tab.href}
			class="flex items-center gap-2 px-4 py-2.5 text-[length:var(--vui-text-sm)] font-medium transition-colors
				{active ? 'text-accent border-b-2 border-accent -mb-px' : 'text-text-sub hover:text-text'}"
		>
			{@const Icon = tab.icon}
			<Icon size={15} strokeWidth={active ? 2.2 : 1.8} />
			{$t(tab.labelKey)}
		</a>
	{/each}
</nav>

{@render children()}
