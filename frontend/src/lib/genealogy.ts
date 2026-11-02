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

export function fullName(n: Pick<PersonName, 'prefix' | 'given' | 'surname' | 'suffix'>): string {
	return [n.prefix, n.given, n.surname, n.suffix].filter((p) => p && p.trim()).join(' ');
}
