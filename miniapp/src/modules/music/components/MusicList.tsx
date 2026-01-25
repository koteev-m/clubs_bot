import React from 'react';

export interface MusicItemProps {
  id: number;
  title: string;
  artist?: string;
  description?: string;
  coverUrl?: string;
  audioUrl?: string;
  tags?: string[];
  isTrackOfNight?: boolean;
  likesCount?: number;
  likedByMe?: boolean;
}

/** Renders simple list of music items. */
export const MusicList: React.FC<{
  items: MusicItemProps[];
  onToggleLike?: (id: number, liked: boolean) => void;
}> = ({ items, onToggleLike }) => {
  return (
    <div>
      {items.map((it) => (
        <div key={it.id} className="p-2 border-b">
          {it.coverUrl && <img src={it.coverUrl} alt={it.title} className="w-20 h-20" />}
          <div className="flex items-center gap-2">
            <div>{it.title}</div>
            {it.isTrackOfNight && (
              <span className="rounded bg-blue-50 px-2 py-0.5 text-xs text-blue-600">Трек ночи</span>
            )}
          </div>
          {it.artist && <div className="text-sm">{it.artist}</div>}
          {it.description && <div className="text-sm text-gray-600">{it.description}</div>}
          {it.tags && it.tags.length > 0 && (
            <div className="mt-1 flex flex-wrap gap-1 text-xs text-gray-500">
              {it.tags.map((tag) => (
                <span key={tag} className="rounded bg-gray-100 px-2 py-0.5">
                  {tag}
                </span>
              ))}
            </div>
          )}
          <div className="mt-2 flex items-center gap-3">
            {it.audioUrl && (
              <a href={it.audioUrl} target="_blank" rel="noreferrer" className="text-blue-500">
                Слушать
              </a>
            )}
            {typeof it.likesCount === 'number' && (
              <span className="text-xs text-gray-500">❤ {it.likesCount}</span>
            )}
            {onToggleLike && (
              <button
                type="button"
                className={`text-xs ${it.likedByMe ? 'text-red-600' : 'text-gray-500'}`}
                onClick={() => onToggleLike(it.id, !!it.likedByMe)}
              >
                {it.likedByMe ? 'Убрать лайк' : 'Лайкнуть'}
              </button>
            )}
          </div>
        </div>
      ))}
    </div>
  );
};
