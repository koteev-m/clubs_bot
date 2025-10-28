import React from 'react';

export interface MusicItemProps {
  title: string;
  dj?: string;
  coverUrl?: string;
  sourceUrl?: string;
}

/** Renders simple list of music items. */
export const MusicList: React.FC<{ items: MusicItemProps[] }> = ({ items }) => {
  return (
    <div>
      {items.map((it, idx) => (
        <div key={idx} className="p-2 border-b">
          {it.coverUrl && <img src={it.coverUrl} alt={it.title} className="w-20 h-20" />}
          <div>{it.title}</div>
          {it.dj && <div className="text-sm">{it.dj}</div>}
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

