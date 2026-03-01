<script lang="ts">
	import '../app.css';
	import { page } from '$app/state';
	import { Search, Library, Activity, Settings, PanelLeftClose, PanelLeft, GitBranch, Languages, LogIn, LogOut } from 'lucide-svelte';
	import type { Snippet } from 'svelte';
	import { language, initLanguage, t } from '$lib/i18n';
	import LanguageSwitcher from '$lib/components/LanguageSwitcher.svelte';

	let { children, data }: { children: Snippet; data: any } = $props();

	// Initialize language during SSR (effect is client-only, won't run server-side)
	// eslint-disable-next-line -- intentionally capturing initial data for SSR
	initLanguage(data?.language ?? 'en');

	// Re-sync language when server data changes on the client
	$effect(() => {
		initLanguage(data?.language ?? 'en');
	});

	let collapsed = $state(false);
	let user = $derived(data?.user);
	let isAdmin = $derived(user?.role === 'admin');

	// Force reactivity on language changes
	let lang = $derived($language);

	const navItems = [
		{ href: '/', labelKey: 'nav.search' as const, icon: Search },
		{ href: '/records', labelKey: 'nav.records' as const, icon: Library },
		{ href: '/family-tree', labelKey: 'nav.familyTree' as const, icon: GitBranch },
		{ href: '/translate', labelKey: 'nav.translate' as const, icon: Languages },
		{ href: '/pipeline', labelKey: 'nav.pipeline' as const, icon: Activity },
		{ href: '/admin', labelKey: 'nav.admin' as const, icon: Settings, adminOnly: true }
	];

	function isActive(href: string): boolean {
		if (href === '/') return page.url.pathname === '/' || page.url.pathname.startsWith('/ask');
		if (href === '/records') return page.url.pathname.startsWith('/records');
		if (href === '/admin') return page.url.pathname.startsWith('/admin');
		return page.url.pathname.startsWith(href);
	}
</script>

<div class="app-shell">
	<nav class="sidebar" class:collapsed>
		<div class="sidebar-header">
			<img src="/logo.svg" alt="Czernin coat of arms" class="sidebar-logo" />
			{#if !collapsed}
				<span class="sidebar-title">Archiver</span>
			{/if}
		</div>

		<ul class="nav-list">
			{#each navItems as item}
				{#if !item.adminOnly || isAdmin}
					{@const active = isActive(item.href)}
					{@const Icon = item.icon}
					<li>
						<a
							href={item.href}
							class="nav-item"
							class:active
							title={collapsed ? $t(item.labelKey) : undefined}
						>
							<Icon size={20} strokeWidth={active ? 2.2 : 1.8} />
							{#if !collapsed}
								<span>{$t(item.labelKey)}</span>
							{/if}
						</a>
					</li>
				{/if}
			{/each}
		</ul>

		<button class="collapse-btn" onclick={() => (collapsed = !collapsed)}>
			{#if collapsed}
				<PanelLeft size={16} strokeWidth={1.8} />
			{:else}
				<PanelLeftClose size={16} strokeWidth={1.8} />
			{/if}
		</button>
	</nav>

	<main class="main-area">
		<div class="top-bar">
			<div class="top-bar-spacer"></div>
			<div class="auth-controls">
				<LanguageSwitcher {user} />
				{#if user?.authenticated}
					<a href="/profile" class="user-link" title={user.email}>
						{user.displayName || user.email}
					</a>
					<a href="/oauth2-google/sign_out?rd=/" class="signout-btn" title={$t('nav.signOut')}>
						<LogOut size={16} strokeWidth={1.8} />
					</a>
				{:else}
					<a href="/signin" class="signin-btn">
						<LogIn size={16} strokeWidth={1.8} />
						<span>{$t('nav.signIn')}</span>
					</a>
				{/if}
			</div>
		</div>
		<div class="content">
			{@render children()}
		</div>
	</main>
</div>

<style>
	.app-shell {
		display: flex;
		height: 100vh;
		overflow: hidden;
		background: var(--vui-bg-deep);
		color: var(--vui-text);
	}

	.sidebar {
		display: flex;
		flex-direction: column;
		height: 100%;
		width: 220px;
		background: var(--vui-glass);
		backdrop-filter: blur(20px);
		-webkit-backdrop-filter: blur(20px);
		border-right: 1px solid var(--vui-border);
		padding: 0;
		flex-shrink: 0;
		transition: width 0.2s ease;
	}

	.sidebar.collapsed {
		width: 60px;
	}

	.sidebar-header {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 20px 18px 16px;
		border-bottom: 1px solid var(--vui-border);
	}

	.sidebar-logo {
		width: 28px;
		height: 28px;
		flex-shrink: 0;
	}

	.sidebar.collapsed .sidebar-header {
		justify-content: center;
		padding: 20px 0 16px;
	}

	.sidebar-title {
		font-size: var(--vui-text-lg);
		font-weight: 700;
		letter-spacing: -0.01em;
		white-space: nowrap;
	}

	.nav-list {
		list-style: none;
		padding: 8px 0;
		margin: 0;
		flex: 1;
	}

	.nav-item {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 10px 18px;
		color: var(--vui-text-muted);
		text-decoration: none;
		font-size: 15px;
		font-weight: 500;
		transition: all 0.15s ease;
		border-left: 3px solid transparent;
	}

	.sidebar.collapsed .nav-item {
		justify-content: center;
		padding: 10px 0;
	}

	.nav-item:hover {
		background: var(--vui-surface);
		color: var(--vui-text);
		text-decoration: none;
	}

	.nav-item.active {
		color: var(--vui-accent);
		background: var(--vui-accent-dim);
		border-left-color: var(--vui-accent);
	}

	.collapse-btn {
		display: flex;
		align-items: center;
		justify-content: center;
		padding: 12px;
		margin: 4px 8px 12px;
		background: none;
		border: 1px solid var(--vui-border);
		border-radius: var(--vui-radius-md);
		color: var(--vui-text-muted);
		cursor: pointer;
		transition: all 0.15s ease;
	}

	.sidebar.collapsed .collapse-btn {
		margin: 4px 8px 12px;
	}

	.collapse-btn:hover {
		background: var(--vui-surface);
		color: var(--vui-text-muted);
		border-color: var(--vui-border-hover);
	}

	.main-area {
		flex: 1;
		display: flex;
		flex-direction: column;
		min-width: 0;
		overflow: hidden;
	}

	.top-bar {
		display: flex;
		align-items: center;
		justify-content: flex-end;
		padding: 12px 24px;
		flex-shrink: 0;
	}

	.top-bar-spacer {
		flex: 1;
	}

	.auth-controls {
		display: flex;
		align-items: center;
		gap: 12px;
	}

	.user-link {
		font-size: var(--vui-text-sm);
		font-weight: 500;
		color: var(--vui-text-muted);
		text-decoration: none;
		transition: color 0.15s ease;
	}

	.user-link:hover {
		color: var(--vui-accent);
		text-decoration: none;
	}

	.auth-controls .signout-btn {
		display: flex;
		align-items: center;
		color: var(--vui-text-muted);
		transition: color 0.15s ease;
	}

	.auth-controls .signout-btn:hover {
		color: var(--vui-text);
	}

	.auth-controls .signin-btn {
		display: flex;
		align-items: center;
		gap: 6px;
		font-size: var(--vui-text-sm);
		font-weight: 500;
		color: var(--vui-text-muted);
		text-decoration: none;
		transition: color 0.15s ease;
	}

	.auth-controls .signin-btn:hover {
		color: var(--vui-accent);
		text-decoration: none;
	}

	.content {
		flex: 1;
		overflow-y: auto;
		padding: 32px 40px;
	}

	@media (max-width: 768px) {
		.sidebar {
			width: 60px;
		}
		.sidebar-title,
		.nav-item span {
			display: none;
		}
		.nav-item {
			justify-content: center;
			padding: 10px 0;
		}
		.sidebar-header {
			justify-content: center;
			padding: 20px 0 16px;
		}
		.collapse-btn {
			display: none;
		}
		.content {
			padding: 24px 16px;
		}
	}
</style>
