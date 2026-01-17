import React from 'react';

export interface MusicItemProps {
  id: number;
  title: string;
  artist?: string;
  coverUrl?: string;
  sourceUrl?: string;
  isTrackOfNight?: boolean;
}

/** Renders simple list of music items. */
export const MusicList: React.FC<{ items: MusicItemProps[] }> = ({ items }) => {
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
          {it.sourceUrl && (
            <a href={it.sourceUrl} target="_blank" rel="noreferrer" className="text-blue-500">
              Listen
            </a>
          )}
        </div>
      ))}
    </div>
  );
};
