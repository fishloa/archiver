<script lang="ts">
	import '../app.css';
	import { page } from '$app/state';
	import { Archive, Search, Library, Activity, Settings, PanelLeftClose, PanelLeft, GitBranch, LogIn, LogOut } from 'lucide-svelte';
	import type { Snippet } from 'svelte';

	let { children, data }: { children: Snippet; data: any } = $props();

	let collapsed = $state(false);
	let user = $derived(data?.user);
	let isAdmin = $derived(user?.role === 'admin');

	const nav = [
		{ href: '/', label: 'Search', icon: Search },
		{ href: '/records', label: 'Records', icon: Library },
		{ href: '/family-tree', label: 'Family Tree', icon: GitBranch },
		{ href: '/pipeline', label: 'Pipeline', icon: Activity },
		{ href: '/admin', label: 'Admin', icon: Settings, adminOnly: true }
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
			<Archive size={20} strokeWidth={1.8} class="text-accent flex-shrink-0" />
			{#if !collapsed}
				<span class="sidebar-title">Archiver</span>
			{/if}
		</div>

		<ul class="nav-list">
			{#each nav as item}
				{#if !item.adminOnly || isAdmin}
					{@const active = isActive(item.href)}
					<li>
						<a
							href={item.href}
							class="nav-item"
							class:active
							title={collapsed ? item.label : undefined}
						>
							<svelte:component this={item.icon} size={20} strokeWidth={active ? 2.2 : 1.8} />
							{#if !collapsed}
								<span>{item.label}</span>
							{/if}
						</a>
					</li>
				{/if}
			{/each}
		</ul>

		<div class="sidebar-footer">
			{#if user?.authenticated}
				<div class="user-info" title={user.email}>
					{#if !collapsed}
						<span class="user-name">{user.displayName || user.email}</span>
					{/if}
				</div>
				<a href="/oauth2-google/sign_out?rd=/" class="nav-item signout-btn" title={collapsed ? 'Sign out' : undefined}>
					<LogOut size={16} strokeWidth={1.8} />
					{#if !collapsed}
						<span>Sign out</span>
					{/if}
				</a>
			{:else}
				<a href="/signin" class="nav-item signin-btn" title={collapsed ? 'Sign in' : undefined}>
					<LogIn size={16} strokeWidth={1.8} />
					{#if !collapsed}
						<span>Sign in</span>
					{/if}
				</a>
			{/if}
		</div>

		<button class="collapse-btn" onclick={() => (collapsed = !collapsed)}>
			{#if collapsed}
				<PanelLeft size={16} strokeWidth={1.8} />
			{:else}
				<PanelLeftClose size={16} strokeWidth={1.8} />
			{/if}
		</button>
	</nav>

	<main class="main-area">
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
		color: var(--vui-text-sub);
		text-decoration: none;
		font-size: var(--vui-text-sm);
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

	.sidebar-footer {
		padding: 8px 0;
		border-top: 1px solid var(--vui-border);
	}

	.user-info {
		padding: 6px 18px;
		overflow: hidden;
	}

	.user-name {
		font-size: var(--vui-text-xs);
		color: var(--vui-text-muted);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
		display: block;
	}

	.signout-btn, .signin-btn {
		font-size: var(--vui-text-xs);
		padding: 8px 18px;
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
		color: var(--vui-text-sub);
		border-color: var(--vui-border-hover);
	}

	.main-area {
		flex: 1;
		display: flex;
		flex-direction: column;
		min-width: 0;
		overflow: hidden;
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
		.nav-item span,
		.user-info {
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
