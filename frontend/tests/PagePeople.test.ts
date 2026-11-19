// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/svelte';
import PagePeople from '../src/lib/components/genealogy/PagePeople.svelte';
import { t } from '../src/lib/i18n';
import { get } from 'svelte/store';
import type { PagePerson } from '../src/lib/server/api';

const person = (over: Partial<PagePerson>): PagePerson => ({
	personId: 7,
	name: 'Anna Czernin',
	birthYear: 1900,
	deathYear: null,
	portraitUrl: null,
	via: 'page',
	role: 'subject',
	roles: ['subject'],
	note: null,
	...over
});

describe('PagePeople', () => {
	afterEach(() => cleanup());

	it('links each name to the family tree person', () => {
		const { container } = render(PagePeople, { people: [person({})] });
		const a = container.querySelector('a') as HTMLAnchorElement;
		expect(a.getAttribute('href')).toBe('/family-tree?personId=7');
		expect(a.textContent).toContain('Anna Czernin');
	});

	it('says so when nobody is linked yet', () => {
		const { container } = render(PagePeople, { people: [] });
		expect(container.textContent).toContain(get(t)('people.none'));
	});

	it('tags a person linked to the whole record, and only that person', () => {
		const tag = get(t)('people.viaRecord');
		const { container } = render(PagePeople, {
			people: [person({ via: 'record' }), person({ personId: 8, name: 'Karl Czernin' })]
		});
		const items = container.querySelectorAll('li');
		expect(items[0].textContent).toContain(tag);
		expect(items[1].textContent).not.toContain(tag);
	});
});
