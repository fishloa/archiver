// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/svelte';
import { tick } from 'svelte';
import CropSelector from '../src/lib/components/genealogy/CropSelector.svelte';

function value(container: HTMLElement, name: string): string {
	return (container.querySelector(`input[name="${name}"]`) as HTMLInputElement).value;
}

describe('CropSelector', () => {
	afterEach(() => cleanup());

	it('turns a drag on the shown image into pixels of the stored original', async () => {
		const { container } = render(CropSelector, { src: '/api/files/7' });
		const img = container.querySelector('img') as HTMLImageElement;
		Object.defineProperty(img, 'naturalWidth', { value: 2000 });
		Object.defineProperty(img, 'naturalHeight', { value: 1000 });
		await fireEvent.load(img);
		await tick();

		const svg = container.querySelector('svg') as SVGSVGElement;
		svg.getBoundingClientRect = () =>
			({ left: 0, top: 0, width: 400, height: 200, right: 400, bottom: 200, x: 0, y: 0 }) as DOMRect;
		await fireEvent.pointerDown(svg, { clientX: 40, clientY: 20, pointerId: 1 });
		await fireEvent.pointerMove(svg, { clientX: 140, clientY: 120, pointerId: 1 });
		await fireEvent.pointerUp(svg, { clientX: 440, clientY: 120, pointerId: 1 });

		expect(value(container, 'cropLeft')).toBe('200');
		expect(value(container, 'cropTop')).toBe('100');
		expect(value(container, 'cropWidth')).toBe('1800');
		expect(value(container, 'cropHeight')).toBe('500');
	});

	it('sends no crop for the whole page', () => {
		const { container } = render(CropSelector, { src: '/api/files/7' });
		expect(value(container, 'cropLeft')).toBe('');
		expect(value(container, 'cropHeight')).toBe('');
	});
});
