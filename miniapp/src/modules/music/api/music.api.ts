import { http } from '../../../shared/api/http';

/** Lists music items. */
export async function listItems(params: { clubId?: number; limit?: number; offset?: number }) {
  const query = new URLSearchParams();
  if (params.clubId) query.append('clubId', String(params.clubId));
  if (params.limit) query.append('limit', String(params.limit));
  if (params.offset) query.append('offset', String(params.offset));
  const res = await http.get(`/api/music/items?${query.toString()}`);
  return res.data;
}

/** Lists playlists. */
export async function listPlaylists(params: { clubId?: number; limit?: number; offset?: number }) {
  const query = new URLSearchParams();
  if (params.clubId) query.append('clubId', String(params.clubId));
  if (params.limit) query.append('limit', String(params.limit));
  if (params.offset) query.append('offset', String(params.offset));
  const res = await http.get(`/api/music/playlists?${query.toString()}`);
  return res.data;
}

/** Fetches playlist with items. */
export async function getPlaylist(id: number) {
  const res = await http.get(`/api/music/playlists/${id}`);
  return res.data;
}

