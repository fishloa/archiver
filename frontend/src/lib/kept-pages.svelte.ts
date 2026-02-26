const KEY_PREFIX = 'kept-pages-';

let cache: Map<number, Set<number>> = new Map();
let version = $state(0);

function storageKey(recordId: number): string {
	return KEY_PREFIX + recordId;
}

function load(recordId: number): Set<number> {
	if (cache.has(recordId)) return cache.get(recordId)!;
	try {
		const raw = localStorage.getItem(storageKey(recordId));
		const set = raw ? new Set<number>(JSON.parse(raw)) : new Set<number>();
		cache.set(recordId, set);
		return set;
	} catch {
		const set = new Set<number>();
		cache.set(recordId, set);
		return set;
	}
}

function save(recordId: number, set: Set<number>) {
	if (set.size === 0) {
		localStorage.removeItem(storageKey(recordId));
	} else {
		localStorage.setItem(storageKey(recordId), JSON.stringify([...set].sort((a, b) => a - b)));
	}
}

export function getKeptPages(recordId: number): number[] {
	void version;
	return [...load(recordId)].sort((a, b) => a - b);
}

export function isKept(recordId: number, seq: number): boolean {
	void version;
	return load(recordId).has(seq);
}

export function keptCount(recordId: number): number {
	void version;
	return load(recordId).size;
}

export function toggleKept(recordId: number, seq: number) {
	const set = load(recordId);
	if (set.has(seq)) {
		set.delete(seq);
	} else {
		set.add(seq);
	}
	save(recordId, set);
	version++;
}

export function clearKept(recordId: number) {
	cache.delete(recordId);
	localStorage.removeItem(storageKey(recordId));
	version++;
}

export function keptPagesParam(recordId: number): string {
	const pages = getKeptPages(recordId);
	if (pages.length === 0) return '';
	const ranges: string[] = [];
	let start = pages[0], end = pages[0];
	for (let i = 1; i < pages.length; i++) {
		if (pages[i] === end + 1) {
			end = pages[i];
		} else {
			ranges.push(start === end ? `${start}` : `${start}-${end}`);
			start = end = pages[i];
		}
	}
	ranges.push(start === end ? `${start}` : `${start}-${end}`);
	return ranges.join(',');
}
