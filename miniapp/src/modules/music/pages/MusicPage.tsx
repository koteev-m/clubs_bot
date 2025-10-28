import React, { useEffect, useState } from 'react';
import { listItems } from '../api/music.api';
import { MusicList } from '../components/MusicList';

/** Page displaying music items. */
export const MusicPage: React.FC = () => {
  const [items, setItems] = useState<any[]>([]);

  useEffect(() => {
    listItems({ limit: 10 }).then(setItems);
  }, []);

  return (
    <div className="p-4">
      <h1 className="text-xl mb-2">Music</h1>
      <MusicList items={items} />
    </div>
  );
};

export default MusicPage;

