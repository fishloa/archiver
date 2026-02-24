<script lang="ts">
	interface Props {
		status: string;
	}

	let { status }: Props = $props();

	const variants: Record<string, string> = {
		ingesting: 'vui-badge-warning',
		ingested: 'vui-badge-info',
		ocr_pending: 'vui-badge-purple',
		ocr_done: 'vui-badge-success',
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
