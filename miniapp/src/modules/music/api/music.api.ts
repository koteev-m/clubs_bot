import { http } from '../../../shared/api/http';

export interface MusicItemDto {
  id: number;
  title: string;
  artist?: string | null;
  durationSec?: number | null;
  coverUrl?: string | null;
  audioUrl?: string | null;
  isTrackOfNight?: boolean;
}

export interface MusicSetDto {
  id: number;
  title: string;
  dj?: string | null;
  description?: string | null;
  durationSec?: number | null;
  coverUrl?: string | null;
  audioUrl?: string | null;
  tags?: string[] | null;
  likesCount: number;
  likedByMe: boolean;
}

export interface MixtapeResponseDto {
  userId: number;
  weekStart: string;
  items: MusicSetDto[];
  generatedAt: string;
}

export interface LikeResponse {
  itemId: number;
  liked: boolean;
  likedAt?: string | null;
}

/** Lists music items. */
export async function listItems(params: { clubId?: number; limit?: number; offset?: number }): Promise<MusicItemDto[]> {
  const query = new URLSearchParams();
  if (params.clubId) query.append('clubId', String(params.clubId));
  if (params.limit) query.append('limit', String(params.limit));
  if (params.offset) query.append('offset', String(params.offset));
  const res = await http.get<MusicItemDto[]>(`/api/music/items?${query.toString()}`);
  return res.data;
}

/** Lists DJ sets. */
export async function listSets(params: { limit?: number; offset?: number }): Promise<MusicSetDto[]> {
  const query = new URLSearchParams();
  if (params.limit) query.append('limit', String(params.limit));
  if (params.offset) query.append('offset', String(params.offset));
  const res = await http.get<MusicSetDto[]>(`/api/music/sets?${query.toString()}`);
  return res.data;
}

/** Fetches weekly global mixtape. */
export async function getWeeklyMixtape(): Promise<MusicSetDto[]> {
  const res = await http.get<MusicSetDto[]>(`/api/music/mixtape/week`);
  return res.data;
}

/** Fetches personal mixtape. */
export async function getPersonalMixtape(): Promise<MixtapeResponseDto> {
  const res = await http.get<MixtapeResponseDto>(`/api/me/mixtape`);
  return res.data;
}

export async function likeSet(id: number): Promise<LikeResponse> {
  const res = await http.post<LikeResponse>(`/api/music/items/${id}/like`);
  return res.data;
}

export async function unlikeSet(id: number): Promise<LikeResponse> {
  const res = await http.delete<LikeResponse>(`/api/music/items/${id}/like`);
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
