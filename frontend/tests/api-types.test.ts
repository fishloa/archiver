import { describe, it, expect } from 'vitest';

/**
 * Tests that API type interfaces are correctly defined.
 * These are compile-time checks that also verify structure at runtime.
 */

describe('AuthUser type', () => {
	it('has correct shape', async () => {
		const mod = await import('../src/lib/server/api.js');
		// AuthUser is an interface — we can't check it at runtime, but we can
		// verify the shape by creating a conforming object
		const user: import('../src/lib/server/api.js').AuthUser = {
			authenticated: true,
			email: 'test@example.com',
			displayName: 'Test',
			role: 'user',
			familyTreePersonId: 1,
		};
		expect(user.authenticated).toBe(true);
		expect(user.familyTreePersonId).toBe(1);
	});

	it('allows unauthenticated shape', () => {
		const user: import('../src/lib/server/api.js').AuthUser = {
			authenticated: false,
		};
		expect(user.authenticated).toBe(false);
		expect(user.email).toBeUndefined();
		expect(user.familyTreePersonId).toBeUndefined();
	});
});

describe('UserProfile type', () => {
	it('has familyTreePersonId field', () => {
		const profile: import('../src/lib/server/api.js').UserProfile = {
			id: 1,
			displayName: 'Test',
			role: 'user',
			loginEmail: 'test@example.com',
			familyTreePersonId: 42,
			emails: [{ id: 1, email: 'test@example.com' }],
		};
		expect(profile.familyTreePersonId).toBe(42);
		expect(profile.emails).toHaveLength(1);
	});

	it('allows undefined familyTreePersonId', () => {
		const profile: import('../src/lib/server/api.js').UserProfile = {
			id: 1,
			displayName: 'Test',
			role: 'user',
			loginEmail: 'test@example.com',
			emails: [],
		};
		expect(profile.familyTreePersonId).toBeUndefined();
	});
});

describe('SearchResult type', () => {
	it('has correct shape', () => {
		const result: import('../src/lib/server/api.js').SearchResult = {
			pageTextId: 1,
			pageId: 2,
			confidence: 0.95,
			engine: 'paddle',
			snippet: 'test snippet',
			seq: 1,
			recordId: 100,
			recordTitle: 'Test Record',
			referenceCode: 'AT-123',
		};
		expect(result.recordId).toBe(100);
		expect(result.snippet).toBe('test snippet');
	});
});

describe('PipelineStats type', () => {
	it('has stages and totals', () => {
		const stats: import('../src/lib/server/api.js').PipelineStats = {
			stages: [
				{
					name: 'ocr',
					records: 10,
					pages: 100,
					jobsPending: 5,
					jobsRunning: 2,
					jobsCompleted: 90,
					jobsFailed: 3,
					workersConnected: 2,
				},
			],
			totals: { records: 10, pages: 100 },
		};
		expect(stats.stages).toHaveLength(1);
		expect(stats.totals.records).toBe(10);
	});
});

describe('SemanticSearchResult type', () => {
	it('has correct shape', () => {
		const result: import('../src/lib/server/api.js').SemanticSearchResult = {
			recordId: 1,
			pageId: 2,
			pageSeq: 3,
			chunkIndex: 0,
			content: 'test content',
			score: 0.85,
			recordTitle: 'Test',
			recordTitleEn: 'Test EN',
			referenceCode: 'REF-1',
			descriptionEn: 'Description',
		};
		expect(result.score).toBe(0.85);
		expect(result.content).toBe('test content');
	});
});

describe('PagePersonMatch type', () => {
	it('has correct shape', () => {
		const match: import('../src/lib/server/api.js').PagePersonMatch = {
			personId: 1,
			personName: 'Test Person',
			score: 0.9,
			context: 'found in page',
			section: 'CZERNIN 1',
			code: 'C1-A',
			birthYear: 1800,
			deathYear: 1860,
		};
		expect(match.personId).toBe(1);
		expect(match.birthYear).toBe(1800);
	});
});

describe('RecordPersonMatch type', () => {
	it('has correct shape', () => {
		const match: import('../src/lib/server/api.js').RecordPersonMatch = {
			personId: 1,
			personName: 'Test Person',
			maxScore: 0.95,
			pageCount: 3,
			section: 'CZERNIN 2',
			code: 'C2-B',
			birthYear: 1900,
			deathYear: 1970,
		};
		expect(match.maxScore).toBe(0.95);
		expect(match.pageCount).toBe(3);
	});
});
