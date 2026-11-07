import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import ClubZones from './ClubZones';
import { ClientLayoutHall } from './layout.api';
import { AdminApiError } from '../admin/api/admin.api';

const hall = (): ClientLayoutHall => ({
  id: '9007199254740999',
  name: 'Зал',
  layoutRevision: '8',
  plan: null,
  zones: [
    { id: '901', name: 'Основная', tableCount: 1 },
    { id: '902', name: 'Балкон', tableCount: 0 },
  ],
  tables: [
    {
      id: '903',
      hallId: '9007199254740999',
      tableNumber: 7,
      label: 'У окна',
      capacity: 4,
      x: 0.2,
      y: 0.3,
      zoneId: '901',
    },
  ],
});
const api = () => ({
  create: vi.fn(),
  update: vi.fn(),
  remove: vi.fn(),
  assign: vi.fn(),
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('owner hall zones', () => {
  it('shows saved memberships and saves names and assignment using the original revision', async () => {
    const calls = api();
    const saved = hall();
    const dirty = vi.fn();
    const view = render(
      <ClubZones
        hall={saved}
        api={calls}
        blocked={false}
        reportDirty={dirty}
      />,
    );
    fireEvent.change(screen.getByLabelText('Новая зона'), {
      target: { value: 'Терраса' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Добавить зону' }));
    await waitFor(() =>
      expect(calls.create).toHaveBeenCalledWith(
        saved.id,
        'Терраса',
        '8',
        expect.any(Function),
      ),
    );
    fireEvent.change(screen.getByLabelText('Название зоны «Балкон»'), {
      target: { value: 'Сцена' },
    });
    // An unrelated fresh snapshot must not silently upgrade a dirty edit's base version.
    view.rerender(
      <ClubZones
        hall={{ ...saved, layoutRevision: '9' }}
        api={calls}
        blocked={false}
        reportDirty={dirty}
      />,
    );
    fireEvent.click(
      screen.getAllByRole('button', {
        name: 'Сохранить название зоны',
      })[1],
    );
    await waitFor(() =>
      expect(calls.update).toHaveBeenCalledWith(
        saved.id,
        '902',
        'Сцена',
        '8',
        expect.any(Function),
      ),
    );
    fireEvent.change(screen.getByLabelText('Зона стола 7 · У окна'), {
      target: { value: '902' },
    });
    fireEvent.click(
      screen.getByRole('button', { name: 'Сохранить зону стола 7' }),
    );
    await waitFor(() =>
      expect(calls.assign).toHaveBeenCalledWith(
        saved.id,
        '903',
        '902',
        '9',
        expect.any(Function),
      ),
    );
    view.unmount();
    render(
      <ClubZones
        hall={{
          ...saved,
          tables: [{ ...saved.tables[0], zoneId: '902' }],
        }}
        api={calls}
        blocked={false}
        reportDirty={dirty}
      />,
    );
    expect(
      (screen.getByLabelText('Зона стола 7 · У окна') as HTMLSelectElement)
        .value,
    ).toBe('902');
  });

  it('blocks deleting a referenced zone and reports failed empty deletion beside the action', async () => {
    const calls = api();
    calls.remove.mockRejectedValue(
      new AdminApiError('conflict', { code: 'zone_in_use', status: 409 }),
    );
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    render(
      <ClubZones
        hall={hall()}
        api={calls}
        blocked={false}
        reportDirty={vi.fn()}
      />,
    );
    expect(
      (
        screen.getByRole('button', {
          name: 'Удалить зону «Основная»',
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
    fireEvent.click(
      screen.getByRole('button', { name: 'Удалить зону «Балкон»' }),
    );
    expect((await screen.findByRole('alert')).textContent).toContain(
      'В зоне остались столы',
    );
    expect(calls.remove).toHaveBeenCalledWith(hall().id, '902', '8');
  });

  it('read-only and pending authority disables every write, preserves version-conflicted draft', async () => {
    const calls = api();
    calls.update.mockRejectedValue(
      new AdminApiError('conflict', {
        code: 'version_conflict',
        status: 409,
      }),
    );
    const view = render(
      <ClubZones
        hall={hall()}
        api={calls}
        blocked={false}
        reportDirty={vi.fn()}
      />,
    );
    fireEvent.change(screen.getByLabelText('Название зоны «Балкон»'), {
      target: { value: 'Сцена' },
    });
    await act(async () =>
      fireEvent.click(
        screen.getAllByRole('button', {
          name: 'Сохранить название зоны',
        })[1],
      ),
    );
    expect((await screen.findByRole('alert')).textContent).toContain(
      'Зал изменён',
    );
    expect(
      (screen.getByLabelText('Название зоны «Балкон»') as HTMLInputElement)
        .value,
    ).toBe('Сцена');
    view.rerender(
      <ClubZones hall={hall()} api={calls} blocked reportDirty={vi.fn()} />,
    );
    expect(
      screen
        .getAllByRole('button')
        .every((button) => (button as HTMLButtonElement).disabled),
    ).toBe(true);
  });
});
