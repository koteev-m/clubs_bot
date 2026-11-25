import { cleanup, render, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import ClientAccess from './ClientAccess';
import { readClientEntry } from './clientEntry';

vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({}),
}));
vi.mock('../auth/hooks/useInitData', () => ({
  useInitData: () => ({ initData: 'signed-test-recipient' }),
}));
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
it.each([
  ['ci', 'cm_', 'query'],
  ['ci', 'ci_', 'fragment'],
  ['cm', 'ci_', 'embedded'],
  ['cm', 'cm_', 'sdk'],
  ['ci', 'inv_', 'sdk'],
  ['cm', 'inv_', 'query'],
])(
  'resolves %s invitation whose opaque value begins %s through %s',
  async (outer, inner, channel) => {
    const raw = inner.padEnd(43, 'A');
    const start = outer + '_' + raw;
    const entry = readClientEntry(
      channel === 'query' ? '?startapp=' + start : '',
      channel === 'fragment'
        ? '#tgWebAppStartParam=' + start
        : channel === 'embedded'
          ? '#tgWebAppData=' + encodeURIComponent('start_param=' + start)
          : '',
      channel === 'sdk' ? start : undefined,
    );
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(async () =>
        Response.json({ code: 'not_found' }, { status: 404 }),
      );
    render(
      <ClientAccess
        initialToken={entry.token}
        invitationKind={entry.invitationKind}
      />,
    );
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    expect(String(fetch.mock.calls[0][0])).toBe(
      '/api/client/' +
        (outer === 'ci' ? 'invitations' : 'manager-invitations') +
        '/resolve',
    );
    expect(JSON.parse(String(fetch.mock.calls[0][1]?.body))).toEqual({
      token: raw,
    });
    expect(entry.token).toBe(raw);
  },
);
