// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/svelte';
import PageLinker from '../src/lib/components/genealogy/PageLinker.svelte';

const props = { recordId: 4093, pageId: 11, seq: 3, src: '/scan.jpg', figures: [] };

function reasonIn(container: HTMLElement, action: string): string {
	return (container.querySelector(`form[action="${action}"] input[name="reason"]`) as HTMLInputElement).value;
}

describe('PageLinker', () => {
	afterEach(() => cleanup());

	it('prefills the reason with the record and page on both forms', async () => {
		const { container } = render(PageLinker, props);
		const picture = reasonIn(container, '?/linkMedia');
		expect(picture).toContain('4093');
		expect(picture).toContain('3');
		await fireEvent.click(container.querySelectorAll('.modes button')[1] as HTMLButtonElement);
		expect(reasonIn(container, '?/linkPerson')).toBe(picture);
	});

	it('keeps the person form prefilled after the picture form reason is cleared', async () => {
		const { container } = render(PageLinker, props);
		const input = container.querySelector('form[action="?/linkMedia"] input[name="reason"]') as HTMLInputElement;
		const prefilled = input.value;
		await fireEvent.input(input, { target: { value: '' } });
		await fireEvent.click(container.querySelectorAll('.modes button')[1] as HTMLButtonElement);
		expect(reasonIn(container, '?/linkPerson')).toBe(prefilled);
	});
});
