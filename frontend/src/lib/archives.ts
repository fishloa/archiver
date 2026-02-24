/** Archive-specific configuration, translations, and NAD mappings. */

export interface ArchiveConfig {
	name: string;
	nameEn: string;
	country: string;
	/** Map of NAD number → English translation of the fond name. */
	nadTranslations: Record<string, string>;
}

const ARCHIVES: Record<string, ArchiveConfig> = {
	'vademecum.nacr.cz': {
		name: 'Národní archiv České republiky',
		nameEn: 'National Archives of the Czech Republic',
		country: 'CZ',
		nadTranslations: {
			'1005': 'Office of the Reichsprotector',
			'1075': 'Ministry of the Interior I',
			'1420': 'Prague Police Directorate II',
			'1464': 'German State Ministry for Bohemia and Moravia',
			'1799': 'State Secretary to the Reichsprotector'
		}
	}
};

export function getArchiveConfig(sourceSystem: string | null): ArchiveConfig | null {
	if (!sourceSystem) return null;
	return ARCHIVES[sourceSystem] ?? null;
}

export function nadTranslation(sourceSystem: string | null, nad: string | number | null): string | null {
	if (!sourceSystem || nad == null) return null;
	const config = ARCHIVES[sourceSystem];
	return config?.nadTranslations[String(nad)] ?? null;
}

export interface SourceMeta {
	nad_number?: number | string | null;
	fond_name?: string | null;
	[key: string]: unknown;
}

export function parseSourceMeta(raw: string | null): SourceMeta {
	if (!raw) return {};
	try {
		return JSON.parse(raw);
	} catch {
		return {};
	}
}
