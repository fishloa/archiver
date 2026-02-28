import { describe, it, expect } from 'vitest';
import { en, type MessageKey } from '../src/lib/messages/en.js';
import { de } from '../src/lib/messages/de.js';
import { cs } from '../src/lib/messages/cs.js';
import { readFileSync, readdirSync, statSync } from 'fs';
import { join, relative } from 'path';

const SRC_DIR = join(import.meta.dirname, '..', 'src');
const enKeys = new Set(Object.keys(en));
const deKeys = new Set(Object.keys(de));
const csKeys = new Set(Object.keys(cs));

function walkDir(dir: string, exts: string[]): string[] {
	const files: string[] = [];
	for (const entry of readdirSync(dir)) {
		const full = join(dir, entry);
		const stat = statSync(full);
		if (stat.isDirectory()) {
			files.push(...walkDir(full, exts));
		} else if (exts.some((ext) => full.endsWith(ext))) {
			files.push(full);
		}
	}
	return files;
}

function extractKeys(files: string[]): Map<string, string[]> {
	const keyLocations = new Map<string, string[]>();
	const pattern = /(?:\$t|\bt)\(\s*['"]([^'"]+)['"]/g;
	for (const file of files) {
		const content = readFileSync(file, 'utf-8');
		let match;
		while ((match = pattern.exec(content)) !== null) {
			const key = match[1];
			const rel = relative(SRC_DIR, file);
			const locations = keyLocations.get(key) ?? [];
			locations.push(rel);
			keyLocations.set(key, locations);
		}
	}
	return keyLocations;
}

const sourceFiles = walkDir(SRC_DIR, ['.svelte', '.ts']).filter((f) => !f.endsWith('/i18n.ts'));
const usedKeys = extractKeys(sourceFiles);

describe('i18n completeness', () => {
	it('every used key exists in en.ts', () => {
		const missing: string[] = [];
		for (const [key] of usedKeys) {
			if (!enKeys.has(key)) missing.push(key);
		}
		expect(missing, `Missing in en.ts: ${missing.join(', ')}`).toEqual([]);
	});

	it('every used key exists in de.ts', () => {
		const missing: string[] = [];
		for (const [key] of usedKeys) {
			if (!deKeys.has(key)) missing.push(key);
		}
		expect(missing, `Missing in de.ts: ${missing.join(', ')}`).toEqual([]);
	});

	it('every used key exists in cs.ts', () => {
		const missing: string[] = [];
		for (const [key] of usedKeys) {
			if (!csKeys.has(key)) missing.push(key);
		}
		expect(missing, `Missing in cs.ts: ${missing.join(', ')}`).toEqual([]);
	});

	it('en and de have the same keys', () => {
		const onlyEn = [...enKeys].filter((k) => !deKeys.has(k));
		const onlyDe = [...deKeys].filter((k) => !enKeys.has(k));
		expect(onlyEn, `Only in en.ts: ${onlyEn.join(', ')}`).toEqual([]);
		expect(onlyDe, `Only in de.ts: ${onlyDe.join(', ')}`).toEqual([]);
	});

	it('en and cs have the same keys', () => {
		const onlyEn = [...enKeys].filter((k) => !csKeys.has(k));
		const onlyCs = [...csKeys].filter((k) => !enKeys.has(k));
		expect(onlyEn, `Only in en.ts: ${onlyEn.join(', ')}`).toEqual([]);
		expect(onlyCs, `Only in cs.ts: ${onlyCs.join(', ')}`).toEqual([]);
	});
});

describe('i18n "This is Me" keys', () => {
	const requiredFamilyKeys: MessageKey[] = [
		'family.thisIsMe',
		'family.thisIsYou',
		'family.toYou',
		'family.genFromYou',
		'family.toAlexander',
		'family.genFromAlexander',
	];

	const requiredProfileKeys: MessageKey[] = [
		'profile.familyTree',
		'profile.familyTreeHint',
		'profile.familyTreeLinked',
	];

	for (const key of [...requiredFamilyKeys, ...requiredProfileKeys]) {
		it(`en.ts has key "${key}"`, () => {
			expect(en[key]).toBeDefined();
			expect(en[key].length).toBeGreaterThan(0);
		});

		it(`de.ts has key "${key}"`, () => {
			expect(de[key as keyof typeof de]).toBeDefined();
		});

		it(`cs.ts has key "${key}"`, () => {
			expect(cs[key as keyof typeof cs]).toBeDefined();
		});
	}
});

describe('i18n message values', () => {
	it('no empty strings in en.ts', () => {
		const empty = Object.entries(en).filter(([, v]) => v === '');
		expect(empty.map(([k]) => k)).toEqual([]);
	});

	it('no empty strings in de.ts', () => {
		const empty = Object.entries(de).filter(([, v]) => v === '');
		expect(empty.map(([k]) => k)).toEqual([]);
	});

	it('placeholder params match between en and de', () => {
		const paramPattern = /\{(\d+)\}/g;
		const mismatches: string[] = [];
		for (const key of Object.keys(en) as MessageKey[]) {
			const enParams = [...en[key].matchAll(paramPattern)].map((m) => m[1]).sort();
			const deVal = (de as Record<string, string>)[key];
			if (!deVal) continue;
			const deParams = [...deVal.matchAll(paramPattern)].map((m) => m[1]).sort();
			if (JSON.stringify(enParams) !== JSON.stringify(deParams)) {
				mismatches.push(`${key}: en={${enParams}} de={${deParams}}`);
			}
		}
		expect(mismatches).toEqual([]);
	});
});
