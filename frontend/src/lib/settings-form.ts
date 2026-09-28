/**
 * Turns the admin form's `setting.*` fields back into the JSON an ai_implementation row stores.
 *
 * Two rules the old version got wrong, both of which lose configuration:
 *
 * - it built the object from the posted fields alone, and the API replaces settings wholesale, so
 *   saving a row silently dropped every key the form had not rendered — which was all of them for
 *   a provider the backend did not describe;
 * - it could only express scalars, so a map of language to model had nowhere to live.
 */

/** A settings value: scalar, or a map of language code to model id. */
export type SettingValue = string | number | Record<string, number | string>;

const INTEGER = /^-?\d+$/;

function scalar(raw: string): string | number {
	return INTEGER.test(raw) ? Number(raw) : raw;
}

/**
 * @param form the submitted form
 * @param existing the row's current settings, preserved where the form says nothing about them
 */
export function settingsFrom(
	form: FormData,
	existing: Record<string, SettingValue> = {}
): string {
	const settings: Record<string, SettingValue> = { ...existing };

	// A map is rebuilt from scratch rather than merged, so removing its last row removes the key
	// rather than leaving the old pairs in place.
	for (const key of form.getAll('setting-map').map(String)) {
		delete settings[key];
	}

	const maps: Record<string, Record<string, number | string>> = {};

	for (const [field, value] of form.entries()) {
		if (!field.startsWith('setting.')) continue;
		const name = field.slice('setting.'.length);
		const raw = String(value).trim();
		const dot = name.indexOf('.');

		if (dot === -1) {
			// An emptied field clears the setting; the default in the provider description applies.
			if (raw === '') delete settings[name];
			else settings[name] = scalar(raw);
			continue;
		}

		const parent = name.slice(0, dot);
		const child = name.slice(dot + 1).trim();
		if (child === '' || raw === '') continue;
		(maps[parent] ??= {})[child] = scalar(raw);
	}

	for (const [key, value] of Object.entries(maps)) {
		settings[key] = value;
	}
	return JSON.stringify(settings);
}

/** The settings of a row, whether the API handed them over as JSON text or as an object. */
export function settingsOf(raw: unknown): Record<string, SettingValue> {
	if (!raw) return {};
	if (typeof raw === 'object') return raw as Record<string, SettingValue>;
	try {
		return JSON.parse(String(raw));
	} catch {
		return {};
	}
}
