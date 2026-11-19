// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/svelte';
import HintList from '../src/lib/components/genealogy/HintList.svelte';
import type { Hint } from '../src/lib/server/api';

const hint: Hint = {
	id: 5,
	pageId: 11,
	recordId: 3,
	pageSeq: 2,
	personId: 7,
	name: 'Anna Czernin',
	birthYear: 1900,
	deathYear: null,
	score: 0.8,
	context: 'Anna Czernin appears',
	figureId: null,
	figureNumber: null
};

describe('HintList', () => {
	afterEach(() => cleanup());

	it('hides the suggestions until they are toggled on', async () => {
		const { container } = render(HintList, { hints: [hint], scope: 'page', scopeId: 11, pagesQueued: null });
		expect(container.textContent).not.toContain('Anna Czernin');
		expect(container.querySelector('form[action="?/confirmHint"]')).toBeNull();
		await fireEvent.click(container.querySelector('button.toggle') as HTMLButtonElement);
		expect(container.textContent).toContain('Anna Czernin');
		expect(container.querySelector('form[action="?/confirmHint"]')).not.toBeNull();
	});

	it('always offers to run suggestions and shows the page count returned', () => {
		const { container } = render(HintList, { hints: [], scope: 'record', scopeId: 3, pagesQueued: 12 });
		expect(container.querySelector('form[action="?/runHints"] button')).not.toBeNull();
		expect(container.textContent).toContain('12');
	});
});
