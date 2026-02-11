import axios from 'axios';
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
  hasStems?: boolean;
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

export interface BattleVotesDto {
  countA: number;
  countB: number;
  percentA: number;
  percentB: number;
  myVote?: number | null;
}

export interface BattleDto {
  id: number;
  clubId?: number | null;
  status: string;
  startsAt: string;
  endsAt: string;
  itemA: MusicSetDto;
  itemB: MusicSetDto;
  votes: BattleVotesDto;
}

export interface FanRankingStatsDto {
  votesCast: number;
  likesGiven: number;
  points: number;
  rank: number;
}

export interface FanRankingDistributionDto {
  totalFans: number;
  topPoints: number[];
  p50: number;
  p90: number;
  p99: number;
}

export interface FanRankingDto {
  myStats: FanRankingStatsDto;
  distribution: FanRankingDistributionDto;
}

export interface StemsDownloadDto {
  mode: 'download';
  blob: Blob;
  fileName: string;
  contentType: string;
}

export type StemsResult = StemsDownloadDto;

type ApiErrorPayload = {
  code?: string;
  message?: string | null;
  status?: number | null;
  details?: Record<string, string> | null;
};

export class MusicApiError extends Error {
  status?: number;
  code?: string;
  details?: Record<string, string>;
  isAbort: boolean;

  constructor(
    message: string,
    options?: {
      status?: number;
      code?: string;
      details?: Record<string, string>;
      isAbort?: boolean;
    },
  ) {
    super(message);
    this.name = 'MusicApiError';
    this.status = options?.status;
    this.code = options?.code;
    this.details = options?.details;
    this.isAbort = options?.isAbort ?? false;
  }
}

export const isAbortMusicError = (error: unknown): boolean => {
  if (error instanceof MusicApiError) return error.isAbort;
  if (axios.isAxiosError(error)) {
    return error.code === 'ERR_CANCELED' || error.name === 'CanceledError';
  }
  if (error instanceof DOMException) {
    return error.name === 'AbortError';
  }
  return false;
};

export const normalizeMusicError = (error: unknown): MusicApiError => {
  if (error instanceof MusicApiError) return error;
  if (isAbortMusicError(error)) {
    return new MusicApiError('Запрос отменен', { isAbort: true });
  }
  if (axios.isAxiosError(error)) {
    const payload = error.response?.data as ApiErrorPayload | undefined;
    return new MusicApiError(payload?.message ?? 'Ошибка запроса', {
      status: payload?.status ?? error.response?.status,
      code: payload?.code,
      details: payload?.details ?? undefined,
    });
  }
  if (error instanceof Error) {
    return new MusicApiError(error.message);
  }
  return new MusicApiError('Неизвестная ошибка');
};

/** Lists music items. */
export async function listItems(
  params: { clubId?: number; limit?: number; offset?: number },
  signal?: AbortSignal,
): Promise<MusicItemDto[]> {
  try {
    const query = new URLSearchParams();
    if (params.clubId) query.append('clubId', String(params.clubId));
    if (params.limit) query.append('limit', String(params.limit));
    if (params.offset) query.append('offset', String(params.offset));
    const res = await http.get<MusicItemDto[]>(`/api/music/items?${query.toString()}`, { signal });
    return res.data;
  } catch (error) {
    throw normalizeMusicError(error);
  }
}

/** Lists DJ sets. */
export async function listSets(params: { limit?: number; offset?: number }, signal?: AbortSignal): Promise<MusicSetDto[]> {
  try {
    const query = new URLSearchParams();
    if (params.limit) query.append('limit', String(params.limit));
    if (params.offset) query.append('offset', String(params.offset));
    const res = await http.get<MusicSetDto[]>(`/api/music/sets?${query.toString()}`, { signal });
    return res.data;
  } catch (error) {
    throw normalizeMusicError(error);
  }
}

/** Fetches weekly global mixtape. */
export async function getWeeklyMixtape(signal?: AbortSignal): Promise<MusicSetDto[]> {
  try {
    const res = await http.get<MusicSetDto[]>(`/api/music/mixtape/week`, { signal });
    return res.data;
  } catch (error) {
    throw normalizeMusicError(error);
  }
}

/** Fetches personal mixtape. */
export async function getPersonalMixtape(signal?: AbortSignal): Promise<MixtapeResponseDto> {
  try {
    const res = await http.get<MixtapeResponseDto>(`/api/me/mixtape`, { signal });
    return res.data;
  } catch (error) {
    throw normalizeMusicError(error);
  }
}

export async function likeSet(id: number, signal?: AbortSignal): Promise<LikeResponse> {
  try {
    const res = await http.post<LikeResponse>(`/api/music/items/${id}/like`, {}, { signal });
    return res.data;
  } catch (error) {
    throw normalizeMusicError(error);
  }
}

export async function unlikeSet(id: number, signal?: AbortSignal): Promise<LikeResponse> {
  try {
    const res = await http.delete<LikeResponse>(`/api/music/items/${id}/like`, { signal });
    return res.data;
  } catch (error) {
    throw normalizeMusicError(error);
  }
}

export async function getCurrentBattle(clubId: number, signal?: AbortSignal): Promise<BattleDto> {
  try {
    const params = new URLSearchParams({ clubId: String(clubId) });
    const res = await http.get<BattleDto>(`/api/music/battles/current?${params.toString()}`, { signal });
    return res.data;
  } catch (error) {
    throw normalizeMusicError(error);
  }
}

export async function listBattles(
  params: { clubId: number; limit?: number; offset?: number },
  signal?: AbortSignal,
): Promise<BattleDto[]> {
  try {
    const query = new URLSearchParams({ clubId: String(params.clubId) });
    if (params.limit) query.append('limit', String(params.limit));
    if (params.offset) query.append('offset', String(params.offset));
    const res = await http.get<BattleDto[]>(`/api/music/battles?${query.toString()}`, { signal });
    return res.data;
  } catch (error) {
    throw normalizeMusicError(error);
  }
}

export async function getBattleDetails(battleId: number, signal?: AbortSignal): Promise<BattleDto> {
  try {
    const res = await http.get<BattleDto>(`/api/music/battles/${battleId}`, { signal });
    return res.data;
  } catch (error) {
    throw normalizeMusicError(error);
  }
}

export async function voteInBattle(
  battleId: number,
  chosenItemId: number,
  signal?: AbortSignal,
): Promise<BattleDto> {
  try {
    const res = await http.post<BattleDto>(`/api/music/battles/${battleId}/vote`, { chosenItemId }, { signal });
    return res.data;
  } catch (error) {
    throw normalizeMusicError(error);
  }
}

export async function getStemsLinkOrDownload(itemId: number, signal?: AbortSignal): Promise<StemsResult> {
  try {
    const res = await http.get<Blob>(`/api/music/items/${itemId}/stems`, {
      signal,
      responseType: 'blob',
    });
    const headerName = res.headers['content-disposition'];
    const fallbackName = `stems-${itemId}.zip`;
    const fileNameMatch = typeof headerName === 'string' ? /filename="?([^"]+)"?/i.exec(headerName) : null;
    const fileName = fileNameMatch?.[1] ?? fallbackName;
    const contentType = (res.headers['content-type'] as string | undefined) ?? 'application/octet-stream';
    return {
      mode: 'download',
      blob: res.data,
      fileName,
      contentType,
    };
  } catch (error) {
    throw normalizeMusicError(error);
  }
}

export async function getFanRanking(
  params: { clubId: number; windowDays?: number },
  signal?: AbortSignal,
): Promise<FanRankingDto> {
  try {
    const query = new URLSearchParams({ clubId: String(params.clubId) });
    if (params.windowDays) query.append('windowDays', String(params.windowDays));
    const res = await http.get<FanRankingDto>(`/api/music/fans/ranking?${query.toString()}`, { signal });
    return res.data;
  } catch (error) {
    throw normalizeMusicError(error);
  }
}

/** Lists playlists. */
export async function listPlaylists(params: { clubId?: number; limit?: number; offset?: number }, signal?: AbortSignal) {
  try {
    const query = new URLSearchParams();
    if (params.clubId) query.append('clubId', String(params.clubId));
    if (params.limit) query.append('limit', String(params.limit));
    if (params.offset) query.append('offset', String(params.offset));
    const res = await http.get(`/api/music/playlists?${query.toString()}`, { signal });
    return res.data;
  } catch (error) {
    throw normalizeMusicError(error);
  }
}

/** Fetches playlist with items. */
export async function getPlaylist(id: number, signal?: AbortSignal) {
  try {
    const res = await http.get(`/api/music/playlists/${id}`, { signal });
    return res.data;
  } catch (error) {
    throw normalizeMusicError(error);
  }
}
