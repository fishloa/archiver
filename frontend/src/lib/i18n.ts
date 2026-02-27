import { writable, derived } from 'svelte/store';
import { en, type MessageKey } from './messages/en';
import { de } from './messages/de';

export type Lang = 'en' | 'de';

const messages: Record<Lang, Record<MessageKey, string>> = { en, de };

export const language = writable<Lang>('en');

// Reactive translator store — use as $t('key') in .svelte files
export const t = derived(language, (lang) => {
	return (key: MessageKey, ...args: (string | number)[]): string => {
		let msg = messages[lang]?.[key] ?? messages.en[key] ?? key;
		for (let i = 0; i < args.length; i++) {
			msg = msg.replace(`{${i}}`, String(args[i]));
		}
		return msg;
	};
});

export function setLanguage(lang: Lang): void {
	language.set(lang);
	if (typeof document !== 'undefined') {
		document.cookie = `archiver_lang=${lang}; path=/; max-age=31536000; SameSite=Lax; Secure`;
	}
}

export function initLanguage(lang: Lang): void {
	language.set(lang);
}

export { type MessageKey } from './messages/en';
