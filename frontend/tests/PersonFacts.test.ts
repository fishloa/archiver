// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import PersonFacts from '../src/lib/components/genealogy/PersonFacts.svelte';
import type { PersonDetail } from '../src/lib/genealogy';

const alexander: PersonDetail = {
	id: 322,
	name: 'Alexander Friedrich Josef Paul Maria Czernin',
	sex: 'U',
	living: false,
	notes: null,
	needsReview: null,
	names: [
		{ id: 1, type: 'birth', given: 'Alexander Friedrich Josef Paul Maria', surname: 'Czernin', prefix: null, suffix: null, nickname: null, isPrimary: true, sortOrder: 0 },
		{ id: 2, type: 'alias', given: 'Lucki', surname: 'Czernin', prefix: null, suffix: null, nickname: null, isPrimary: false, sortOrder: 1 }
	],
	events: [
		{
			id: 10, type: 'birth', tag: 'BIRT', date: '30 APR 1913', dateFrom: '1913-04-30', dateTo: '1913-04-30',
			place: 'Wien', placeId: 3, description: null, coupleId: null, spouseId: null, text: '', year: 1913,
			citations: [{ id: 5, sourceId: 1, sourceTitle: 'Legacy file', pageId: null, detail: null, quality: 1 }]
		},
		{
			id: 11, type: 'marriage', tag: 'MARR', date: '9 JUL 1949', dateFrom: null, dateTo: null,
			place: 'Haywards Heath', placeId: 4, description: null, coupleId: 7, spouseId: 400, text: '', year: 1949,
			citations: []
		}
	],
	couples: [{ id: 7, type: 'marriage', sortOrder: 1, notes: null, spouseId: 400, spouseName: 'Diana Zannick Hutton/Hulton' }],
	parentLinks: [],
	recordLinks: [
		{ id: 3, recordId: 4082, pageId: 9, pageSeq: 1, role: 'subject', note: null, quality: 3, title: 'Heiratsurkunde', titleEn: 'Marriage certificate', referenceCode: null }
	],
	media: [
		{ id: 8, pageId: 9, recordId: 4082, pageSeq: 1, cropLeft: 1, cropTop: 2, cropWidth: 3, cropHeight: 4, caption: 'Wedding', dateText: null, isPortrait: true, imageUrl: '/api/family-tree/media/8/image' }
	],
	portrait: { id: 8, pageId: 9, recordId: 4082, pageSeq: 1, cropLeft: 1, cropTop: 2, cropWidth: 3, cropHeight: 4, caption: 'Wedding', dateText: null, isPortrait: true, imageUrl: '/api/family-tree/media/8/image' },
	sources: [{ id: 1, title: 'Legacy file', author: null, recordId: null, url: null }]
};

describe('PersonFacts', () => {
	afterEach(() => cleanup());

	it('shows the portrait, other names, cited events, spouse and linked document', async () => {
		const onNavigate = vi.fn();
		render(PersonFacts, { person: alexander, onNavigate });

		expect(screen.getByRole('img', { name: 'Wedding' }).getAttribute('src')).toBe(
			'/api/family-tree/media/8/image'
		);
		expect(screen.getByText(/Lucki/)).toBeTruthy();
		expect(screen.getByText('30 APR 1913')).toBeTruthy();
		expect(screen.getByText('Legacy file', { selector: '.vui-badge' })).toBeTruthy();
		expect(screen.queryByText(/questionable/)).toBeNull();
		expect(screen.getByRole('link', { name: 'Marriage certificate' }).getAttribute('href')).toBe(
			'/records/4082/pages/1'
		);
		await fireEvent.click(screen.getAllByRole('button', { name: 'Diana Zannick Hutton/Hulton' })[0]);
		expect(onNavigate).toHaveBeenCalledWith(400);
	});

	it('grades only citations of archive documents', () => {
		const birth = alexander.events[0];
		render(PersonFacts, {
			person: {
				...alexander,
				events: [
					{
						...birth,
						citations: [
							{ id: 5, sourceId: 1, sourceTitle: 'Legacy file', pageId: null, detail: null, quality: 1 },
							{ id: 6, sourceId: 2, sourceTitle: 'Taufbuch', pageId: 9, detail: 'fol. 3', quality: 3 },
							{ id: 7, sourceId: 3, sourceTitle: 'Heiratsakt', pageId: null, detail: null, quality: 2 }
						]
					}
				],
				sources: [
					alexander.sources[0],
					{ id: 2, title: 'Taufbuch', author: null, recordId: null, url: null },
					{ id: 3, title: 'Heiratsakt', author: null, recordId: 4082, url: null }
				]
			},
			onNavigate: vi.fn()
		});
		expect(screen.getByText('Legacy file', { selector: '.vui-badge' })).toBeTruthy();
		expect(screen.getByText('Taufbuch – fol. 3 · primary evidence')).toBeTruthy();
		expect(screen.getByText('Heiratsakt · secondary evidence')).toBeTruthy();
	});

	it('says so when nothing is recorded', () => {
		render(PersonFacts, {
			person: { ...alexander, names: [alexander.names[0]], events: [], couples: [], recordLinks: [], media: [], portrait: null, sources: [] },
			onNavigate: vi.fn()
		});
		expect(screen.getByText('No events recorded')).toBeTruthy();
		expect(screen.queryByRole('img')).toBeNull();
	});
});
