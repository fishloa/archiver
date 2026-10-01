import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vitest/config';

export default defineConfig(({ mode }) => ({
	plugins: [tailwindcss(), sveltekit()],
	// Components are mounted in jsdom under test, so Svelte must resolve to its browser build.
	resolve: mode === 'test' ? { conditions: ['browser'] } : undefined,
	test: {
		include: ['tests/**/*.test.ts'],
		exclude: ['tests/i18n-completeness.test.ts']
	}
}));
