import { describe, expect, it } from 'vitest';
import { settingsFrom, settingsOf } from '../src/lib/settings-form';

function form(pairs: [string, string][]): FormData {
	const f = new FormData();
	for (const [k, v] of pairs) f.append(k, v);
	return f;
}

describe('settingsFrom', () => {
	it('keeps settings the form never rendered', () => {
		// The bug this exists to prevent: the API replaces settings wholesale, so a form that only
		// knows about tickIntervalMs used to wipe the model map on save.
		const existing = { htrId: 579509, htrByLang: { cs: 263129 }, tokenUrl: 'https://example' };
		const out = JSON.parse(settingsFrom(form([['setting.tickIntervalMs', '20000']]), existing));

		expect(out.htrId).toBe(579509);
		expect(out.htrByLang).toEqual({ cs: 263129 });
		expect(out.tokenUrl).toBe('https://example');
		expect(out.tickIntervalMs).toBe(20000);
	});

	it('assembles a language map from its rows', () => {
		const out = JSON.parse(
			settingsFrom(
				form([
					['setting-map', 'htrByLang'],
					['setting.htrByLang.cs', '263129'],
					['setting.htrByLang.de', '579509']
				])
			)
		);
		expect(out.htrByLang).toEqual({ cs: 263129, de: 579509 });
	});

	it('removes a language when its row is removed', () => {
		const existing = { htrByLang: { cs: 263129, de: 265149 } };
		const out = JSON.parse(
			settingsFrom(
				form([
					['setting-map', 'htrByLang'],
					['setting.htrByLang.cs', '263129']
				]),
				existing
			)
		);
		expect(out.htrByLang).toEqual({ cs: 263129 });
	});

	it('removes the map entirely when its last row goes', () => {
		const existing = { htrByLang: { cs: 263129 }, htrId: 579509 };
		const out = JSON.parse(settingsFrom(form([['setting-map', 'htrByLang']]), existing));
		expect(out.htrByLang).toBeUndefined();
		expect(out.htrId).toBe(579509);
	});

	it('stores integers as numbers, because the backend reads them with asInt', () => {
		const out = JSON.parse(settingsFrom(form([['setting.monthlyCredits', '150']])));
		expect(out.monthlyCredits).toBe(150);
	});

	it('clears a setting whose field was emptied', () => {
		const out = JSON.parse(settingsFrom(form([['setting.queryPrefix', '']]), { queryPrefix: 'x' }));
		expect(out.queryPrefix).toBeUndefined();
	});

	it('ignores a half-filled language row', () => {
		const out = JSON.parse(
			settingsFrom(
				form([
					['setting-map', 'htrByLang'],
					['setting.htrByLang.', '579509'],
					['setting.htrByLang.de', '']
				])
			)
		);
		expect(out.htrByLang).toBeUndefined();
	});
});

describe('settingsOf', () => {
	it('reads settings as text or as an object, and survives nonsense', () => {
		expect(settingsOf('{"htrId":1}')).toEqual({ htrId: 1 });
		expect(settingsOf({ htrId: 1 })).toEqual({ htrId: 1 });
		expect(settingsOf('not json')).toEqual({});
		expect(settingsOf(null)).toEqual({});
	});
});
