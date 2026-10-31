import { afterEach, describe, expect, it, vi } from 'vitest';
import { ClientLayout, createClientLayoutApi, LayoutState } from './layout.api';

const club = '9223372036854775800';
const hall = '9223372036854775801';
const table = '9223372036854775802';
const data = (version = '1'): ClientLayout => ({
  id: club,
  clubId: club,
  clientId: '9007199254740993',
  permissions: ['client.club.read', 'client.club.layout.write'],
  halls: [
    {
      id: hall,
      name: 'Основной зал',
      layoutRevision: version,
      plan: { contentType: 'image/png', sha256: 'synthetic', sizeBytes: 8 },
      tables: [
        {
          id: table,
          hallId: hall,
          tableNumber: 1,
          label: 'У окна',
          capacity: 2,
          x: 0.2,
          y: 0.3,
        },
      ],
    },
  ],
});
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
const deferred = <T>() => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
};
afterEach(() => {
  vi.restoreAllMocks();
});

describe('client layout adapter', () => {
  it('preserves BIGINT IDs, exact paths and optimistic revision for table edits', async () => {
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json(data()))
      .mockResolvedValueOnce(json(data('2')));
    const api = createClientLayoutApi('signed-client', club, vi.fn(), vi.fn());
    await api.load();
    await api.editor.updateHallTable(hall, table, {
      label: 'Диван',
      capacity: 4,
    });
    expect(fetch.mock.calls[1][0]).toBe(
      '/api/client/clubs/' + club + '/halls/' + hall + '/tables/' + table,
    );
    const request = fetch.mock.calls[1][1]!;
    expect(JSON.parse(String(request.body))).toEqual({
      label: 'Диван',
      capacity: 4,
      expectedVersion: '1',
    });
    expect(request.headers).toMatchObject({
      'X-Telegram-Init-Data': 'signed-client',
      'Content-Type': 'application/json',
      'Idempotency-Key': expect.any(String),
    });
    expect(request.cache).toBe('no-store');
    api.dispose();
  });
  it('retries a lost write with exactly the original intent, version and key', async () => {
    const states: LayoutState[] = [];
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json(data()))
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce(json(data('2')));
    const api = createClientLayoutApi(
      'signed-client',
      club,
      (state) => states.push(state),
      vi.fn(),
    );
    await api.load();
    await expect(
      api.editor.updateHallTable(hall, table, { x: 0.4, y: 0.7 }),
    ).rejects.toMatchObject({ code: 'unknown_outcome' });
    expect(states.at(-1)?.pending).toBe(true);
    await expect(
      api.halls.createHall(club, {
        name: 'Другой',
        geometryJson: '',
        isActive: false,
      }),
    ).rejects.toBeDefined();
    await api.retry();
    expect(fetch.mock.calls[2][1]?.headers).toEqual(
      fetch.mock.calls[1][1]?.headers,
    );
    expect(fetch.mock.calls[2][1]?.body).toEqual(fetch.mock.calls[1][1]?.body);
    expect(states.at(-1)?.pending).toBe(false);
    api.dispose();
  });
  it('uploads raw images with the current version and does not use admin APIs', async () => {
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json(data()))
      .mockResolvedValueOnce(json(data('2')));
    const api = createClientLayoutApi('signed-client', club, vi.fn(), vi.fn());
    const file = new File(['synthetic png'], 'plan.png', { type: 'image/png' });
    await api.load();
    await api.editor.uploadHallPlan(hall, file);
    expect(fetch.mock.calls[1][0]).toBe(
      '/api/client/clubs/' + club + '/halls/' + hall + '/plan',
    );
    expect(fetch.mock.calls[1][1]?.body).toBe(file);
    expect(fetch.mock.calls[1][1]?.headers).toMatchObject({
      'Content-Type': 'image/png',
      'X-Layout-Version': '1',
    });
    api.dispose();
  });
  it('distinguishes an empty plan from a denied binary and terminally fences late data', async () => {
    const late = deferred<Response>();
    const states: LayoutState[] = [];
    const lost = vi.fn();
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json(data()))
      .mockImplementationOnce(() => late.promise)
      .mockResolvedValueOnce(json(data()))
      .mockResolvedValueOnce(json({ code: 'not_found' }, 404));
    const api = createClientLayoutApi(
      'signed-client',
      club,
      (state) => states.push(state),
      lost,
    );
    await api.load();
    const pendingRead = api.load();
    await expect(
      api.editor.fetchHallPlanBlob(club, hall),
    ).rejects.toMatchObject({ status: 404 });
    expect(lost).toHaveBeenCalledTimes(1);
    late.resolve(json(data()));
    await expect(pendingRead).rejects.toMatchObject({ isAbort: true });
    expect(states.at(-1)?.layout).toBeUndefined();
    await expect(api.load()).rejects.toMatchObject({ status: 404 });
  });
  it('does not let an older read restore permission or revision after a newer response', async () => {
    const old = deferred<Response>();
    const states: LayoutState[] = [];
    const newer = { ...data('4'), permissions: ['client.club.read'] };
    vi.spyOn(globalThis, 'fetch')
      .mockImplementationOnce(() => old.promise)
      .mockResolvedValueOnce(json(newer));
    const api = createClientLayoutApi(
      'signed-client',
      club,
      (state) => states.push(state),
      vi.fn(),
    );
    const previous = api.load();
    await api.load();
    old.resolve(json(data('1')));
    expect(await previous).toEqual(newer);
    expect(states.at(-1)?.layout?.permissions).toEqual(['client.club.read']);
    await expect(
      api.editor.updateHallTable(hall, table, { x: 0.5, y: 0.5 }),
    ).rejects.toMatchObject({ status: 403 });
    api.dispose();
  });
  it('reports initial network errors, supports retry and cancels an abandoned identity', async () => {
    const states: LayoutState[] = [];
    const lost = vi.fn();
    const last = deferred<Response>();
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce(json(data()))
      .mockImplementationOnce(() => last.promise);
    const api = createClientLayoutApi(
      'signed-client',
      club,
      (state) => states.push(state),
      lost,
    );
    await expect(api.load()).rejects.toBeDefined();
    expect(states.at(-1)?.error?.code).toBe('unknown_outcome');
    await api.load();
    const abandoned = api.load();
    const signal = fetch.mock.calls[2][1]?.signal;
    api.dispose();
    expect(signal?.aborted).toBe(true);
    last.resolve(json({ code: 'not_found' }, 404));
    await expect(abandoned).rejects.toBeDefined();
    expect(lost).not.toHaveBeenCalled();
  });
});

describe('client plan response lifetime', () => {
  it.each(['dispose', 'deny', 'external abort'])(
    'does not expose a late private image body after %s',
    async (ending) => {
      const body = deferred<Blob>();
      const blob = vi.fn(() => body.promise);
      const binary = new Response(null, {
        status: 200,
        headers: { 'Content-Type': 'image/png' },
      });
      Object.defineProperty(binary, 'blob', { value: blob });
      const lost = vi.fn();
      vi.spyOn(globalThis, 'fetch')
        .mockResolvedValueOnce(json(data()))
        .mockResolvedValueOnce(json(data()))
        .mockResolvedValueOnce(binary)
        .mockResolvedValueOnce(json({ code: 'not_found' }, 404));
      const api = createClientLayoutApi('signed-client', club, vi.fn(), lost);
      const external = new AbortController();
      await api.load();
      const image = api.editor.fetchHallPlanBlob(
        club,
        hall,
        null,
        external.signal,
      );
      await vi.waitFor(() => expect(blob).toHaveBeenCalledTimes(1));
      if (ending === 'dispose') api.dispose();
      else if (ending === 'external abort') external.abort();
      else await expect(api.load()).rejects.toMatchObject({ status: 404 });
      body.resolve(new Blob(['private image'], { type: 'image/png' }));
      await expect(image).rejects.toMatchObject({ isAbort: true });
      if (ending !== 'deny') expect(lost).not.toHaveBeenCalled();
      api.dispose();
    },
  );
});

describe('client layout mutation denial', () => {
  it('publishes an idle empty state before terminally aborting a refused command', async () => {
    const states: LayoutState[] = [];
    const lost = vi.fn();
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json(data()))
      .mockResolvedValueOnce(json({ code: 'not_found' }, 404));
    const api = createClientLayoutApi(
      'signed-client',
      club,
      (state) => states.push(state),
      lost,
    );
    await api.load();
    await expect(
      api.editor.updateHallTable(hall, table, { label: 'Changed' }),
    ).rejects.toMatchObject({ status: 404 });
    expect(states.some((state) => state.saving)).toBe(true);
    expect(states.at(-1)).toMatchObject({
      saving: false,
      pending: false,
      layout: undefined,
    });
    expect(lost).toHaveBeenCalledTimes(1);
    api.dispose();
  });
});
