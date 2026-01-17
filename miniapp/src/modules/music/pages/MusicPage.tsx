import React, { useEffect, useState } from 'react';
import { listItems, type MusicItemDto } from '../api/music.api';
import { MusicList, type MusicItemProps } from '../components/MusicList';
import { getApiErrorInfo, isRequestCanceled } from '../../../shared/api/error';

/** Page displaying music items. */
export const MusicPage: React.FC = () => {
  const [items, setItems] = useState<MusicItemProps[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let isActive = true;
    setLoading(true);
    setError('');
    listItems({ limit: 20 })
      .then((data: MusicItemDto[]) => {
        if (!isActive) return;
        setItems(
          data.map((item) => ({
            id: item.id,
            title: item.title,
            artist: item.artist,
            coverUrl: item.coverUrl ?? undefined,
            isTrackOfNight: item.isTrackOfNight,
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

  return (
    <div className="p-4">
      <h1 className="text-xl mb-2">Музыка</h1>
      {error && <div className="text-sm text-red-600 mb-2">{error}</div>}
      {loading && <div className="text-sm text-gray-600">Загрузка...</div>}
      {!loading && items.length === 0 && !error && (
        <div className="text-sm text-gray-500">Пока нет треков</div>
      )}
      {!loading && items.length > 0 && <MusicList items={items} />}
    </div>
  );
};

export default MusicPage;
