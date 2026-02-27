/**
 * i18n completeness test
 *
 * Scans all .svelte and .ts files in src/ for t('key') calls,
 * then verifies that every key exists in both en.ts and de.ts message files.
 *
 * Run: npx tsx tests/i18n-completeness.test.ts
 */

import { readFileSync, readdirSync, statSync } from 'fs';
import { join, relative } from 'path';
import { en } from '../src/lib/messages/en.js';
import { de } from '../src/lib/messages/de.js';

const SRC_DIR = join(import.meta.dirname, '..', 'src');

function walkDir(dir: string, exts: string[]): string[] {
	const files: string[] = [];
	for (const entry of readdirSync(dir)) {
		const full = join(dir, entry);
		const stat = statSync(full);
		if (stat.isDirectory()) {
			files.push(...walkDir(full, exts));
		} else if (exts.some(ext => full.endsWith(ext))) {
			files.push(full);
		}
	}
	return files;
}

// Extract all t('key') calls from source files
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

const files = walkDir(SRC_DIR, ['.svelte', '.ts']).filter(f => !f.endsWith('/i18n.ts'));
const usedKeys = extractKeys(files);
const enKeys = new Set(Object.keys(en));
const deKeys = new Set(Object.keys(de));

let errors = 0;

// Check every used key exists in both languages
for (const [key, locations] of usedKeys.entries()) {
	if (!enKeys.has(key)) {
		console.error(`MISSING IN en.ts: "${key}" (used in ${locations.join(', ')})`);
		errors++;
	}
	if (!deKeys.has(key)) {
		console.error(`MISSING IN de.ts: "${key}" (used in ${locations.join(', ')})`);
		errors++;
	}
}

// Check en and de have the same keys
for (const key of enKeys) {
	if (!deKeys.has(key)) {
		console.error(`KEY IN en.ts BUT NOT de.ts: "${key}"`);
		errors++;
	}
}
for (const key of deKeys) {
	if (!enKeys.has(key)) {
		console.error(`KEY IN de.ts BUT NOT en.ts: "${key}"`);
		errors++;
	}
}

// Check for unused keys in message files
const unusedEn: string[] = [];
for (const key of enKeys) {
	if (!usedKeys.has(key)) {
		unusedEn.push(key);
	}
}
if (unusedEn.length > 0) {
	console.warn(`\nWARNING: ${unusedEn.length} key(s) defined in en.ts but never used in code:`);
	for (const key of unusedEn) {
		console.warn(`  - "${key}"`);
	}
}

console.log(`\nScanned ${files.length} files, found ${usedKeys.size} unique i18n keys`);
console.log(`en.ts: ${enKeys.size} keys, de.ts: ${deKeys.size} keys`);

if (errors > 0) {
	console.error(`\nFAILED: ${errors} error(s) found`);
	process.exit(1);
} else {
	console.log('\nPASSED: All i18n keys are complete in both languages');
}
