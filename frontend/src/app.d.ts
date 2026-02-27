declare global {
	namespace App {
		interface Locals {
			userEmail?: string;
			language: import('$lib/i18n').Lang;
		}
	}
}

export {};
