<script lang="ts">
	let { data } = $props();
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
		<p class="hint">If you believe it should be, contact the archive owner.</p>

		<div class="signout-row">
			<a class="btn-primary" href="/oauth2-google/sign_out?rd=/signin">Sign out (Google)</a>
			<a class="btn-primary" href="/oauth2-apple/sign_out?rd=/signin">Sign out (Apple)</a>
		</div>
	</section>
</div>

<style>
	.no-access-page {
		max-width: 480px;
		margin: 96px auto 0;
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

	.signout-row {
		display: flex;
		justify-content: center;
		gap: 12px;
		margin-top: 24px;
	}

	.btn-primary {
		display: inline-flex;
		align-items: center;
		padding: 8px 16px;
		background: var(--vui-accent);
		color: var(--vui-bg-deep);
		border: none;
		border-radius: var(--vui-radius-md);
		font-size: var(--vui-text-sm);
		font-weight: 600;
		text-decoration: none;
		transition: opacity 0.15s ease;
	}

	.btn-primary:hover {
		opacity: 0.9;
		text-decoration: none;
	}
</style>
