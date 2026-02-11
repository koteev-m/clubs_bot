import React from 'react';
import { BattleDto } from '../api/music.api';
import { useBattle } from '../hooks/useBattle';
import { useBattleVote } from '../hooks/useBattleVote';
import { useBattlesList } from '../hooks/useBattlesList';

const BattleProgress: React.FC<{ battle: BattleDto }> = ({ battle }) => (
  <div className="text-xs text-gray-600 mt-2">
    <div className="flex justify-between">
      <span>{battle.itemA.title}</span>
      <span>{battle.votes.percentA}%</span>
    </div>
    <div className="h-2 bg-gray-100 rounded mb-1 overflow-hidden">
      <div className="h-full bg-blue-500" style={{ width: `${battle.votes.percentA}%` }} />
    </div>
    <div className="flex justify-between">
      <span>{battle.itemB.title}</span>
      <span>{battle.votes.percentB}%</span>
    </div>
    <div className="h-2 bg-gray-100 rounded overflow-hidden">
      <div className="h-full bg-purple-500" style={{ width: `${battle.votes.percentB}%` }} />
    </div>
    <div className="mt-2">Голоса: {battle.votes.countA + battle.votes.countB}</div>
  </div>
);

export const MusicBattlesSection: React.FC<{ clubId?: number; enabled?: boolean }> = ({ clubId, enabled = true }) => {
  const currentBattle = useBattle(clubId, enabled);
  const battlesList = useBattlesList(clubId, 5, 0, enabled);
  const voteState = useBattleVote();

  const activeBattle =
    currentBattle.battle && voteState.data?.id === currentBattle.battle.id ? voteState.data : currentBattle.battle;

  const handleVote = async (chosenItemId: number) => {
    if (!activeBattle) return;
    const next = await voteState.vote(activeBattle.id, chosenItemId);
    if (next) {
      await currentBattle.reload();
      await battlesList.reload();
    }
  };

  return (
    <section className="mb-6" aria-label="battles-section">
      <div className="flex items-center justify-between mb-2">
        <h2 className="text-lg">Battles</h2>
        <button type="button" className="text-xs text-blue-600" onClick={() => void currentBattle.reload()}>
          Обновить
        </button>
      </div>

      {currentBattle.status === 'unauthorized' && <div className="text-sm text-gray-600">Нужна авторизация для голосования</div>}
      {currentBattle.status === 'forbidden' && <div className="text-sm text-red-600">У вас нет доступа к battle</div>}
      {currentBattle.status === 'error' && (
        <div className="text-sm text-red-600">
          {currentBattle.errorMessage}
          {currentBattle.canRetry && (
            <button type="button" className="ml-2 underline" onClick={() => void currentBattle.reload()}>
              Повторить
            </button>
          )}
        </div>
      )}
      {currentBattle.status === 'loading' && <div className="text-sm text-gray-600">Загрузка battle...</div>}

      {currentBattle.status === 'ready' && !activeBattle && (
        <div className="text-sm text-gray-500">Сейчас нет активного battle</div>
      )}

      {activeBattle && (
        <div className="border rounded p-3 bg-white">
          <div className="text-xs text-gray-500 mb-1">
            {activeBattle.status === 'ACTIVE' ? 'Идёт голосование' : 'Battle завершён'}
          </div>
          <div className="font-medium">{activeBattle.itemA.title} vs {activeBattle.itemB.title}</div>
          <BattleProgress battle={activeBattle} />
          {activeBattle.votes.myVote && (
            <div className="text-xs text-green-700 mt-2">
              Ваш выбор: {activeBattle.votes.myVote === activeBattle.itemA.id ? activeBattle.itemA.title : activeBattle.itemB.title}
            </div>
          )}
          {activeBattle.status === 'ACTIVE' ? (
            <div className="mt-3 flex gap-2">
              <button
                type="button"
                className="px-2 py-1 text-sm rounded border"
                disabled={voteState.status === 'loading'}
                onClick={() => void handleVote(activeBattle.itemA.id)}
              >
                Голос за {activeBattle.itemA.title}
              </button>
              <button
                type="button"
                className="px-2 py-1 text-sm rounded border"
                disabled={voteState.status === 'loading'}
                onClick={() => void handleVote(activeBattle.itemB.id)}
              >
                Голос за {activeBattle.itemB.title}
              </button>
            </div>
          ) : (
            <div className="text-xs text-gray-500 mt-2">Голосование закрыто</div>
          )}
          {voteState.status === 'error' && <div className="text-xs text-red-600 mt-2">{voteState.errorMessage}</div>}
          {voteState.status === 'unauthorized' && <div className="text-xs text-gray-600 mt-2">Нужна авторизация</div>}
          {voteState.status === 'forbidden' && <div className="text-xs text-red-600 mt-2">Нет доступа к голосованию</div>}
        </div>
      )}

      {battlesList.status === 'ready' && battlesList.items.length > 0 && (
        <div className="mt-3">
          <h3 className="text-sm font-medium mb-1">Последние battles</h3>
          <ul className="text-xs text-gray-600 space-y-1">
            {battlesList.items.map((battle) => (
              <li key={battle.id}>
                {battle.itemA.title} vs {battle.itemB.title} · {battle.votes.percentA}%/{battle.votes.percentB}%
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
};
