<script lang="ts">
	interface Props {
		status: string;
	}

	let { status }: Props = $props();

	const variants: Record<string, string> = {
		ingested: 'vui-badge-info',
		processing: 'vui-badge-warning',
		complete: 'vui-badge-success',
		error: 'vui-badge-danger'
	};

	let cls = $derived(variants[status] ?? 'vui-badge-purple');
</script>

<span class="vui-badge {cls}">
	<span class="vui-badge-dot"></span>
	{status}
</span>
