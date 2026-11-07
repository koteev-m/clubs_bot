import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import ClubFinance from './ClubFinance';
import { FinanceView } from './finance.types';

vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({}),
}));
const initial: FinanceView = {
  clubId: '42',
  clientId: '21',
  version: '1',
  canWrite: true,
  bracelets: [{ id: '1', name: 'Обычный', orderIndex: 0, enabled: true }],
  revenueGroups: [{ id: '2', name: 'Бар', orderIndex: 0, enabled: true }],
  revenueArticles: [],
};
const response = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status });
const deferred = () => {
  let resolve!: (value: Response) => void;
  const promise = new Promise<Response>((done) => {
    resolve = done;
  });
  return { promise, resolve };
};
function mount() {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
  return render(
    <ClubFinance
      credential="synthetic"
      id="42"
      back={vi.fn()}
      reportDirty={vi.fn()}
      reportPending={vi.fn()}
    />,
  );
}
async function editBracelet(name = 'Гостевой') {
  fireEvent.click(
    await screen.findByRole('button', { name: 'Изменить Обычный' }),
  );
  fireEvent.change(screen.getByRole('textbox', { name: 'Название' }), {
    target: { value: name },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить настройку' }));
}
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it('persists article flags, category and order and reloads the saved template without a money operation', async () => {
  let saved = structuredClone(initial);
  const writes: Record<string, unknown>[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (url, options) => {
    expect(String(url)).toBe('/api/client/clubs/42/finance/template');
    expect(options?.cache).toBe('no-store');
    expect(options?.headers).toHaveProperty(
      'X-Telegram-Init-Data',
      'synthetic',
    );
    if (options?.method === 'POST') {
      const body = JSON.parse(String(options.body));
      writes.push(body);
      expect(options.headers).toHaveProperty('Idempotency-Key');
      saved = {
        ...saved,
        version: '2',
        revenueArticles: [
          {
            id: '3',
            name: body.name,
            groupId: body.groupId,
            orderIndex: body.orderIndex,
            includeInTotal: body.includeInTotal,
            showSeparately: body.showSeparately,
            enabled: true,
          },
        ],
      };
    }
    return response(saved);
  });
  const mounted = mount();
  fireEvent.click(
    await screen.findByRole('button', { name: 'Добавить статью' }),
  );
  fireEvent.change(screen.getByRole('textbox', { name: 'Название' }), {
    target: { value: 'Напитки' },
  });
  fireEvent.change(screen.getByRole('combobox'), { target: { value: '2' } });
  fireEvent.change(
    screen.getByRole('textbox', { name: 'Порядок отображения' }),
    { target: { value: '7' } },
  );
  fireEvent.click(
    screen.getByRole('checkbox', { name: 'Включать в общую выручку' }),
  );
  fireEvent.click(
    screen.getByRole('checkbox', { name: 'Показывать отдельно' }),
  );
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить настройку' }));
  await screen.findByText('Сохранено');
  expect(writes).toEqual([
    {
      expectedVersion: '1',
      entity: 'article',
      action: 'create',
      name: 'Напитки',
      orderIndex: 7,
      groupId: '2',
      includeInTotal: true,
      showSeparately: true,
    },
  ]);
  mounted.unmount();
  mount();
  expect(await screen.findByText(/Входит в общую выручку/)).toBeTruthy();
  expect(screen.getByText('Включено в шаблон · Порядок: 7')).toBeTruthy();
  expect(writes).toHaveLength(1);
});

it('retries unknown outcomes with the identical key and payload', async () => {
  const writes: RequestInit[] = [];
  let saved = initial;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, options) => {
    if (options?.method === 'POST') {
      writes.push(options);
      if (writes.length === 1) throw new TypeError('offline');
      saved = {
        ...initial,
        version: '2',
        bracelets: [{ ...initial.bracelets[0], name: 'Гостевой' }],
      };
    }
    return response(saved);
  });
  mount();
  await editBracelet();
  fireEvent.click(
    await screen.findByRole('button', { name: 'Повторить ту же операцию' }),
  );
  await screen.findByText('Сохранено');
  expect(writes).toHaveLength(2);
  expect(writes[0].headers).toEqual(writes[1].headers);
  expect(writes[0].body).toBe(writes[1].body);
});

it('shows a version conflict beside the editor and preserves the draft until explicit discard', async () => {
  let conflict = false;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, options) => {
    if (options?.method === 'POST') {
      conflict = true;
      return response({ code: 'version_conflict' }, 409);
    }
    return response(
      conflict
        ? {
            ...initial,
            version: '3',
            bracelets: [{ ...initial.bracelets[0], name: 'Другой' }],
          }
        : initial,
    );
  });
  mount();
  await editBracelet();
  await screen.findByText(/Шаблон изменился/);
  expect(
    (screen.getByRole('textbox', { name: 'Название' }) as HTMLInputElement)
      .value,
  ).toBe('Гостевой');
  expect(
    screen
      .getByRole('button', { name: 'Сохранить настройку' })
      .closest('fieldset')?.disabled,
  ).toBe(true);
  fireEvent.click(screen.getByRole('button', { name: 'Отменить изменения' }));
  expect(
    await screen.findByRole('button', { name: 'Изменить Другой' }),
  ).toBeTruthy();
});

it('does not resurrect a late write after denied read, including after a fresh authorized read', async () => {
  const write = deferred();
  let reads = 0;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, options) => {
    if (options?.method === 'POST') return write.promise;
    reads++;
    if (reads === 2) return response({ code: 'not_found' }, 404);
    return response(initial);
  });
  mount();
  await editBracelet();
  act(() => window.dispatchEvent(new Event('focus')));
  await screen.findByText('Настройки недоступны с текущими правами.');
  expect(screen.queryByText('Обычный', { selector: 'h3' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Повторить загрузку' }));
  await screen.findByText('Обычный', { selector: 'h3' });
  await act(async () =>
    write.resolve(
      response({
        ...initial,
        version: '2',
        bracelets: [{ ...initial.bracelets[0], name: 'Запоздавший' }],
      }),
    ),
  );
  expect(screen.queryByText('Запоздавший')).toBeNull();
  expect(screen.queryByText('Сохранено')).toBeNull();
});

it('never replaces newer confirmed data with an older mutation response', async () => {
  const write = deferred();
  let reads = 0;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, options) => {
    if (options?.method === 'POST') return write.promise;
    return response(
      ++reads > 1
        ? {
            ...initial,
            version: '3',
            bracelets: [{ ...initial.bracelets[0], name: 'Новая версия' }],
          }
        : initial,
    );
  });
  mount();
  await editBracelet();
  act(() => window.dispatchEvent(new Event('focus')));
  await screen.findByText('Новая версия', { selector: 'h3' });
  await act(async () =>
    write.resolve(
      response({
        ...initial,
        version: '2',
        bracelets: [{ ...initial.bracelets[0], name: 'Старая версия' }],
      }),
    ),
  );
  expect(screen.queryByText('Старая версия')).toBeNull();
  expect(screen.queryByText('Сохранено')).toBeNull();
});

it('hides another club snapshot and exposes no write controls for a read-only grant', async () => {
  const fetch = vi
    .spyOn(globalThis, 'fetch')
    .mockResolvedValue(response({ ...initial, clubId: '99' }));
  const mounted = mount();
  await screen.findByText('Настройки недоступны с текущими правами.');
  expect(screen.queryByText('Обычный')).toBeNull();
  mounted.unmount();
  fetch.mockImplementation(async () =>
    response({ ...initial, canWrite: false }),
  );
  mount();
  await screen.findByText('Доступно только чтение финансового шаблона.');
  expect(
    screen.queryByRole('button', { name: 'Добавить тип браслета' }),
  ).toBeNull();
});

it('disables without deletion and shows category dependency conflicts near the action', async () => {
  let saved = initial;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, options) => {
    if (options?.method === 'POST') {
      const body = JSON.parse(String(options.body));
      if (body.entity === 'group')
        return response({ code: 'finance_group_in_use' }, 409);
      expect(body.action).toBe('disable');
      saved = {
        ...saved,
        version: '2',
        bracelets: [{ ...saved.bracelets[0], enabled: false }],
      };
    }
    return response(saved);
  });
  mount();
  fireEvent.click(
    await screen.findByRole('button', { name: 'Отключить Обычный' }),
  );
  await screen.findByText('Отключено · Порядок: 0');
  await waitFor(() =>
    expect(
      (
        screen.getByRole('button', {
          name: 'Отключить Бар',
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(false),
  );
  fireEvent.click(screen.getByRole('button', { name: 'Отключить Бар' }));
  const group = screen.getByRole('region', { name: 'Категории выручки' });
  expect(await within(group).findByText(/Сначала перенесите/)).toBeTruthy();
});

it('fences a delayed write when a successful GET observes write-grant revocation at the same version', async () => {
  const write = deferred();
  let reads = 0;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (_, options) => {
    if (options?.method === 'POST') return write.promise;
    return response(++reads > 1 ? { ...initial, canWrite: false } : initial);
  });
  mount();
  await editBracelet();
  act(() => window.dispatchEvent(new Event('focus')));
  await screen.findByText('Доступно только чтение финансового шаблона.');
  await act(async () =>
    write.resolve(
      response({
        ...initial,
        version: '2',
        bracelets: [{ ...initial.bracelets[0], name: 'Запоздавший' }],
      }),
    ),
  );
  expect(screen.queryByText('Запоздавший')).toBeNull();
  expect(
    screen.queryByRole('button', { name: 'Добавить тип браслета' }),
  ).toBeNull();
  expect(screen.queryByText('Сохранено')).toBeNull();
});
