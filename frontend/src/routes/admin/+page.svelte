<script lang="ts">
	import { enhance } from '$app/forms';
	import { invalidateAll } from '$app/navigation';
	import {
		ShieldCheck,
		Play,
		Loader,
		AlertTriangle,
		CircleCheckBig,
		Clock,
		RefreshCw,
		Users,
		Plus,
		Trash2,
		Pencil,
		X
	} from 'lucide-svelte';

	let { data, form } = $props();
	let stats = $derived(data.stats);
	let users = $derived(data.users ?? []);

	let auditing = $state(false);

	type StatusRow = { status: string; cnt: number };
	type JobRow = { kind: string; status: string; cnt: number };
	type EventRow = { record_id: number; stage: string; event: string; detail: string | null; created_at: string; record_title: string | null };

	let recordsByStatus = $derived((stats.recordsByStatus ?? []) as StatusRow[]);
	let jobsByKindAndStatus = $derived((stats.jobsByKindAndStatus ?? []) as JobRow[]);
	let recentEvents = $derived((stats.recentEvents ?? []) as EventRow[]);

	let anomalies = $derived([
		{ label: 'Stale claimed jobs (>1hr)', count: stats.staleClaimedJobs as number, severity: 'warning' },
		{ label: 'Failed jobs (retriable)', count: stats.failedRetriableJobs as number, severity: 'danger' },
		{ label: 'Stuck ingesting records', count: stats.stuckIngestingRecords as number, severity: 'danger' },
		{ label: 'OCR done, no post-OCR jobs', count: stats.ocrDoneNoPostOcrJobs as number, severity: 'warning' }
	]);

	let totalAnomalies = $derived(anomalies.reduce((sum, a) => sum + a.count, 0));

	function formatDate(iso: string): string {
		return new Date(iso).toLocaleString();
	}

	// Group jobs by kind
	let jobKinds = $derived.by(() => {
		const map = new Map<string, JobRow[]>();
		for (const row of jobsByKindAndStatus) {
			const list = map.get(row.kind) ?? [];
			list.push(row);
			map.set(row.kind, list);
		}
		return [...map.entries()];
	});

	// User management state
	let editingUserId = $state<number | null>(null);
	let editName = $state('');
	let editRole = $state('user');
	let showAddUser = $state(false);
	let newName = $state('');
	let newRole = $state('user');
	let newEmails = $state('');
	let addingEmailForUserId = $state<number | null>(null);
	let newEmailValue = $state('');

	function startEdit(user: any) {
		editingUserId = user.id;
		editName = user.display_name ?? '';
		editRole = user.role ?? 'user';
	}

	function cancelEdit() {
		editingUserId = null;
	}
</script>

<svelte:head>
	<title>Admin &ndash; Archiver</title>
</svelte:head>

<div class="flex items-center justify-between mb-8">
	<h1 class="text-[length:var(--vui-text-2xl)] font-extrabold tracking-tight flex items-center gap-3">
		<ShieldCheck size={24} strokeWidth={2} class="text-accent" />
		Admin
	</h1>
	<button class="vui-btn vui-btn-ghost vui-btn-sm" onclick={() => invalidateAll()}>
		<RefreshCw size={13} strokeWidth={2} /> Refresh
	</button>
</div>

<!-- Users Section -->
<div class="vui-card mb-6 vui-animate-fade-in">
	<div class="flex items-center justify-between mb-4">
		<h2 class="text-[length:var(--vui-text-lg)] font-bold flex items-center gap-2">
			<Users size={18} strokeWidth={2} />
			Users
		</h2>
		<button class="vui-btn vui-btn-primary vui-btn-sm" onclick={() => { showAddUser = !showAddUser; }}>
			<Plus size={13} strokeWidth={2} /> Add User
		</button>
	</div>

	{#if showAddUser}
		<form method="POST" action="?/createUser" class="mb-4 p-3 rounded-md border border-border bg-bg-deep" use:enhance={() => {
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
				<input type="text" name="displayName" placeholder="Display name" bind:value={newName} required
					class="vui-input text-[length:var(--vui-text-sm)]" />
				<select name="role" bind:value={newRole} class="vui-input text-[length:var(--vui-text-sm)]">
					<option value="user">User</option>
					<option value="admin">Admin</option>
				</select>
				<input type="text" name="emails" placeholder="email@example.com" bind:value={newEmails}
					class="vui-input text-[length:var(--vui-text-sm)]" />
			</div>
			<div class="flex gap-2">
				<button type="submit" class="vui-btn vui-btn-primary vui-btn-sm">Create</button>
				<button type="button" class="vui-btn vui-btn-ghost vui-btn-sm" onclick={() => { showAddUser = false; }}>Cancel</button>
			</div>
		</form>
	{/if}

	{#if users.length === 0}
		<p class="text-text-sub text-[length:var(--vui-text-sm)]">No users found.</p>
	{:else}
		<div class="overflow-x-auto">
			<table class="w-full text-left text-[length:var(--vui-text-sm)]">
				<thead>
					<tr class="border-b border-border">
						<th class="px-3 py-2 text-[11px] font-semibold uppercase tracking-wide text-text-sub">Name</th>
						<th class="px-3 py-2 text-[11px] font-semibold uppercase tracking-wide text-text-sub">Emails</th>
						<th class="px-3 py-2 text-[11px] font-semibold uppercase tracking-wide text-text-sub">Role</th>
						<th class="px-3 py-2 text-[11px] font-semibold uppercase tracking-wide text-text-sub w-28">Actions</th>
					</tr>
				</thead>
				<tbody>
					{#each users as user}
						<tr class="border-b border-border">
							{#if editingUserId === user.id}
								<td class="px-3 py-2" colspan="4">
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
										<button type="submit" class="vui-btn vui-btn-primary vui-btn-sm">Save</button>
										<button type="button" class="vui-btn vui-btn-ghost vui-btn-sm" onclick={cancelEdit}>
											<X size={13} />
										</button>
									</form>
								</td>
							{:else}
								<td class="px-3 py-2 text-text">{user.display_name ?? '—'}</td>
								<td class="px-3 py-2 text-text-sub">
									{user.emails || '—'}
									{#if addingEmailForUserId === user.id}
										<form method="POST" action="?/addEmail" class="inline-flex items-center gap-1 ml-2" use:enhance={() => {
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
										<button class="text-accent hover:text-accent-hover ml-1 text-[length:var(--vui-text-xs)]"
											onclick={() => { addingEmailForUserId = user.id; }}>+</button>
									{/if}
								</td>
								<td class="px-3 py-2">
									<span class="px-2 py-0.5 rounded text-[length:var(--vui-text-xs)] border border-border
										{user.role === 'admin' ? 'text-yellow-400' : 'text-text-sub'}">
										{user.role}
									</span>
								</td>
								<td class="px-3 py-2">
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
												onclick={(e) => { if (!confirm('Delete this user?')) e.preventDefault(); }}>
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

<!-- Audit Panel -->
<div class="vui-card mb-6 vui-animate-fade-in">
	<div class="flex items-center justify-between mb-4">
		<h2 class="text-[length:var(--vui-text-lg)] font-bold">Pipeline Auditor</h2>
		{#if totalAnomalies > 0}
			<span class="vui-badge vui-badge-warning">
				<AlertTriangle size={11} class="inline" /> {totalAnomalies} anomalies detected
			</span>
		{:else}
			<span class="vui-badge vui-badge-success">
				<CircleCheckBig size={11} class="inline" /> All clear
			</span>
		{/if}
	</div>

	<!-- Anomaly cards -->
	<div class="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
		{#each anomalies as anomaly}
			{@const hasIssue = anomaly.count > 0}
			<div class="px-3 py-2.5 rounded-md border {hasIssue ? 'border-yellow-500/30 bg-yellow-500/5' : 'border-border bg-bg-deep'}">
				<div class="text-[length:var(--vui-text-2xl)] font-extrabold tabular-nums {hasIssue ? 'text-yellow-400' : 'text-text-muted'}">
					{anomaly.count}
				</div>
				<div class="text-[length:var(--vui-text-xs)] text-text-sub">{anomaly.label}</div>
			</div>
		{/each}
	</div>

	<!-- Run Audit Button -->
	<form method="POST" action="?/audit" use:enhance={() => {
		auditing = true;
		return async ({ update }) => {
			await update();
			auditing = false;
			await invalidateAll();
		};
	}}>
		<button type="submit" class="vui-btn vui-btn-primary" disabled={auditing}>
			{#if auditing}
				<Loader size={13} strokeWidth={2} class="animate-spin" /> Running audit...
			{:else}
				<Play size={13} strokeWidth={2} /> Run Pipeline Audit
			{/if}
		</button>
	</form>

	{#if form?.fixed !== undefined}
		<div class="mt-3 px-3 py-2 rounded-md bg-green-500/10 border border-green-500/30 text-[length:var(--vui-text-sm)]">
			<CircleCheckBig size={13} class="inline text-green-400" />
			Audit complete: <strong>{form.fixed}</strong> record(s)/job(s) fixed
		</div>
	{/if}
</div>

<!-- Record Status Breakdown -->
<div class="grid grid-cols-1 md:grid-cols-2 gap-6 mb-6">
	<div class="vui-card vui-animate-fade-in">
		<h2 class="text-[length:var(--vui-text-lg)] font-bold mb-4">Records by Status</h2>
		<div class="space-y-2">
			{#each recordsByStatus as row}
				<div class="flex items-center justify-between py-1.5 px-3 rounded bg-bg-deep border border-border">
					<span class="text-[length:var(--vui-text-sm)] font-medium text-text">{row.status}</span>
					<span class="text-[length:var(--vui-text-sm)] font-bold tabular-nums text-accent">{row.cnt}</span>
				</div>
			{/each}
		</div>
	</div>

	<!-- Jobs by Kind -->
	<div class="vui-card vui-animate-fade-in">
		<h2 class="text-[length:var(--vui-text-lg)] font-bold mb-4">Jobs by Kind</h2>
		<div class="space-y-3">
			{#each jobKinds as [kind, rows]}
				<div>
					<div class="text-[length:var(--vui-text-sm)] font-semibold text-text mb-1">{kind.replace(/_/g, ' ')}</div>
					<div class="flex flex-wrap gap-2">
						{#each rows as row}
							<span class="px-2 py-0.5 rounded text-[length:var(--vui-text-xs)] tabular-nums border border-border
								{row.status === 'completed' ? 'text-green-400' : row.status === 'failed' ? 'text-red-400' : row.status === 'claimed' ? 'text-yellow-400' : 'text-text-sub'}">
								{row.cnt} {row.status}
							</span>
						{/each}
					</div>
				</div>
			{/each}
		</div>
	</div>
</div>

<!-- Recent Pipeline Events -->
<div class="vui-card vui-animate-fade-in">
	<h2 class="text-[length:var(--vui-text-lg)] font-bold mb-4 flex items-center gap-2">
		<Clock size={16} strokeWidth={2} />
		Recent Pipeline Events
	</h2>
	{#if recentEvents.length === 0}
		<p class="text-text-sub text-[length:var(--vui-text-sm)]">No pipeline events yet.</p>
	{:else}
		<div class="overflow-x-auto">
			<table class="w-full text-left text-[length:var(--vui-text-sm)]">
				<thead>
					<tr class="border-b border-border">
						<th class="px-3 py-2 text-[11px] font-semibold uppercase tracking-wide text-text-sub">Time</th>
						<th class="px-3 py-2 text-[11px] font-semibold uppercase tracking-wide text-text-sub">Stage</th>
						<th class="px-3 py-2 text-[11px] font-semibold uppercase tracking-wide text-text-sub">Event</th>
						<th class="px-3 py-2 text-[11px] font-semibold uppercase tracking-wide text-text-sub">Detail</th>
						<th class="px-3 py-2 text-[11px] font-semibold uppercase tracking-wide text-text-sub">Record</th>
					</tr>
				</thead>
				<tbody>
					{#each recentEvents as ev}
						<tr class="border-b border-border">
							<td class="px-3 py-2 text-text-sub whitespace-nowrap tabular-nums">{formatDate(ev.created_at)}</td>
							<td class="px-3 py-2 text-text">{ev.stage}</td>
							<td class="px-3 py-2">
								<span class="{ev.event === 'completed' ? 'text-green-400' : ev.event === 'failed' ? 'text-red-400' : 'text-yellow-400'}">
									{ev.event}
								</span>
							</td>
							<td class="px-3 py-2 text-text-sub">{ev.detail ?? ''}</td>
							<td class="px-3 py-2 max-w-[280px]">
								<a href="/records/{ev.record_id}" class="text-accent hover:text-accent-hover hover:underline" title={ev.record_title ?? `#${ev.record_id}`}>
									<span class="text-text-muted">#{ev.record_id}</span>
									{#if ev.record_title}
										<span class="ml-1.5 truncate inline-block max-w-[200px] align-bottom">{ev.record_title}</span>
									{/if}
								</a>
							</td>
						</tr>
					{/each}
				</tbody>
			</table>
		</div>
	{/if}
</div>
