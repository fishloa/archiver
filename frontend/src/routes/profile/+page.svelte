<script lang="ts">
	import { enhance } from '$app/forms';
	import { invalidateAll } from '$app/navigation';
	import { Mail, Trash2, Plus } from 'lucide-svelte';
	import { language, t } from '$lib/i18n';

	let { data, form } = $props();
	let profile = $derived(data.profile);
	let newEmail = $state('');
	let saving = $state(false);
	let lang = $derived($language);
</script>

<svelte:head>
	<title>{t('profile.title')} — Archiver</title>
</svelte:head>

<div class="profile-page">
	<h1>{t('profile.title')}</h1>

	<section class="card">
		<h2>{t('profile.displayName')}</h2>
		<p class="hint">{t('profile.displayNameHint')}</p>
		<form
			method="POST"
			action="?/updateName"
			use:enhance={() => {
				saving = true;
				return async ({ update }) => {
					await update();
					saving = false;
					invalidateAll();
				};
			}}
		>
			<div class="input-row">
				<input type="text" name="displayName" value={profile.displayName} class="text-input" />
				<button type="submit" class="btn-primary" disabled={saving}>
					{saving ? t('profile.saving') : t('profile.save')}
				</button>
			</div>
		</form>
	</section>

	<section class="card">
		<h2>{t('profile.emailAddresses')}</h2>
		<p class="hint">
			{t('profile.emailHint')}
		</p>

		<ul class="email-list">
			{#each profile.emails as emailEntry}
				{@const isLogin = emailEntry.email === profile.loginEmail}
				<li class="email-row">
					<Mail size={16} strokeWidth={1.8} class="email-icon" />
					<span class="email-addr" class:is-login={isLogin}>
						{emailEntry.email}
					</span>
					{#if isLogin}
						<span class="badge">{t('profile.current')}</span>
					{:else}
						<form
							method="POST"
							action="?/removeEmail"
							use:enhance={() => {
								return async ({ update }) => {
									await update();
									invalidateAll();
								};
							}}
						>
							<input type="hidden" name="emailId" value={emailEntry.id} />
							<button type="submit" class="btn-icon-danger" title={t('profile.removeEmail')}>
								<Trash2 size={14} strokeWidth={1.8} />
							</button>
						</form>
					{/if}
				</li>
			{/each}
		</ul>

		<form
			method="POST"
			action="?/addEmail"
			use:enhance={() => {
				return async ({ update }) => {
					await update();
					newEmail = '';
					invalidateAll();
				};
			}}
		>
			<div class="input-row">
				<input
					type="email"
					name="email"
					bind:value={newEmail}
					placeholder={t('profile.addEmail')}
					class="text-input"
				/>
				<button type="submit" class="btn-primary" disabled={!newEmail.trim()}>
					<Plus size={16} strokeWidth={2} />
					{t('profile.add')}
				</button>
			</div>
		</form>

		{#if form?.error}
			<p class="error-msg">{form.error}</p>
		{/if}
	</section>
</div>

<style>
	.profile-page {
		max-width: 560px;
	}

	h1 {
		font-size: var(--vui-text-2xl);
		font-weight: 700;
		margin-bottom: 24px;
	}

	.card {
		background: var(--vui-glass);
		border: 1px solid var(--vui-border);
		border-radius: var(--vui-radius-lg);
		padding: 20px 24px;
		margin-bottom: 20px;
	}

	h2 {
		font-size: var(--vui-text-base);
		font-weight: 600;
		margin-bottom: 4px;
	}

	.hint {
		font-size: var(--vui-text-xs);
		color: var(--vui-text-muted);
		margin-bottom: 12px;
	}

	.input-row {
		display: flex;
		gap: 8px;
	}

	.text-input {
		flex: 1;
		padding: 8px 12px;
		background: var(--vui-bg-deep);
		border: 1px solid var(--vui-border);
		border-radius: var(--vui-radius-md);
		color: var(--vui-text);
		font-size: var(--vui-text-sm);
	}

	.text-input:focus {
		outline: none;
		border-color: var(--vui-accent);
	}

	.btn-primary {
		display: flex;
		align-items: center;
		gap: 4px;
		padding: 8px 16px;
		background: var(--vui-accent);
		color: var(--vui-bg-deep);
		border: none;
		border-radius: var(--vui-radius-md);
		font-size: var(--vui-text-sm);
		font-weight: 600;
		cursor: pointer;
		transition: opacity 0.15s ease;
		white-space: nowrap;
	}

	.btn-primary:hover {
		opacity: 0.9;
	}

	.btn-primary:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}

	.email-list {
		list-style: none;
		padding: 0;
		margin: 0 0 12px;
	}

	.email-row {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 8px 0;
		border-bottom: 1px solid var(--vui-border);
	}

	.email-row:last-child {
		border-bottom: none;
	}

	:global(.email-icon) {
		color: var(--vui-text-muted);
		flex-shrink: 0;
	}

	.email-addr {
		flex: 1;
		font-size: var(--vui-text-sm);
		color: var(--vui-text-sub);
	}

	.email-addr.is-login {
		color: var(--vui-text);
		font-weight: 500;
	}

	.badge {
		font-size: 10px;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.04em;
		color: var(--vui-accent);
		background: var(--vui-accent-dim);
		padding: 2px 8px;
		border-radius: var(--vui-radius-sm);
	}

	.btn-icon-danger {
		display: flex;
		align-items: center;
		justify-content: center;
		padding: 4px;
		background: none;
		border: none;
		color: var(--vui-text-muted);
		cursor: pointer;
		border-radius: var(--vui-radius-sm);
		transition: all 0.15s ease;
	}

	.btn-icon-danger:hover {
		color: #ef4444;
		background: rgba(239, 68, 68, 0.1);
	}

	.error-msg {
		font-size: var(--vui-text-xs);
		color: #ef4444;
		margin-top: 8px;
	}
</style>
