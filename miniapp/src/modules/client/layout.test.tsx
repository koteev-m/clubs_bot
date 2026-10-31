import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ClientAccess from './ClientAccess';
import { ClientLayout } from './layout.api';

const auth = vi.hoisted(() => ({ initData: 'synthetic-layout-client' }));
const backs = vi.hoisted(() => new Set<() => void>());
const telegram = vi.hoisted(() => ({
  BackButton: {
    show: vi.fn(),
    hide: vi.fn(),
    onClick: (back: () => void) => backs.add(back),
    offClick: (back: () => void) => backs.delete(back),
  },
}));
vi.mock('../auth/hooks/useInitData', () => ({ useInitData: () => auth }));
vi.mock('../../app/providers/TelegramProvider', () => ({
  useTelegram: () => telegram,
}));
const club = '9007199254740999';
const hall = '9007199254741000';
const table = '9007199254741001';
const profile = {
  id: club,
  clientId: '20',
  clientName: 'Организация',
  name: 'Свой клуб',
  description: '',
  timezone: 'Europe/Moscow',
  countryCode: 'RU',
  city: 'Москва',
  address: 'Синтетический адрес',
  unit: '',
  version: '1',
  savedAt: '2026-10-31T00:00:00Z',
  permissions: [
    'client.club.read',
    'client.club.profile.write',
    'client.club.layout.write',
  ],
  availableActions: ['update'],
};
const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
const rect = {
  width: 200,
  height: 100,
  top: 0,
  left: 0,
  right: 200,
  bottom: 100,
  x: 0,
  y: 0,
  toJSON: () => ({}),
};
beforeEach(() => {
  window.history.replaceState(
    {},
    '',
    '/app?mode=client&screen=club&resource=' + club,
  );
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
  vi.spyOn(window, 'confirm').mockReturnValue(false);
  Object.defineProperty(URL, 'createObjectURL', {
    value: vi.fn(() => 'blob:private-plan'),
    configurable: true,
  });
  Object.defineProperty(URL, 'revokeObjectURL', {
    value: vi.fn(),
    configurable: true,
  });
  vi.spyOn(HTMLImageElement.prototype, 'naturalWidth', 'get').mockReturnValue(
    200,
  );
  vi.spyOn(HTMLImageElement.prototype, 'naturalHeight', 'get').mockReturnValue(
    100,
  );
});
afterEach(() => {
  cleanup();
  backs.clear();
  vi.restoreAllMocks();
});

describe('cabinet uses the shared layout editor', () => {
  it('creates a hall without JSON, uploads a plan, adds and edits a table, and protects native Back input', async () => {
    let stored: ClientLayout = {
      id: club,
      clubId: club,
      clientId: '20',
      permissions: profile.permissions,
      halls: [],
    };
    const patches: Record<string, unknown>[] = [];
    const paths: string[] = [];
    const fetch = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(async (input, options) => {
        const path = String(input);
        paths.push(path);
        if (path.endsWith('/profile')) return json(profile);
        if (path.endsWith('/layout')) return json(stored);
        if (path.endsWith('/halls') && options?.method === 'POST') {
          const body = JSON.parse(String(options.body));
          expect(Object.keys(body)).toEqual(['name']);
          stored = {
            ...stored,
            halls: [
              {
                id: hall,
                name: body.name,
                layoutRevision: '1',
                tables: [],
                plan: null,
              },
            ],
          };
          return json(stored);
        }
        const first = stored.halls[0];
        if (path.endsWith('/plan')) {
          if (options?.method === 'POST') {
            expect(options.body).toBeInstanceOf(File);
            first.plan = {
              contentType: 'image/png',
              sha256: 'synthetic',
              sizeBytes: 4,
            };
            first.layoutRevision = '2';
            return json(stored);
          }
          return new Response(new Blob(['plan'], { type: 'image/png' }));
        }
        const body = JSON.parse(String(options?.body));
        if (path.endsWith('/tables')) {
          expect(body.expectedVersion).toBe('2');
          first.tables = [{ ...body, id: table, hallId: hall }];
          first.layoutRevision = '3';
          return json(stored);
        }
        if (path.endsWith('/tables/' + table)) {
          patches.push(body);
          first.tables[0] = { ...first.tables[0], ...body };
          first.layoutRevision = String(Number(first.layoutRevision) + 1);
          return json(stored);
        }
        throw new Error('Unexpected synthetic path ' + path);
      });
    render(<ClientAccess initialToken={null} />);
    await screen.findByRole('heading', { name: 'Свой клуб' });
    fireEvent.click(screen.getByRole('button', { name: /Залы и столы/ }));
    await screen.findByText('Залов нет');
    expect(screen.queryByLabelText('JSON схемы')).toBeNull();
    fireEvent.change(screen.getByLabelText('Название', { exact: true }), {
      target: { value: 'Первый зал' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Создать' }));
    await screen.findByText('Первый зал', { exact: true });
    fireEvent.click(screen.getByRole('button', { name: 'Открыть схему' }));
    await screen.findByText('План еще не загружен.');
    const input = document.querySelector('input[type=file]')!;
    fireEvent.change(input, {
      target: {
        files: [new File(['plan'], 'plan.png', { type: 'image/png' })],
      },
    });
    const stage = await screen.findByTestId('hall-plan-stage');
    stage.getBoundingClientRect = () => rect;
    fireEvent.load(screen.getByTestId('hall-plan-image'));
    fireEvent.click(stage, { clientX: 100, clientY: 50 });
    await screen.findByRole('button', { name: '#1 · Стол 1' });
    fireEvent.change(screen.getByLabelText('Название', { exact: true }), {
      target: { value: 'У окна' },
    });
    fireEvent.change(screen.getByLabelText('Вместимость'), {
      target: { value: '6' },
    });
    const marker = screen.getByTestId('table-marker-' + table);
    fireEvent.pointerDown(marker, { clientX: 100, clientY: 50, pointerId: 1 });
    fireEvent.pointerMove(marker, { clientX: 140, clientY: 70, pointerId: 1 });
    fireEvent.pointerUp(marker, { clientX: 140, clientY: 70, pointerId: 1 });
    expect(window.confirm).toHaveBeenCalled();
    expect(patches).toHaveLength(0);
    expect(
      (screen.getByLabelText('Название', { exact: true }) as HTMLInputElement)
        .value,
    ).toBe('У окна');
    expect(
      (screen.getByLabelText('Вместимость') as HTMLInputElement).value,
    ).toBe('6');
    for (const back of backs) back();
    expect(window.confirm).toHaveBeenCalled();
    expect(
      (screen.getByLabelText('Название', { exact: true }) as HTMLInputElement)
        .value,
    ).toBe('У окна');
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }));
    await screen.findByRole('button', { name: '#1 · У окна' });
    expect(patches[0]).toEqual({
      tableNumber: 1,
      label: 'У окна',
      capacity: 6,
      expectedVersion: '3',
    });
    expect(fetch).toHaveBeenCalled();
    expect(paths.some((path) => path.includes('/api/admin/'))).toBe(false);
    expect(window.location.search).toContain('hall=' + hall);
  });
  it('shows only read actions and clears plan URLs and tables after access is lost', async () => {
    let revoked = false;
    const layout: ClientLayout = {
      id: club,
      clubId: club,
      clientId: '20',
      permissions: ['client.club.read'],
      halls: [
        {
          id: hall,
          name: 'Доступный зал',
          layoutRevision: '1',
          plan: { contentType: 'image/png', sha256: 'synthetic', sizeBytes: 4 },
          tables: [
            {
              id: table,
              hallId: hall,
              label: 'Частный стол',
              tableNumber: 1,
              capacity: 2,
              x: 0.5,
              y: 0.5,
            },
          ],
        },
      ],
    };
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (revoked) return json({ code: 'not_found' }, 404);
      if (String(input).endsWith('/profile'))
        return json({
          ...profile,
          permissions: ['client.club.read'],
          availableActions: [],
        });
      if (String(input).endsWith('/plan'))
        return new Response(new Blob(['plan'], { type: 'image/png' }));
      return json(layout);
    });
    render(<ClientAccess initialToken={null} />);
    await screen.findByRole('button', { name: /Залы и столы/ });
    fireEvent.click(screen.getByRole('button', { name: /Залы и столы/ }));
    await screen.findByText('Доступный зал');
    expect(screen.queryByRole('button', { name: 'Создать' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Редактировать' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Схема и столы' }));
    await screen.findByTestId('hall-plan-image');
    expect(document.querySelector('input[type=file]')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Удалить' })).toBeNull();
    revoked = true;
    fireEvent(window, new Event('pageshow'));
    await screen.findByText(/Доступ утрачен или запись недоступна/);
    expect(screen.queryByTestId('hall-plan-image')).toBeNull();
    expect(screen.queryByText('Частный стол')).toBeNull();
    await waitFor(() =>
      expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:private-plan'),
    );
    act(() => {
      for (const back of [...backs]) back();
    });
    expect(window.location.search).toContain('screen=club');
    expect(window.location.search).not.toContain('screen=layout');
  });
});

describe('cabinet mutation access loss', () => {
  it('releases browser Back after a save is refused without retaining a false pending command', async () => {
    let revoked = false;
    const layout: ClientLayout = {
      id: club,
      clubId: club,
      clientId: '20',
      permissions: profile.permissions,
      halls: [
        {
          id: hall,
          name: 'Зал',
          layoutRevision: '1',
          plan: null,
          tables: [
            {
              id: table,
              hallId: hall,
              label: 'Стол',
              tableNumber: 1,
              capacity: 2,
              x: 0.5,
              y: 0.5,
            },
          ],
        },
      ],
    };
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, options) => {
      if (options?.method === 'PATCH') {
        revoked = true;
        return json({ code: 'not_found' }, 404);
      }
      if (revoked) return json({ code: 'not_found' }, 404);
      if (String(input).endsWith('/profile')) return json(profile);
      return json(layout);
    });
    render(<ClientAccess initialToken={null} />);
    fireEvent.click(
      await screen.findByRole('button', { name: /Залы и столы/ }),
    );
    fireEvent.click(
      await screen.findByRole('button', { name: 'Открыть схему' }),
    );
    fireEvent.click(await screen.findByRole('button', { name: '#1 · Стол' }));
    fireEvent.change(screen.getByLabelText('Название', { exact: true }), {
      target: { value: 'Изменённый стол' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }));
    await screen.findByText(/Доступ утрачен или запись недоступна/);
    expect(
      screen.queryByRole('button', { name: 'Повторить ту же операцию' }),
    ).toBeNull();
    act(() => window.history.back());
    await waitFor(() => expect(window.location.search).not.toContain('hall='));
    act(() => window.history.back());
    await waitFor(() =>
      expect(window.location.search).toContain('screen=club'),
    );
  });
});
