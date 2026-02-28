<script lang="ts">
	import { enhance } from '$app/forms';
	import { invalidateAll } from '$app/navigation';
	import { t } from '$lib/i18n';
	import { Plus, Trash2, Pencil, X, Users } from 'lucide-svelte';
	import type { AdminUser } from '$lib/server/api';

	let { data, form } = $props();
	let users = $derived(data.users ?? []) as AdminUser[];

	let editingUserId = $state<number | null>(null);
	let editName = $state('');
	let editRole = $state('user');
	let showAddUser = $state(false);
	let newName = $state('');
	let newRole = $state('user');
	let newEmails = $state('');
	let addingEmailForUserId = $state<number | null>(null);
	let newEmailValue = $state('');

	function startEdit(user: AdminUser) {
		editingUserId = user.id;
		editName = user.display_name ?? '';
		editRole = user.role ?? 'user';
	}

	function cancelEdit() {
		editingUserId = null;
	}
</script>

<div class="vui-card vui-animate-fade-in">
	<div class="flex items-center justify-between mb-4">
		<h2 class="text-[length:var(--vui-text-lg)] font-bold flex items-center gap-2">
			<Users size={18} strokeWidth={2} />
			{$t('admin.users')}
		</h2>
		<button class="vui-btn vui-btn-primary vui-btn-sm" onclick={() => { showAddUser = !showAddUser; }}>
			<Plus size={13} strokeWidth={2} /> {$t('admin.addUser')}
		</button>
	</div>

	{#if showAddUser}
		<form method="POST" action="?/createUser" class="vui-card mb-4 !bg-bg-deep" use:enhance={() => {
			return async ({ update }) => {
				await update();
				showAddUser = false;
				newName = '';
				newRole = 'user';
				newEmails = '';
				await invalidateAll();
			};
		}}>
			<div class="grid grid-cols-1 md:grid-cols-3 gap-3 mb-3">
				<input type="text" name="displayName" placeholder={$t('admin.displayName')} bind:value={newName} required
					class="vui-input text-[length:var(--vui-text-sm)]" />
				<select name="role" bind:value={newRole} class="vui-input text-[length:var(--vui-text-sm)]">
					<option value="user">User</option>
					<option value="admin">Admin</option>
				</select>
				<input type="text" name="emails" placeholder="email@example.com" bind:value={newEmails}
					class="vui-input text-[length:var(--vui-text-sm)]" />
			</div>
			<div class="flex gap-2">
				<button type="submit" class="vui-btn vui-btn-primary vui-btn-sm">{$t('admin.create')}</button>
				<button type="button" class="vui-btn vui-btn-ghost vui-btn-sm" onclick={() => { showAddUser = false; }}>{$t('admin.cancel')}</button>
			</div>
		</form>
	{/if}

	{#if users.length === 0}
		<p class="text-text-sub text-[length:var(--vui-text-sm)]">{$t('admin.noUsers')}</p>
	{:else}
		<div class="overflow-x-auto">
			<table class="w-full text-left text-[length:var(--vui-text-sm)]">
				<thead>
					<tr class="border-b border-border">
						<th class="px-4 py-3 text-[11px] font-semibold uppercase tracking-wide text-text-sub">{$t('admin.name')}</th>
						<th class="px-4 py-3 text-[11px] font-semibold uppercase tracking-wide text-text-sub">{$t('admin.emails')}</th>
						<th class="px-4 py-3 text-[11px] font-semibold uppercase tracking-wide text-text-sub">{$t('admin.role')}</th>
						<th class="px-4 py-3 text-[11px] font-semibold uppercase tracking-wide text-text-sub w-28">{$t('admin.actions')}</th>
					</tr>
				</thead>
				<tbody>
					{#each users as user}
						<tr class="border-b border-border hover:bg-surface/50 transition-colors">
							{#if editingUserId === user.id}
								<td class="px-4 py-3" colspan="4">
									<form method="POST" action="?/updateUser" class="flex items-center gap-2" use:enhance={() => {
										return async ({ update }) => {
											await update();
											editingUserId = null;
											await invalidateAll();
										};
									}}>
										<input type="hidden" name="id" value={user.id} />
										<input type="text" name="displayName" bind:value={editName} class="vui-input text-[length:var(--vui-text-sm)] flex-1" />
										<select name="role" bind:value={editRole} class="vui-input text-[length:var(--vui-text-sm)] w-24">
											<option value="user">User</option>
											<option value="admin">Admin</option>
										</select>
										<button type="submit" class="vui-btn vui-btn-primary vui-btn-sm">{$t('admin.save')}</button>
										<button type="button" class="vui-btn vui-btn-ghost vui-btn-sm" onclick={cancelEdit}>
											<X size={13} />
										</button>
									</form>
								</td>
							{:else}
								<td class="px-4 py-3 text-text">{user.display_name ?? '—'}</td>
								<td class="px-4 py-3">
									<div class="flex flex-wrap items-center gap-1.5">
										{#each user.emails as email}
											<span class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[length:var(--vui-text-xs)] bg-bg-deep border border-border text-text-sub">
												{email.email}
												<form method="POST" action="?/removeEmail" class="inline" use:enhance={() => {
													return async ({ update }) => {
														await update();
														await invalidateAll();
													};
												}}>
													<input type="hidden" name="userId" value={user.id} />
													<input type="hidden" name="emailId" value={email.id} />
													<button type="submit" class="text-text-muted hover:text-red-400 transition-colors" title="Remove">
														<X size={11} />
													</button>
												</form>
											</span>
										{/each}
										{#if addingEmailForUserId === user.id}
											<form method="POST" action="?/addEmail" class="inline-flex items-center gap-1" use:enhance={() => {
												return async ({ update }) => {
													await update();
													addingEmailForUserId = null;
													newEmailValue = '';
													await invalidateAll();
												};
											}}>
												<input type="hidden" name="userId" value={user.id} />
												<input type="email" name="email" bind:value={newEmailValue} placeholder="new@email.com"
													class="vui-input text-[length:var(--vui-text-xs)] w-40" />
												<button type="submit" class="vui-btn vui-btn-primary vui-btn-sm text-[length:var(--vui-text-xs)]">Add</button>
												<button type="button" class="vui-btn vui-btn-ghost vui-btn-sm text-[length:var(--vui-text-xs)]" onclick={() => { addingEmailForUserId = null; }}>
													<X size={11} />
												</button>
											</form>
										{:else}
											<button class="text-accent hover:text-accent-hover text-[length:var(--vui-text-xs)] px-1.5 py-0.5 rounded border border-dashed border-border hover:border-accent transition-colors"
												onclick={() => { addingEmailForUserId = user.id; }}>+</button>
										{/if}
									</div>
								</td>
								<td class="px-4 py-3">
									<span class="px-2.5 py-1 rounded text-[length:var(--vui-text-xs)] font-medium border border-border
										{user.role === 'admin' ? 'text-yellow-400 border-yellow-500/30 bg-yellow-500/5' : 'text-text-sub'}">
										{user.role}
									</span>
								</td>
								<td class="px-4 py-3">
									<div class="flex items-center gap-1">
										<button class="vui-btn vui-btn-ghost vui-btn-sm" title="Edit" onclick={() => startEdit(user)}>
											<Pencil size={13} />
										</button>
										<form method="POST" action="?/deleteUser" use:enhance={() => {
											return async ({ update }) => {
												await update();
												await invalidateAll();
											};
										}}>
											<input type="hidden" name="id" value={user.id} />
											<button type="submit" class="vui-btn vui-btn-ghost vui-btn-sm text-red-400" title="Delete"
												onclick={(e) => { if (!confirm($t('admin.deleteConfirm'))) e.preventDefault(); }}>
												<Trash2 size={13} />
											</button>
										</form>
									</div>
								</td>
							{/if}
						</tr>
					{/each}
				</tbody>
			</table>
		</div>
	{/if}
</div>
