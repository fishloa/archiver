<script lang="ts">
	import { enhance } from '$app/forms';

	let { data, form } = $props();
	let status = $derived(data.request?.status ?? 'none');
	let sending = $state(false);
</script>

<svelte:head>
	<title>No access — Czernin Archive</title>
</svelte:head>

<div class="no-access-page">
	<section class="card">
		<h1>No access</h1>
		<p>
			You are signed in as <strong>{data.signedInAs}</strong>, but that address is not on the
			access list for this archive.
		</p>

		{#if status === 'pending'}
			<p class="notice">
				Your request for access has been sent. You will be able to use the archive once it is
				approved; sign in again then.
			</p>
		{:else if status === 'denied'}
			<p class="hint">Your request for access was not approved.</p>
		{:else if data.request}
			<p class="hint">You can ask the archive owner for access.</p>
			<form
				method="POST"
				action="?/request"
				class="request-form"
				use:enhance={() => {
					sending = true;
					return async ({ update }) => {
						await update();
						sending = false;
					};
				}}
			>
				{#if form?.error}
					<div class="form-error">{form.error}</div>
				{/if}
				<label class="field">
					<span class="field-label">Your name</span>
					<input
						name="name"
						type="text"
						required
						maxlength="200"
						value={form?.name ?? ''}
						class="field-input"
					/>
				</label>
				<label class="field">
					<span class="field-label">Message (optional)</span>
					<textarea
						name="message"
						rows="4"
						maxlength="2000"
						placeholder="Who you are and why you need access"
						class="field-input">{form?.message ?? ''}</textarea
					>
				</label>
				<button type="submit" class="btn-primary" disabled={sending}>Request access</button>
			</form>
		{:else}
			<p class="hint">If you believe it should be, contact the archive owner.</p>
		{/if}

		<div class="signout-row">
			<a class="btn-secondary" href="/signout">Sign out</a>
		</div>
	</section>
</div>

<style>
	.no-access-page {
		max-width: 480px;
		margin: 96px auto 0;
		padding: 0 16px;
	}

	.card {
		background: var(--vui-glass);
		border: 1px solid var(--vui-border);
		border-radius: var(--vui-radius-lg);
		padding: 32px;
		text-align: center;
	}

	h1 {
		font-size: var(--vui-text-2xl);
		font-weight: 700;
		margin-bottom: 16px;
	}

	p {
		font-size: var(--vui-text-sm);
		color: var(--vui-text);
		line-height: 1.6;
		margin-bottom: 12px;
	}

	.hint {
		color: var(--vui-text-muted);
	}

	.notice {
		padding: 12px 14px;
		border-radius: var(--vui-radius-md);
		background: var(--vui-accent-dim);
		border: 1px solid var(--vui-accent);
	}

	.request-form {
		display: flex;
		flex-direction: column;
		gap: 14px;
		margin-top: 16px;
		text-align: left;
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
		color: var(--vui-text-muted);
	}

	.field-input {
		padding: 10px 14px;
		background: var(--vui-bg-deep);
		border: 1.5px solid var(--vui-border);
		border-radius: var(--vui-radius-md);
		color: var(--vui-text);
		font-size: 14px;
		font-family: inherit;
		outline: none;
		resize: vertical;
	}

	.field-input:focus {
		border-color: var(--vui-accent);
	}

	.form-error {
		padding: 10px 14px;
		border-radius: var(--vui-radius-md);
		background: rgba(239, 68, 68, 0.08);
		border: 1px solid rgba(239, 68, 68, 0.3);
		color: #ef4444;
		font-size: 13px;
	}

	.signout-row {
		display: flex;
		justify-content: center;
		gap: 12px;
		margin-top: 24px;
	}

	.btn-primary,
	.btn-secondary {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		padding: 8px 16px;
		border-radius: var(--vui-radius-md);
		font-size: var(--vui-text-sm);
		font-weight: 600;
		text-decoration: none;
		font-family: inherit;
		cursor: pointer;
		transition: opacity 0.15s ease;
	}

	.btn-primary {
		background: var(--vui-accent);
		color: var(--vui-bg-deep);
		border: none;
	}

	.btn-primary:disabled {
		opacity: 0.5;
		cursor: default;
	}

	.btn-secondary {
		background: none;
		color: var(--vui-text-muted);
		border: 1.5px solid var(--vui-border);
	}

	.btn-primary:hover,
	.btn-secondary:hover {
		opacity: 0.9;
		text-decoration: none;
	}
</style>
