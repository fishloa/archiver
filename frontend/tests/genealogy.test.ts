import { describe, expect, it } from 'vitest';
import {
	EVENT_TAGS,
	changeSummary,
	cropFromPoints,
	eventLabel,
	formToBody,
	qualityLabel
} from '../src/lib/genealogy';
import { en } from '../src/lib/messages/en';
import { de } from '../src/lib/messages/de';
import { cs } from '../src/lib/messages/cs';

function form(pairs: [string, string][]): FormData {
	const f = new FormData();
	for (const [k, v] of pairs) f.append(k, v);
	return f;
}

describe('cropFromPoints', () => {
	it('orders the corners and rounds to whole pixels', () => {
		expect(cropFromPoints({ x: 30.4, y: 20.6 }, { x: 10.2, y: 5 }, 100, 80)).toEqual({
			left: 10,
			top: 5,
			width: 20,
			height: 16
		});
	});

	it('keeps a drag that leaves the image inside it', () => {
		expect(cropFromPoints({ x: 90, y: 70 }, { x: 130, y: -20 }, 100, 80)).toEqual({
			left: 90,
			top: 0,
			width: 10,
			height: 70
		});
	});

	it('is nothing when the drag has no area', () => {
		expect(cropFromPoints({ x: 5, y: 5 }, { x: 5, y: 60 }, 100, 80)).toBeNull();
		expect(cropFromPoints({ x: 120, y: 5 }, { x: 150, y: 60 }, 100, 80)).toBeNull();
	});
});

describe('formToBody', () => {
	it('types numbers and booleans and drops empty fields from a create', () => {
		const out = formToBody(
			form([
				['_method', 'POST'],
				['_path', 'events'],
				['personId', '322'],
				['type', 'BIRT'],
				['dateText', '30 APR 1913'],
				['description', ''],
				['reason', 'register']
			])
		);
		expect(out).toEqual({
			method: 'POST',
			path: 'events',
			body: { personId: 322, type: 'BIRT', dateText: '30 APR 1913', reason: 'register' }
		});
	});

	it('clears an emptied field on update and takes a checked box over its hidden false', () => {
		const out = formToBody(
			form([
				['_method', 'PUT'],
				['_path', 'persons/12'],
				['notes', ''],
				['living', 'false'],
				['living', 'true'],
				['reason', 'x']
			])
		);
		expect(out.body).toEqual({ notes: null, living: true, reason: 'x' });
		expect(formToBody(form([['_method', 'PUT'], ['_path', 'persons/12'], ['living', 'false']])).body)
			.toEqual({ living: false });
	});

	it('refuses paths and methods outside the genealogy API', () => {
		expect(() => formToBody(form([['_method', 'POST'], ['_path', '../records/1']]))).toThrow();
		expect(() => formToBody(form([['_method', 'PATCH'], ['_path', 'events/1']]))).toThrow();
		expect(() => formToBody(form([['_method', 'POST'], ['_path', 'events'], ['personId', 'x']]))).toThrow();
		expect(formToBody(form([['_method', 'POST'], ['_path', 'changes/9/undo']])).path).toBe(
			'changes/9/undo'
		);
		expect(formToBody(form([['_method', 'POST'], ['_path', 'persons/4/names']])).path).toBe(
			'persons/4/names'
		);
	});
});

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

describe('changeSummary', () => {
	it('lists only the fields that changed', () => {
		expect(
			changeSummary({
				id: 1,
				entity: 'name',
				entityId: 4,
				op: 'update',
				before: { id: 4, given: 'Anna', surname: null, updated_at: 'a' },
				after: { id: 4, given: 'Anna', surname: 'Novák', updated_at: 'b' },
				reason: 'r',
				changedBy: 'x',
				changedAt: '2026-11-02T10:00:00Z'
			})
		).toBe('surname: – → Novák');
	});
});
