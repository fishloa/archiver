import { describe, expect, it } from 'vitest';
import { EVENT_TAGS, eventLabel, qualityLabel } from '../src/lib/genealogy';
import { en } from '../src/lib/messages/en';
import { de } from '../src/lib/messages/de';
import { cs } from '../src/lib/messages/cs';

describe('labels', () => {
	it('names every GEDCOM event and quality in all three languages', () => {
		for (const tag of EVENT_TAGS) {
			for (const messages of [en, de, cs]) {
				expect(messages[eventLabel(tag)]).toBeTruthy();
			}
		}
		for (const q of [0, 1, 2, 3, null]) {
			expect(en[qualityLabel(q)]).toBeTruthy();
		}
	});
});
