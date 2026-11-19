// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/svelte';
import PersonAvatar from '../src/lib/components/genealogy/PersonAvatar.svelte';

describe('PersonAvatar', () => {
	afterEach(() => cleanup());

	it('renders a decorative round image when there is a url', () => {
		const { container } = render(PersonAvatar, { url: '/api/portraits/3', name: 'Anna Czernin' });
		const img = container.querySelector('img') as HTMLImageElement;
		expect(img.getAttribute('alt')).toBe('');
		expect(img.getAttribute('src')).toBe('/api/portraits/3');
	});

	it('renders nothing when the url is null', () => {
		const { container } = render(PersonAvatar, { url: null, name: 'Anna Czernin' });
		expect(container.querySelector('img')).toBeNull();
	});
});
