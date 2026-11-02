import type { MessageKey } from '$lib/i18n';

export const EVENT_TAGS = [
	'BIRT',
	'CHR',
	'BAPM',
	'DEAT',
	'BURI',
	'MARR',
	'DIV',
	'ADOP',
	'OCCU',
	'TITL',
	'RESI',
	'NATU',
	'EMIG',
	'IMMI',
	'EDUC',
	'EVEN'
] as const;
export type EventTag = (typeof EVENT_TAGS)[number];

export const NAME_TYPES = ['birth', 'married', 'alias', 'religious', 'title', 'other'] as const;
export const LINK_TYPES = ['birth', 'adopted', 'step', 'foster', 'unknown'] as const;
export const COUPLE_TYPES = ['marriage', 'partnership', 'unknown'] as const;
export const ROLES = [
	'subject',
	'mentioned',
	'author',
	'signatory',
	'witness',
	'recipient',
	'pictured',
	'other'
] as const;
export const SEXES = ['M', 'F', 'U'] as const;

export const EVENT_LABELS: Record<EventTag, MessageKey> = {
	BIRT: 'family.event.BIRT',
	CHR: 'family.event.CHR',
	BAPM: 'family.event.BAPM',
	DEAT: 'family.event.DEAT',
	BURI: 'family.event.BURI',
	MARR: 'family.event.MARR',
	DIV: 'family.event.DIV',
	ADOP: 'family.event.ADOP',
	OCCU: 'family.event.OCCU',
	TITL: 'family.event.TITL',
	RESI: 'family.event.RESI',
	NATU: 'family.event.NATU',
	EMIG: 'family.event.EMIG',
	IMMI: 'family.event.IMMI',
	EDUC: 'family.event.EDUC',
	EVEN: 'family.event.EVEN'
};

export const NAME_TYPE_LABELS: Record<(typeof NAME_TYPES)[number], MessageKey> = {
	birth: 'family.nameType.birth',
	married: 'family.nameType.married',
	alias: 'family.nameType.alias',
	religious: 'family.nameType.religious',
	title: 'family.nameType.title',
	other: 'family.nameType.other'
};

export const LINK_TYPE_LABELS: Record<(typeof LINK_TYPES)[number], MessageKey> = {
	birth: 'family.linkType.birth',
	adopted: 'family.linkType.adopted',
	step: 'family.linkType.step',
	foster: 'family.linkType.foster',
	unknown: 'family.linkType.unknown'
};

export const COUPLE_TYPE_LABELS: Record<(typeof COUPLE_TYPES)[number], MessageKey> = {
	marriage: 'family.coupleType.marriage',
	partnership: 'family.coupleType.partnership',
	unknown: 'family.coupleType.unknown'
};

export const ROLE_LABELS: Record<(typeof ROLES)[number], MessageKey> = {
	subject: 'family.role.subject',
	mentioned: 'family.role.mentioned',
	author: 'family.role.author',
	signatory: 'family.role.signatory',
	witness: 'family.role.witness',
	recipient: 'family.role.recipient',
	pictured: 'family.role.pictured',
	other: 'family.role.other'
};

export const SEX_LABELS: Record<(typeof SEXES)[number], MessageKey> = {
	M: 'family.sex.M',
	F: 'family.sex.F',
	U: 'family.sex.U'
};

/** GEDCOM QUAY 0-3; null when the citation does not say. */
export const QUALITY_LABELS: MessageKey[] = [
	'family.quality.0',
	'family.quality.1',
	'family.quality.2',
	'family.quality.3'
];

export function eventLabel(tag: string): MessageKey {
	return EVENT_LABELS[tag as EventTag] ?? 'family.event.EVEN';
}

export function qualityLabel(quality: number | null): MessageKey {
	return quality == null ? 'family.quality.none' : (QUALITY_LABELS[quality] ?? 'family.quality.none');
}

export interface Citation {
	id: number;
	sourceId: number;
	sourceTitle: string;
	pageId: number | null;
	detail: string | null;
	quality: number | null;
}

export interface TreeEvent {
	id: number;
	type: string;
	tag: string;
	date: string | null;
	dateFrom: string | null;
	dateTo: string | null;
	place: string | null;
	placeId: number | null;
	description: string | null;
	coupleId: number | null;
	spouseId: number | null;
	text: string;
	year: number | null;
	citations: Citation[];
}

export interface PersonName {
	id: number;
	type: string;
	given: string | null;
	surname: string | null;
	prefix: string | null;
	suffix: string | null;
	nickname: string | null;
	isPrimary: boolean;
	sortOrder: number;
}

export interface Couple {
	id: number;
	type: string;
	sortOrder: number;
	notes: string | null;
	spouseId: number;
	spouseName: string;
}

export interface ParentLink {
	id: number;
	type: string;
	coupleId: number | null;
	parentId: number;
	childId: number;
	relation: 'parent' | 'child';
	otherName: string;
}

export interface RecordLink {
	id: number;
	recordId: number;
	pageId: number | null;
	pageSeq: number | null;
	role: string;
	note: string | null;
	quality: number | null;
	title: string | null;
	titleEn: string | null;
	referenceCode: string | null;
}

export interface MediaItem {
	id: number;
	pageId: number;
	recordId: number;
	pageSeq: number;
	cropLeft: number | null;
	cropTop: number | null;
	cropWidth: number | null;
	cropHeight: number | null;
	caption: string | null;
	dateText: string | null;
	isPortrait: boolean;
	imageUrl: string;
}

export interface Source {
	id: number;
	title: string;
	author: string | null;
	recordId: number | null;
	url: string | null;
}

export interface PersonDetail {
	id: number;
	name: string;
	sex: string;
	living: boolean;
	notes: string | null;
	needsReview: string | null;
	names: PersonName[];
	events: TreeEvent[];
	couples: Couple[];
	parentLinks: ParentLink[];
	recordLinks: RecordLink[];
	media: MediaItem[];
	portrait: MediaItem | null;
	sources: Source[];
}

export interface Change {
	id: number;
	entity: string;
	entityId: number;
	op: string;
	before: Record<string, unknown> | null;
	after: Record<string, unknown> | null;
	reason: string;
	changedBy: string;
	changedAt: string;
}

export function fullName(n: Pick<PersonName, 'prefix' | 'given' | 'surname' | 'suffix'>): string {
	return [n.prefix, n.given, n.surname, n.suffix].filter((p) => p && p.trim()).join(' ');
}

/** The fields a change altered, as "field: old → new". */
export function changeSummary(c: Change): string {
	const before = c.before ?? {};
	const after = c.after ?? {};
	const keys = Object.keys({ ...before, ...after }).filter(
		(k) => !['id', 'created_at', 'updated_at', 'uid'].includes(k)
	);
	const changed = keys.filter((k) => JSON.stringify(before[k]) !== JSON.stringify(after[k]));
	return changed
		.map((k) => `${k}: ${show(before[k])} → ${show(after[k])}`)
		.join('; ');
}

function show(v: unknown): string {
	return v == null ? '–' : String(v);
}

export interface Crop {
	left: number;
	top: number;
	width: number;
	height: number;
}

/**
 * The rectangle between two corner points, in whole pixels of the stored image, kept inside it.
 * Null when nothing is left to crop.
 */
export function cropFromPoints(
	a: { x: number; y: number },
	b: { x: number; y: number },
	width: number,
	height: number
): Crop | null {
	const cx = (v: number) => Math.min(width, Math.max(0, Math.round(v)));
	const cy = (v: number) => Math.min(height, Math.max(0, Math.round(v)));
	const left = Math.min(cx(a.x), cx(b.x));
	const right = Math.max(cx(a.x), cx(b.x));
	const top = Math.min(cy(a.y), cy(b.y));
	const bottom = Math.max(cy(a.y), cy(b.y));
	if (right - left < 1 || bottom - top < 1) return null;
	return { left, top, width: right - left, height: bottom - top };
}

const NUMBER_FIELDS = new Set([
	'personId',
	'person1Id',
	'person2Id',
	'parentId',
	'childId',
	'coupleId',
	'placeId',
	'sourceId',
	'pageId',
	'recordId',
	'eventId',
	'nameId',
	'parentChildId',
	'mediaId',
	'quality',
	'sortOrder',
	'fromYear',
	'toYear',
	'lat',
	'lon',
	'cropLeft',
	'cropTop',
	'cropWidth',
	'cropHeight'
]);
const BOOLEAN_FIELDS = new Set(['living', 'isPrimary', 'isPortrait']);
const PATH =
	/^(persons|names|events|couples|parent-links|places|place-names|sources|citations|record-links|media|media-people|changes)(\/\d+)?(\/(names|undo|merge\/\d+))?$/;
const METHODS = ['POST', 'PUT', 'DELETE'] as const;
export type WriteMethod = (typeof METHODS)[number];

/**
 * An edit form as the admin API wants it. `_method` and `_path` say where it goes; the rest are
 * fields. An empty field is left out of a create and cleared by an update. A checkbox is preceded
 * by a hidden "false" of the same name, so the last value wins.
 */
export function formToBody(form: FormData): {
	method: WriteMethod;
	path: string;
	body: Record<string, unknown>;
} {
	const method = String(form.get('_method') ?? 'POST') as WriteMethod;
	const path = String(form.get('_path') ?? '');
	if (!METHODS.includes(method)) throw new Error(`unsupported method ${method}`);
	if (!PATH.test(path)) throw new Error(`unsupported path ${path}`);
	const body: Record<string, unknown> = {};
	for (const key of new Set(form.keys())) {
		if (key === '_method' || key === '_path') continue;
		const values = form.getAll(key).map(String);
		const value = values[values.length - 1];
		if (BOOLEAN_FIELDS.has(key)) {
			body[key] = value === 'true' || value === 'on';
		} else if (value.trim() === '') {
			if (method === 'PUT') body[key] = null;
		} else if (NUMBER_FIELDS.has(key)) {
			const n = Number(value);
			if (Number.isNaN(n)) throw new Error(`${key} must be a number`);
			body[key] = n;
		} else {
			body[key] = value;
		}
	}
	return { method, path, body };
}
