import { renderHook, waitFor } from '@testing-library/react';
import { useHallHotspots } from './useHallHotspots';

const sample = {
  features: [
    { id: 1, geometry: { type: 'Polygon', coordinates: [[[0, 0], [1, 1], [2, 2]]] } },
  ],
};

vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ json: () => sample })));

describe('useHallHotspots', () => {
  it('returns polygon for table id', async () => {
    const { result } = renderHook(() => useHallHotspots('url'));
    await waitFor(() => {
      expect(result.current.getPolygon(1).length).toBe(3);
    });
  });
});
