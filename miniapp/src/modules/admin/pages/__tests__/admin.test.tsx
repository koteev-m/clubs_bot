import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import AdminShell from '../AdminShell';
import ClubsScreen from '../ClubsScreen';
import ClubHallsScreen from '../ClubHallsScreen';
import {
  AdminApiError,
  AdminClub,
  AdminHall,
  createClub,
  listClubs,
  listHalls,
  makeHallActive,
} from '../../api/admin.api';
import { useUiStore } from '../../../../shared/store/ui';

vi.mock('../../../../widgets/ToastHost', () => ({
  default: () => null,
}));

vi.mock('../../api/admin.api', async () => {
  const actual = await vi.importActual<typeof import('../../api/admin.api')>('../../api/admin.api');
  return {
    ...actual,
    listClubs: vi.fn(),
    createClub: vi.fn(),
    updateClub: vi.fn(),
    deleteClub: vi.fn(),
    listHalls: vi.fn(),
    createHall: vi.fn(),
    updateHall: vi.fn(),
    deleteHall: vi.fn(),
    makeHallActive: vi.fn(),
  };
});

const createDeferred = <T,>() => {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

describe('admin screens', () => {
  beforeEach(() => {
    useUiStore.setState({ toasts: [] });
    vi.clearAllMocks();
  });

  afterEach(() => {
    useUiStore.setState({ toasts: [] });
  });

  it('renders friendly 403 screen without raw JSON', async () => {
    vi.mocked(listClubs).mockRejectedValue(new AdminApiError('Forbidden', { status: 403, code: 'forbidden' }));
    window.history.replaceState({}, '', '/?mode=admin');
    render(<AdminShell />);

    expect(await screen.findByText('Нет доступа к админ-панели')).toBeTruthy();
    expect(useUiStore.getState().toasts).toContain('Недостаточно прав');
    expect(screen.queryByText(/"code"/i)).toBeNull();
  });

  it('aborts requests without showing error toast', async () => {
    vi.mocked(listClubs).mockImplementation((signal?: AbortSignal) => {
      return new Promise<AdminClub[]>((_, reject) => {
        signal?.addEventListener('abort', () => {
          reject(new AdminApiError('aborted', { isAbort: true }));
        });
      });
    });

    const { unmount } = render(<ClubsScreen onSelectClub={vi.fn()} onForbidden={vi.fn()} />);
    unmount();

    await waitFor(() => {
      expect(useUiStore.getState().toasts.length).toBe(0);
    });
  });

  it('uses request id guard to prevent stale updates', async () => {
    const first = createDeferred<AdminClub[]>();
    const second = createDeferred<AdminClub[]>();
    vi.mocked(listClubs).mockImplementationOnce(() => first.promise).mockImplementationOnce(() => second.promise);

    render(<ClubsScreen onSelectClub={vi.fn()} onForbidden={vi.fn()} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Обновить' }));

    const clubB: AdminClub = {
      id: 2,
      name: 'Club B',
      city: 'City B',
      isActive: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    };
    second.resolve([clubB]);

    expect(await screen.findByText('Club B')).toBeTruthy();

    const clubA: AdminClub = {
      id: 1,
      name: 'Club A',
      city: 'City A',
      isActive: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    };
    first.resolve([clubA]);

    await waitFor(() => {
      expect(screen.queryByText('Club A')).toBeNull();
    });
  });

  it('creates club and shows success toast', async () => {
    const club: AdminClub = {
      id: 1,
      name: 'Club One',
      city: 'City',
      isActive: true,
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    };
    vi.mocked(listClubs).mockResolvedValueOnce([]).mockResolvedValueOnce([club]);
    vi.mocked(createClub).mockResolvedValue(club);

    render(<ClubsScreen onSelectClub={vi.fn()} onForbidden={vi.fn()} />);

    fireEvent.change(await screen.findByLabelText('Название'), { target: { value: club.name } });
    fireEvent.change(screen.getByLabelText('Город'), { target: { value: club.city } });
    fireEvent.click(screen.getByRole('button', { name: 'Создать' }));

    await waitFor(() => {
      expect(useUiStore.getState().toasts).toContain('Клуб создан');
    });
  });

  it('shows toast on create club forbidden without switching to global forbidden', async () => {
    vi.mocked(listClubs).mockResolvedValueOnce([]);
    vi.mocked(createClub).mockRejectedValue(new AdminApiError('Forbidden', { status: 403, code: 'forbidden' }));
    const onForbidden = vi.fn();

    render(<ClubsScreen onSelectClub={vi.fn()} onForbidden={onForbidden} />);

    fireEvent.change(await screen.findByLabelText('Название'), { target: { value: 'Club' } });
    fireEvent.change(screen.getByLabelText('Город'), { target: { value: 'City' } });
    fireEvent.click(screen.getByRole('button', { name: 'Создать' }));

    await waitFor(() => {
      expect(useUiStore.getState().toasts).toContain('Недостаточно прав');
    });
    expect(onForbidden).not.toHaveBeenCalled();
    expect(screen.queryByText('Нет доступа к админ-панели')).toBeNull();
  });

  it('makes hall active and updates UI', async () => {
    const inactiveHall: AdminHall = {
      id: 10,
      clubId: 7,
      name: 'Main Hall',
      isActive: false,
      layoutRevision: 2,
      geometryFingerprint: 'abc',
      createdAt: '2024-01-01',
      updatedAt: '2024-01-01',
    };
    const activeHall = { ...inactiveHall, isActive: true };
    vi.mocked(listHalls).mockResolvedValueOnce([inactiveHall]).mockResolvedValueOnce([activeHall]);
    vi.mocked(makeHallActive).mockResolvedValue(activeHall);

    render(<ClubHallsScreen clubId={7} onBack={vi.fn()} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Сделать активным' }));

    await waitFor(() => {
      expect(useUiStore.getState().toasts).toContain('Зал активирован');
    });
    expect(await screen.findByText('Активный')).toBeTruthy();
  });

  it('navigates back on list halls forbidden without switching to global forbidden', async () => {
    vi.mocked(listHalls).mockRejectedValue(new AdminApiError('Forbidden', { status: 403, code: 'forbidden' }));
    const onBack = vi.fn();

    render(<ClubHallsScreen clubId={3} onBack={onBack} />);

    await waitFor(() => {
      expect(useUiStore.getState().toasts).toContain('Недостаточно прав');
    });
    expect(onBack).toHaveBeenCalled();
    expect(screen.queryByText('Нет доступа к админ-панели')).toBeNull();
  });
});
