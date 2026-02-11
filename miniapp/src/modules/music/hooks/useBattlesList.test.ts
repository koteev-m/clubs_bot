import { renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useBattlesList } from './useBattlesList';
import { listBattles, MusicApiError } from '../api/music.api';

vi.mock('../api/music.api', async () => {
  const actual = await vi.importActual<typeof import('../api/music.api')>('../api/music.api');
  return {
    ...actual,
    listBattles: vi.fn(),
  };
});

describe('useBattlesList', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('does not switch to error on canceled request', async () => {
    vi.mocked(listBattles).mockRejectedValue(new MusicApiError('aborted', { isAbort: true }));

    const { result } = renderHook(() => useBattlesList(1));

    await waitFor(() => {
      expect(listBattles).toHaveBeenCalled();
    });

    expect(result.current.status).not.toBe('error');
    expect(result.current.errorMessage).toBe('');
  });

  it('does not auto-load when disabled', async () => {
    renderHook(() => useBattlesList(1, 20, 0, false));

    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(listBattles).not.toHaveBeenCalled();
  });

  it('aborts in-flight request when disabled', async () => {
    const abortSpy = vi.fn();
    vi.mocked(listBattles).mockImplementation(
      (_params, signal?: AbortSignal) =>
        new Promise((resolve, reject) => {
          signal?.addEventListener('abort', () => {
            abortSpy();
            reject(new MusicApiError('aborted', { isAbort: true }));
          });
          setTimeout(() => resolve([]), 20);
        }),
    );

    const { rerender } = renderHook(({ enabled }) => useBattlesList(1, 20, 0, enabled), {
      initialProps: { enabled: true },
    });

    rerender({ enabled: false });

    await waitFor(() => {
      expect(abortSpy).toHaveBeenCalled();
    });
  });
});
