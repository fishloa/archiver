<script lang="ts">
	import { enhance } from '$app/forms';
	import { invalidateAll } from '$app/navigation';
	import { tick } from 'svelte';
	import { t } from '$lib/i18n';
	import { Plus, Trash2, Pencil, X, Check, Users } from 'lucide-svelte';
	import type { AdminUser } from '$lib/server/api';

	let { data, form } = $props();
	let users = $derived(data.users ?? []) as AdminUser[];

	// Single source of truth: null = viewing, number = editing that user, 'new' = creating
	let editingId = $state<number | 'new' | null>(null);
	let draftName = $state('');
	let draftRole = $state('user');
	let draftEmails = $state('');

	let addingEmailForUserId = $state<number | null>(null);
	let newEmailValue = $state('');

	let nameInputEl = $state<HTMLInputElement | null>(null);

	function startEdit(user: AdminUser) {
		editingId = user.id;
		draftName = user.display_name ?? '';
		draftRole = user.role ?? 'user';
		draftEmails = '';
		tick().then(() => nameInputEl?.focus());
	}

	function startCreate() {
		editingId = 'new';
		draftName = '';
		draftRole = 'user';
		draftEmails = '';
		tick().then(() => nameInputEl?.focus());
	}

	function cancelEdit() {
		editingId = null;
	}

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') cancelEdit();
	}
</script>

<div class="vui-card vui-animate-fade-in">
	<div class="flex items-center justify-between mb-4">
		<h2 class="text-[length:var(--vui-text-lg)] font-bold flex items-center gap-2">
			<Users size={18} strokeWidth={2} />
			{$t('admin.users')}
		</h2>
		<button
			class="vui-btn vui-btn-primary vui-btn-sm"
			onclick={() => { editingId === 'new' ? cancelEdit() : startCreate(); }}
		>
			{#if editingId === 'new'}
				<X size={13} strokeWidth={2} /> {$t('admin.cancel')}
			{:else}
				<Plus size={13} strokeWidth={2} /> {$t('admin.addUser')}
			{/if}
		</button>
	</div>

	{#if users.length === 0 && editingId !== 'new'}
		<p class="text-text-sub text-[length:var(--vui-text-sm)]">{$t('admin.noUsers')}</p>
	{:else}
		<div class="overflow-x-auto">
			<table class="w-full text-left text-[length:var(--vui-text-sm)] table-fixed">
				<colgroup>
					<col style="width: 260px" />
					<col />
					<col style="width: 140px" />
					<col style="width: 120px" />
				</colgroup>
				<thead>
					<tr class="border-b border-border">
						<th class="px-4 py-3 text-[11px] font-semibold uppercase tracking-wide text-text-sub">{$t('admin.name')}</th>
						<th class="px-4 py-3 text-[11px] font-semibold uppercase tracking-wide text-text-sub">{$t('admin.emails')}</th>
						<th class="px-4 py-3 text-[11px] font-semibold uppercase tracking-wide text-text-sub">{$t('admin.role')}</th>
						<th class="px-4 py-3 text-[11px] font-semibold uppercase tracking-wide text-text-sub text-right">{$t('admin.actions')}</th>
					</tr>
				</thead>
				<tbody>
					<!-- New user: display row + editor row -->
					{#if editingId === 'new'}
						<tr class="border-b border-border bg-accent/5">
							<td class="px-4 py-3 text-text-muted italic" colspan="3">{$t('admin.addUser')}</td>
							<td class="px-4 py-3 text-right whitespace-nowrap">
								<button class="vui-btn vui-btn-ghost vui-btn-sm" onclick={cancelEdit} title={$t('admin.cancel')}>
									<X size={13} />
								</button>
							</td>
						</tr>
						<tr class="border-b border-border">
							<td colspan="4" class="p-4 bg-surface/30 border-t border-border">
								<form method="POST" action="?/createUser" class="grid grid-cols-[1fr_1fr_auto] gap-3 items-end" use:enhance={() => {
									return async ({ update }) => {
										await update();
										editingId = null;
										draftName = '';
										draftRole = 'user';
										draftEmails = '';
										await invalidateAll();
									};
								}}>
									<label class="block">
										<span class="block text-[11px] font-semibold uppercase tracking-wide text-text-sub mb-1">{$t('admin.name')}</span>
										<input type="text" name="displayName" bind:value={draftName} bind:this={nameInputEl}
											required placeholder={$t('admin.displayName')}
											onkeydown={handleKeydown}
											class="vui-input text-[length:var(--vui-text-sm)] w-full" />
									</label>
									<label class="block">
										<span class="block text-[11px] font-semibold uppercase tracking-wide text-text-sub mb-1">{$t('admin.emails')}</span>
										<input type="text" name="emails" bind:value={draftEmails}
											placeholder="email@example.com"
											onkeydown={handleKeydown}
											class="vui-input text-[length:var(--vui-text-sm)] w-full" />
									</label>
									<div class="flex items-end gap-2">
										<label class="block">
											<span class="block text-[11px] font-semibold uppercase tracking-wide text-text-sub mb-1">{$t('admin.role')}</span>
											<select name="role" bind:value={draftRole} class="vui-input text-[length:var(--vui-text-sm)]">
												<option value="user">User</option>
												<option value="admin">Admin</option>
											</select>
										</label>
										<button type="submit" class="vui-btn vui-btn-primary vui-btn-sm">
											<Check size={13} strokeWidth={2} /> {$t('admin.create')}
										</button>
									</div>
								</form>
							</td>
						</tr>
					{/if}

					{#each users as user}
						<!-- Display row: always rendered -->
						<tr class="border-b border-border hover:bg-surface/50 transition-colors {editingId === user.id ? 'bg-accent/5' : ''}">
							<td class="px-4 py-3 text-text align-middle truncate">{user.display_name ?? '—'}</td>
							<td class="px-4 py-3 align-middle">
								<div class="flex flex-wrap items-center gap-1.5 min-w-0">
									{#each user.emails as email}
										<span class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[length:var(--vui-text-xs)] bg-bg-deep border border-border text-text-sub max-w-[220px]">
											<span class="truncate">{email.email}</span>
											<form method="POST" action="?/removeEmail" class="inline flex-shrink-0" use:enhance={() => {
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
							<td class="px-4 py-3 align-middle">
								<span class="px-2.5 py-1 rounded text-[length:var(--vui-text-xs)] font-medium border border-border
									{user.role === 'admin' ? 'text-yellow-400 border-yellow-500/30 bg-yellow-500/5' : 'text-text-sub'}">
									{user.role}
								</span>
							</td>
							<td class="px-4 py-3 align-middle">
								<div class="flex justify-end gap-2 whitespace-nowrap">
									{#if editingId === user.id}
										<button class="vui-btn vui-btn-ghost vui-btn-sm" onclick={cancelEdit} title={$t('admin.cancel')}>
											<X size={13} />
										</button>
									{:else}
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
									{/if}
								</div>
							</td>
						</tr>

						<!-- Editor row: only when editing this user -->
						{#if editingId === user.id}
							<tr class="border-b border-border">
								<td colspan="4" class="p-4 bg-surface/30 border-t border-border">
									<form method="POST" action="?/updateUser" class="flex items-end gap-3" use:enhance={() => {
										return async ({ update }) => {
											await update();
											editingId = null;
											await invalidateAll();
										};
									}}>
										<input type="hidden" name="id" value={user.id} />
										<label class="flex-1 block">
											<span class="block text-[11px] font-semibold uppercase tracking-wide text-text-sub mb-1">{$t('admin.name')}</span>
											<input type="text" name="displayName" bind:value={draftName} bind:this={nameInputEl}
												onkeydown={handleKeydown}
												class="vui-input text-[length:var(--vui-text-sm)] w-full" />
										</label>
										<label class="block">
											<span class="block text-[11px] font-semibold uppercase tracking-wide text-text-sub mb-1">{$t('admin.role')}</span>
											<select name="role" bind:value={draftRole} class="vui-input text-[length:var(--vui-text-sm)]">
												<option value="user">User</option>
												<option value="admin">Admin</option>
											</select>
										</label>
										<button type="submit" class="vui-btn vui-btn-primary vui-btn-sm">
											<Check size={13} strokeWidth={2} /> {$t('admin.save')}
										</button>
									</form>
								</td>
							</tr>
						{/if}
					{/each}
				</tbody>
			</table>
		</div>
	{/if}
</div>
