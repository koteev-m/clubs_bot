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
import { useUiStore } from '../../shared/store/ui';
import { ClientLayout } from './layout.api';
import ClubTableCreationForm from '../admin/pages/ClubTableCreationForm';
import HallEditorScreen from '../admin/pages/HallEditorScreen';
import { AdminApiError } from '../admin/api/admin.api';
import { HallEditorApi } from '../admin/pages/layoutEditor.types';
import { describeNight, nextWeekday, shiftDate } from './calendar';

vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({}),
}));
beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});
beforeEach(async () => {
  const { webcrypto } = await vi.importActual<{ webcrypto: Crypto }>(
    'node:crypto',
  );
  vi.stubGlobal('crypto', webcrypto);
});

afterEach(() => {
  vi.unstubAllGlobals();
  cleanup();
  vi.restoreAllMocks();
});

const hallTable = (hallId: string, number: number) => ({
  id: 'table-' + hallId,
  hallId,
  tableNumber: number,
  label: 'Стол ' + number,
  capacity: 2,
  x: number === 7 ? 0.2 : null,
  y: number === 7 ? 0.8 : null,
});
const layout: ClientLayout = {
  id: '42',
  clubId: '42',
  clientId: '21',
  permissions: ['client.club.read', 'client.club.layout.write'],
  halls: [
    {
      id: '1',
      name: 'Main',
      layoutRevision: '1',
      plan: {
        contentType: 'image/png',
        sha256:
          '559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd',
        sizeBytes: 8,
      },
      tables: [hallTable('1', 7)],
    },
    {
      id: '2',
      name: 'Welcome',
      layoutRevision: '1',
      plan: {
        contentType: 'image/jpeg',
        sha256:
          'df7e70e5021544f4834bbee64a9e3789febc4be81470df629cad6ddb03320a5c',
        sizeBytes: 8,
      },
      tables: [hallTable('2', 8)],
    },
    { id: '3', name: 'Empty', layoutRevision: '1', plan: null, tables: [] },
  ],
};
const props = {
  credential: 'synthetic',
  id: '42',
  back: vi.fn(),
  openHall: vi.fn(),
  reportDirty: vi.fn(),
  reportPending: vi.fn(),
};

it('switches PNG JPEG and empty halls without leaking a plan, tables or a draft, then reopens', async () => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true,
    value: vi.fn((blob: Blob) => 'blob:' + blob.type),
  });
  Object.defineProperty(URL, 'revokeObjectURL', {
    configurable: true,
    value: vi.fn(),
  });
  let finishJpeg!: (response: Response) => void;
  const jpeg = new Promise<Response>((resolve) => {
    finishJpeg = resolve;
  });
  const fetch = vi
    .spyOn(globalThis, 'fetch')
    .mockImplementation(async (input) => {
      const path = String(input);
      if (path.endsWith('/layout')) return new Response(JSON.stringify(layout));
      if (path.endsWith('/halls/1/plan'))
        return new Response('A', { headers: { 'Content-Type': 'image/png' } });
      if (path.endsWith('/halls/2/plan'))
        return jpeg.then((response) => response.clone());
      throw new Error('Unexpected path ' + path);
    });
  const view = render(<ClubLayout {...props} hallId="1" />);
  await waitFor(() =>
    expect(screen.getByAltText('План зала').getAttribute('src')).toBe(
      'blob:image/png',
    ),
  );
  fireEvent.click(screen.getByRole('button', { name: 'Добавить столы' }));
  fireEvent.change(screen.getByLabelText('Номер нового стола'), {
    target: { value: '99' },
  });
  view.rerender(<ClubLayout {...props} hallId="2" />);
  expect(screen.queryByAltText('План зала')).toBeNull();
  expect(screen.queryByRole('form', { name: 'Создание столов' })).toBeNull();
  await screen.findByRole('button', { name: '#8 · Стол 8' });
  expect(screen.queryByRole('button', { name: '#7 · Стол 7' })).toBeNull();
  finishJpeg(new Response('B', { headers: { 'Content-Type': 'image/jpeg' } }));
  await waitFor(() =>
    expect(screen.getByAltText('План зала').getAttribute('src')).toBe(
      'blob:image/jpeg',
    ),
  );
  view.rerender(<ClubLayout {...props} hallId="3" />);
  await screen.findByText('План еще не загружен.');
  expect(screen.queryByAltText('План зала')).toBeNull();
  expect(screen.queryByRole('button', { name: /#(7|8) ·/ })).toBeNull();
  expect(
    fetch.mock.calls.some(([path]) => String(path).endsWith('/halls/3/plan')),
  ).toBe(false);
  view.rerender(<ClubLayout {...props} hallId="1" />);
  await screen.findByRole('button', { name: '#7 · Стол 7' });
  fireEvent.click(
    screen.getByRole('button', { name: 'Загрузить актуальные данные' }),
  );
  await waitFor(() =>
    expect(screen.getByAltText('План зала').getAttribute('src')).toBe(
      'blob:image/png',
    ),
  );
  view.unmount();
  render(<ClubLayout {...props} hallId="2" />);
  await waitFor(() =>
    expect(screen.getByAltText('План зала').getAttribute('src')).toBe(
      'blob:image/jpeg',
    ),
  );
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:image/png');
});

it('keeps a saved duplicate-image diagnosis separate from transient hall state', async () => {
  const duplicated = structuredClone(layout);
  duplicated.halls[1].plan = duplicated.halls[0].plan;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (path) =>
    String(path).endsWith('/layout')
      ? new Response(JSON.stringify(duplicated))
      : new Response('A'),
  );
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true,
    value: vi.fn(() => 'blob:A'),
  });
  Object.defineProperty(URL, 'revokeObjectURL', {
    configurable: true,
    value: vi.fn(),
  });
  render(<ClubLayout {...props} hallId="1" />);
  await screen.findByText(
    /В сохранённых данных этого зала и «Welcome» одинаковое изображение/,
  );
});

it.each([false, true])(
  'fences late table and image responses without a parent key (failure=%s)',
  async (failure) => {
    let finish!: (tables: ReturnType<typeof hallTable>[]) => void;
    let failTables!: (error: AdminApiError) => void;
    let failPlan!: (error: AdminApiError) => void;
    let finishPlan!: (result: { status: number; blob: Blob }) => void;
    const api = {
      listHallTables: vi.fn((id: string) =>
        id === '1'
          ? new Promise<ReturnType<typeof hallTable>[]>((resolve, reject) => {
              finish = resolve;
              failTables = reject;
            })
          : Promise.resolve([hallTable('2', 8)]),
      ),
      fetchHallPlanBlob: vi.fn((_club: string, id: string) =>
        id === '1'
          ? new Promise<{ status: number; blob: Blob }>((resolve, reject) => {
              finishPlan = resolve;
              failPlan = reject;
            })
          : Promise.resolve({ status: 404 }),
      ),
    } as unknown as HallEditorApi<string>;
    const back = vi.fn();
    const view = render(
      <HallEditorScreen
        clubId="42"
        hallId="1"
        api={api}
        onBack={back}
        clientMode
      />,
    );
    view.rerender(
      <HallEditorScreen
        clubId="42"
        hallId="2"
        api={api}
        onBack={back}
        clientMode
      />,
    );
    await screen.findByRole('button', { name: '#8 · Стол 8' });
    await act(async () => {
      if (failure) {
        const error = new AdminApiError('Old hall denied', {
          status: 403,
          code: 'forbidden',
        });
        failTables(error);
        failPlan(error);
      } else {
        finish([hallTable('1', 7)]);
        finishPlan({ status: 200, blob: new Blob(['A']) });
      }
    });
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: '#7 · Стол 7' })).toBeNull(),
    );
    expect(screen.queryByAltText('План зала')).toBeNull();
    expect(back).not.toHaveBeenCalled();
  },
);

it('explains a club-wide number conflict and preserves single range and arbitrary creation', () => {
  const save = vi.fn();
  render(
    <ClubTableCreationForm
      reserved={[7]}
      locations={{ 7: 'Main' }}
      busy={false}
      onSave={save}
      onClose={vi.fn()}
    />,
  );
  fireEvent.change(screen.getByLabelText('Номер нового стола'), {
    target: { value: '7' },
  });
  expect(screen.getByRole('alert').textContent).toContain(
    '№7 уже занят в зале «Main»',
  );
  expect(
    screen.getByRole<HTMLButtonElement>('button', {
      name: 'Создать стол',
    }).disabled,
  ).toBe(true);
  fireEvent.change(screen.getByLabelText('Номер нового стола'), {
    target: { value: '8' },
  });
  fireEvent.submit(screen.getByRole('form', { name: 'Создание столов' }));
  expect(save).toHaveBeenLastCalledWith(
    expect.objectContaining({ numbers: [8], mode: 'single' }),
  );
  fireEvent.change(screen.getByLabelText('Способ добавления'), {
    target: { value: 'range' },
  });
  fireEvent.change(screen.getByLabelText('Количество столов'), {
    target: { value: '3' },
  });
  fireEvent.submit(screen.getByRole('form', { name: 'Создание столов' }));
  expect(save).toHaveBeenLastCalledWith(
    expect.objectContaining({ numbers: [8, 9, 10], mode: 'range' }),
  );
  fireEvent.change(screen.getByLabelText('Способ добавления'), {
    target: { value: 'list' },
  });
  fireEvent.change(screen.getByLabelText('Номера столов'), {
    target: { value: '12, 18; 25' },
  });
  fireEvent.submit(screen.getByRole('form', { name: 'Создание столов' }));
  expect(save).toHaveBeenLastCalledWith(
    expect.objectContaining({ numbers: [12, 18, 25], mode: 'list' }),
  );
});

it('describes Friday after midnight without changing canonical flags, including year rollover', () => {
  const hours = {
    open: '00:00',
    close: '12:00',
    startNextDay: true,
    nextDay: false,
  };
  expect(describeNight('2026-11-06', hours)).toEqual({
    text: 'Вечеринка пятницы. Открытие: суббота, 00:00. Закрытие: суббота, 12:00.',
    dates:
      'Дата вечеринки: 06.11.2026. Открытие: 07.11.2026. Закрытие: 07.11.2026.',
  });
  expect(nextWeekday('2026-11-05', 5)).toBe('2026-11-06');
  expect(
    describeNight('2026-11-06', {
      ...hours,
      open: '00:00:00',
      close: '12:00:00',
    }).text,
  ).toBe(describeNight('2026-11-06', hours).text);
  expect(shiftDate('2026-12-31', 1)).toBe('2027-01-01');
  expect(hours).toEqual({
    open: '00:00',
    close: '12:00',
    startNextDay: true,
    nextDay: false,
  });
});

it('renames only the selected card with one versioned command and no read after success', async () => {
  let finish!: (response: Response) => void;
  const saving = new Promise<Response>((resolve) => {
    finish = resolve;
  });
  const fetch = vi
    .spyOn(globalThis, 'fetch')
    .mockImplementation(async (_path, options) =>
      options?.method === 'PATCH'
        ? saving
        : new Response(JSON.stringify(layout)),
    );
  render(<ClubLayout {...props} />);
  const main = (await screen.findByRole('heading', { name: 'Main' })).closest(
    '.client-hall-card',
  )!;
  const welcome = screen
    .getByRole('heading', { name: 'Welcome' })
    .closest('.client-hall-card')!;
  vi.mocked(window.scrollTo).mockClear();
  fireEvent.click(
    within(welcome as HTMLElement).getByRole('button', {
      name: 'Редактировать',
    }),
  );
  const form = within(welcome as HTMLElement).getByRole('form', {
    name: 'Редактирование названия зала',
  });
  const name = within(form).getByRole<HTMLInputElement>('textbox', {
    name: 'Название зала',
  });
  expect(name.value).toBe('Welcome');
  expect(document.activeElement).toBe(name);
  expect(within(main as HTMLElement).queryByRole('form')).toBeNull();
  expect(screen.queryByText('Создать зал')).toBeNull();
  expect(window.scrollTo).not.toHaveBeenCalled();
  fireEvent.change(name, { target: { value: 'Вход' } });
  fireEvent.submit(form);
  fireEvent.submit(form);
  expect(fetch.mock.calls).toHaveLength(2);
  const [path, options] = fetch.mock.calls[1];
  expect(path).toBe('/api/client/clubs/42/halls/2');
  expect(options?.method).toBe('PATCH');
  expect(JSON.parse(String(options?.body))).toEqual({
    name: 'Вход',
    expectedVersion: '1',
  });
  expect(options?.headers).toHaveProperty('Idempotency-Key');
  expect(
    within(form).getByRole<HTMLButtonElement>('button', { name: 'Отмена' })
      .disabled,
  ).toBe(true);
  finish(
    new Response(
      JSON.stringify({
        ...layout,
        halls: layout.halls.map((hall) =>
          hall.id === '2'
            ? { ...hall, name: 'Вход', layoutRevision: '2' }
            : hall,
        ),
      }),
    ),
  );
  await within(welcome as HTMLElement).findByRole('heading', { name: 'Вход' });
  expect(within(welcome as HTMLElement).queryByRole('form')).toBeNull();
  expect(
    within(main as HTMLElement).getByRole('heading', { name: 'Main' }),
  ).toBeTruthy();
  expect(fetch.mock.calls).toHaveLength(2);
});

it('keeps a duplicate draft in its card and lets the owner explicitly cancel', async () => {
  const fetch = vi
    .spyOn(globalThis, 'fetch')
    .mockImplementation(async (_path, options) =>
      options?.method === 'PATCH'
        ? new Response(JSON.stringify({ code: 'hall_name_conflict' }), {
            status: 409,
          })
        : new Response(JSON.stringify(layout)),
    );
  render(<ClubLayout {...props} />);
  const card = (
    await screen.findByRole('heading', { name: 'Welcome' })
  ).closest<HTMLElement>('.client-hall-card')!;
  fireEvent.click(within(card).getByRole('button', { name: 'Редактировать' }));
  const input = within(card).getByRole<HTMLInputElement>('textbox');
  fireEvent.change(input, { target: { value: 'Main' } });
  fireEvent.click(within(card).getByRole('button', { name: 'Сохранить' }));
  await within(card).findByRole('alert');
  expect(input.value).toBe('Main');
  expect(input.getAttribute('aria-invalid')).toBe('true');
  expect(
    within(card).getByText('Зал с таким названием уже существует'),
  ).toBeTruthy();
  fireEvent.click(within(card).getByRole('button', { name: 'Отмена' }));
  expect(within(card).queryByRole('textbox')).toBeNull();
  expect(within(card).getByRole('heading', { name: 'Welcome' })).toBeTruthy();
  expect(fetch.mock.calls).toHaveLength(2);
});

it('retains the rename draft after a version conflict until the owner accepts a reload', async () => {
  let refreshed = false;
  const fetch = vi
    .spyOn(globalThis, 'fetch')
    .mockImplementation(async (_path, options) => {
      if (options?.method === 'PATCH') {
        refreshed = true;
        return new Response(JSON.stringify({ code: 'version_conflict' }), {
          status: 409,
        });
      }
      return new Response(
        JSON.stringify({
          ...layout,
          halls: layout.halls.map((hall) =>
            refreshed && hall.id === '2'
              ? { ...hall, name: 'С сервера', layoutRevision: '2' }
              : hall,
          ),
        }),
      );
    });
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
  render(<ClubLayout {...props} />);
  const card = (
    await screen.findByRole('heading', { name: 'Welcome' })
  ).closest<HTMLElement>('.client-hall-card')!;
  fireEvent.click(within(card).getByRole('button', { name: 'Редактировать' }));
  const input = within(card).getByRole<HTMLInputElement>('textbox');
  fireEvent.change(input, { target: { value: 'Черновик' } });
  fireEvent.click(within(card).getByRole('button', { name: 'Сохранить' }));
  await screen.findByRole('alert');
  expect(input.value).toBe('Черновик');
  expect(
    within(card).getByRole<HTMLButtonElement>('button', { name: 'Сохранить' })
      .disabled,
  ).toBe(true);
  const reload = screen.getByRole('button', {
    name: 'Загрузить актуальные данные',
  });
  fireEvent.click(reload);
  expect(fetch.mock.calls).toHaveLength(2);
  confirm.mockReturnValue(true);
  fireEvent.click(reload);
  await screen.findByRole('heading', { name: 'С сервера' });
  expect(
    screen.queryByRole('form', { name: 'Редактирование названия зала' }),
  ).toBeNull();
  expect(fetch.mock.calls).toHaveLength(3);
});

it.each(['saved', 'conflict'] as const)(
  'replays the same rename after a lost response and handles %s',
  async (outcome) => {
    const patches: Array<RequestInit> = [];
    const renamed = {
      ...layout,
      halls: layout.halls.map((hall) =>
        hall.id === '2' ? { ...hall, name: 'Вход', layoutRevision: '2' } : hall,
      ),
    };
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(async (_path, options) => {
        if (options?.method !== 'PATCH')
          return new Response(JSON.stringify(layout));
        patches.push(options);
        if (patches.length === 1)
          throw new TypeError('Synthetic connection loss');
        if (outcome === 'conflict')
          return new Response(JSON.stringify({ code: 'hall_name_conflict' }), {
            status: 409,
          });
        return new Response(JSON.stringify(renamed));
      });
    render(<ClubLayout {...props} />);
    const card = (
      await screen.findByRole('heading', { name: 'Welcome' })
    ).closest<HTMLElement>('.client-hall-card')!;
    fireEvent.click(
      within(card).getByRole('button', { name: 'Редактировать' }),
    );
    const input = within(card).getByRole<HTMLInputElement>('textbox');
    fireEvent.change(input, { target: { value: 'Вход' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Сохранить' }));
    const retry = await screen.findByRole('button', {
      name: 'Повторить ту же операцию',
    });
    expect(input.value).toBe('Вход');
    expect(input.disabled).toBe(true);
    fireEvent.click(retry);
    if (outcome === 'saved') {
      await screen.findByRole('heading', { name: 'Вход' });
      expect(
        screen.queryByRole('form', { name: 'Редактирование названия зала' }),
      ).toBeNull();
    } else {
      await within(card).findByText('Зал с таким названием уже существует');
      expect(input.value).toBe('Вход');
      expect(input.disabled).toBe(false);
    }
    expect(patches).toHaveLength(2);
    expect(patches[1].body).toBe(patches[0].body);
    expect(patches[1].headers).toEqual(patches[0].headers);
    expect(fetch.mock.calls).toHaveLength(3);
  },
);

it('final review regression: an inline rename cannot roll the card back past a layout already read from the server', async () => {
  const initialToasts = useUiStore.getState().toasts.length;
  const rename = (name: string, version: string) => ({
    ...layout,
    halls: layout.halls.map((hall) =>
      hall.id === '2' ? { ...hall, name, layoutRevision: version } : hall,
    ),
  });
  const recent = rename('Уже изменён в другой вкладке', '3');
  let reads = 0;
  let finish!: (result: Response) => void;
  const write = new Promise<Response>((resolve) => {
    finish = resolve;
  });
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_path, options) => {
    if (options?.method === 'PATCH') return write;
    return Response.json(++reads === 1 ? layout : recent);
  });
  render(<ClubLayout {...props} />);
  const card = (
    await screen.findByRole('heading', { name: 'Welcome' })
  ).closest<HTMLElement>('.client-hall-card')!;
  fireEvent.click(within(card).getByRole('button', { name: 'Редактировать' }));
  fireEvent.change(within(card).getByRole('textbox'), {
    target: { value: 'Моя правка' },
  });
  fireEvent.click(within(card).getByRole('button', { name: 'Сохранить' }));
  const alreadySampledWrite = Response.json(rename('Моя правка', '2'));
  await act(async () => fireEvent.focus(window));
  expect(reads).toBe(2);
  await act(async () => finish(alreadySampledWrite));
  expect(
    within(card).queryByRole('heading', {
      name: 'Уже изменён в другой вкладке',
    }),
  ).not.toBeNull();
  expect(within(card).getByRole<HTMLInputElement>('textbox').value).toBe(
    'Моя правка',
  );
  expect(useUiStore.getState().toasts.slice(initialToasts)).not.toContain(
    'Зал обновлен',
  );
  expect(
    screen.getByRole('button', { name: 'Загрузить актуальные данные' }),
  ).toBeTruthy();
});
