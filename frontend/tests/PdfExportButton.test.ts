// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import PdfExportButton from '../src/lib/components/PdfExportButton.svelte';

/**
 * The button's click-to-download flow against a fake API: ask, poll, then hand the file to the
 * browser. The polling delays are real timers made fast, so the loop under test is the real one.
 */
describe('PdfExportButton', () => {
	const assign = vi.fn();
	let fetchMock: ReturnType<typeof vi.fn>;

	beforeEach(() => {
		vi.useFakeTimers();
		assign.mockReset();
		Object.defineProperty(window, 'location', {
			value: { assign },
			writable: true,
			configurable: true
		});
		fetchMock = vi.fn();
		vi.stubGlobal('fetch', fetchMock);
	});

	afterEach(() => {
		cleanup();
		vi.useRealTimers();
		vi.unstubAllGlobals();
	});

	const json = (status: number, body: unknown) =>
		new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

	it('asks for the export, waits for it, then downloads it', async () => {
		fetchMock
			.mockResolvedValueOnce(json(202, { id: 'abc', state: 'queued', error: null }))
			.mockResolvedValueOnce(json(200, { id: 'abc', state: 'building', error: null }))
			.mockResolvedValueOnce(json(200, { id: 'abc', state: 'ready', error: null }));

		render(PdfExportButton, { recordId: 7, variant: 'english', pages: '1-3', label: 'Get PDF' });
		await fireEvent.click(screen.getByRole('button'));
		await vi.runAllTimersAsync();

		const [createUrl, createInit] = fetchMock.mock.calls[0];
		expect(createUrl).toContain('/records/7/pdf-exports');
		expect(createInit.method).toBe('POST');
		expect(JSON.parse(createInit.body)).toEqual({ variant: 'english', pages: '1-3' });
		expect(fetchMock.mock.calls[1][0]).toContain('/pdf-exports/abc');
		expect(assign).toHaveBeenCalledTimes(1);
		expect(assign.mock.calls[0][0]).toContain('/pdf-exports/abc/file');
	});

	it('shows why an export failed and downloads nothing', async () => {
		fetchMock.mockResolvedValueOnce(
			json(200, { id: 'x', state: 'failed', error: 'out of memory' })
		);

		render(PdfExportButton, { recordId: 7, label: 'Get PDF' });
		await fireEvent.click(screen.getByRole('button'));
		await vi.runAllTimersAsync();

		expect(await screen.findByRole('alert')).toBeTruthy();
		expect(screen.getByRole('alert').textContent).toContain('out of memory');
		expect(assign).not.toHaveBeenCalled();
	});

	it('reports a refused request and lets the user try again', async () => {
		fetchMock.mockResolvedValueOnce(new Response('nope', { status: 500 }));

		render(PdfExportButton, { recordId: 7, label: 'Get PDF' });
		const button = screen.getByRole('button') as HTMLButtonElement;
		await fireEvent.click(button);
		await vi.runAllTimersAsync();

		expect(screen.getByRole('alert')).toBeTruthy();
		expect(button.disabled).toBe(false);
		expect(assign).not.toHaveBeenCalled();
	});

	it('does not download once the page that asked has gone away', async () => {
		fetchMock
			.mockResolvedValueOnce(json(202, { id: 'abc', state: 'queued', error: null }))
			.mockResolvedValue(json(200, { id: 'abc', state: 'ready', error: null }));

		const view = render(PdfExportButton, { recordId: 7, label: 'Get PDF' });
		await fireEvent.click(screen.getByRole('button'));
		view.unmount();
		await vi.runAllTimersAsync();

		expect(assign).not.toHaveBeenCalled();
	});
});
