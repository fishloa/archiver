<script lang="ts">
	import '../app.css';
	import { Archive } from 'lucide-svelte';
	import type { Snippet } from 'svelte';

	let { children }: { children: Snippet } = $props();
</script>

<div class="min-h-screen bg-bg-deep text-text">
	<nav class="vui-glass border-b border-border sticky top-0 z-10">
		<div class="mx-auto flex max-w-6xl items-center gap-3 px-6 py-3">
			<Archive size={18} strokeWidth={1.8} class="text-accent" />
			<a href="/" class="text-[var(--vui-text-lg)] font-bold tracking-tight">Archiver</a>
		</div>
	</nav>
	<main class="mx-auto max-w-6xl px-6 py-8">
		{@render children()}
	</main>
</div>
