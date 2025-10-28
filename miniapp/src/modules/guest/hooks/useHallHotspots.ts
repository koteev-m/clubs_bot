import { useEffect, useState } from 'react';

interface Feature {
  id: number;
  geometry: { type: 'Polygon'; coordinates: number[][][] };
}

/** Loads GeoJSON and exposes selected polygon by table id. */
export function useHallHotspots(url: string) {
  const [features, setFeatures] = useState<Feature[]>([]);

  useEffect(() => {
    fetch(url)
      .then((r) => r.json())
      .then((data) => setFeatures(data.features as Feature[]));
  }, [url]);

  function getPolygon(id: number): number[][] {
    const feature = features.find((f) => f.id === id);
    return feature?.geometry.coordinates[0] ?? [];
  }

  return { getPolygon };
}
