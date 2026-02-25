export interface RecordResponse {
	id: number;
	archiveId: number | null;
	collectionId: number | null;
	sourceSystem: string | null;
	sourceRecordId: string | null;
	title: string | null;
	description: string | null;
	dateRangeText: string | null;
	dateStartYear: number | null;
	dateEndYear: number | null;
	referenceCode: string | null;
	inventoryNumber: string | null;
	callNumber: string | null;
	containerType: string | null;
	containerNumber: string | null;
	findingAidNumber: string | null;
	indexTerms: string | null;
	rawSourceMetadata: string | null;
	pdfAttachmentId: number | null;
	titleEn: string | null;
	descriptionEn: string | null;
	attachmentCount: number;
	pageCount: number;
	status: string;
	createdAt: string;
	updatedAt: string;
	sourceUrl: string | null;
}

export interface PageResponse {
	id: number;
	recordId: number;
	seq: number;
	attachmentId: number | null;
	pageLabel: string | null;
	width: number | null;
	height: number | null;
	sourceUrl: string | null;
}

export interface SpringPage<T> {
	content: T[];
	totalElements: number;
	totalPages: number;
	number: number;
	size: number;
	first: boolean;
	last: boolean;
	empty: boolean;
}
