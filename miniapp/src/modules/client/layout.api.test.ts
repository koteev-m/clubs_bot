import { beforeEach } from 'vitest';
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
      plan: {
        contentType: 'image/png',
        sha256:
          '41c04b3f2d0c69a66494faa96f05b622709379f3a5251078e0cd39fa9c44d67e',
        sizeBytes: 8,
      },
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
beforeEach(async () => {
  const { webcrypto } = await vi.importActual<{ webcrypto: Crypto }>(
    'node:crypto',
  );
  vi.stubGlobal('crypto', webcrypto);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('client layout adapter', () => {
  it.each([false, true])(
    'write-only revoke fences late zone success and stale reads, restoration=%s',
    async (restore) => {
      const writeResponse = deferred<Response>();
      const oldRead = deferred<Response>();
      const states: LayoutState[] = [];
      const fetch = vi
        .spyOn(globalThis, 'fetch')
        .mockResolvedValueOnce(json(data('5')))
        .mockImplementationOnce(() => writeResponse.promise)
        .mockImplementationOnce(() => oldRead.promise)
        .mockResolvedValueOnce(
          json({ ...data('4'), permissions: ['client.club.read'] }),
        );
      const api = createClientLayoutApi(
        'signed-client',
        club,
        (state) => states.push(state),
        vi.fn(),
      );
      await api.load();
      const confirmed = vi.fn();
      const write = api.zones.create(hall, 'Балкон', '5', confirmed);
      const olderRead = api.revalidate();
      await api.revalidate();
      expect(states.at(-1)?.layout?.permissions).toEqual(['client.club.read']);
      expect(states.at(-1)?.layout?.halls[0].layoutRevision).toBe('5');
      expect(states.at(-1)?.pending).toBe(true);
      if (restore) {
        fetch.mockResolvedValueOnce(json(data('6')));
        await api.revalidate();
      }
      writeResponse.resolve(
        json({
          ...data('7'),
          halls: [{ ...data('7').halls[0], name: 'Forbidden late result' }],
        }),
      );
      await expect(write).rejects.toMatchObject({ code: 'authority_changed' });
      oldRead.resolve(json(data('99')));
      await olderRead;
      expect(states.at(-1)?.layout?.halls[0].layoutRevision).toBe('5');
      expect(states.at(-1)?.layout?.halls[0].name).toBe('Основной зал');
      expect(
        states.at(-1)?.layout?.permissions.includes('client.club.layout.write'),
      ).toBe(restore);
      expect(states.at(-1)?.pending).toBe(false);
      expect(confirmed).not.toHaveBeenCalled();
      api.dispose();
    },
  );

  it('write-only revoke retains unknown zone intent for exact idempotent replay', async () => {
    let failWrite!: (error: Error) => void;
    const unknown = new Promise<Response>((_resolve, reject) => {
      failWrite = reject;
    });
    const states: LayoutState[] = [];
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json(data()))
      .mockImplementationOnce(() => unknown)
      .mockResolvedValueOnce(
        json({ ...data(), permissions: ['client.club.read'] }),
      );
    const api = createClientLayoutApi(
      'signed-client',
      club,
      (state) => states.push(state),
      vi.fn(),
    );
    await api.load();
    const write = api.zones.create(hall, 'Балкон', '1');
    await api.revalidate();
    failWrite(new Error('lost after authority changed'));
    await expect(write).rejects.toMatchObject({ code: 'unknown_outcome' });
    expect(states.at(-1)?.pending).toBe(true);
    fetch.mockResolvedValueOnce(json(data()));
    await api.revalidate();
    fetch.mockResolvedValueOnce(json(data('2')));
    await api.retry();
    expect(fetch.mock.calls[4][1]?.body).toBe(fetch.mock.calls[1][1]?.body);
    expect(fetch.mock.calls[4][1]?.headers).toEqual(
      fetch.mock.calls[1][1]?.headers,
    );
    expect(states.at(-1)?.pending).toBe(false);
    api.dispose();
  });

  it('zone commands retain the exact unknown intent and idempotency key on retry', async () => {
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json(data()))
      .mockRejectedValueOnce(new Error('lost response'))
      .mockResolvedValueOnce(json(data('2')));
    const api = createClientLayoutApi('signed-client', club, vi.fn(), vi.fn());
    await api.load();
    const confirmed = vi.fn();
    await expect(
      api.zones.create(hall, 'Балкон', '1', confirmed),
    ).rejects.toMatchObject({
      code: 'unknown_outcome',
    });
    expect(confirmed).not.toHaveBeenCalled();
    expect((await api.retry())?.refreshEditor).toBe(false);
    expect(confirmed).toHaveBeenCalledTimes(1);
    await api.retry();
    expect(confirmed).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls[1][0]).toBe(
      '/api/client/clubs/' + club + '/halls/' + hall + '/zones',
    );
    expect(fetch.mock.calls[1][1]?.body).toBe(fetch.mock.calls[2][1]?.body);
    expect(fetch.mock.calls[1][1]?.headers).toEqual(
      fetch.mock.calls[2][1]?.headers,
    );
    expect(JSON.parse(String(fetch.mock.calls[1][1]?.body))).toEqual({
      name: 'Балкон',
      expectedVersion: '1',
    });
    api.dispose();
  });

  it('zone assignment uses exact table and zone strings and cannot resurrect revoked state', async () => {
    const late = deferred<Response>();
    const changed = vi.fn();
    const lost = vi.fn();
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json(data()))
      .mockImplementationOnce(() => late.promise)
      .mockResolvedValueOnce(json({ code: 'not_found' }, 404));
    const api = createClientLayoutApi('signed-client', club, changed, lost);
    await api.load();
    const confirmed = vi.fn();
    const write = api.zones.assign(
      hall,
      table,
      '9007199254740999',
      '1',
      confirmed,
    );
    await expect(api.revalidate()).rejects.toMatchObject({ status: 404 });
    late.resolve(json(data('2')));
    await expect(write).rejects.toBeDefined();
    expect(JSON.parse(String(fetch.mock.calls[1][1]?.body))).toEqual({
      zoneId: '9007199254740999',
      expectedVersion: '1',
    });
    expect(lost).toHaveBeenCalledOnce();
    expect(confirmed).not.toHaveBeenCalled();
    expect(changed.mock.calls.at(-1)?.[0].layout).toBeUndefined();
    api.dispose();
  });

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
    const file = new File(['synthetic png'], 'plan.png', {
      type: 'image/png',
    });
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
  it('confirms a denied layout after a binary 404 and terminally fences late data', async () => {
    const late = deferred<Response>();
    const states: LayoutState[] = [];
    const lost = vi.fn();
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json(data()))
      .mockImplementationOnce(() => late.promise)
      .mockResolvedValueOnce(json(data()))
      .mockResolvedValueOnce(json({ code: 'not_found' }, 404))
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
  it.each([
    ['temporary error', json({ code: 'internal_error' }, 503), 503],
    ['new image', json(data('2')), 409],
  ])(
    'keeps the layout authorized after a binary 404 and %s',
    async (_, metadata, status) => {
      const changed = vi.fn();
      const lost = vi.fn();
      vi.spyOn(globalThis, 'fetch')
        .mockResolvedValueOnce(json(data()))
        .mockResolvedValueOnce(json(data()))
        .mockResolvedValueOnce(json({ code: 'not_found' }, 404))
        .mockResolvedValueOnce(metadata)
        .mockResolvedValueOnce(json(data('2')))
        .mockResolvedValueOnce(json(data('2')))
        .mockResolvedValueOnce(new Response('new image'))
        .mockResolvedValueOnce(json(data('2')));
      const api = createClientLayoutApi('synthetic', club, changed, lost);
      await api.load();
      await expect(
        api.editor.fetchHallPlanBlob(club, hall),
      ).rejects.toMatchObject({ status });
      expect(lost).not.toHaveBeenCalled();
      expect(changed.mock.lastCall?.[0].layout?.clubId).toBe(club);
      expect((await api.load()).halls[0].layoutRevision).toBe('2');
      expect((await api.editor.fetchHallPlanBlob(club, hall)).status).toBe(200);
      api.dispose();
    },
  );

  it('ignores a late refusal when an image metadata check is cancelled', async () => {
    const late = deferred<Response>();
    const lost = vi.fn();
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json(data()))
      .mockResolvedValueOnce(json(data()))
      .mockResolvedValueOnce(json({ code: 'not_found' }, 404))
      .mockReturnValueOnce(late.promise)
      .mockResolvedValueOnce(json(data()));
    const api = createClientLayoutApi('synthetic', club, vi.fn(), lost);
    await api.load();
    const controller = new AbortController();
    const plan = api.editor.fetchHallPlanBlob(
      club,
      hall,
      undefined,
      controller.signal,
    );
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(4));
    controller.abort();
    late.resolve(json({ code: 'not_found' }, 404));
    await expect(plan).rejects.toMatchObject({ isAbort: true });
    expect(lost).not.toHaveBeenCalled();
    await expect(api.load()).resolves.toMatchObject({ clubId: club });
    api.dispose();
  });

  it('rechecks a raced image absence and clears the conflict only after an explicit refresh', async () => {
    const states: LayoutState[] = [];
    const lost = vi.fn();
    const removed = data('2');
    removed.halls[0].plan = null;
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json(data()))
      .mockResolvedValueOnce(json(data()))
      .mockResolvedValueOnce(json({ code: 'not_found' }, 404))
      .mockResolvedValueOnce(json(removed))
      .mockResolvedValueOnce(json(removed))
      .mockResolvedValueOnce(json(removed));
    const api = createClientLayoutApi(
      'signed-client',
      club,
      (state) => states.push(state),
      lost,
    );
    await api.load();
    await expect(api.editor.fetchHallPlanBlob(club, hall)).resolves.toEqual({
      status: 404,
    });
    expect(lost).not.toHaveBeenCalled();
    await api.revalidate();
    expect(states.at(-1)?.error?.code).toBe('version_conflict');
    await expect(api.editor.removeHallPlan!(hall)).rejects.toMatchObject({
      code: 'version_conflict',
    });
    await api.load();
    expect(states.at(-1)?.error).toBeUndefined();
    expect(states.at(-1)?.layout?.halls[0].plan).toBeNull();
    api.dispose();
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
      const body = deferred<ArrayBuffer>();
      const readBody = vi.fn(() => body.promise);
      const binary = new Response(null, {
        status: 200,
        headers: { 'Content-Type': 'image/png' },
      });
      Object.defineProperty(binary, 'arrayBuffer', { value: readBody });
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
      await vi.waitFor(() => expect(readBody).toHaveBeenCalledTimes(1));
      if (ending === 'dispose') api.dispose();
      else if (ending === 'external abort') external.abort();
      else await expect(api.load()).rejects.toMatchObject({ status: 404 });
      body.resolve(new TextEncoder().encode('private image').buffer);
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

it.each(['different bytes', 'moved tables', 'revoked grant'] as const)(
  'withholds the image when its snapshot fails confirmation through %s',
  async (change) => {
    const states: LayoutState[] = [];
    const lost = vi.fn();
    const moved = data('2');
    moved.halls[0].tables[0].x = 0.8;
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json(data()))
      .mockResolvedValueOnce(
        new Response(
          change === 'different bytes' ? 'wrong bytes' : 'new image',
        ),
      )
      .mockResolvedValueOnce(
        change === 'revoked grant' ? json({}, 404) : json(moved),
      );
    const api = createClientLayoutApi(
      'synthetic',
      club,
      (state) => states.push(state),
      lost,
    );
    await expect(
      api.editor.fetchHallPlanBlob(club, hall),
    ).rejects.toMatchObject({
      status: change === 'revoked grant' ? 404 : 409,
    });
    if (change === 'revoked grant') {
      expect(lost).toHaveBeenCalledTimes(1);
      expect(states.at(-1)?.layout).toBeUndefined();
    } else {
      expect(lost).not.toHaveBeenCalled();
    }
    expect(fetch).toHaveBeenCalledTimes(change === 'different bytes' ? 2 : 3);
    api.dispose();
  },
);

it('refuses an image before loading its bytes when a bound preview has old table coordinates', async () => {
  const moved = data('2');
  moved.halls[0].tables[0].x = 0.8;
  const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(moved));
  const api = createClientLayoutApi(
    'synthetic',
    club,
    vi.fn(),
    vi.fn(),
    data().halls[0],
  );
  await expect(api.editor.fetchHallPlanBlob(club, hall)).rejects.toMatchObject({
    status: 409,
    code: 'version_conflict',
  });
  expect(fetch).toHaveBeenCalledTimes(1);
  api.dispose();
});
