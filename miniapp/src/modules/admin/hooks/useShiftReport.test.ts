import { renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useShiftReport } from './useShiftReport';
import { getShiftReport } from '../api/adminFinance.api';
import { AdminApiError } from '../api/admin.api';

vi.mock('../api/adminFinance.api', async () => {
  const actual = await vi.importActual<typeof import('../api/adminFinance.api')>('../api/adminFinance.api');
  return {
    ...actual,
    getShiftReport: vi.fn(),
  };
});

describe('useShiftReport', () => {
  it('ignores canceled requests without switching to error state', async () => {
    vi.mocked(getShiftReport).mockRejectedValue(new AdminApiError('aborted', { isAbort: true }));

    const { result } = renderHook(() => useShiftReport(1, '2024-01-01T00:00:00Z'));

    await waitFor(() => {
      expect(getShiftReport).toHaveBeenCalled();
    });

    expect(result.current.status).not.toBe('error');
    expect(result.current.status).not.toBe('forbidden');
    expect(result.current.errorMessage).toBe('');
  });
});
