import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  getPersonalMixtape,
  getStemsLinkOrDownload,
  getWeeklyMixtape,
  likeSet,
  listSets,
  MusicApiError,
  type MusicSetDto,
  unlikeSet,
} from '../api/music.api';
import { MusicList, type MusicItemProps } from '../components/MusicList';
import { isRequestCanceled } from '../../../shared/api/error';
import { MusicBattlesSection } from '../components/MusicBattlesSection';
import { FanRankingSection } from '../components/FanRankingSection';
import { useGuestStore } from '../../guest/state/guest.store';

const mapSetToItem = (item: MusicSetDto): MusicItemProps => ({
  id: item.id,
  title: item.title,
  artist: item.dj ?? undefined,
  description: item.description ?? undefined,
  coverUrl: item.coverUrl ?? undefined,
  audioUrl: item.audioUrl ?? undefined,
  tags: item.tags ?? undefined,
  likesCount: item.likesCount,
  likedByMe: item.likedByMe,
  hasStems: item.hasStems ?? false,
});

/** Page displaying music items. */
export const MusicPage: React.FC = () => {
  const selectedClub = useGuestStore((state) => state.selectedClub);
  const clubId = useMemo(() => selectedClub, [selectedClub]);

  const [items, setItems] = useState<MusicItemProps[]>([]);
  const [weeklyMixtape, setWeeklyMixtape] = useState<MusicItemProps[]>([]);
  const [personalMixtape, setPersonalMixtape] = useState<MusicItemProps[]>([]);
  const [loading, setLoading] = useState(true);
  const [mixtapeLoading, setMixtapeLoading] = useState(true);
  const [error, setError] = useState('');
  const [mixtapeError, setMixtapeError] = useState('');
  const [stemsErrorByItem, setStemsErrorByItem] = useState<Record<number, string>>({});
  const musicRequestRef = useRef(0);
  const mixtapeRequestRef = useRef(0);

  useEffect(() => {
    const requestId = ++musicRequestRef.current;
    const controller = new AbortController();
    setLoading(true);
    setError('');
    listSets({ limit: 20 }, controller.signal)
      .then((data: MusicSetDto[]) => {
        if (musicRequestRef.current !== requestId) return;
        setItems(data.map(mapSetToItem));
      })
      .catch((nextError: unknown) => {
        if (isRequestCanceled(nextError) || musicRequestRef.current !== requestId) return;
        if (nextError instanceof MusicApiError && (!nextError.status || nextError.status >= 500)) {
          setError(!nextError.status ? 'Не удалось связаться с сервером' : 'Сервис временно недоступен');
          return;
        }
        setError('Не удалось загрузить музыку');
      })
      .finally(() => {
        if (musicRequestRef.current === requestId) {
          setLoading(false);
        }
      });
    return () => {
      controller.abort();
    };
  }, []);

  useEffect(() => {
    const requestId = ++mixtapeRequestRef.current;
    const controller = new AbortController();
    setMixtapeLoading(true);
    setMixtapeError('');
    Promise.all([getWeeklyMixtape(controller.signal), getPersonalMixtape(controller.signal)])
      .then(([weekly, personal]) => {
        if (mixtapeRequestRef.current !== requestId) return;
        setWeeklyMixtape(weekly.map(mapSetToItem));
        setPersonalMixtape(personal.items.map(mapSetToItem));
      })
      .catch((nextError: unknown) => {
        if (isRequestCanceled(nextError) || mixtapeRequestRef.current !== requestId) return;
        if (nextError instanceof MusicApiError && (!nextError.status || nextError.status >= 500)) {
          setMixtapeError(!nextError.status ? 'Не удалось связаться с сервером' : 'Сервис временно недоступен');
          return;
        }
        setMixtapeError('Не удалось загрузить микстейп');
      })
      .finally(() => {
        if (mixtapeRequestRef.current === requestId) {
          setMixtapeLoading(false);
        }
      });

    return () => {
      controller.abort();
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

  const handleDownloadStems = (id: number) => {
    setStemsErrorByItem((prev) => ({ ...prev, [id]: '' }));
    getStemsLinkOrDownload(id)
      .then((result) => {
        const blobUrl = URL.createObjectURL(result.blob);
        const link = document.createElement('a');
        link.href = blobUrl;
        link.download = result.fileName;
        link.click();
        URL.revokeObjectURL(blobUrl);
      })
      .catch((nextError: unknown) => {
        if (isRequestCanceled(nextError)) return;
        if (nextError instanceof MusicApiError && nextError.status === 403) {
          setStemsErrorByItem((prev) => ({ ...prev, [id]: 'Нет доступа к stems' }));
          return;
        }
        setStemsErrorByItem((prev) => ({ ...prev, [id]: 'Не удалось скачать stems' }));
      });
  };

  return (
    <div className="p-4">
      <h1 className="text-xl mb-2">Музыка</h1>

      <MusicBattlesSection clubId={clubId} enabled={Boolean(clubId)} />
      <FanRankingSection clubId={clubId} enabled={Boolean(clubId)} />

      <div className="mb-6">
        <h2 className="text-lg mb-2">Микстейп недели</h2>
        {mixtapeError && <div className="text-sm text-red-600 mb-2">{mixtapeError}</div>}
        {mixtapeLoading && <div className="text-sm text-gray-600">Загрузка...</div>}
        {!mixtapeLoading && weeklyMixtape.length === 0 && !mixtapeError && (
          <div className="text-sm text-gray-500">Пока нет микстейпа недели</div>
        )}
        {!mixtapeLoading && weeklyMixtape.length > 0 && (
          <MusicList
            items={weeklyMixtape}
            onToggleLike={handleToggleLike}
            onDownloadStems={handleDownloadStems}
            stemsErrorByItem={stemsErrorByItem}
          />
        )}
      </div>
      <div className="mb-6">
        <h2 className="text-lg mb-2">Мой микстейп</h2>
        {mixtapeLoading && <div className="text-sm text-gray-600">Загрузка...</div>}
        {!mixtapeLoading && personalMixtape.length === 0 && !mixtapeError && (
          <div className="text-sm text-gray-500">Лайкните сеты, чтобы получить персональную подборку</div>
        )}
        {!mixtapeLoading && personalMixtape.length > 0 && (
          <MusicList
            items={personalMixtape}
            onToggleLike={handleToggleLike}
            onDownloadStems={handleDownloadStems}
            stemsErrorByItem={stemsErrorByItem}
          />
        )}
      </div>
      {error && <div className="text-sm text-red-600 mb-2">{error}</div>}
      {loading && <div className="text-sm text-gray-600">Загрузка...</div>}
      {!loading && items.length === 0 && !error && <div className="text-sm text-gray-500">Пока нет сетов</div>}
      {!loading && items.length > 0 && (
        <MusicList
          items={items}
          onToggleLike={handleToggleLike}
          onDownloadStems={handleDownloadStems}
          stemsErrorByItem={stemsErrorByItem}
        />
      )}
    </div>
  );
};

export default MusicPage;
