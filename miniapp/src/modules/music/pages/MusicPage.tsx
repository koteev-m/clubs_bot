import React, { useEffect, useState } from 'react';
import {
  getPersonalMixtape,
  getWeeklyMixtape,
  likeSet,
  listSets,
  type MusicSetDto,
  unlikeSet,
} from '../api/music.api';
import { MusicList, type MusicItemProps } from '../components/MusicList';
import { getApiErrorInfo, isRequestCanceled } from '../../../shared/api/error';

/** Page displaying music items. */
export const MusicPage: React.FC = () => {
  const [items, setItems] = useState<MusicItemProps[]>([]);
  const [weeklyMixtape, setWeeklyMixtape] = useState<MusicItemProps[]>([]);
  const [personalMixtape, setPersonalMixtape] = useState<MusicItemProps[]>([]);
  const [loading, setLoading] = useState(true);
  const [mixtapeLoading, setMixtapeLoading] = useState(true);
  const [error, setError] = useState('');
  const [mixtapeError, setMixtapeError] = useState('');

  useEffect(() => {
    let isActive = true;
    setLoading(true);
    setError('');
    listSets({ limit: 20 })
      .then((data: MusicSetDto[]) => {
        if (!isActive) return;
        setItems(
          data.map((item) => ({
            id: item.id,
            title: item.title,
            artist: item.dj ?? undefined,
            description: item.description ?? undefined,
            coverUrl: item.coverUrl ?? undefined,
            audioUrl: item.audioUrl ?? undefined,
            tags: item.tags ?? undefined,
            likesCount: item.likesCount,
            likedByMe: item.likedByMe,
          })),
        );
      })
      .catch((error: unknown) => {
        if (!isActive || isRequestCanceled(error)) return;
        const { hasResponse } = getApiErrorInfo(error);
        setError(hasResponse ? 'Не удалось загрузить музыку' : 'Не удалось связаться с сервером');
      })
      .finally(() => {
        if (isActive) setLoading(false);
      });
    return () => {
      isActive = false;
    };
  }, []);

  useEffect(() => {
    let isActive = true;
    setMixtapeLoading(true);
    setMixtapeError('');
    Promise.all([getWeeklyMixtape(), getPersonalMixtape()])
      .then(([weekly, personal]) => {
        if (!isActive) return;
        setWeeklyMixtape(
          weekly.map((item) => ({
            id: item.id,
            title: item.title,
            artist: item.dj ?? undefined,
            description: item.description ?? undefined,
            coverUrl: item.coverUrl ?? undefined,
            audioUrl: item.audioUrl ?? undefined,
            tags: item.tags ?? undefined,
            likesCount: item.likesCount,
            likedByMe: item.likedByMe,
          })),
        );
        setPersonalMixtape(
          personal.items.map((item) => ({
            id: item.id,
            title: item.title,
            artist: item.dj ?? undefined,
            description: item.description ?? undefined,
            coverUrl: item.coverUrl ?? undefined,
            audioUrl: item.audioUrl ?? undefined,
            tags: item.tags ?? undefined,
            likesCount: item.likesCount,
            likedByMe: item.likedByMe,
          })),
        );
      })
      .catch((error: unknown) => {
        if (!isActive || isRequestCanceled(error)) return;
        const { hasResponse } = getApiErrorInfo(error);
        setMixtapeError(hasResponse ? 'Не удалось загрузить микстейп' : 'Не удалось связаться с сервером');
      })
      .finally(() => {
        if (isActive) setMixtapeLoading(false);
      });
    return () => {
      isActive = false;
    };
  }, []);

  const handleToggleLike = (id: number, liked: boolean) => {
    const toggle = liked ? unlikeSet : likeSet;
    toggle(id)
      .then(() => {
        setItems((prev) =>
          prev.map((item) =>
            item.id === id
              ? {
                  ...item,
                  likedByMe: !liked,
                  likesCount: Math.max(0, item.likesCount + (liked ? -1 : 1)),
                }
              : item,
          ),
        );
        setWeeklyMixtape((prev) =>
          prev.map((item) =>
            item.id === id
              ? {
                  ...item,
                  likedByMe: !liked,
                  likesCount: Math.max(0, item.likesCount + (liked ? -1 : 1)),
                }
              : item,
          ),
        );
        setPersonalMixtape((prev) =>
          prev.map((item) =>
            item.id === id
              ? {
                  ...item,
                  likedByMe: !liked,
                  likesCount: Math.max(0, item.likesCount + (liked ? -1 : 1)),
                }
              : item,
          ),
        );
      })
      .catch(() => {
        setError('Не удалось обновить лайк');
      });
  };

  return (
    <div className="p-4">
      <h1 className="text-xl mb-2">Музыка</h1>
      <div className="mb-6">
        <h2 className="text-lg mb-2">Микстейп недели</h2>
        {mixtapeError && <div className="text-sm text-red-600 mb-2">{mixtapeError}</div>}
        {mixtapeLoading && <div className="text-sm text-gray-600">Загрузка...</div>}
        {!mixtapeLoading && weeklyMixtape.length === 0 && !mixtapeError && (
          <div className="text-sm text-gray-500">Пока нет микстейпа недели</div>
        )}
        {!mixtapeLoading && weeklyMixtape.length > 0 && (
          <MusicList items={weeklyMixtape} onToggleLike={handleToggleLike} />
        )}
      </div>
      <div className="mb-6">
        <h2 className="text-lg mb-2">Мой микстейп</h2>
        {mixtapeLoading && <div className="text-sm text-gray-600">Загрузка...</div>}
        {!mixtapeLoading && personalMixtape.length === 0 && !mixtapeError && (
          <div className="text-sm text-gray-500">Лайкните сеты, чтобы получить персональную подборку</div>
        )}
        {!mixtapeLoading && personalMixtape.length > 0 && (
          <MusicList items={personalMixtape} onToggleLike={handleToggleLike} />
        )}
      </div>
      {error && <div className="text-sm text-red-600 mb-2">{error}</div>}
      {loading && <div className="text-sm text-gray-600">Загрузка...</div>}
      {!loading && items.length === 0 && !error && (
        <div className="text-sm text-gray-500">Пока нет сетов</div>
      )}
      {!loading && items.length > 0 && <MusicList items={items} onToggleLike={handleToggleLike} />}
    </div>
  );
};

export default MusicPage;
