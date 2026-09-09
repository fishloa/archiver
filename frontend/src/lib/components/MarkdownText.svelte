<script lang="ts">
	import { marked } from 'marked';
	import DOMPurify from 'isomorphic-dompurify';

	/**
	 * Renders Markdown produced by the OCR engines — headings, tables, lists, emphasis.
	 *
	 * The text is model output derived from scanned documents, so it is not trusted
	 * input: it is sanitised before being inserted as HTML. isomorphic-dompurify is
	 * used rather than plain dompurify because this renders during SSR as well as in
	 * the browser.
	 *
	 * Plain-text pages must NOT be passed here. A typescript's centred page number
	 * "- 5 -" is byte-identical to a Markdown bullet, so plain text rendered as
	 * Markdown turns page numbers into list items. The caller decides, using the
	 * content type recorded on the page.
	 */
	interface Props {
		text: string;
		/**
		 * Page this text belongs to. When given, the image references the OCR engine leaves in
		 * the markdown — signatures, stamps and seals on 27,448 pages — are resolved to crops of
		 * the original scan. Without it they render as broken images.
		 */
		pageId?: number;
	}
	let { text, pageId }: Props = $props();

	/**
	 * Rewrites the engine's bare image references to the endpoint that crops them from the scan.
	 * The engine emits ![img-0.jpeg](img-0.jpeg) with no usable location; the coordinates live in
	 * the stored OCR response, so the backend does the cropping.
	 */
	function resolveOcrImages(md: string): string {
		if (!pageId) {
			// Nothing can resolve them, so drop the reference rather than show a broken image.
			return md.replace(/!\[([^\]]*)\]\((img-[^)]+)\)/g, '');
		}
		return md.replace(
			/!\[([^\]]*)\]\((img-[^)]+)\)/g,
			(_m, alt, id) => `![${alt}](/api/pages/${pageId}/ocr-image/${encodeURIComponent(id)})`
		);
	}

	let html = $derived(
		DOMPurify.sanitize(
			marked.parse(resolveOcrImages(text ?? ''), {
				async: false,
				gfm: true,
				breaks: false
			}) as string
		)
	);
</script>

<div class="markdown-text">
	<!-- eslint-disable-next-line svelte/no-at-html-tags -- sanitised above -->
	{@html html}
</div>

<style>
	.markdown-text {
		font-size: var(--vui-text-sm);
		line-height: 1.65;
	}
	.markdown-text :global(h1),
	.markdown-text :global(h2),
	.markdown-text :global(h3),
	.markdown-text :global(h4),
	.markdown-text :global(h5),
	.markdown-text :global(h6) {
		font-weight: 600;
		margin: 1.1em 0 0.4em;
		font-size: 1.05em;
	}
	.markdown-text :global(h1) {
		font-size: 1.15em;
	}
	.markdown-text :global(:first-child) {
		margin-top: 0;
	}
	.markdown-text :global(p) {
		margin: 0 0 0.85em;
	}
	.markdown-text :global(p:last-child) {
		margin-bottom: 0;
	}
	.markdown-text :global(ul),
	.markdown-text :global(ol) {
		margin: 0 0 0.85em;
		padding-left: 1.3em;
	}
	.markdown-text :global(ul) {
		list-style: disc;
	}
	.markdown-text :global(ol) {
		list-style: decimal;
	}
	.markdown-text :global(li) {
		margin: 0.15em 0;
	}
	/* Wide OCR tables must scroll inside the panel rather than widen it. */
	.markdown-text :global(table) {
		display: block;
		overflow-x: auto;
		border-collapse: collapse;
		margin: 0 0 0.85em;
		font-size: 0.95em;
	}
	.markdown-text :global(th),
	.markdown-text :global(td) {
		border: 1px solid var(--vui-border, currentColor);
		padding: 0.3em 0.55em;
		text-align: left;
		vertical-align: top;
	}
	.markdown-text :global(th) {
		font-weight: 600;
	}
	.markdown-text :global(blockquote) {
		margin: 0 0 0.85em;
		padding-left: 0.8em;
		border-left: 2px solid var(--vui-border, currentColor);
	}
	.markdown-text :global(code) {
		font-family: ui-monospace, monospace;
		font-size: 0.92em;
	}
	.markdown-text :global(hr) {
		border: 0;
		border-top: 1px solid var(--vui-border, currentColor);
		margin: 1em 0;
	}
	/* Signatures, stamps and seals cropped from the scan — small, so they read as insets. */
	.markdown-text :global(img) {
		max-width: min(100%, 320px);
		max-height: 160px;
		border: 1px solid var(--vui-border, currentColor);
		border-radius: 4px;
		background: #fff;
		padding: 2px;
		margin: 0.35em 0;
	}
</style>
