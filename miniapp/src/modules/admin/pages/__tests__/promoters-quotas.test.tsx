import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import PromotersQuotasScreen from '../PromotersQuotasScreen';
import { listClubs } from '../../api/admin.api';
import { listPromoters } from '../../api/promoters.api';
import { useUiStore } from '../../../../shared/store/ui';

vi.mock('../../../../widgets/ToastHost', () => ({
  default: () => null,
}));

vi.mock('../../api/admin.api', async () => {
  const actual = await vi.importActual<typeof import('../../api/admin.api')>('../../api/admin.api');
  return {
    ...actual,
    listClubs: vi.fn(),
  };
});

vi.mock('../../api/promoters.api', async () => {
  const actual = await vi.importActual<typeof import('../../api/promoters.api')>('../../api/promoters.api');
  return {
    ...actual,
    listPromoters: vi.fn(),
    updatePromoterAccess: vi.fn(),
    createPromoterQuota: vi.fn(),
    updatePromoterQuota: vi.fn(),
  };
});

describe('PromotersQuotasScreen', () => {
  beforeEach(() => {
    useUiStore.setState({ toasts: [] });
    vi.clearAllMocks();
  });

  it('renders header and club selector', async () => {
    vi.mocked(listClubs).mockResolvedValue([
      {
        id: 1,
        name: 'Club A',
        city: 'City',
        isActive: true,
        createdAt: '2024-01-01',
        updatedAt: '2024-01-01',
      },
    ]);
    vi.mocked(listPromoters).mockResolvedValue([]);

    render(<PromotersQuotasScreen clubId={null} onSelectClub={vi.fn()} onForbidden={vi.fn()} />);

    expect(await screen.findByText('Промоутеры и квоты')).toBeTruthy();
    expect(await screen.findByLabelText('Клуб')).toBeTruthy();
    await waitFor(() => {
      expect(screen.getByText('Club A (City)')).toBeTruthy();
    });
  });
});
