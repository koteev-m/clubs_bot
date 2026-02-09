import { renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useStories } from './useStories';
import { listStories } from '../api/adminAnalytics.api';
import { AdminApiError } from '../api/admin.api';

vi.mock('../api/adminAnalytics.api', async () => {
  const actual = await vi.importActual<typeof import('../api/adminAnalytics.api')>('../api/adminAnalytics.api');
  return {
    ...actual,
    listStories: vi.fn(),
  };
});

describe('useStories', () => {
  it('ignores canceled list requests without switching to error state', async () => {
    vi.mocked(listStories).mockRejectedValue(new AdminApiError('aborted', { isAbort: true }));

    const { result } = renderHook(() => useStories(1, 20, 0));

    await waitFor(() => {
      expect(listStories).toHaveBeenCalled();
    });

    expect(result.current.list.status).not.toBe('error');
    expect(result.current.list.status).not.toBe('forbidden');
    expect(result.current.list.errorMessage).toBe('');
  });
});
