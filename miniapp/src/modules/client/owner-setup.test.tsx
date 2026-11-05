import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import ClubLayout from './ClubLayout';
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
afterEach(() => {
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
      plan: { contentType: 'image/png', sha256: 'A', sizeBytes: 8 },
      tables: [hallTable('1', 7)],
    },
    {
      id: '2',
      name: 'Welcome',
      layoutRevision: '1',
      plan: { contentType: 'image/jpeg', sha256: 'B', sizeBytes: 8 },
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
  fireEvent.click(screen.getByRole('button', { name: 'Обновить' }));
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
      : new Response(new Blob(['A'])),
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
