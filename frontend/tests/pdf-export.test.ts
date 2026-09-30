import { describe, expect, it } from 'vitest';
import {
	createUrl,
	describeState,
	errorFrom,
	exportRequest,
	fileUrl,
	nextPollDelay,
	statusUrl
} from '../src/lib/pdf-export';

describe('addresses', () => {
	it('builds the three endpoint addresses', () => {
		expect(createUrl(4037)).toBe('/api/records/4037/pdf-exports');
		expect(statusUrl('abc-123')).toBe('/api/pdf-exports/abc-123');
		expect(fileUrl('abc-123')).toBe('/api/pdf-exports/abc-123/file');
	});

	it('encodes an id rather than trusting it', () => {
		expect(statusUrl('a/b')).toBe('/api/pdf-exports/a%2Fb');
	});
});

describe('exportRequest', () => {
	it('asks for the whole record when no pages are given', () => {
		expect(exportRequest('original', '')).toEqual({ variant: 'original' });
		expect(exportRequest('english', '   ')).toEqual({ variant: 'english' });
	});

	it('passes a trimmed page selection through', () => {
		expect(exportRequest('side-by-side', ' 1,3,5-10 ')).toEqual({
			variant: 'side-by-side',
			pages: '1,3,5-10'
		});
	});
});

describe('nextPollDelay', () => {
	it('polls quickly at first, then backs off to a ceiling', () => {
		expect([0, 1, 2, 3, 4].map(nextPollDelay)).toEqual([2000, 2000, 2000, 2000, 2000]);
		expect(nextPollDelay(5)).toBe(2500);
		expect(nextPollDelay(6)).toBe(3000);
		expect(nextPollDelay(10)).toBe(5000);
		expect(nextPollDelay(500)).toBe(5000);
	});

	it('never gets shorter as the wait gets longer', () => {
		let previous = 0;
		for (let attempt = 0; attempt < 40; attempt++) {
			const delay = nextPollDelay(attempt);
			expect(delay).toBeGreaterThanOrEqual(previous);
			previous = delay;
		}
	});
});

describe('describeState', () => {
	it('keeps waiting while queued or building', () => {
		expect(describeState('queued', null)).toEqual({ terminal: false, ok: false, message: '' });
		expect(describeState('building', null)).toEqual({ terminal: false, ok: false, message: '' });
	});

	it('is done and good when ready', () => {
		expect(describeState('ready', null)).toEqual({ terminal: true, ok: true, message: '' });
	});

	it('is done and bad when failed, saying why', () => {
		const failed = describeState('failed', 'no scan for page 2');
		expect(failed.terminal).toBe(true);
		expect(failed.ok).toBe(false);
		expect(failed.message).toBe('no scan for page 2');
	});

	it('says something useful when it failed with no reason', () => {
		expect(describeState('failed', null).message).not.toBe('');
	});

	it('tells the person to ask again when expired', () => {
		const expired = describeState('expired', null);
		expect(expired.terminal).toBe(true);
		expect(expired.ok).toBe(false);
		expect(expired.message).toMatch(/request it again/i);
	});
});

describe('errorFrom', () => {
	it('reads the error out of a JSON body', () => {
		expect(errorFrom(400, '{"error":"No page matches that selection"}')).toBe(
			'No page matches that selection'
		);
	});

	it('falls back to the status when the body is not JSON or has no error', () => {
		expect(errorFrom(502, '<html>Bad gateway</html>')).toBe('Request failed (502)');
		expect(errorFrom(500, '{"other":1}')).toBe('Request failed (500)');
		expect(errorFrom(500, '')).toBe('Request failed (500)');
	});
});
