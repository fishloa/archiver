<script lang="ts">
	import { enhance } from '$app/forms';
	import { invalidateAll } from '$app/navigation';
	import { tick } from 'svelte';
	import { t } from '$lib/i18n';
	import { Plus, Trash2, Pencil, X, Check, Users, Mail } from 'lucide-svelte';
	import type { AdminUser } from '$lib/server/api';

	let { data, form } = $props();
	let users = $derived(data.users ?? []) as AdminUser[];

	// Mode: null = list only, 'new' = creating, number = editing that user
	let mode = $state<number | 'new' | null>(null);
	let draftName = $state('');
	let draftRole = $state('user');
	let draftEmails = $state('');
	let error = $state('');

	let nameInputEl = $state<HTMLInputElement | null>(null);

	function startCreate() {
		mode = 'new';
		draftName = '';
		draftRole = 'user';
		draftEmails = '';
		error = '';
		tick().then(() => nameInputEl?.focus());
	}

	function startEdit(user: AdminUser) {
		mode = user.id;
		draftName = user.display_name ?? '';
		draftRole = user.role ?? 'user';
		draftEmails = '';
		error = '';
		tick().then(() => nameInputEl?.focus());
	}

	function cancel() {
		mode = null;
		error = '';
	}

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') cancel();
	}

	const editingUser = $derived(
		typeof mode === 'number' ? users.find((u) => u.id === mode) ?? null : null
	);

	// Inline email add state
	let addingEmailForUserId = $state<number | null>(null);
	let newEmailValue = $state('');
</script>

<div class="users-page">
	<!-- Form panel: shown when creating or editing -->
	{#if mode !== null}
		<div class="form-card">
			<div class="form-header">
				<h2 class="form-title">
					{#if mode === 'new'}
						{$t('admin.addUser')}
					{:else}
						Edit User
					{/if}
				</h2>
				<button class="form-close" onclick={cancel} title={$t('admin.cancel')}>
					<X size={18} />
				</button>
			</div>

			{#if error}
				<div class="form-error">{error}</div>
			{/if}

			{#if mode === 'new'}
				<form method="POST" action="?/createUser" use:enhance={() => {
					error = '';
					return async ({ result, update }) => {
						if (result.type === 'failure' || result.type === 'error') {
							error = 'Failed to create user. Check the backend logs.';
							return;
						}
						await update();
						mode = null;
						await invalidateAll();
					};
				}}>
					<div class="form-fields">
						<div class="field">
							<label for="create-name" class="field-label">{$t('admin.name')}</label>
							<input id="create-name" type="text" name="displayName"
								bind:value={draftName} bind:this={nameInputEl}
								required placeholder={$t('admin.displayName')}
								onkeydown={handleKeydown}
								class="field-input" />
						</div>
						<div class="field">
							<label for="create-email" class="field-label">{$t('admin.emails')}</label>
							<input id="create-email" type="text" name="emails"
								bind:value={draftEmails}
								placeholder="email@example.com"
								onkeydown={handleKeydown}
								class="field-input" />
							<span class="field-hint">Comma-separated for multiple emails</span>
						</div>
						<div class="field">
							<label for="create-role" class="field-label">{$t('admin.role')}</label>
							<select id="create-role" name="role" bind:value={draftRole} class="field-input field-select">
								<option value="user">User</option>
								<option value="admin">Admin</option>
							</select>
						</div>
					</div>
					<div class="form-actions">
						<button type="button" class="btn-cancel" onclick={cancel}>{$t('admin.cancel')}</button>
						<button type="submit" class="btn-submit">
							<Check size={14} strokeWidth={2.5} />
							{$t('admin.create')}
						</button>
					</div>
				</form>
			{:else if editingUser}
				<!-- Name + Role form -->
				<form method="POST" action="?/updateUser" use:enhance={() => {
					error = '';
					return async ({ result, update }) => {
						if (result.type === 'failure' || result.type === 'error') {
							error = 'Failed to update user. Check the backend logs.';
							return;
						}
						await update();
						mode = null;
						await invalidateAll();
					};
				}}>
					<input type="hidden" name="id" value={editingUser.id} />
					<div class="form-fields">
						<div class="field">
							<label for="edit-name" class="field-label">{$t('admin.name')}</label>
							<input id="edit-name" type="text" name="displayName"
								bind:value={draftName} bind:this={nameInputEl}
								onkeydown={handleKeydown}
								class="field-input" />
						</div>
						<div class="field">
							<label for="edit-role" class="field-label">{$t('admin.role')}</label>
							<select id="edit-role" name="role" bind:value={draftRole} class="field-input field-select">
								<option value="user">User</option>
								<option value="admin">Admin</option>
							</select>
						</div>
					</div>
					<div class="form-actions">
						<button type="button" class="btn-cancel" onclick={cancel}>{$t('admin.cancel')}</button>
						<button type="submit" class="btn-submit">
							<Check size={14} strokeWidth={2.5} />
							{$t('admin.save')}
						</button>
					</div>
				</form>

				<!-- Emails section (separate from update form to avoid nesting) -->
				<div class="email-section">
					<span class="field-label">{$t('admin.emails')}</span>
					<div class="email-list">
						{#each editingUser.emails as email}
							<div class="email-chip">
								<Mail size={12} />
								<span class="email-text">{email.email}</span>
								<form method="POST" action="?/removeEmail" class="inline" use:enhance={() => {
									return async ({ update }) => {
										await update();
										await invalidateAll();
									};
								}}>
									<input type="hidden" name="userId" value={editingUser.id} />
									<input type="hidden" name="emailId" value={email.id} />
									<button type="submit" class="email-remove" title={$t('profile.removeEmail')}>
										<X size={12} />
									</button>
								</form>
							</div>
						{/each}
						<form method="POST" action="?/addEmail" class="email-add-form" use:enhance={() => {
							return async ({ update }) => {
								await update();
								newEmailValue = '';
								await invalidateAll();
							};
						}}>
							<input type="hidden" name="userId" value={editingUser.id} />
							<input type="email" name="email" bind:value={newEmailValue}
								placeholder={$t('profile.addEmail')}
								class="email-add-input" />
							<button type="submit" class="email-add-btn" disabled={!newEmailValue.trim()}>
								<Plus size={12} strokeWidth={2.5} />
							</button>
						</form>
					</div>
				</div>
			{/if}
		</div>
	{/if}

	<!-- User list -->
	<div class="list-card">
		<div class="list-header">
			<h2 class="list-title">
				<Users size={18} strokeWidth={2} />
				{$t('admin.users')}
			</h2>
			{#if mode === null}
				<button class="btn-add" onclick={startCreate}>
					<Plus size={14} strokeWidth={2.5} />
					{$t('admin.addUser')}
				</button>
			{/if}
		</div>

		{#if users.length === 0}
			<p class="empty">{$t('admin.noUsers')}</p>
		{:else}
			<div class="user-list">
				{#each users as user}
					<div class="user-row" class:active={mode === user.id}>
						<div class="user-info">
							<span class="user-name">{user.display_name ?? '—'}</span>
							<div class="user-emails">
								{#each user.emails as email}
									<span class="user-email">{email.email}</span>
								{/each}
							</div>
						</div>
						<span class="user-role" class:role-admin={user.role === 'admin'}>
							{user.role}
						</span>
						<div class="user-actions">
							<button class="action-btn" title="Edit" onclick={() => startEdit(user)}>
								<Pencil size={14} />
							</button>
							<form method="POST" action="?/deleteUser" class="inline" use:enhance={() => {
								return async ({ update }) => {
									await update();
									if (mode === user.id) mode = null;
									await invalidateAll();
								};
							}}>
								<input type="hidden" name="id" value={user.id} />
								<button type="submit" class="action-btn action-danger" title="Delete"
									onclick={(e) => { if (!confirm($t('admin.deleteConfirm'))) e.preventDefault(); }}>
									<Trash2 size={14} />
								</button>
							</form>
						</div>
					</div>
				{/each}
			</div>
		{/if}
	</div>
</div>

<style>
	.users-page {
		max-width: 680px;
	}

	/* ── Form card ── */
	.form-card {
		background: var(--vui-surface);
		border: 1.5px solid var(--vui-accent);
		border-radius: 12px;
		padding: 24px;
		margin-bottom: 24px;
	}

	.form-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-bottom: 20px;
	}

	.form-title {
		font-size: 18px;
		font-weight: 700;
		color: var(--vui-text);
	}

	.form-close {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 32px;
		height: 32px;
		border: none;
		border-radius: 8px;
		background: none;
		color: var(--vui-text-muted);
		cursor: pointer;
	}
	.form-close:hover {
		background: var(--vui-bg-deep);
		color: var(--vui-text);
	}

	.form-error {
		padding: 10px 14px;
		margin-bottom: 16px;
		border-radius: 8px;
		background: rgba(239, 68, 68, 0.08);
		border: 1px solid rgba(239, 68, 68, 0.3);
		color: #ef4444;
		font-size: 13px;
	}

	.form-fields {
		display: flex;
		flex-direction: column;
		gap: 16px;
		margin-bottom: 24px;
	}

	.field {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}

	.field-label {
		font-size: 11px;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: var(--vui-text-sub);
	}

	.field-input {
		padding: 10px 14px;
		background: var(--vui-bg-deep);
		border: 1.5px solid var(--vui-border);
		border-radius: 8px;
		color: var(--vui-text);
		font-size: 14px;
		font-family: inherit;
		outline: none;
		transition: border-color 0.15s ease;
	}
	.field-input:focus {
		border-color: var(--vui-accent);
	}
	.field-input::placeholder {
		color: var(--vui-text-muted);
	}

	.field-select {
		cursor: pointer;
		appearance: auto;
	}

	.field-hint {
		font-size: 11px;
		color: var(--vui-text-muted);
	}

	/* ── Email management in edit form ── */
	.email-list {
		display: flex;
		flex-direction: column;
		gap: 8px;
	}

	.email-chip {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 8px 12px;
		background: var(--vui-bg-deep);
		border: 1px solid var(--vui-border);
		border-radius: 8px;
		font-size: 13px;
		color: var(--vui-text-sub);
	}

	.email-text {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.email-remove {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 20px;
		height: 20px;
		border: none;
		border-radius: 4px;
		background: none;
		color: var(--vui-text-muted);
		cursor: pointer;
		flex-shrink: 0;
	}
	.email-remove:hover {
		color: #ef4444;
		background: rgba(239, 68, 68, 0.1);
	}

	.email-add-form {
		display: flex;
		gap: 6px;
	}

	.email-add-input {
		flex: 1;
		padding: 8px 12px;
		background: var(--vui-bg-deep);
		border: 1.5px dashed var(--vui-border);
		border-radius: 8px;
		color: var(--vui-text);
		font-size: 13px;
		font-family: inherit;
		outline: none;
	}
	.email-add-input:focus {
		border-color: var(--vui-accent);
		border-style: solid;
	}
	.email-add-input::placeholder {
		color: var(--vui-text-muted);
	}

	.email-add-btn {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 34px;
		height: 34px;
		border: 1.5px solid var(--vui-accent);
		border-radius: 8px;
		background: var(--vui-accent-dim);
		color: var(--vui-accent);
		cursor: pointer;
		flex-shrink: 0;
	}
	.email-add-btn:hover {
		background: var(--vui-accent);
		color: var(--vui-bg-deep);
	}
	.email-add-btn:disabled {
		opacity: 0.3;
		cursor: not-allowed;
	}

	.form-actions {
		display: flex;
		justify-content: flex-end;
		gap: 8px;
		padding-top: 16px;
		border-top: 1px solid var(--vui-border);
	}

	.btn-cancel {
		padding: 8px 16px;
		border: 1.5px solid var(--vui-border);
		border-radius: 8px;
		background: none;
		color: var(--vui-text-sub);
		font-size: 13px;
		font-weight: 500;
		cursor: pointer;
		font-family: inherit;
	}
	.btn-cancel:hover {
		border-color: var(--vui-text-muted);
		color: var(--vui-text);
	}

	.btn-submit {
		display: flex;
		align-items: center;
		gap: 6px;
		padding: 8px 20px;
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

	/* ── User list card ── */
	.list-card {
		background: var(--vui-surface);
		border: 1.5px solid var(--vui-border);
		border-radius: 12px;
		padding: 20px 24px;
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

	.btn-add {
		display: flex;
		align-items: center;
		gap: 6px;
		padding: 7px 14px;
		border: none;
		border-radius: 8px;
		background: var(--vui-accent);
		color: var(--vui-bg-deep);
		font-size: 12px;
		font-weight: 600;
		cursor: pointer;
		font-family: inherit;
	}
	.btn-add:hover {
		opacity: 0.9;
	}

	.empty {
		color: var(--vui-text-muted);
		font-size: 14px;
	}

	.user-list {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}

	.user-row {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 12px 14px;
		border-radius: 8px;
		transition: background 0.1s ease;
	}
	.user-row:hover {
		background: var(--vui-bg-deep);
	}
	.user-row.active {
		background: var(--vui-accent-dim);
	}

	.user-info {
		flex: 1;
		min-width: 0;
	}

	.user-name {
		display: block;
		font-size: 14px;
		font-weight: 600;
		color: var(--vui-text);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.user-emails {
		display: flex;
		flex-wrap: wrap;
		gap: 4px;
		margin-top: 2px;
	}

	.user-email {
		font-size: 11px;
		color: var(--vui-text-muted);
	}

	.user-email + .user-email::before {
		content: ', ';
	}

	.user-role {
		padding: 3px 10px;
		border-radius: 6px;
		font-size: 11px;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.03em;
		border: 1px solid var(--vui-border);
		color: var(--vui-text-sub);
		flex-shrink: 0;
	}
	.user-role.role-admin {
		color: #eab308;
		border-color: rgba(234, 179, 8, 0.3);
		background: rgba(234, 179, 8, 0.05);
	}

	.user-actions {
		display: flex;
		gap: 4px;
		flex-shrink: 0;
	}

	.action-btn {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 30px;
		height: 30px;
		border: none;
		border-radius: 6px;
		background: none;
		color: var(--vui-text-muted);
		cursor: pointer;
	}
	.action-btn:hover {
		background: var(--vui-bg-deep);
		color: var(--vui-text);
	}
	.action-danger:hover {
		color: #ef4444;
		background: rgba(239, 68, 68, 0.08);
	}

	.email-section {
		margin-top: 20px;
		padding-top: 20px;
		border-top: 1px solid var(--vui-border);
		display: flex;
		flex-direction: column;
		gap: 8px;
	}

	.inline {
		display: inline;
	}
</style>
