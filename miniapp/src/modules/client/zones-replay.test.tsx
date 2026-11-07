import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import ClubLayout from './ClubLayout';
import type { ClientLayout } from './layout.api';

vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({}),
}));
// Keep the actual cabinet, zone forms and HTTP adapter. The unrelated image editor is not under test.
vi.mock('../admin/pages/HallEditorScreen', () => ({
  default: () => <div>Редактор схемы</div>,
}));

const initial = (): ClientLayout => ({
  id: '42',
  clubId: '42',
  clientId: '21',
  permissions: ['client.club.read', 'client.club.layout.write'],
  halls: [
    {
      id: '1',
      name: 'Первый зал',
      layoutRevision: '8',
      plan: null,
      zones: [
        { id: '901', name: 'Зона A', tableCount: 1 },
        { id: '902', name: 'Балкон', tableCount: 0 },
      ],
      tables: [
        {
          id: '903',
          hallId: '1',
          tableNumber: 7,
          label: 'У окна',
          capacity: 4,
          x: 0.2,
          y: 0.3,
          zoneId: '901',
        },
      ],
    },
  ],
});
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status });
const form = (label: string) =>
  within(screen.getByLabelText(label).closest('form')!);
const input = (label: string) =>
  screen.getByLabelText(label) as HTMLInputElement;
const mount = () =>
  render(
    <ClubLayout
      credential="synthetic"
      id="42"
      hallId="1"
      back={vi.fn()}
      openHall={vi.fn()}
      reportDirty={vi.fn()}
      reportPending={vi.fn()}
    />,
  );

beforeEach(async () => {
  const { webcrypto } = await vi.importActual<{ webcrypto: Crypto }>(
    'node:crypto',
  );
  vi.stubGlobal('crypto', webcrypto);
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
  vi.spyOn(window, 'confirm').mockReturnValue(true);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it('replays creating B with the original key without losing zone A or table drafts', async () => {
  let saved = initial();
  const attempts: { body: string; key: string | null }[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_path, options) => {
    if (!options?.method) return json(saved);
    attempts.push({
      body: String(options.body),
      key: new Headers(options.headers).get('Idempotency-Key'),
    });
    if (attempts.length === 1) {
      saved = {
        ...saved,
        halls: [
          {
            ...saved.halls[0],
            layoutRevision: '9',
            zones: [
              ...saved.halls[0].zones!,
              { id: '904', name: 'Зона B', tableCount: 0 },
            ],
          },
        ],
      };
      throw new Error('response lost after commit');
    }
    return json(saved);
  });
  mount();
  await screen.findByLabelText('Название зоны «Зона A»');
  fireEvent.change(input('Название зоны «Зона A»'), {
    target: { value: 'Черновик A' },
  });
  fireEvent.change(input('Зона стола 7 · У окна'), {
    target: { value: '902' },
  });
  fireEvent.change(input('Новая зона'), { target: { value: 'Зона B' } });
  fireEvent.click(screen.getByRole('button', { name: 'Добавить зону' }));
  fireEvent.click(
    await screen.findByRole('button', { name: 'Повторить ту же операцию' }),
  );
  await screen.findByLabelText('Название зоны «Зона B»');
  expect(input('Название зоны «Зона A»').value).toBe('Черновик A');
  expect(input('Зона стола 7 · У окна').value).toBe('902');
  expect(input('Новая зона').value).toBe('');
  expect(form('Название зоны «Зона A»').queryByRole('status')).toBeNull();
  expect(form('Зона стола 7 · У окна').queryByRole('status')).toBeNull();
  expect(attempts).toHaveLength(2);
  expect(attempts[1]).toEqual(attempts[0]);
  expect(JSON.parse(attempts[1].body)).toEqual({
    name: 'Зона B',
    expectedVersion: '8',
  });
});

const element = (id = '42', hallId = '1') => (
  <ClubLayout
    key={id}
    credential="synthetic"
    id={id}
    hallId={hallId}
    back={vi.fn()}
    openHall={vi.fn()}
    reportDirty={vi.fn()}
    reportPending={vi.fn()}
  />
);
function server() {
  const state = { saved: initial(), denied: false, loseNext: false };
  const attempts: {
    path: string;
    body: Record<string, string>;
    raw: string;
    key: string | null;
  }[] = [];
  const receipts = new Map<string, ClientLayout>();
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path, options) => {
    if (state.denied) return json({ code: 'not_found' }, 404);
    if (!options?.method) return json(state.saved);
    const body = JSON.parse(String(options.body));
    const key = new Headers(options.headers).get('Idempotency-Key')!;
    attempts.push({ path: String(path), body, raw: String(options.body), key });
    if (receipts.has(key)) return json(receipts.get(key));
    const hall = state.saved.halls[0];
    if (body.expectedVersion !== hall.layoutRevision)
      return json({ code: 'version_conflict' }, 409);
    const updated = structuredClone(hall);
    updated.layoutRevision = String(Number(hall.layoutRevision) + 1);
    if (String(path).endsWith('/zones'))
      updated.zones!.push({ id: '904', name: body.name, tableCount: 0 });
    else if (String(path).includes('/zones/'))
      updated.zones = updated.zones!.map((zone) =>
        zone.id === String(path).split('/').at(-1)
          ? { ...zone, name: body.name }
          : zone,
      );
    else
      updated.tables = updated.tables.map((table) =>
        table.id === String(path).split('/').at(-1)
          ? { ...table, zoneId: body.zoneId }
          : table,
      );
    state.saved = {
      ...state.saved,
      halls: [updated, ...state.saved.halls.slice(1)],
    };
    receipts.set(key, structuredClone(state.saved));
    if (state.loseNext) {
      state.loseNext = false;
      throw new Error('lost response after commit');
    }
    return json(state.saved);
  });
  return { state, attempts };
}
const change = (label: string, value: string) =>
  fireEvent.change(input(label), { target: { value } });
const saveZone = (label: string) =>
  fireEvent.click(
    form(label).getByRole('button', { name: 'Сохранить название зоны' }),
  );
const reload = async () =>
  act(async () =>
    fireEvent.click(
      screen.getByRole('button', { name: 'Загрузить актуальные данные' }),
    ),
  );

it('keeps original draft versions after reread; cancel adopts fresh values, ordinary save affects only its form', async () => {
  const { state, attempts } = server();
  mount();
  await screen.findByLabelText('Название зоны «Зона A»');
  change('Название зоны «Зона A»', 'Черновик A');
  change('Зона стола 7 · У окна', '902');
  state.saved.halls[0] = {
    ...state.saved.halls[0],
    layoutRevision: '10',
    zones: state.saved.halls[0].zones!.map((zone) =>
      zone.id === '902' ? { ...zone, name: 'Обновлённый балкон' } : zone,
    ),
  };
  await reload();
  expect(input('Название зоны «Обновлённый балкон»').value).toBe(
    'Обновлённый балкон',
  );
  expect(input('Название зоны «Зона A»').value).toBe('Черновик A');
  expect(input('Зона стола 7 · У окна').value).toBe('902');
  saveZone('Название зоны «Зона A»');
  await waitFor(() => expect(attempts).toHaveLength(1));
  expect(attempts[0].body).toEqual({
    name: 'Черновик A',
    expectedVersion: '8',
  });
  expect(
    (await form('Название зоны «Зона A»').findByRole('alert')).textContent,
  ).toContain('Зал изменён');
  expect(form('Название зоны «Зона A»').queryByRole('status')).toBeNull();
  await reload();
  expect(input('Название зоны «Зона A»').value).toBe('Черновик A');
  fireEvent.click(
    form('Название зоны «Зона A»').getByRole('button', {
      name: 'Отменить изменения',
    }),
  );
  expect(input('Название зоны «Зона A»').value).toBe('Зона A');
  expect(attempts).toHaveLength(1);
  change('Название зоны «Зона A»', 'Новое имя A');
  saveZone('Название зоны «Зона A»');
  await screen.findByLabelText('Название зоны «Новое имя A»');
  expect(attempts[1].body.expectedVersion).toBe('10');
  expect(
    form('Название зоны «Новое имя A»').getByRole('status').textContent,
  ).toBe('Зона сохранена.');
  expect(input('Зона стола 7 · У окна').value).toBe('902');
  fireEvent.click(
    screen.getByRole('button', { name: 'Сохранить зону стола 7' }),
  );
  await waitFor(() => expect(attempts).toHaveLength(3));
  expect(attempts[2].body).toEqual({ zoneId: '902', expectedVersion: '8' });
  await reload();
  fireEvent.click(
    form('Зона стола 7 · У окна').getByRole('button', {
      name: 'Отменить изменения',
    }),
  );
  expect(input('Зона стола 7 · У окна').value).toBe('901');
  expect(form('Зона стола 7 · У окна').queryByRole('status')).toBeNull();
});

it.each(['rename', 'assignment'] as const)(
  'replay completes only the submitted %s form and retains other base versions',
  async (kind) => {
    const { state, attempts } = server();
    state.loseNext = true;
    mount();
    await screen.findByLabelText('Название зоны «Зона A»');
    change('Название зоны «Зона A»', 'Черновик A');
    if (kind === 'rename') {
      change('Название зоны «Балкон»', 'Сцена');
      saveZone('Название зоны «Балкон»');
    } else {
      change('Зона стола 7 · У окна', '902');
      fireEvent.click(
        screen.getByRole('button', { name: 'Сохранить зону стола 7' }),
      );
    }
    fireEvent.click(
      await screen.findByRole('button', { name: 'Повторить ту же операцию' }),
    );
    const label =
      kind === 'rename' ? 'Название зоны «Сцена»' : 'Зона стола 7 · У окна';
    await waitFor(() => expect(form(label).getByRole('status')).toBeTruthy());
    expect(
      form(label).queryByRole('button', { name: 'Отменить изменения' }),
    ).toBeNull();
    expect(input(label).value).toBe(kind === 'rename' ? 'Сцена' : '902');
    expect(input('Название зоны «Зона A»').value).toBe('Черновик A');
    expect(form('Название зоны «Зона A»').queryByRole('status')).toBeNull();
    expect(attempts[1]).toEqual(attempts[0]);
    saveZone('Название зоны «Зона A»');
    await waitFor(() => expect(attempts).toHaveLength(3));
    expect(attempts[2].body.expectedVersion).toBe('8');
    expect(
      (await form('Название зоны «Зона A»').findByRole('alert')).textContent,
    ).toContain('Зал изменён');
  },
);

it('write revoke blocks drafts, restore retains their versions, read revoke clears private forms', async () => {
  const { state, attempts } = server();
  const view = mount();
  await screen.findByLabelText('Название зоны «Зона A»');
  change('Название зоны «Зона A»', 'Приватный черновик');
  state.saved = { ...state.saved, permissions: ['client.club.read'] };
  await act(async () => {
    window.dispatchEvent(new Event('focus'));
  });
  expect(input('Название зоны «Зона A»').disabled).toBe(true);
  expect(input('Название зоны «Зона A»').value).toBe('Приватный черновик');
  state.saved = { ...state.saved, permissions: initial().permissions };
  await act(async () => {
    window.dispatchEvent(new Event('focus'));
  });
  expect(input('Название зоны «Зона A»').disabled).toBe(false);
  saveZone('Название зоны «Зона A»');
  await screen.findByLabelText('Название зоны «Приватный черновик»');
  expect(attempts[0].body.expectedVersion).toBe('8');
  change('Новая зона', 'Новый приватный черновик');
  state.denied = true;
  await act(async () => {
    window.dispatchEvent(new Event('focus'));
  });
  expect(screen.queryByRole('region', { name: 'Зоны зала' })).toBeNull();
  expect(screen.queryByDisplayValue('Новый приватный черновик')).toBeNull();
  view.unmount();
  state.denied = false;
  mount();
  await screen.findByLabelText('Новая зона');
  expect(input('Новая зона').value).toBe('');
});

it('hall or club changes never transfer drafts or a replay completion to another form', async () => {
  const { state } = server();
  state.saved.halls.push({
    ...structuredClone(state.saved.halls[0]),
    id: '2',
    name: 'Второй зал',
  });
  state.loseNext = true;
  const view = render(element());
  await screen.findByLabelText('Название зоны «Зона A»');
  change('Название зоны «Зона A»', 'Первый черновик');
  change('Новая зона', 'Зона B');
  fireEvent.click(screen.getByRole('button', { name: 'Добавить зону' }));
  await screen.findByRole('button', { name: 'Повторить ту же операцию' });
  view.rerender(element('42', '2'));
  expect(input('Название зоны «Зона A»').value).toBe('Зона A');
  expect(input('Новая зона').value).toBe('');
  fireEvent.click(
    screen.getByRole('button', { name: 'Повторить ту же операцию' }),
  );
  await waitFor(() =>
    expect(
      screen.queryByRole('button', { name: 'Повторить ту же операцию' }),
    ).toBeNull(),
  );
  expect(screen.queryByText('Зона сохранена.')).toBeNull();
  expect(screen.queryByLabelText('Название зоны «Зона B»')).toBeNull();
  view.rerender(element());
  await screen.findByLabelText('Название зоны «Зона B»');
  expect(input('Название зоны «Зона A»').value).toBe('Зона A');
  change('Название зоны «Зона A»', 'Старый клуб');
  state.saved = { ...initial(), id: '43', clubId: '43', clientId: '22' };
  view.rerender(element('43'));
  await screen.findByLabelText('Название зоны «Зона A»');
  expect(input('Название зоны «Зона A»').value).toBe('Зона A');
  expect(screen.queryByText('Зона сохранена.')).toBeNull();
});

it('late replay does not complete its form or overwrite a newer observed hall', async () => {
  const { state } = server();
  state.loseNext = true;
  mount();
  await screen.findByLabelText('Новая зона');
  change('Название зоны «Зона A»', 'Черновик A');
  change('Новая зона', 'Зона B');
  fireEvent.click(screen.getByRole('button', { name: 'Добавить зону' }));
  const button = await screen.findByRole('button', {
    name: 'Повторить ту же операцию',
  });
  let resolve!: (value: Response) => void;
  const replay = new Promise<Response>((done) => {
    resolve = done;
  });
  const old = structuredClone(state.saved);
  vi.mocked(fetch).mockImplementationOnce(() => replay);
  fireEvent.click(button);
  state.saved = {
    ...state.saved,
    halls: [
      {
        ...state.saved.halls[0],
        layoutRevision: '10',
        zones: state.saved.halls[0].zones!.map((zone) =>
          zone.id === '904' ? { ...zone, name: 'Новее B' } : zone,
        ),
      },
    ],
  };
  await act(async () => {
    window.dispatchEvent(new Event('focus'));
  });
  await act(async () => {
    resolve(json(old));
  });
  expect(input('Название зоны «Новее B»').value).toBe('Новее B');
  expect(input('Название зоны «Зона A»').value).toBe('Черновик A');
  expect(input('Новая зона').value).toBe('Зона B');
  expect(screen.queryByText('Зона сохранена.')).toBeNull();
});
