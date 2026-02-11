import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MusicBattlesSection } from '../MusicBattlesSection';
import { useBattle } from '../../hooks/useBattle';
import { useBattlesList } from '../../hooks/useBattlesList';
import { useBattleVote } from '../../hooks/useBattleVote';

vi.mock('../../hooks/useBattle', () => ({ useBattle: vi.fn() }));
vi.mock('../../hooks/useBattlesList', () => ({ useBattlesList: vi.fn() }));
vi.mock('../../hooks/useBattleVote', () => ({ useBattleVote: vi.fn() }));

const baseListState = {
  status: 'ready' as const,
  items: [],
  errorMessage: '',
  canRetry: false,
  reload: vi.fn(async () => {}),
};

const baseVoteState = {
  status: 'idle' as const,
  data: null,
  errorMessage: '',
  vote: vi.fn(async () => null),
  reset: vi.fn(),
};


const staleVoteBattle = {
  id: 999,
  clubId: 1,
  status: 'ACTIVE',
  startsAt: '2025-01-01T00:00:00Z',
  endsAt: '2025-01-01T01:00:00Z',
  itemA: {
    id: 1,
    title: 'Track A',
    likesCount: 0,
    likedByMe: false,
  },
  itemB: {
    id: 2,
    title: 'Track B',
    likesCount: 0,
    likedByMe: false,
  },
  votes: {
    countA: 10,
    countB: 5,
    percentA: 67,
    percentB: 33,
    myVote: null,
  },
};

describe('MusicBattlesSection', () => {
  it('renders empty state when there is no active battle', () => {
    vi.mocked(useBattle).mockReturnValue({
      status: 'ready',
      battle: null,
      errorMessage: '',
      canRetry: false,
      reload: vi.fn(async () => {}),
    });
    vi.mocked(useBattlesList).mockReturnValue(baseListState);
    vi.mocked(useBattleVote).mockReturnValue(baseVoteState);

    render(<MusicBattlesSection clubId={1} enabled />);

    expect(screen.getByText('Сейчас нет активного battle')).toBeTruthy();
  });


  it('renders empty state when current battle is null even if vote data exists', () => {
    vi.mocked(useBattle).mockReturnValue({
      status: 'ready',
      battle: null,
      errorMessage: '',
      canRetry: false,
      reload: vi.fn(async () => {}),
    });
    vi.mocked(useBattlesList).mockReturnValue(baseListState);
    vi.mocked(useBattleVote).mockReturnValue({ ...baseVoteState, data: staleVoteBattle });

    render(<MusicBattlesSection clubId={1} enabled />);

    expect(screen.getByText('Сейчас нет активного battle')).toBeTruthy();
    expect(screen.queryByText('Track A vs Track B')).toBeNull();
  });

  it('renders unauthorized caveat', () => {
    vi.mocked(useBattle).mockReturnValue({
      status: 'unauthorized',
      battle: null,
      errorMessage: 'Нужна авторизация',
      canRetry: false,
      reload: vi.fn(async () => {}),
    });
    vi.mocked(useBattlesList).mockReturnValue(baseListState);
    vi.mocked(useBattleVote).mockReturnValue(baseVoteState);

    render(<MusicBattlesSection clubId={1} enabled />);

    expect(screen.getByText('Нужна авторизация для голосования')).toBeTruthy();
  });

  it('renders network error with retry action', () => {
    const reload = vi.fn(async () => {});
    vi.mocked(useBattle).mockReturnValue({
      status: 'error',
      battle: null,
      errorMessage: 'Сервис временно недоступен',
      canRetry: true,
      reload,
    });
    vi.mocked(useBattlesList).mockReturnValue(baseListState);
    vi.mocked(useBattleVote).mockReturnValue(baseVoteState);

    render(<MusicBattlesSection clubId={1} enabled />);

    expect(screen.getByText('Сервис временно недоступен')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Повторить' })).toBeTruthy();
  });
});
