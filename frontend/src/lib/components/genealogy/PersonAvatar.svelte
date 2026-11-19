<script lang="ts">
	let { url, name, size = 'md' }: { url: string | null; name: string; size?: 'sm' | 'md' } =
		$props();
</script>

{#if url}
	<img class="avatar {size}" src={url} alt="" loading="lazy" />
{/if}

<style>
	.avatar {
		border-radius: 50%;
		object-fit: cover;
		flex-shrink: 0;
	}
	.sm {
		width: 1.5rem;
		height: 1.5rem;
	}
	.md {
		width: 2.5rem;
		height: 2.5rem;
	}
</style>
