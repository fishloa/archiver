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
		pdf_pending: 'vui-badge-info',
		pdf_done: 'vui-badge-success',
		translating: 'vui-badge-warning',
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
