<script lang="ts">
	interface Props {
		status: string;
	}

	let { status }: Props = $props();

	const colors: Record<string, string> = {
		ingested: 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200',
		processing: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200',
		complete: 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200',
		error: 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200'
	};

	let cls = $derived(colors[status] ?? 'bg-zinc-100 text-zinc-800 dark:bg-zinc-700 dark:text-zinc-200');
</script>

<span class="inline-block rounded-full px-2 py-0.5 text-xs font-medium {cls}">
	{status}
</span>
