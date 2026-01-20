import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import GuestListsScreen from '../GuestListsScreen';
import InvitationsScreen from '../InvitationsScreen';
import TablesScreen from '../TablesScreen';
import {
  addGuestListEntriesBulk,
  assignBooking,
  getGuestListDetails,
  listClubEvents,
  listClubHalls,
  listGuestLists,
  listHallTables,
  listPromoterClubs,
  listInvitations,
} from '../../api/promoter.api';
import { useUiStore } from '../../../../shared/store/ui';

vi.mock('../../../../widgets/ToastHost', () => ({
  default: () => null,
}));

vi.mock('../../../../app/providers/TelegramProvider', () => ({
  useTelegram: () => ({ openTelegramLink: vi.fn(), openLink: vi.fn() }),
}));

vi.mock('../../api/promoter.api', async () => {
  const actual = await vi.importActual<typeof import('../../api/promoter.api')>('../../api/promoter.api');
  return {
    ...actual,
    listPromoterClubs: vi.fn(),
    listGuestLists: vi.fn(),
    listClubEvents: vi.fn(),
    addGuestListEntriesBulk: vi.fn(),
    listInvitations: vi.fn(),
    listClubHalls: vi.fn(),
    listHallTables: vi.fn(),
    getGuestListDetails: vi.fn(),
    assignBooking: vi.fn(),
  };
});

describe('promoter screens', () => {
  beforeEach(() => {
    useUiStore.setState({ toasts: [] });
    vi.clearAllMocks();
    if (!navigator.clipboard) {
      Object.assign(navigator, {
        clipboard: {
          writeText: vi.fn().mockResolvedValue(undefined),
        },
      });
    }
  });

  afterEach(() => {
    useUiStore.setState({ toasts: [] });
  });

  it('bulk paste normalizes lines before sending', async () => {
    vi.mocked(listPromoterClubs).mockResolvedValue([
      { id: 1, name: 'Club', city: 'City' },
    ]);
    vi.mocked(listGuestLists).mockResolvedValue([
      {
        id: 10,
        clubId: 1,
        eventId: 2,
        ownerType: 'PROMOTER',
        ownerUserId: 1,
        name: 'List',
        limit: 20,
        status: 'ACTIVE',
        createdAt: '2024-01-01',
        updatedAt: '2024-01-01',
      },
    ]);
    vi.mocked(addGuestListEntriesBulk).mockResolvedValue({ addedCount: 2 });

    render(
      <GuestListsScreen
        clubId={1}
        guestListId={10}
        onSelectClub={vi.fn()}
        onSelectGuestList={vi.fn()}
        onForbidden={vi.fn()}
      />,
    );

    const textarea = await screen.findByLabelText('Массовая вставка (1 строка = 1 гость)');
    fireEvent.change(textarea, { target: { value: ' Alice \n\n Bob \n' } });
    fireEvent.click(screen.getByRole('button', { name: 'Добавить пачкой' }));

    await waitFor(() => {
      expect(addGuestListEntriesBulk).toHaveBeenCalled();
    });
    expect(vi.mocked(addGuestListEntriesBulk).mock.calls[0][1]).toBe('Alice\nBob');
  });

  it('copy link shows toast', async () => {
    vi.mocked(listGuestLists).mockResolvedValue([
      {
        id: 3,
        clubId: 1,
        eventId: 2,
        ownerType: 'PROMOTER',
        ownerUserId: 1,
        name: 'List',
        limit: 10,
        status: 'ACTIVE',
        createdAt: '2024-01-01',
        updatedAt: '2024-01-01',
      },
    ]);
    vi.mocked(listInvitations).mockResolvedValue([
      {
        entry: {
          id: 7,
          guestListId: 3,
          displayName: 'Guest',
          status: 'ADDED',
          createdAt: '2024-01-01',
          updatedAt: '2024-01-01',
        },
        invitationUrl: 'https://example.com',
        qrPayload: 'inv:token',
        expiresAt: '2024-01-02T00:00:00Z',
      },
    ]);
    vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined);

    render(<InvitationsScreen guestListId={3} onSelectGuestList={vi.fn()} onForbidden={vi.fn()} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Копировать ссылку' }));

    await waitFor(() => {
      expect(useUiStore.getState().toasts).toContain('Ссылка скопирована');
    });
  });

  it('assigns booking after selecting entry and table', async () => {
    vi.mocked(listPromoterClubs).mockResolvedValue([
      { id: 1, name: 'Club', city: 'City' },
    ]);
    vi.mocked(listClubHalls).mockResolvedValue([{ id: 5, clubId: 1, name: 'Hall', isActive: true }]);
    vi.mocked(listGuestLists).mockResolvedValue([
      {
        id: 22,
        clubId: 1,
        eventId: 9,
        ownerType: 'PROMOTER',
        ownerUserId: 1,
        name: 'List',
        limit: 10,
        status: 'ACTIVE',
        createdAt: '2024-01-01',
        updatedAt: '2024-01-01',
      },
    ]);
    vi.mocked(listClubEvents).mockResolvedValue([
      { id: 9, clubId: 1, startUtc: '2024-01-01T20:00:00Z', endUtc: '2024-01-01T23:00:00Z', title: 'Event', isSpecial: false },
    ]);
    vi.mocked(listHallTables).mockResolvedValue([
      {
        id: 30,
        hallId: 5,
        clubId: 1,
        label: 'A1',
        minDeposit: 0,
        capacity: 2,
        mysteryEligible: false,
        tableNumber: 1,
        x: 0.5,
        y: 0.5,
      },
    ]);
    vi.mocked(getGuestListDetails).mockResolvedValue({
      guestList: {
        id: 22,
        clubId: 1,
        eventId: 9,
        ownerType: 'PROMOTER',
        ownerUserId: 1,
        name: 'List',
        limit: 10,
        status: 'ACTIVE',
        createdAt: '2024-01-01',
        updatedAt: '2024-01-01',
      },
      entries: [
        {
          id: 101,
          guestListId: 22,
          displayName: 'Guest',
          status: 'ADDED',
          createdAt: '2024-01-01',
          updatedAt: '2024-01-01',
        },
      ],
      stats: { added: 1, invited: 0, confirmed: 0, declined: 0, arrived: 0, noShow: 0 },
    });
    vi.mocked(assignBooking).mockResolvedValue({ bookingId: 77 });

    render(
      <TablesScreen
        clubId={1}
        eventId={9}
        guestListId={22}
        onSelectClub={vi.fn()}
        onSelectEvent={vi.fn()}
        onSelectGuestList={vi.fn()}
        onForbidden={vi.fn()}
      />,
    );

    fireEvent.change(await screen.findByLabelText('Гость'), { target: { value: '101' } });
    fireEvent.click(await screen.findByRole('button', { name: 'A1' }));
    fireEvent.click(screen.getByRole('button', { name: 'Забронировать стол' }));

    await waitFor(() => {
      expect(assignBooking).toHaveBeenCalledWith({
        guestListEntryId: 101,
        hallId: 5,
        tableId: 30,
        eventId: 9,
      });
    });
  });
});
